package com.lagradost.cloudstream3.desktop.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Handles disk persistence and in-memory caching for repositories,
 * plugin lists, and local JAR manifests.
 */
internal object RepositoryStorage {
    private val reposFile by lazy { File(PluginFileUtils.getExtensionsDir(), "repos.json") }
    private val repoCacheFile by lazy { File(PluginFileUtils.getExtensionsDir(), "repo_cache.json") }
    private val pluginsCacheFile by lazy { File(PluginFileUtils.getExtensionsDir(), "plugins_cache.json") }

    val repoCache = ConcurrentHashMap<String, Repository>()
    val pluginsCache = ConcurrentHashMap<String, List<SitePlugin>>()
    private val manifestCache = ConcurrentHashMap<String, Pair<Long, Map<String, Any>>>()

    private val _savedRepositories = MutableStateFlow<List<RepositoryData>>(emptyList())
    val savedRepositories: StateFlow<List<RepositoryData>> = _savedRepositories.asStateFlow()

    private val syncMutex = Mutex()

    fun getSavedRepositories(): List<RepositoryData> = _savedRepositories.value

    fun getRepositoryManifest(url: String): Repository? = repoCache[url]

    fun getPluginsJsonUrl(repoUrl: String): String {
        val cached = repoCache[repoUrl]?.pluginLists?.firstOrNull()
        if (!cached.isNullOrBlank()) return cached
        if (repoUrl.endsWith("repo.json", ignoreCase = true)) {
            return repoUrl.replace("repo.json", "builds/plugins.json", ignoreCase = true)
        }
        return repoUrl
    }

    fun refreshSavedRepositoriesFromDisk() {
        _savedRepositories.value = readRepositoriesFromDisk()
    }

    fun readRepositoriesFromDisk(): List<RepositoryData> {
        if (!reposFile.exists()) return emptyList()
        return try {
            val root = PluginNetworkClient.mapper.readTree(reposFile.readText())
            if (!root.isArray) return emptyList()
            if (root.size() > 0 && root[0].isTextual) {
                val urls = PluginNetworkClient.mapper.readValue(root.toString(), object : TypeReference<List<String>>() {})
                val migrated = urls.map { url -> RepositoryData(name = url, url = url) }
                writeRepositoriesToDisk(migrated)
                migrated
            } else {
                PluginNetworkClient.mapper.readValue(root.toString(), object : TypeReference<List<RepositoryData>>() {})
                    .map { normalizeRepositoryData(it) }
            }
        } catch (e: Exception) {
            AppLogger.i("Failed to read repos.json: ${e.message}")
            emptyList()
        }
    }

    fun writeRepositoriesToDisk(repos: List<RepositoryData>) {
        try {
            reposFile.parentFile?.mkdirs()
            PluginNetworkClient.mapper.writeValue(reposFile, repos)
            _savedRepositories.value = repos
        } catch (e: Exception) {
            AppLogger.w("Could not write repositories to disk: ${e.message}")
        }
    }

    fun normalizeRepositoryData(data: RepositoryData): RepositoryData {
        val icon = data.iconUrl?.trim()?.takeIf { it.isNotEmpty() }
        val name = data.name.trim().ifEmpty { data.url }
        return data.copy(iconUrl = icon, name = name)
    }

    suspend fun saveRepository(repository: RepositoryData): Boolean = syncMutex.withLock {
        val incoming = normalizeRepositoryData(repository)
        val current = readRepositoriesFromDisk().toMutableList()
        val index = current.indexOfFirst { it.url == incoming.url }
        val isNew = index < 0
        if (index >= 0) {
            val existing = current[index]
            val updatedName = if (incoming.name.isNotBlank() && incoming.name != incoming.url) incoming.name else existing.name
            val updatedIcon = incoming.iconUrl ?: existing.iconUrl
            if (existing.name == updatedName && existing.iconUrl == updatedIcon) {
                return false
            }
            current[index] = existing.copy(
                name = updatedName,
                iconUrl = updatedIcon,
            )
        } else {
            current.add(incoming)
        }
        writeRepositoriesToDisk(current.distinctBy { it.url })
        isNew
    }

    suspend fun removeRepository(url: String) = syncMutex.withLock {
        val currentList = readRepositoriesFromDisk()
        val repoToRemove = currentList.find { it.url == url }

        val current = currentList.filter { it.url != url }
        writeRepositoriesToDisk(current)

        val repo = repoCache.remove(url)
        if (repo != null) {
            for (listUrl in repo.pluginLists) {
                pluginsCache.remove(listUrl)
            }
        }
        saveCachesToDisk()

        val nameToUse = repo?.name ?: repoToRemove?.name
        if (!nameToUse.isNullOrBlank()) {
            PluginFileUtils.deleteRepositoryDirectory(nameToUse)
        }
    }

    fun loadCachesFromDisk(): Map<String, String> {
        val cachedIcons = mutableMapOf<String, String>()
        try {
            if (repoCacheFile.exists()) {
                val data: Map<String, Repository> = PluginNetworkClient.mapper.readValue(
                    repoCacheFile,
                    object : TypeReference<Map<String, Repository>>() {},
                )
                repoCache.putAll(data)
            }
            if (pluginsCacheFile.exists()) {
                val data: Map<String, List<SitePlugin>> = PluginNetworkClient.mapper.readValue(
                    pluginsCacheFile,
                    object : TypeReference<Map<String, List<SitePlugin>>>() {},
                )
                pluginsCache.putAll(data)
            }
            pluginsCache.values.flatten().forEach { plugin ->
                val icon = plugin.iconUrl
                if (!icon.isNullOrEmpty()) {
                    cachedIcons[plugin.internalName] = icon
                    cachedIcons[plugin.name] = icon
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to load repository caches from disk", e)
        }
        return cachedIcons
    }

    fun saveCachesToDisk() {
        try {
            repoCacheFile.parentFile?.mkdirs()
            PluginNetworkClient.mapper.writeValue(repoCacheFile, HashMap(repoCache))
            PluginNetworkClient.mapper.writeValue(pluginsCacheFile, HashMap(pluginsCache))
        } catch (e: Exception) {
            AppLogger.e("Failed to save repository caches to disk", e)
        }
    }

    fun clearCaches() {
        repoCache.clear()
        pluginsCache.clear()
    }

    fun updateCachedPlugin(listUrl: String, freshPlugin: SitePlugin) {
        val current = pluginsCache[listUrl] ?: return
        val updated = current.map { if (it.internalName == freshPlugin.internalName) freshPlugin else it }
        pluginsCache[listUrl] = updated
        saveCachesToDisk()
    }

    fun readPluginManifest(jarFile: File): Map<String, Any>? {
        if (!jarFile.exists() || jarFile.length() <= 0L) return null
        val lastModified = jarFile.lastModified()
        val cached = manifestCache[jarFile.absolutePath]
        if (cached != null && cached.first == lastModified) {
            return cached.second
        }
        try {
            ZipFile(jarFile).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(manifestEntry).use { input ->
                    val data: Map<String, Any> = PluginNetworkClient.mapper.readValue(input, object : TypeReference<Map<String, Any>>() {})
                    manifestCache[jarFile.absolutePath] = Pair(lastModified, data)
                    return data
                }
            }
        } catch (e: Exception) {
            AppLogger.w("Failed to read plugin manifest from ${jarFile.name}: ${e.message}")
            return null
        }
    }
}
