package com.lagradost.common.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

object DesktopDataStore {
    @PublishedApi internal val mapper: ObjectMapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val dataFile = File(PlatformPaths.dataDir, "datastore.json")

    val rawKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    var isPreCacheLoaded = false

    @Volatile
    var isWorkerMode = System.getProperty("cloudstream.sandbox.worker") == "true"

    fun enableWorkerMode() {
        isWorkerMode = true
        isPreCacheLoaded = true
    }

    val historyUpdates = MutableStateFlow(0)
    val bookmarkUpdates = MutableStateFlow(0)
    val pluginUpdatesFlow = MutableStateFlow(0)
    val keyUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    fun notifyBookmarksChanged() {
        bookmarkUpdates.value++
    }

    @PublishedApi
    internal val dbLock = java.util.concurrent.locks.ReentrantLock()

    @PublishedApi
    internal inline fun <T> withDbLock(block: () -> T): T {
        dbLock.lock()
        try {
            return block()
        } finally {
            dbLock.unlock()
        }
    }

    fun init() {
        withDbLock {
            val db = DatabaseFactory.database

            try {
                db.cloudstreamDBQueries.selectAllKeyValues().executeAsList().forEach { row ->
                    rawKeyCache[row.key] = row.value_
                }
                isPreCacheLoaded = true
            } catch (e: Exception) {
                AppLogger.e("Failed to pre-cache key-values", e)
            }

            if (dataFile.exists() && dataFile.length() > 0L) {
                try {
                    AppLogger.i("Migrating legacy datastore.json to SQLDelight...")
                    val cache: Map<String, String> = mapper.readValue(dataFile)

                    db.cloudstreamDBQueries.transaction {
                        for ((key, jsonStr) in cache) {
                            when (key) {
                                "user_bookmarks" -> {
                                    try {
                                        val bookmarks: List<DesktopBookmark> = mapper.readValue(jsonStr, object : TypeReference<List<DesktopBookmark>>() {})
                                        bookmarks.forEach { b ->
                                            db.cloudstreamDBQueries.insertBookmark(b.id, b.name, b.url, b.apiName, b.posterUrl, b.watchType.toLong(), b.dateAdded, 0L)
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e("Failed to migrate bookmarks", e)
                                    }
                                }
                                "user_watch_history" -> {
                                    try {
                                        val history: List<WatchHistory> = mapper.readValue(jsonStr, object : TypeReference<List<WatchHistory>>() {})
                                        history.forEach { h ->
                                            db.cloudstreamDBQueries.insertWatchHistory(
                                                h.parentId, h.episodeId ?: "", h.showName, h.showUrl, h.apiName, h.posterUrl,
                                                h.episodeThumbnailUrl, h.screenshotUrl,
                                                h.episode?.toLong(), h.season?.toLong(), h.position, h.duration, h.updateTime,
                                                h.episodeName, h.episodeDescription, 0L,
                                            )
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e("Failed to migrate watch history", e)
                                    }
                                }
                                "plugin_updates_history_v2" -> {
                                    try {
                                        val updates: List<PluginUpdateRecord> = mapper.readValue(jsonStr, object : TypeReference<List<PluginUpdateRecord>>() {})
                                        updates.forEach { u ->
                                            db.cloudstreamDBQueries.insertPluginUpdate(u.pluginName, u.version.toLong(), u.iconUrl, u.timestamp)
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e("Failed to migrate plugin updates", e)
                                    }
                                }
                                else -> {
                                    rawKeyCache[key] = jsonStr
                                    db.cloudstreamDBQueries.insertKeyValue(key, jsonStr)
                                }
                            }
                        }
                    }
                    val bakFile = File(PlatformPaths.dataDir, "datastore.json.bak")
                    dataFile.renameTo(bakFile)
                    AppLogger.i("Migration complete. Old file renamed to datastore.json.bak")
                } catch (e: Exception) {
                    AppLogger.e("Critical failure migrating datastore.json", e)
                }
            }
        }
    }

    private val dbWriteDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)
    private val ioScope = kotlinx.coroutines.CoroutineScope(dbWriteDispatcher + kotlinx.coroutines.SupervisorJob())

    @Volatile
    var onPersistKeyOverride: ((key: String, json: String) -> Unit)? = null

    @Volatile
    var onRemoveKeyOverride: ((key: String) -> Unit)? = null

    fun setRawJson(key: String, json: String) {
        val override = onPersistKeyOverride
        if (override != null) {
            rawKeyCache[key] = json
            keyUpdates.tryEmit(key)
            override.invoke(key, json)
            return
        }
        if (isWorkerMode) {
            rawKeyCache[key] = json
            keyUpdates.tryEmit(key)
            return
        }
        if (rawKeyCache[key] == json) return
        rawKeyCache[key] = json
        keyUpdates.tryEmit(key)
        ioScope.launch {
            try {
                withDbLock {
                    DatabaseFactory.database.cloudstreamDBQueries.insertKeyValue(key, json)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to persist raw key $key to SQLite", e)
            }
        }
    }

    fun <T> setKey(key: String, value: T) {
        if (isWorkerMode) {
            try {
                val json = mapper.writeValueAsString(value)
                if (rawKeyCache[key] == json) return
                rawKeyCache[key] = json
                keyUpdates.tryEmit(key)
                onPersistKeyOverride?.invoke(key, json)
            } catch (t: Throwable) {
                AppLogger.e("Failed to serialize key $key in worker", t)
            }
            return
        }
        try {
            val json = mapper.writeValueAsString(value)
            if (rawKeyCache[key] == json) return
            rawKeyCache[key] = json
            keyUpdates.tryEmit(key)
            val override = onPersistKeyOverride
            if (override != null) {
                override.invoke(key, json)
                return
            }
            ioScope.launch {
                try {
                    withDbLock {
                        DatabaseFactory.database.cloudstreamDBQueries.insertKeyValue(key, json)
                    }
                } catch (e: Exception) {
                    AppLogger.e("Failed to persist key $key to SQLite", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to serialize key $key", e)
        }
    }

    fun <T> getKey(key: String, clazz: Class<T>): T? {
        if (isWorkerMode) {
            val json = rawKeyCache[key] ?: return null
            return try {
                mapper.readValue(json, clazz)
            } catch (t: Throwable) {
                AppLogger.e("DesktopDataStore: Failed to deserialize key '$key' to ${clazz.simpleName}", t)
                null
            }
        }
        val json = rawKeyCache[key] ?: if (!isPreCacheLoaded) {
            val dbJson = withDbLock {
                try {
                    DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull()
                } catch (e: Exception) {
                    AppLogger.e("Failed to select key $key from SQLite", e)
                    null
                }
            }
            if (dbJson != null) {
                rawKeyCache[key] = dbJson
            }
            dbJson
        } else {
            null
        } ?: return null
        return try {
            mapper.readValue(json, clazz)
        } catch (e: Exception) {
            AppLogger.e("DesktopDataStore: Failed to deserialize key '$key' to ${clazz.simpleName}", e)
            null
        }
    }

    inline fun <reified T> getKey(key: String): T? {
        if (isWorkerMode) {
            val json = rawKeyCache[key] ?: return null
            return try {
                mapper.readValue(json)
            } catch (t: Throwable) {
                AppLogger.e("DesktopDataStore: Failed to deserialize key '$key'", t)
                null
            }
        }
        val json = rawKeyCache[key] ?: if (!isPreCacheLoaded) {
            val dbJson = withDbLock {
                try {
                    DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull()
                } catch (e: Exception) {
                    AppLogger.e("Failed to select key $key from SQLite", e)
                    null
                }
            }
            if (dbJson != null) {
                rawKeyCache[key] = dbJson
            }
            dbJson
        } else {
            null
        } ?: return null
        return try {
            mapper.readValue(json)
        } catch (e: Exception) {
            AppLogger.e("DesktopDataStore: Failed to deserialize key '$key'", e)
            null
        }
    }

    fun containsKey(key: String): Boolean {
        if (isWorkerMode) return rawKeyCache.containsKey(key)
        if (rawKeyCache.containsKey(key)) return true
        if (isPreCacheLoaded) return false
        return withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull() != null
            } catch (e: Exception) {
                AppLogger.e("Failed to check if key exists: $key", e)
                false
            }
        }
    }

    fun removeKey(key: String) {
        if (isWorkerMode) {
            rawKeyCache.remove(key)
            keyUpdates.tryEmit(key)
            onRemoveKeyOverride?.invoke(key)
            return
        }
        rawKeyCache.remove(key)
        keyUpdates.tryEmit(key)
        val override = onRemoveKeyOverride
        if (override != null) {
            override.invoke(key)
            return
        }
        ioScope.launch {
            try {
                withDbLock {
                    DatabaseFactory.database.cloudstreamDBQueries.deleteKeyValue(key)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to delete key $key from SQLite", e)
            }
        }
    }

    var activeProfileProvider: () -> Int = { 0 }
    val activeProfileId: Int
        get() = activeProfileProvider()

    fun getProfileKey(key: String, profileId: Int = activeProfileId): String = "$profileId/$key"

    fun <T> setProfileKey(key: String, value: T, profileId: Int = activeProfileId) {
        setKey(getProfileKey(key, profileId), value)
    }

    fun <T> getProfileKey(key: String, clazz: Class<T>, profileId: Int = activeProfileId): T? {
        return getKey(getProfileKey(key, profileId), clazz)
    }

    inline fun <reified T> getProfileKey(key: String, profileId: Int = activeProfileId): T? {
        return getKey<T>(getProfileKey(key, profileId))
    }

    fun removeProfileKey(key: String, profileId: Int = activeProfileId) {
        removeKey(getProfileKey(key, profileId))
    }

    fun getAllKeysWithPrefix(prefix: String): List<String> {
        if (isWorkerMode || isPreCacheLoaded) {
            return rawKeyCache.keys().toList().filter { it.startsWith(prefix) }
        }
        return withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.selectAllKeyValues()
                    .executeAsList()
                    .map { it.key }
                    .filter { it.startsWith(prefix) }
            } catch (e: Exception) {
                AppLogger.e("Failed to get keys with prefix: $prefix", e)
                emptyList()
            }
        }
    }

    // ── Bookmarks Store Delegation ──────────────────────────────────────────────

    fun normalizeUrl(url: String): String = DesktopBookmarkStore.normalizeUrl(url)

    fun bookmarkId(apiName: String, url: String): String = DesktopBookmarkStore.bookmarkId(apiName, url)

    fun getBookmarks(profileId: Int = activeProfileId): List<DesktopBookmark> =
        DesktopBookmarkStore.getBookmarks(profileId)

    fun addBookmark(bookmark: DesktopBookmark, profileId: Int = activeProfileId) =
        DesktopBookmarkStore.addBookmark(bookmark, profileId)

    fun removeBookmark(id: String, profileId: Int = activeProfileId) =
        DesktopBookmarkStore.removeBookmark(id, profileId)

    fun isBookmarked(id: String, profileId: Int = activeProfileId): Boolean =
        DesktopBookmarkStore.isBookmarked(id, profileId)

    fun clearAllBookmarks(profileId: Int = activeProfileId) =
        DesktopBookmarkStore.clearAllBookmarks(profileId)

    // ── Watch History Store Delegation ──────────────────────────────────────────

    fun notifyHistoryChanged(force: Boolean = false) =
        DesktopWatchHistoryStore.notifyHistoryChanged(force)

    fun watchHistoryId(
        apiName: String,
        showUrl: String,
        season: Int? = null,
        episode: Int? = null,
        episodeData: String? = null,
        profileId: Int = activeProfileId,
    ): String = DesktopWatchHistoryStore.watchHistoryId(apiName, showUrl, season, episode, episodeData, profileId)

    fun getAllWatchHistory(profileId: Int = activeProfileId): List<WatchHistory> =
        DesktopWatchHistoryStore.getAllWatchHistory(profileId)

    fun getLastWatched(parentId: String): WatchHistory? =
        DesktopWatchHistoryStore.getLastWatched(parentId)

    fun getWatchHistoryByParent(parentId: String): List<WatchHistory> =
        DesktopWatchHistoryStore.getWatchHistoryByParent(parentId)

    fun getLatestWatchHistoryForShow(showUrl: String): WatchHistory? =
        DesktopWatchHistoryStore.getLatestWatchHistoryForShow(showUrl)

    fun getEpisodeWatched(parentId: String, episodeId: String?): WatchHistory? =
        DesktopWatchHistoryStore.getEpisodeWatched(parentId, episodeId)

    fun setLastWatched(history: WatchHistory, forceNotify: Boolean = false) =
        DesktopWatchHistoryStore.setLastWatched(history, forceNotify)

    fun setMultipleLastWatched(histories: List<WatchHistory>) =
        DesktopWatchHistoryStore.setMultipleLastWatched(histories)

    fun clearAllWatchHistory(profileId: Int = activeProfileId) =
        DesktopWatchHistoryStore.clearAllWatchHistory(profileId)

    fun removeWatchHistory(parentId: String) =
        DesktopWatchHistoryStore.removeWatchHistory(parentId)

    fun removeEpisodeWatched(
        parentId: String,
        episodeId: String,
        season: Int? = null,
        episode: Int? = null,
        extraEpisodeIds: List<String> = emptyList(),
    ) = DesktopWatchHistoryStore.removeEpisodeWatched(parentId, episodeId, season, episode, extraEpisodeIds)

    fun removeMultipleEpisodesWatched(
        parentId: String,
        episodeIds: List<String>,
        extraParentIds: List<String> = emptyList(),
    ) = DesktopWatchHistoryStore.removeMultipleEpisodesWatched(parentId, episodeIds, extraParentIds)

    fun migrateWatchHistoryAndBookmark(
        oldApiName: String,
        oldUrl: String,
        newApiName: String,
        newUrl: String,
        newName: String,
        newPosterUrl: String?,
        profileId: Int = activeProfileId,
    ): Boolean = DesktopWatchHistoryStore.migrateWatchHistoryAndBookmark(
        oldApiName, oldUrl, newApiName, newUrl, newName, newPosterUrl, profileId,
    )

    // ── Plugin Updates History ──────────────────────────────────────────────────

    private const val UNREAD_UPDATES_KEY = "unread_plugin_updates"

    fun getUpdatesHistory(): List<PluginUpdateRecord> {
        return withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.selectAllPluginUpdates().executeAsList().map {
                    PluginUpdateRecord(it.pluginName, it.version.toInt(), it.iconUrl, it.timestamp)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to get updates history", e)
                emptyList()
            }
        }
    }

    fun addUpdateHistory(history: List<PluginUpdateRecord>) {
        if (history.isEmpty()) return

        withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.transaction {
                    history.forEach {
                        DatabaseFactory.database.cloudstreamDBQueries.insertPluginUpdate(
                            it.pluginName,
                            it.version.toLong(),
                            it.iconUrl,
                            it.timestamp,
                        )
                    }
                    DatabaseFactory.database.cloudstreamDBQueries.deleteOldPluginUpdates()
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to add update history", e)
            }
        }
        pluginUpdatesFlow.value++
    }

    fun clearUpdatesHistory() {
        withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.deleteAllPluginUpdates()
            } catch (e: Exception) {
                AppLogger.e("Failed to clear updates history", e)
            }
        }
        pluginUpdatesFlow.value++
    }

    fun hasUnreadUpdates(): Boolean {
        return getKey<Boolean>(UNREAD_UPDATES_KEY) ?: false
    }

    fun setUnreadUpdates(hasUnread: Boolean) {
        setKey(UNREAD_UPDATES_KEY, hasUnread)
        pluginUpdatesFlow.value++
    }

    // ── Preference Keys ─────────────────────────────────────────────────────────

    const val PREF_ALLOW_EXTERNAL_BROWSER = "ALLOW_EXTERNAL_BROWSER"
    const val PREF_ALLOW_CF_BYPASS = "ALLOW_CF_BYPASS"
    const val PREF_ISOLATED_EXTERNAL_BROWSER = "ISOLATED_EXTERNAL_BROWSER"
    const val PREF_DONT_ASK_EXTERNAL_LINKS = "DONT_ASK_EXTERNAL_LINKS"

    const val PREF_DISCORD_RPC_ENABLED = "DISCORD_RPC_ENABLED"
    const val PREF_DISCORD_RPC_SHOW_TITLE = "DISCORD_RPC_SHOW_TITLE"
    const val PREF_DISCORD_RPC_SHOW_PROGRESS = "DISCORD_RPC_SHOW_PROGRESS"
    const val PREF_DISCORD_RPC_SHOW_BROWSING = "DISCORD_RPC_SHOW_BROWSING"
    const val PREF_DISCORD_CUSTOM_APP_ID = "DISCORD_CUSTOM_APP_ID"

    const val PREF_P2P_ENABLED = "p2p_torrent_enabled"
    const val PREF_P2P_PORT = "p2p_torrent_port"
    const val PREF_P2P_CACHE_SIZE_GB = "p2p_torrent_cache_gb"
    const val PREF_P2P_SHOW_HUD = "p2p_torrent_show_hud"

    const val PREF_ENABLE_DOWNLOAD_BUTTONS = "ENABLE_DOWNLOAD_BUTTONS"
    const val PREF_DOWNLOAD_THREADS = "DOWNLOAD_THREADS"
    const val PREF_DOWNLOAD_MAX_CONCURRENT = "DOWNLOAD_MAX_CONCURRENT"
    const val PREF_DOWNLOAD_PATH = "DOWNLOAD_PATH"

    const val PREF_TORBOX_API_KEY = "torbox_api_key"
    const val PREF_TORBOX_ENABLED = "torbox_enabled"
    const val PREF_TORBOX_AUTO_RESOLVE = "torbox_auto_resolve"

    // ── Trusted Plugins Registry ────────────────────────────────────────────────

    private const val TRUSTED_PLUGINS_KEY = "trusted_plugins_set"

    fun getTrustedPlugins(): Set<String> {
        if (isWorkerMode) {
            val cached = rawKeyCache[TRUSTED_PLUGINS_KEY] ?: return emptySet()
            return try {
                val list: List<String> = mapper.readValue(cached, object : TypeReference<List<String>>() {})
                list.map { it.lowercase().trim() }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
        val cached = rawKeyCache[TRUSTED_PLUGINS_KEY]
        val json = if (cached != null) {
            cached
        } else {
            val dbVal = withDbLock {
                try {
                    DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(TRUSTED_PLUGINS_KEY).executeAsOneOrNull()
                } catch (e: Exception) {
                    AppLogger.e("Failed to select trusted plugins from SQLite", e)
                    null
                }
            }
            if (dbVal != null) rawKeyCache[TRUSTED_PLUGINS_KEY] = dbVal
            dbVal
        } ?: return emptySet()

        return try {
            val list: List<String> = mapper.readValue(json, object : TypeReference<List<String>>() {})
            list.map { it.lowercase().trim() }.toSet()
        } catch (e: Exception) {
            AppLogger.e("DesktopDataStore: Failed to deserialize trusted plugins set", e)
            emptySet()
        }
    }

    fun isPluginTrusted(internalName: String): Boolean {
        val cleanName = internalName.removeSuffix("-jvm").lowercase().trim()
        val trusted = getTrustedPlugins()
        if (trusted.contains(cleanName) || trusted.contains(internalName.lowercase().trim())) return true
        val stripped = cleanName.removeSuffix("provider").removeSuffix("plugin").removePrefix("com.")
        if (stripped.isNotBlank() && (trusted.contains(stripped) || trusted.contains(stripped.substringAfterLast('.')))) return true
        val lastSegment = cleanName.substringAfterLast('.')
        if (lastSegment.isNotBlank() && (trusted.contains(lastSegment) || trusted.contains(lastSegment.removeSuffix("provider").removeSuffix("plugin")))) return true
        return false
    }

    fun setPluginsTrusted(internalNames: Collection<String>, trusted: Boolean) = withDbLock {
        if (internalNames.isEmpty()) return@withDbLock
        val current = getTrustedPlugins().toMutableSet()
        for (internalName in internalNames) {
            val cleanName = internalName.removeSuffix("-jvm").lowercase().trim()
            val lower = internalName.lowercase().trim()
            if (trusted) {
                current.add(cleanName)
                current.add(lower)
            } else {
                current.remove(cleanName)
                current.remove(lower)
            }
        }
        val list = current.toList()
        try {
            val json = mapper.writeValueAsString(list)
            rawKeyCache[TRUSTED_PLUGINS_KEY] = json
            DatabaseFactory.database.cloudstreamDBQueries.insertKeyValue(TRUSTED_PLUGINS_KEY, json)
        } catch (e: Exception) {
            AppLogger.e("Failed to save trusted plugins", e)
        }
    }

    fun setPluginTrusted(internalName: String, trusted: Boolean) {
        setPluginsTrusted(listOf(internalName), trusted)
    }

    // ── Ignored Verification Domains ────────────────────────────────────────────

    private const val IGNORED_CF_DOMAINS_KEY = "ignored_verification_domains"
    val sessionIgnoredCfDomains = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getIgnoredCfDomains(): Set<String> {
        if (isWorkerMode) return emptySet()
        val cached = rawKeyCache[IGNORED_CF_DOMAINS_KEY]
        val json = if (cached != null) {
            cached
        } else {
            val dbVal = withDbLock {
                try {
                    DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(IGNORED_CF_DOMAINS_KEY).executeAsOneOrNull()
                } catch (e: Exception) {
                    AppLogger.e("Failed to select ignored verification domains from SQLite", e)
                    null
                }
            }
            if (dbVal != null) rawKeyCache[IGNORED_CF_DOMAINS_KEY] = dbVal
            dbVal
        } ?: return emptySet()

        return try {
            val list: List<String> = mapper.readValue(json, object : TypeReference<List<String>>() {})
            list.map { it.lowercase().trim() }.toSet()
        } catch (e: Exception) {
            AppLogger.e("DesktopDataStore: Failed to deserialize ignored verification domains", e)
            emptySet()
        }
    }

    fun isDomainCfIgnored(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        val clean = domain.lowercase().trim().removePrefix("www.")
        val ignored = getIgnoredCfDomains()
        return ignored.contains(clean) || ignored.contains(domain.lowercase().trim())
    }

    fun isDomainCfSilenced(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        val clean = domain.lowercase().trim().removePrefix("www.")
        if (sessionIgnoredCfDomains.contains(clean) || sessionIgnoredCfDomains.contains(domain.lowercase().trim())) return true
        return isDomainCfIgnored(domain)
    }

    fun silenceDomainForSession(domain: String?) {
        if (domain.isNullOrBlank()) return
        val clean = domain.lowercase().trim().removePrefix("www.")
        sessionIgnoredCfDomains.add(clean)
        sessionIgnoredCfDomains.add(domain.lowercase().trim())
    }

    fun setDomainCfIgnored(domain: String?, ignored: Boolean) = withDbLock {
        if (domain.isNullOrBlank()) return@withDbLock
        val clean = domain.lowercase().trim().removePrefix("www.")
        val current = getIgnoredCfDomains().toMutableSet()
        if (ignored) {
            current.add(clean)
            current.add(domain.lowercase().trim())
        } else {
            current.remove(clean)
            current.remove(domain.lowercase().trim())
            sessionIgnoredCfDomains.remove(clean)
            sessionIgnoredCfDomains.remove(domain.lowercase().trim())
        }
        val list = current.toList()
        try {
            val json = mapper.writeValueAsString(list)
            rawKeyCache[IGNORED_CF_DOMAINS_KEY] = json
            DatabaseFactory.database.cloudstreamDBQueries.insertKeyValue(IGNORED_CF_DOMAINS_KEY, json)
        } catch (e: Exception) {
            AppLogger.e("Failed to save ignored verification domains", e)
        }
    }
}
