package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles remote repository synchronization, metadata scraping,
 * remote plugin discovery, and icon caching.
 */
internal object PluginCatalogSync {
    private val networkThrottle = Semaphore(3)
    val fetchMutexes = ConcurrentHashMap<String, Mutex>()
    val syncMutex = Mutex()

    private val _remotePluginIcons = MutableStateFlow<Map<String, String>>(emptyMap())
    val remotePluginIcons: StateFlow<Map<String, String>> = _remotePluginIcons.asStateFlow()

    private val _syncGeneration = MutableStateFlow(0)
    val syncGeneration: StateFlow<Int> = _syncGeneration.asStateFlow()

    private val _failedIconUrls = ConcurrentHashMap.newKeySet<String>()

    fun incrementSyncGeneration() {
        _syncGeneration.update { it + 1 }
    }

    fun isIconFailed(url: String): Boolean = _failedIconUrls.contains(url)

    fun markIconFailed(url: String) {
        _failedIconUrls.add(url)
    }

    fun setInitialIcons(cachedIcons: Map<String, String>) {
        _remotePluginIcons.value = cachedIcons + scanLocalPluginIcons()
        incrementSyncGeneration()
    }

    fun getPluginIcon(providerName: String?): String? {
        if (providerName.isNullOrBlank()) return null
        val clean = if (providerName.contains("::")) providerName.substringAfter("::") else providerName
        val icons = _remotePluginIcons.value
        icons[clean]?.let { return it }
        icons[providerName]?.let { return it }
        val sanitizeRegex = Regex("[^a-zA-Z0-9]")
        val pName = clean.lowercase().replace(sanitizeRegex, "").replace("provider", "").replace("plugin", "")
        return icons.entries.firstOrNull { (k, _) ->
            val kName = k.lowercase().replace(sanitizeRegex, "").replace("provider", "").replace("plugin", "")
            if (kName.length < 3) return@firstOrNull false
            pName.isNotEmpty() && (pName.contains(kName) || kName.contains(pName))
        }?.value
    }

    fun scanLocalPluginIcons(): Map<String, String> {
        val icons = mutableMapOf<String, String>()
        val extensionsDir = PluginFileUtils.getExtensionsDir()
        if (!extensionsDir.exists()) return icons
        extensionsDir.walkTopDown()
            .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
            .filter {
                !it.name.endsWith("-jvm.jar") &&
                    !it.name.contains(".pending_delete") &&
                    !it.name.endsWith(".uninstalled") &&
                    !File(it.parentFile, "${it.name}.uninstalled").exists() &&
                    !File(it.parentFile, "${it.nameWithoutExtension}.uninstalled").exists()
            }
            .forEach { jar ->
                val manifest = RepositoryStorage.readPluginManifest(jar) ?: return@forEach
                val iconUrl = manifest["iconUrl"] as? String ?: return@forEach
                val internalName = manifest["internalName"] as? String
                val name = manifest["name"] as? String
                if (internalName != null) icons[internalName] = iconUrl
                if (name != null) icons[name] = iconUrl
            }
        return icons
    }

    suspend fun addRepositoryFromInput(inputUrl: String): List<Repository>? = withContext(Dispatchers.IO) {
        val trimmed = inputUrl.trim()
        if (trimmed.isEmpty()) return@withContext null
        val resolvedUrl = PluginNetworkClient.parseRepoUrl(trimmed) ?: trimmed

        val request = Request.Builder().url(resolvedUrl).build()
        var body: String? = null
        try {
            PluginNetworkClient.redirectClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) body = response.body.string()
            }
        } catch (e: Exception) {
            AppLogger.i("Failed to fetch $resolvedUrl: ${e.message}")
        }

        val responseBody = body
        if (responseBody != null && responseBody.trimStart().startsWith("[")) {
            try {
                val nodes = PluginNetworkClient.mapper.readTree(responseBody)
                val urls = nodes.mapNotNull { it.get("url")?.asText() }
                val addedRepos = mutableListOf<Repository>()
                for (url in urls) {
                    addSingleRepository(url)?.let { addedRepos.add(it) }
                }
                if (addedRepos.isNotEmpty()) {
                    rebuildRemotePluginCatalog()
                    incrementSyncGeneration()
                }
                return@withContext addedRepos.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                AppLogger.i("Failed to parse repository bundle: ${e.message}")
            }
        }

        val repo = addSingleRepository(resolvedUrl)
        if (repo != null) {
            rebuildRemotePluginCatalog()
            incrementSyncGeneration()
        }
        return@withContext if (repo != null) listOf(repo) else null
    }

    suspend fun addSingleRepository(url: String): Repository? {
        val resolvedUrl = PluginNetworkClient.parseRepoUrl(url) ?: url
        val manifest = PluginNetworkClient.fetchRepository(resolvedUrl) ?: return null

        RepositoryStorage.repoCache[resolvedUrl] = manifest
        RepositoryStorage.saveRepository(RepositoryData(iconUrl = manifest.iconUrl, name = manifest.name, url = resolvedUrl))

        manifest.pluginLists.forEach { listUrl ->
            try {
                getCachedPlugins(listUrl)
            } catch (e: Exception) {
                AppLogger.e("Failed to pre-fetch plugins for $listUrl", e)
            }
        }
        RepositoryStorage.saveCachesToDisk()
        incrementSyncGeneration()
        return manifest
    }

    suspend fun getCachedRepository(url: String): Repository? {
        if (RepositoryStorage.repoCache.containsKey(url)) return RepositoryStorage.repoCache[url]
        val repo = PluginNetworkClient.fetchRepository(url)
        if (repo != null) {
            RepositoryStorage.repoCache[url] = repo
            RepositoryStorage.saveCachesToDisk()
        }
        return repo
    }

    suspend fun getCachedPlugins(listUrl: String): List<SitePlugin> {
        RepositoryStorage.pluginsCache[listUrl]?.let { return it }
        val mutex = fetchMutexes.computeIfAbsent(listUrl) { Mutex() }
        val plugins = mutex.withLock {
            RepositoryStorage.pluginsCache[listUrl]?.let { return@withLock it }
            val fetched = PluginNetworkClient.fetchPlugins(listUrl)
            RepositoryStorage.pluginsCache[listUrl] = fetched
            RepositoryStorage.saveCachesToDisk()
            fetched
        }

        val newIcons = _remotePluginIcons.value.toMutableMap()
        plugins.forEach { remotePlugin ->
            val remoteIcon = remotePlugin.iconUrl
            if (!remoteIcon.isNullOrEmpty()) {
                newIcons[remotePlugin.internalName] = remoteIcon
                newIcons[remotePlugin.name] = remoteIcon
            }
        }
        _remotePluginIcons.value = newIcons

        return plugins
    }

    suspend fun refreshAllRepositoryMetadata(): Int = withContext(Dispatchers.IO) {
        val updates = ConcurrentHashMap<String, Pair<String?, String>>()
        coroutineScope {
            RepositoryStorage.getSavedRepositories().map { saved ->
                async {
                    val manifest = networkThrottle.withPermit {
                        PluginNetworkClient.fetchRepository(saved.url)
                    } ?: return@async
                    RepositoryStorage.repoCache[saved.url] = manifest
                    updates[saved.url] = Pair(manifest.iconUrl, manifest.name)
                    AppLogger.i("Refreshed repo metadata: ${manifest.name}")
                }
            }.awaitAll()
        }
        if (updates.isNotEmpty()) {
            synchronized(this@PluginCatalogSync) {
                val current = RepositoryStorage.readRepositoriesFromDisk().toMutableList()
                for ((url, pair) in updates) {
                    val (iconUrl, name) = pair
                    val index = current.indexOfFirst { it.url == url }
                    if (index >= 0) {
                        val existing = current[index]
                        current[index] = existing.copy(
                            name = if (name.isNotBlank() && name != url) name else existing.name,
                            iconUrl = iconUrl ?: existing.iconUrl,
                        )
                    }
                }
                RepositoryStorage.writeRepositoriesToDisk(current.distinctBy { it.url })
            }
        }
        updates.size
    }

    suspend fun rebuildRemotePluginCatalog(
        onRepoFetched: (suspend (repoName: String, completed: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val iconMap = ConcurrentHashMap<String, String>()
        val total = AtomicInteger(0)
        val savedRepos = RepositoryStorage.getSavedRepositories()
        val completedCounter = AtomicInteger(0)

        coroutineScope {
            savedRepos.map { saved ->
                async {
                    try {
                        val repo = networkThrottle.withPermit {
                            PluginNetworkClient.fetchRepository(saved.url)
                        }
                        if (repo != null) {
                            RepositoryStorage.repoCache[saved.url] = repo
                            repo.pluginLists.map { listUrl ->
                                async {
                                    try {
                                        val plugins = networkThrottle.withPermit {
                                            PluginNetworkClient.fetchPlugins(listUrl)
                                        }
                                        RepositoryStorage.pluginsCache[listUrl] = plugins
                                        plugins.forEach { plugin ->
                                            val icon = plugin.iconUrl
                                            if (!icon.isNullOrEmpty()) {
                                                iconMap[plugin.internalName] = icon
                                                iconMap[plugin.name] = icon
                                            }
                                        }
                                        total.addAndGet(plugins.size)
                                    } catch (e: Exception) {
                                        AppLogger.w("Failed to fetch or parse plugins list from $listUrl: ${e.message}")
                                    }
                                }
                            }.awaitAll()
                        }
                    } catch (e: Exception) {
                        AppLogger.w("Failed to fetch repository manifest from ${saved.url}: ${e.message}")
                    } finally {
                        val done = completedCounter.incrementAndGet()
                        _remotePluginIcons.value = iconMap + scanLocalPluginIcons()
                        _syncGeneration.update { it + 1 }
                        onRepoFetched?.invoke(saved.name, done, savedRepos.size)
                    }
                }
            }.awaitAll()
        }

        _remotePluginIcons.value = iconMap + scanLocalPluginIcons()
        total.get()
    }

    fun getAllPlugins(): List<Pair<String, SitePlugin>> {
        val list = mutableListOf<Pair<String, SitePlugin>>()
        for (saved in RepositoryStorage.getSavedRepositories()) {
            val repo = RepositoryStorage.repoCache[saved.url]
            val lists = repo?.pluginLists?.takeIf { it.isNotEmpty() } ?: listOf(RepositoryStorage.getPluginsJsonUrl(saved.url))
            for (listUrl in lists) {
                RepositoryStorage.pluginsCache[listUrl]?.forEach { list.add(Pair(saved.name, it)) }
            }
        }
        return list.distinctBy { Pair(it.first, it.second.internalName) }
            .sortedWith(compareBy({ it.second.name.lowercase() }, { it.first }))
    }

    suspend fun syncAll(
        onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null,
        force: Boolean = false,
    ): DesktopRepositoryManager.SyncReport = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            val needsInitialCatalog = RepositoryStorage.pluginsCache.isEmpty() || RepositoryStorage.repoCache.isEmpty()
            if (!force && !needsInitialCatalog && now - DesktopRepositoryManager.getLastSyncTimestamp() < DesktopRepositoryManager.AUTO_UPDATE_COOLDOWN_MS) {
                AppLogger.i("Skipping syncAll; last sync was ${(now - DesktopRepositoryManager.getLastSyncTimestamp()) / 60000}m ago (cooldown: 4h)")
                return@withContext DesktopRepositoryManager.SyncReport(
                    reposRefreshed = 0,
                    catalogPlugins = 0,
                    pluginsUpdated = 0,
                    iconsCached = _remotePluginIcons.value.size,
                    newPluginsLoaded = 0,
                )
            }

            DesktopRepositoryManager.setLastSyncTimestamp(now)
            PluginFileUtils.cleanStaleTempFiles()
            val reposRefreshed = refreshAllRepositoryMetadata()
            val catalogPlugins = rebuildRemotePluginCatalog { _, done, total ->
                onProgress?.invoke(done, total)
            }
            val pluginsUpdated = PluginUpdateEngine.autoUpdatePlugins(force = true)
            val newPluginsLoaded = com.lagradost.runtime.loader.ExtensionLoader.rescanAndLoadNewPlugins(PluginFileUtils.getExtensionsDir())
            val iconsCached = _remotePluginIcons.value.size

            _syncGeneration.update { it + 1 }
            RepositoryStorage.saveCachesToDisk()

            DesktopRepositoryManager.SyncReport(
                reposRefreshed = reposRefreshed,
                catalogPlugins = catalogPlugins,
                pluginsUpdated = pluginsUpdated.size,
                iconsCached = iconsCached,
                newPluginsLoaded = newPluginsLoaded,
            )
        }
    }
}
