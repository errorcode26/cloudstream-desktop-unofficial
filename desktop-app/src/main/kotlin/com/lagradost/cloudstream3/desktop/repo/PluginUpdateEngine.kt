package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginUpdateRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages plugin auto-updates and hot updates for active plugins,
 * including Win32 file lock releases, sandbox unloading, bytecode verification,
 * atomic replacement, and automated rollback upon verification failure.
 */
internal object PluginUpdateEngine {
    private val _isAutoUpdating = MutableStateFlow(false)
    val isAutoUpdating: StateFlow<Boolean> = _isAutoUpdating.asStateFlow()

    private val lastAutoUpdateTime = AtomicLong(0L)
    private const val AUTO_UPDATE_COOLDOWN = 15 * 60 * 1000L

    private val autoUpdateMutex = Mutex()

    suspend fun autoUpdatePlugins(force: Boolean = false): List<PluginUpdateRecord> = withContext(Dispatchers.IO) {
        autoUpdateMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastAutoUpdateTime.get()
            if (!force && last > 0L && now - last < AUTO_UPDATE_COOLDOWN) {
                AppLogger.i("Skipping autoUpdatePlugins; last auto-update check was ${(now - last) / 60000}m ago (cooldown: 15m)")
                return@withContext emptyList()
            }

            _isAutoUpdating.value = true
            try {
                lastAutoUpdateTime.set(now)
                val updatedList = mutableListOf<PluginUpdateRecord>()
                val savedRepos = RepositoryStorage.getSavedRepositories()

                savedRepos.forEach repoLoop@{ saved ->
                    try {
                        val repo = PluginNetworkClient.fetchRepository(saved.url) ?: return@repoLoop
                        val remotePlugins = coroutineScope {
                            repo.pluginLists.map { listUrl ->
                                async {
                                    try {
                                        val fetched = PluginNetworkClient.fetchPlugins(listUrl)
                                        if (fetched.isNotEmpty()) {
                                            RepositoryStorage.pluginsCache[listUrl] = fetched
                                            fetched
                                        } else {
                                            PluginCatalogSync.getCachedPlugins(listUrl)
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.d("DesktopRepoManager", "Plugins list fetch fallback for $listUrl: ${e.message}")
                                        PluginCatalogSync.getCachedPlugins(listUrl)
                                    }
                                }
                            }.awaitAll().flatten()
                        }.distinctBy { it.internalName }

                        val repoDir = PluginFileUtils.resolveRepoDir(repo.name) ?: run {
                            AppLogger.w("Skipping repository with unsafe name: '${repo.name}'")
                            return@repoLoop
                        }
                        if (!repoDir.exists()) repoDir.mkdirs()

                        remotePlugins.forEach { remotePlugin ->
                            val localJar = File(repoDir, "${remotePlugin.internalName}.jar").takeIf { it.exists() }
                                ?: File(repoDir, "${remotePlugin.internalName}.cs3").takeIf { it.exists() }
                                ?: File(repoDir, "${remotePlugin.internalName}-jvm.jar").takeIf { it.exists() }
                            if (localJar != null && localJar.exists()) {
                                val localManifest = RepositoryStorage.readPluginManifest(localJar)
                                val localVersion = localManifest?.get("version")?.toString()?.toIntOrNull() ?: 0
                                val baseName = remotePlugin.internalName
                                val staleFailed = File(repoDir, "$baseName.d2j_failed")
                                val globalFailed = File(com.lagradost.common.platform.PlatformPaths.pluginCacheDir, "$baseName.d2j_failed")
                                val isStaleOrCorrupt = localJar.length() <= 0L ||
                                    localManifest == null ||
                                    staleFailed.exists() ||
                                    globalFailed.exists() ||
                                    !PluginFileUtils.validatePluginArchive(localJar, remotePlugin.internalName)

                                if (remotePlugin.version > localVersion || isStaleOrCorrupt) {
                                    val reason = if (isStaleOrCorrupt) "repairing stale/corrupt plugin" else "v$localVersion -> v${remotePlugin.version}"
                                    AppLogger.i("Auto-updating ${remotePlugin.internalName} in ${saved.name} ($reason)...")

                                    val wasLoaded = com.lagradost.runtime.loader.ExtensionLoader.plugins.containsKey(localJar.absolutePath) ||
                                        com.lagradost.runtime.loader.ExtensionLoader.plugins.containsKey(
                                            try {
                                                localJar.canonicalPath
                                            } catch (e: Throwable) {
                                                AppLogger.d("DesktopRepoManager", "Canonical path note: ${e.message}")
                                                localJar.absolutePath
                                            },
                                        )
                                    val wasTrusted = com.lagradost.runtime.loader.ExtensionLoader.isTrusted(localJar, remotePlugin.internalName, manifestName = remotePlugin.name)

                                    if (wasLoaded) {
                                        if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                                            try {
                                                com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.unloadPlugin(localJar)
                                            } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "Sandboxed unload note during update: ${e.message}") }
                                        }
                                        com.lagradost.runtime.loader.ExtensionLoader.unloadPlugin(localJar.absolutePath)
                                        try {
                                            @Suppress("ExplicitGarbageCollectionCall")
                                            System.gc()
                                            Thread.sleep(100)
                                        } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "GC sleep note: ${e.message}") }
                                    }

                                    val backupFile = File(repoDir, "${localJar.name}.bak")
                                    try {
                                        Files.copy(localJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                    } catch (e: Throwable) { AppLogger.w("DesktopRepoManager", "Failed to backup ${localJar.name}: ${e.message}") }

                                    val cacheDir = com.lagradost.common.platform.PlatformPaths.pluginCacheDir
                                    val staleArtifacts = listOf(
                                        File(cacheDir, "$baseName-jvm.jar"),
                                        File(cacheDir, "$baseName-secure.jar"),
                                        File(cacheDir, "$baseName.d2j_failed"),
                                        File(repoDir, "$baseName-jvm.jar"),
                                        File(repoDir, "$baseName-secure.jar"),
                                        File(repoDir, "$baseName.d2j_failed"),
                                    )
                                    staleArtifacts.forEach { f ->
                                        try {
                                            if (f.exists()) f.delete()
                                        } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "Stale artifact cleanup note: ${e.message}") }
                                    }

                                    val newJar = PluginFileUtils.downloadPlugin(saved.name, remotePlugin)
                                    if (newJar == null) {
                                        try {
                                            if (backupFile.exists() && !localJar.exists()) {
                                                Files.move(backupFile.toPath(), localJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                            }
                                            backupFile.delete()
                                        } catch (e: Throwable) { AppLogger.w("DesktopRepoManager", "Backup restore note: ${e.message}") }
                                        updatedList.add(
                                            PluginUpdateRecord(
                                                pluginName = remotePlugin.name,
                                                version = remotePlugin.version,
                                                iconUrl = remotePlugin.iconUrl,
                                                timestamp = System.currentTimeMillis(),
                                                isSuccess = false,
                                                errorMessage = "Download failed (network or server error)",
                                            ),
                                        )
                                    } else {
                                        try {
                                            if (wasTrusted) {
                                                com.lagradost.runtime.loader.ExtensionLoader.addTrusted(newJar, remotePlugin.internalName, manifestName = remotePlugin.name)
                                            }

                                            if (wasLoaded) {
                                                if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                                                    com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.loadPlugin(newJar, forceRetry = true)
                                                } else {
                                                    com.lagradost.runtime.loader.ExtensionLoader.loadAndInit(newJar, forceBypassSecurity = wasTrusted, forceRetry = true)
                                                }
                                                try {
                                                    ActiveProviderRepository.refreshProviders()
                                                } catch (e: Throwable) { AppLogger.w("DesktopRepoManager", "Provider refresh note after update: ${e.message}") }
                                            } else {
                                                com.lagradost.runtime.loader.ExtensionLoader.transpileOrVerify(newJar, forceRetry = true)
                                            }

                                            try {
                                                backupFile.delete()
                                            } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "Backup file delete note: ${e.message}") }
                                            updatedList.add(
                                                PluginUpdateRecord(
                                                    pluginName = remotePlugin.name,
                                                    version = remotePlugin.version,
                                                    iconUrl = remotePlugin.iconUrl,
                                                    timestamp = System.currentTimeMillis(),
                                                    isSuccess = true,
                                                    errorMessage = null,
                                                ),
                                            )
                                            AppLogger.i("Auto-update and bytecode verification succeeded for ${remotePlugin.internalName} (v$localVersion -> v${remotePlugin.version})")
                                        } catch (e: Exception) {
                                            AppLogger.e("Bytecode transpilation/verification failed for ${remotePlugin.internalName}; reverting to previous version", e)
                                            try {
                                                newJar.delete()
                                                staleArtifacts.forEach { f ->
                                                    try {
                                                        if (f.exists()) f.delete()
                                                    } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "Rollback stale artifact cleanup note: ${e.message}") }
                                                }
                                                if (backupFile.exists()) {
                                                    Files.move(backupFile.toPath(), localJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                                    if (wasLoaded) {
                                                        try {
                                                            if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                                                                com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.loadPlugin(localJar)
                                                            } else {
                                                                com.lagradost.runtime.loader.ExtensionLoader.loadAndInit(localJar, forceBypassSecurity = wasTrusted)
                                                            }
                                                            ActiveProviderRepository.refreshProviders()
                                                        } catch (e: Throwable) { AppLogger.w("DesktopRepoManager", "Rollback refreshProviders note: ${e.message}") }
                                                    }
                                                }
                                            } catch (rollbackErr: Throwable) {
                                                AppLogger.e("Failed to rollback ${remotePlugin.internalName}", rollbackErr)
                                            } finally {
                                                try {
                                                    backupFile.delete()
                                                } catch (e: Throwable) { AppLogger.d("DesktopRepoManager", "Rollback backup delete note: ${e.message}") }
                                            }
                                            updatedList.add(
                                                PluginUpdateRecord(
                                                    pluginName = remotePlugin.name,
                                                    version = remotePlugin.version,
                                                    iconUrl = remotePlugin.iconUrl,
                                                    timestamp = System.currentTimeMillis(),
                                                    isSuccess = false,
                                                    errorMessage = "Bytecode verification failed: ${e.message ?: e.javaClass.simpleName}",
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        AppLogger.w("Auto-update check failed for repository '${saved.name}': ${e.message}")
                    }
                }
                if (updatedList.isNotEmpty()) {
                    DesktopDataStore.addUpdateHistory(updatedList)
                    DesktopDataStore.setUnreadUpdates(true)
                }
                updatedList
            } finally {
                _isAutoUpdating.value = false
            }
        }
    }

    suspend fun checkAndApplyActivePluginUpdate(activeJar: File): Boolean = withContext(Dispatchers.IO) {
        if (!activeJar.exists()) return@withContext false

        val localManifest = RepositoryStorage.readPluginManifest(activeJar) ?: return@withContext false
        val localVersion = localManifest["version"]?.toString()?.toIntOrNull() ?: 0
        val internalName = localManifest["internalName"]?.toString()
            ?: activeJar.nameWithoutExtension.removeSuffix("-jvm").removeSuffix("-secure")
        val displayName = localManifest["name"]?.toString() ?: internalName

        val repoFolder = activeJar.parentFile?.name.orEmpty()
        val savedRepos = RepositoryStorage.getSavedRepositories()
        val targetRepo = savedRepos.firstOrNull { it.name.equals(repoFolder, ignoreCase = true) }
            ?: savedRepos.firstOrNull()
            ?: return@withContext false

        AppLogger.i("DesktopRepoManager", "Checking active plugin update for '$displayName' (v$localVersion) from '${targetRepo.name}'...")
        com.lagradost.cloudstream3.desktop.init.PluginWarmupState.setCheckingUpdates()

        val remotePlugin = try {
            withTimeoutOrNull(3_500L) {
                val repo = RepositoryStorage.repoCache[targetRepo.url] ?: PluginNetworkClient.fetchRepository(targetRepo.url) ?: return@withTimeoutOrNull null
                RepositoryStorage.repoCache[targetRepo.url] = repo
                val pluginLists = repo.pluginLists.takeIf { it.isNotEmpty() } ?: listOf(RepositoryStorage.getPluginsJsonUrl(targetRepo.url))

                var found: SitePlugin? = null
                for (listUrl in pluginLists) {
                    val fetched = try {
                        PluginNetworkClient.fetchPlugins(listUrl)
                    } catch (_: Throwable) {
                        RepositoryStorage.pluginsCache[listUrl] ?: emptyList()
                    }
                    if (fetched.isNotEmpty()) {
                        RepositoryStorage.pluginsCache[listUrl] = fetched
                        found = fetched.firstOrNull { it.internalName.equals(internalName, ignoreCase = true) }
                        if (found != null) break
                    }
                }
                found
            }
        } catch (e: Exception) {
            AppLogger.w("DesktopRepoManager", "Active plugin update check network note for $internalName: ${e.message}")
            null
        } ?: return@withContext false

        if (remotePlugin.version <= localVersion) {
            AppLogger.d("DesktopRepoManager", "Active plugin '$displayName' is up to date (v$localVersion).")
            com.lagradost.cloudstream3.desktop.init.PluginWarmupState.reset()
            return@withContext false
        }

        AppLogger.i("DesktopRepoManager", "Active plugin update found for '$displayName': v$localVersion -> v${remotePlugin.version}")

        com.lagradost.cloudstream3.desktop.init.PluginWarmupState.setUpdateInstalling(displayName, localVersion, remotePlugin.version)

        val repoDir = activeJar.parentFile ?: return@withContext false
        val baseName = remotePlugin.internalName
        val backupFile = File(repoDir, "${activeJar.name}.bak")

        val wasLoaded = com.lagradost.runtime.loader.ExtensionLoader.plugins.containsKey(activeJar.absolutePath) ||
            com.lagradost.runtime.loader.ExtensionLoader.plugins.containsKey(
                try { activeJar.canonicalPath } catch (_: Throwable) { activeJar.absolutePath }
            )
        val wasTrusted = com.lagradost.runtime.loader.ExtensionLoader.isTrusted(activeJar, remotePlugin.internalName, manifestName = remotePlugin.name)

        val cacheDir = com.lagradost.common.platform.PlatformPaths.pluginCacheDir
        val staleArtifacts = listOf(
            File(cacheDir, "$baseName-jvm.jar"),
            File(cacheDir, "$baseName-secure.jar"),
            File(cacheDir, "$baseName.d2j_failed"),
            File(repoDir, "$baseName-jvm.jar"),
            File(repoDir, "$baseName-secure.jar"),
            File(repoDir, "$baseName.d2j_failed"),
        )

        try {
            if (wasLoaded) {
                if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                    try {
                        com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.unloadPlugin(activeJar)
                    } catch (e: Throwable) {
                        AppLogger.d("DesktopRepoManager", "Sandboxed unload note during active update: ${e.message}")
                    }
                }
                com.lagradost.runtime.loader.ExtensionLoader.unloadPlugin(activeJar.absolutePath)
                @Suppress("ExplicitGarbageCollectionCall")
                System.gc()
                delay(120L)
            }

            try {
                Files.copy(activeJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Throwable) {
                AppLogger.w("DesktopRepoManager", "Failed to backup ${activeJar.name}: ${e.message}")
            }

            staleArtifacts.forEach { f ->
                try { if (f.exists()) f.delete() } catch (_: Throwable) {}
            }

            val newJar = PluginFileUtils.downloadPlugin(targetRepo.name, remotePlugin)
            if (newJar == null || !newJar.exists() || newJar.length() <= 0L) {
                throw IOException("Failed to download updated JAR from remote repository.")
            }

            if (wasTrusted) {
                com.lagradost.runtime.loader.ExtensionLoader.addTrusted(newJar, remotePlugin.internalName, manifestName = remotePlugin.name)
            }

            if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.loadPlugin(newJar, forceRetry = true)
            } else {
                com.lagradost.runtime.loader.ExtensionLoader.loadAndInit(newJar, forceBypassSecurity = wasTrusted, forceRetry = true)
            }

            try {
                ActiveProviderRepository.refreshProviders()
            } catch (e: Throwable) {
                AppLogger.w("DesktopRepoManager", "Provider refresh note after update: ${e.message}")
            }

            try { backupFile.delete() } catch (_: Throwable) {}
            DesktopDataStore.addUpdateHistory(
                listOf(
                    PluginUpdateRecord(
                        pluginName = remotePlugin.name,
                        version = remotePlugin.version,
                        iconUrl = remotePlugin.iconUrl,
                        timestamp = System.currentTimeMillis(),
                        isSuccess = true,
                        errorMessage = null,
                    ),
                ),
            )

            com.lagradost.cloudstream3.desktop.init.PluginWarmupState.setUpdateSuccess(displayName, remotePlugin.version)
            AppLogger.i("DesktopRepoManager", "Successfully hot-updated active plugin '$displayName' to v${remotePlugin.version}")
            true
        } catch (t: Throwable) {
            AppLogger.e("DesktopRepoManager", "Active plugin update failed for '$displayName'; rolling back to previous version", t)

            try {
                staleArtifacts.forEach { f ->
                    try { if (f.exists()) f.delete() } catch (_: Throwable) {}
                }
                if (backupFile.exists()) {
                    Files.move(backupFile.toPath(), activeJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (wasLoaded) {
                        if (com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.isEnabled()) {
                            com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.loadPlugin(activeJar)
                        } else {
                            com.lagradost.runtime.loader.ExtensionLoader.loadAndInit(activeJar, forceBypassSecurity = wasTrusted)
                        }
                        try {
                            ActiveProviderRepository.refreshProviders()
                        } catch (_: Throwable) {}
                    }
                }
            } catch (rollbackErr: Throwable) {
                AppLogger.e("DesktopRepoManager", "Critical rollback failure for '$displayName'", rollbackErr)
            } finally {
                try { backupFile.delete() } catch (_: Throwable) {}
            }

            com.lagradost.cloudstream3.desktop.init.PluginWarmupState.setUpdateFailed(displayName)
            AppToastManager.showWarning(
                "Update failed for $displayName. Restored previous version.",
                durationMs = 5000L,
            )
            false
        }
    }
}
