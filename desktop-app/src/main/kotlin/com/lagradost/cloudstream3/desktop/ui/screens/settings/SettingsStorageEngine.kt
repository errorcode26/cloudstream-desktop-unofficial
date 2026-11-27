package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.StorageCacheType
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.StorageMetrics
import com.lagradost.cloudstream3.desktop.updates.UnifiedUpdateManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File

object SettingsStorageEngine {
    val imageCacheDir: File get() = File(PlatformPaths.appDataDir, "image_cache")
    val networkCacheDir: File get() = File(PlatformPaths.cacheDir, "network")
    val dbFile: File get() = File(PlatformPaths.dataDir, "cloudstream.db")
    val logsDir: File get() = PlatformPaths.logsDir
    val streamCacheDir: File get() = File(PlatformPaths.cacheDir, "stream_cache")
    val updatesDir: File get() = UnifiedUpdateManager.getUpdatesDirectory()

    fun getDirectorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            AppLogger.d("SettingsStorageEngine: Directory size calculation failed for ${dir.name}: ${e.message}")
            0L
        }
    }

    fun calculateStorageMetrics(): StorageMetrics {
        val img = getDirectorySizeBytes(imageCacheDir)
        val net = getDirectorySizeBytes(networkCacheDir)
        val db = if (dbFile.exists()) dbFile.length() else 0L
        val logs = getDirectorySizeBytes(logsDir)
        val stream = getDirectorySizeBytes(streamCacheDir)
        val upd = getDirectorySizeBytes(updatesDir)
        return StorageMetrics(
            imageCacheBytes = img,
            networkCacheBytes = net,
            databaseBytes = db,
            logsBytes = logs,
            streamCacheBytes = stream,
            updatesBytes = upd,
        )
    }

    fun clearCache(type: StorageCacheType): Pair<Boolean, String> {
        val (dir, label) = when (type) {
            StorageCacheType.IMAGE -> imageCacheDir to "Image cache"
            StorageCacheType.NETWORK -> networkCacheDir to "Network cache"
            StorageCacheType.LOGS -> logsDir to "Logs"
            StorageCacheType.STREAM -> streamCacheDir to "Stream & temp cache"
            StorageCacheType.UPDATES -> updatesDir to "Update installers"
        }
        return try {
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.deleteRecursively() }
            }
            true to "$label cleared"
        } catch (e: Exception) {
            false to "Failed to clear $label: ${e.message}"
        }
    }

    fun vacuumDatabase(): Pair<Boolean, String> {
        return try {
            DesktopDataStore.vacuumDatabase()
            true to "Database defragmented and compacted"
        } catch (e: Exception) {
            false to "Optimization failed: ${e.message}"
        }
    }

    fun resetSettingsToDefault(): Pair<Boolean, String> {
        return try {
            DesktopDataStore.clearAllSettings()
            com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.reloadFromDataStore()
            true to "Settings restored to defaults"
        } catch (e: Exception) {
            false to "Failed to reset settings: ${e.message}"
        }
    }

    fun executeFactoryReset() {
        val target = PlatformPaths.appDataDir
        try {
            com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager.close()
        } catch (e: Throwable) {
            AppLogger.d("Settings: Teardown step notice during reset: ${e.message}")
        }
        try {
            DesktopTorrentEngine.binary.stop()
        } catch (e: Throwable) {
            AppLogger.d("Settings: Teardown step notice during reset: ${e.message}")
        }
        try {
            com.lagradost.runtime.loader.ExtensionLoader.unloadAllPlugins()
        } catch (e: Throwable) {
            AppLogger.d("Settings: Teardown step notice during reset: ${e.message}")
        }

        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        @Suppress("deprecation")
        System.runFinalization()

        if (target.exists()) {
            try {
                target.deleteRecursively()
            } catch (e: Throwable) {
                AppLogger.w("Settings: Direct appDataDir delete notice during reset: ${e.message}")
            }
        }

        try {
            val isWindows = PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            if (isWindows) {
                val escapedPath = target.absolutePath.replace("'", "''")
                ProcessBuilder(
                    "powershell.exe",
                    "-WindowStyle", "Hidden",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Start-Sleep -Seconds 2; Stop-Process -Name torrserver -Force -ErrorAction SilentlyContinue; if (Test-Path -LiteralPath '$escapedPath') { Remove-Item -LiteralPath '$escapedPath' -Recurse -Force -ErrorAction SilentlyContinue }"
                ).directory(tempDir).start()
            } else {
                val escapedPath = target.absolutePath.replace("\"", "\\\"")
                ProcessBuilder(
                    "sh",
                    "-c",
                    "sleep 2; if [ -d \"$escapedPath\" ]; then rm -rf \"$escapedPath\" 2>/dev/null; fi"
                ).directory(tempDir).start()
            }
        } catch (e: Throwable) {
            AppLogger.e("Settings: Background delayed wipe launcher failed", e)
        }

        kotlin.system.exitProcess(0)
    }
}
