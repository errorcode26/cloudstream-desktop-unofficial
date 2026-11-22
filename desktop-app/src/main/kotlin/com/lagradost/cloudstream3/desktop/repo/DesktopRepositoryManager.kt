package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginUpdateRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Public repository coordinator facade for desktop extension repositories.
 * Delegates storage, synchronization, and updates to dedicated components.
 */
object DesktopRepositoryManager {
    const val AUTO_UPDATE_COOLDOWN_MS = 4 * 60 * 60 * 1000L // 4 hours

    fun getLastSyncTimestamp(): Long {
        return DesktopDataStore.getKey<Long>(PreferenceKeys.LAST_PLUGIN_SYNC_TIMESTAMP) ?: 0L
    }

    fun setLastSyncTimestamp(time: Long) {
        DesktopDataStore.setKey(PreferenceKeys.LAST_PLUGIN_SYNC_TIMESTAMP, time)
    }

    val savedRepositories: StateFlow<List<RepositoryData>>
        get() = RepositoryStorage.savedRepositories

    val remotePluginIcons: StateFlow<Map<String, String>>
        get() = PluginCatalogSync.remotePluginIcons

    fun getPluginIcon(providerName: String?): String? =
        PluginCatalogSync.getPluginIcon(providerName)

    val syncGeneration: StateFlow<Int>
        get() = PluginCatalogSync.syncGeneration

    fun incrementSyncGeneration() =
        PluginCatalogSync.incrementSyncGeneration()

    fun isIconFailed(url: String): Boolean =
        PluginCatalogSync.isIconFailed(url)

    fun markIconFailed(url: String) =
        PluginCatalogSync.markIconFailed(url)

    data class SyncReport(
        val reposRefreshed: Int,
        val catalogPlugins: Int,
        val pluginsUpdated: Int,
        val iconsCached: Int,
        val newPluginsLoaded: Int,
    ) {
        val summary: String
            get() = "Sync done: $reposRefreshed repos, $catalogPlugins plugins listed, $pluginsUpdated updated, $iconsCached icons, $newPluginsLoaded newly loaded."
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        RepositoryStorage.refreshSavedRepositoriesFromDisk()
        val cachedIcons = RepositoryStorage.loadCachesFromDisk()
        PluginCatalogSync.setInitialIcons(cachedIcons)
    }

    fun getSavedRepositories(): List<RepositoryData> =
        RepositoryStorage.getSavedRepositories()

    fun getRepositoryManifest(url: String): Repository? =
        RepositoryStorage.getRepositoryManifest(url)

    fun getPluginsJsonUrl(repoUrl: String): String =
        RepositoryStorage.getPluginsJsonUrl(repoUrl)

    suspend fun saveRepository(repository: RepositoryData): Boolean =
        RepositoryStorage.saveRepository(repository)

    suspend fun removeRepository(url: String) =
        RepositoryStorage.removeRepository(url)

    suspend fun addRepositoryFromInput(inputUrl: String): List<Repository>? =
        PluginCatalogSync.addRepositoryFromInput(inputUrl)

    suspend fun getCachedRepository(url: String): Repository? =
        PluginCatalogSync.getCachedRepository(url)

    suspend fun getCachedPlugins(listUrl: String): List<SitePlugin> =
        PluginCatalogSync.getCachedPlugins(listUrl)

    fun updateCachedPlugin(listUrl: String, freshPlugin: SitePlugin) {
        RepositoryStorage.updateCachedPlugin(listUrl, freshPlugin)
        PluginCatalogSync.incrementSyncGeneration()
    }

    fun readPluginManifest(jarFile: File): Map<String, Any>? =
        RepositoryStorage.readPluginManifest(jarFile)

    fun getExtensionsDir(): File =
        PluginFileUtils.getExtensionsDir()

    suspend fun downloadPlugin(repoName: String, plugin: SitePlugin): File? =
        PluginFileUtils.downloadPlugin(repoName, plugin)

    fun clearCaches() =
        RepositoryStorage.clearCaches()

    suspend fun checkAndApplyPluginUpdates() {
        autoUpdatePlugins(force = true)
    }

    suspend fun refreshAllRepositoryMetadata(): Int =
        PluginCatalogSync.refreshAllRepositoryMetadata()

    suspend fun rebuildRemotePluginCatalog(
        onRepoFetched: (suspend (repoName: String, completed: Int, total: Int) -> Unit)? = null,
    ): Int = PluginCatalogSync.rebuildRemotePluginCatalog(onRepoFetched)

    val isAutoUpdating: StateFlow<Boolean>
        get() = PluginUpdateEngine.isAutoUpdating

    suspend fun autoUpdatePlugins(force: Boolean = false): List<PluginUpdateRecord> =
        PluginUpdateEngine.autoUpdatePlugins(force)

    suspend fun checkAndApplyActivePluginUpdate(activeJar: File): Boolean =
        PluginUpdateEngine.checkAndApplyActivePluginUpdate(activeJar)

    fun getAllPlugins(): List<Pair<String, SitePlugin>> =
        PluginCatalogSync.getAllPlugins()

    suspend fun syncAll(
        onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null,
        force: Boolean = false,
    ): SyncReport = PluginCatalogSync.syncAll(onProgress, force)
}
