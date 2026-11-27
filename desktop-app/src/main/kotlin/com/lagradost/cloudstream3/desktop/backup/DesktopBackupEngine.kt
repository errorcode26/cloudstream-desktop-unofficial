package com.lagradost.cloudstream3.desktop.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.desktop.AppConfig
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.downloader.DesktopDownloadManager
import com.lagradost.cloudstream3.desktop.init.loadClonedSites
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.cloudstream3.desktop.models.CustomSite
import com.lagradost.cloudstream3.desktop.profile.Profile
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.repo.Repository
import com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.CustomFontManager
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.db.DesktopDatabase
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DesktopBackupEngine {
    const val BACKUP_EXTENSION = "cs3backup"
    const val CURRENT_BACKUP_FORMAT_VERSION = 1

    private const val ENTRY_MANIFEST = "backup_manifest.json"
    private const val ENTRY_SQLITE_DB = "db/cloudstream.db"
    private const val ENTRY_PORTABLE_JSON = "db/portable_export.json"
    private const val ENTRY_REPOS_JSON = "extensions/repos.json"
    private const val ENTRY_REPO_CACHE_JSON = "extensions/repo_cache.json"
    private const val ENTRY_PLUGINS_CACHE_JSON = "extensions/plugins_cache.json"
    private const val ENTRY_EXTENSIONS_MANIFEST = "extensions/extensions_manifest.json"
    private const val PREFIX_EXTENSIONS_FILES = "extensions/files/"
    private const val PREFIX_AVATARS = "assets/avatars/"
    private const val PREFIX_FONTS = "assets/fonts/"
    private const val ENTRY_COOKIES_JSON = "network/cookies.json"

    private const val PREF_PROFILES = "cs_desktop_profiles_v1"
    private const val MAX_AUTO_BACKUPS_PER_KIND = 5
    private const val DAILY_BACKUP_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val operationMutex = Mutex()
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun getBackupsRootDir(): File = PlatformPaths.backupsDir

    fun getAutoBackupsDir(): File = File(PlatformPaths.backupsDir, "auto").also { it.mkdirs() }

    fun defaultBackupFileName(kind: BackupKind = BackupKind.MANUAL): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val prefix = when (kind) {
            BackupKind.MANUAL -> "cloudstream_backup"
            BackupKind.AUTO_STARTUP -> "auto"
            BackupKind.PRE_UPDATE -> "pre_update"
            BackupKind.PRE_RESTORE -> "pre_restore"
        }
        return "${prefix}_$stamp.$BACKUP_EXTENSION"
    }

    internal fun isCredentialKey(key: String): Boolean {
        val unprefixed = key.substringAfter('/', missingDelimiterValue = key).lowercase(Locale.US)
        if (unprefixed == AccountManager.ACCOUNT_TOKEN || unprefixed.startsWith("${AccountManager.ACCOUNT_TOKEN}_")) {
            return true
        }
        return unprefixed == DesktopDataStore.PREF_TORBOX_API_KEY ||
            unprefixed == MetadataConfig.KEY_TMDB_API_KEY ||
            unprefixed == "pref_subsource_api_key" ||
            unprefixed.endsWith("_api_key") ||
            unprefixed.endsWith("_access_token") ||
            unprefixed.endsWith("_refresh_token") ||
            unprefixed.endsWith("_client_secret") ||
            unprefixed.endsWith("_password") ||
            unprefixed.contains("_oauth_")
    }

    suspend fun createBackup(
        targetFile: File,
        options: BackupOptions = BackupOptions(),
        kind: BackupKind = BackupKind.MANUAL,
    ): BackupInspection = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            createBackupInternal(targetFile, options, kind)
        }
    }

    suspend fun createDailyAutoBackupIfDue(maxAgeMs: Long = DAILY_BACKUP_INTERVAL_MS): BackupInspection? =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val autoDir = getAutoBackupsDir()
                val latest = autoDir.listFiles { f ->
                    f.isFile && f.name.startsWith("auto_") && f.extension.equals(BACKUP_EXTENSION, ignoreCase = true)
                }?.maxByOrNull { it.lastModified() }
                if (latest != null && (System.currentTimeMillis() - latest.lastModified()) < maxAgeMs) {
                    return@withLock null
                }
                val target = File(autoDir, defaultBackupFileName(BackupKind.AUTO_STARTUP))
                val inspection = createBackupInternal(
                    targetFile = target,
                    options = fastSafetyOptions(),
                    kind = BackupKind.AUTO_STARTUP,
                )
                pruneAutoBackups(autoDir, "auto_", MAX_AUTO_BACKUPS_PER_KIND)
                inspection
            }
        }

    suspend fun createPreUpdateBackup(): BackupInspection = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val autoDir = getAutoBackupsDir()
            val target = File(autoDir, defaultBackupFileName(BackupKind.PRE_UPDATE))
            val inspection = createBackupInternal(
                targetFile = target,
                options = fastSafetyOptions(),
                kind = BackupKind.PRE_UPDATE,
            )
            pruneAutoBackups(autoDir, "pre_update_", MAX_AUTO_BACKUPS_PER_KIND)
            inspection
        }
    }

    fun listBackups(): List<BackupArchiveEntry> {
        val dirs = listOf(getBackupsRootDir(), getAutoBackupsDir())
        val archives = dirs.flatMap { dir ->
            dir.listFiles { f ->
                f.isFile && f.extension.equals(BACKUP_EXTENSION, ignoreCase = true)
            }?.toList().orEmpty()
        }
        return archives
            .distinctBy { it.canonicalPath }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val kind = when {
                    file.name.startsWith("auto_") -> BackupKind.AUTO_STARTUP
                    file.name.startsWith("pre_update_") -> BackupKind.PRE_UPDATE
                    file.name.startsWith("pre_restore_") -> BackupKind.PRE_RESTORE
                    else -> BackupKind.MANUAL
                }
                val counts = runCatching { readManifestOnly(file)?.counts }.getOrNull()
                BackupArchiveEntry(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    kind = kind,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    counts = counts,
                )
            }
    }

    suspend fun inspectBackup(backupFile: File): BackupInspection = withContext(Dispatchers.IO) {
        require(backupFile.exists() && backupFile.isFile) { "Backup file does not exist: ${backupFile.absolutePath}" }
        val stagingDir = Files.createTempDirectory("cs_backup_inspect_").toFile()
        try {
            val manifest = extractAndVerifyArchive(backupFile, stagingDir)
            val portableJson = File(stagingDir, ENTRY_PORTABLE_JSON)
            val portableExport = if (portableJson.exists()) {
                mapper.readValue<PortableDatabaseExport>(portableJson)
            } else {
                null
            }
            val hasCreds = portableExport?.keyValues?.any { isCredentialKey(it.key) } ?: false
            val extensionsFile = File(stagingDir, ENTRY_EXTENSIONS_MANIFEST)
            val extensions = if (extensionsFile.exists()) {
                mapper.readValue<List<BackedUpExtension>>(extensionsFile)
            } else {
                emptyList()
            }
            val reposFile = File(stagingDir, ENTRY_REPOS_JSON)
            val repoUrls = if (reposFile.exists()) {
                readRepoUrls(reposFile)
            } else {
                emptyList()
            }
            BackupInspection(
                filePath = backupFile.absolutePath,
                fileSizeBytes = backupFile.length(),
                manifest = manifest,
                hasSqliteDatabase = File(stagingDir, ENTRY_SQLITE_DB).exists(),
                hasPortableJson = portableJson.exists(),
                hasRepositories = reposFile.exists(),
                hasCredentialsInBackup = hasCreds,
                extensions = extensions,
                repositoryUrls = repoUrls,
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun restoreBackup(
        backupFile: File,
        options: RestoreOptions = RestoreOptions(),
        reloadRuntimeComponents: Boolean = true,
    ): RestoreResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            restoreBackupInternal(backupFile, options, reloadRuntimeComponents)
        }
    }

    private fun fastSafetyOptions() = BackupOptions(
        includeDatabase = true,
        includeCredentials = true,
        includeRepositories = true,
        includeExtensionBinaries = false,
        includeAssetsAndCookies = true,
    )

    private suspend fun createBackupInternal(
        targetFile: File,
        options: BackupOptions,
        kind: BackupKind,
    ): BackupInspection {
        runCatching { ProfileManager.awaitPendingSaves() }
        val stagingDir = Files.createTempDirectory("cs_backup_stage_").toFile()
        val targetTmp = File(
            targetFile.parentFile ?: getBackupsRootDir(),
            "${targetFile.name}.${System.nanoTime()}.tmp",
        )
        try {
            targetFile.parentFile?.mkdirs()
            val stagedEntries = linkedMapOf<String, ByteArray>()
            var counts = BackupCounts()
            var hasCreds = false
            var repoUrls = emptyList<String>()
            var extensionsList = emptyList<BackedUpExtension>()

            if (options.includeDatabase) {
                val stagedDb = File(stagingDir, "snapshot.db")
                DesktopDataStore.createConsistentSnapshot(stagedDb)
                if (!options.includeCredentials) {
                    stripCredentialsFromDbFile(stagedDb)
                }
                val (export, dbCounts) = exportDatabaseContents(stagedDb, includeCredentials = options.includeCredentials)
                hasCreds = export.keyValues.any { isCredentialKey(it.key) }
                val portableBytes = mapper.writeValueAsBytes(export)
                val dbBytes = stagedDb.readBytes()
                stagedEntries[ENTRY_SQLITE_DB] = dbBytes
                stagedEntries[ENTRY_PORTABLE_JSON] = portableBytes
                counts = counts.copy(
                    profiles = dbCounts.profiles,
                    bookmarks = dbCounts.bookmarks,
                    watchHistoryEntries = dbCounts.watchHistoryEntries,
                    watchHistoryShows = dbCounts.watchHistoryShows,
                    trackerBindings = dbCounts.trackerBindings,
                    trackerSyncAudits = dbCounts.trackerSyncAudits,
                    downloadTasks = dbCounts.downloadTasks,
                    pluginUpdates = dbCounts.pluginUpdates,
                    settingsKeys = dbCounts.settingsKeys,
                    clonedSites = dbCounts.clonedSites,
                )
            }

            if (options.includeRepositories || options.includeExtensionBinaries) {
                val extDir = PlatformPaths.extensionsDir
                val reposFile = File(extDir, "repos.json")
                val repoCacheFile = File(extDir, "repo_cache.json")
                val pluginsCacheFile = File(extDir, "plugins_cache.json")

                val repoList = if (reposFile.exists()) readReposList(reposFile) else emptyList()
                repoUrls = repoList.map { it.url }

                if (options.includeRepositories) {
                    if (reposFile.exists()) stagedEntries[ENTRY_REPOS_JSON] = reposFile.readBytes()
                    if (repoCacheFile.exists()) stagedEntries[ENTRY_REPO_CACHE_JSON] = repoCacheFile.readBytes()
                    if (pluginsCacheFile.exists()) stagedEntries[ENTRY_PLUGINS_CACHE_JSON] = pluginsCacheFile.readBytes()
                }

                extensionsList = collectExtensionsManifest(extDir, repoList, options.includeExtensionBinaries)
                stagedEntries[ENTRY_EXTENSIONS_MANIFEST] = mapper.writeValueAsBytes(extensionsList)
                if (options.includeExtensionBinaries) {
                    for (ext in extensionsList) {
                        val file = File(extDir, ext.relativePath)
                        if (file.exists() && file.isFile) {
                            stagedEntries["$PREFIX_EXTENSIONS_FILES${ext.relativePath}"] = file.readBytes()
                        }
                    }
                }
                counts = counts.copy(
                    repositories = repoList.size,
                    extensions = extensionsList.size,
                )
            }

            if (options.includeAssetsAndCookies) {
                val avatarsDir = File(PlatformPaths.appDataDir, "profiles/avatars")
                var avatarCount = 0
                if (avatarsDir.exists()) {
                    avatarsDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                        stagedEntries["$PREFIX_AVATARS${file.name}"] = file.readBytes()
                        avatarCount++
                    }
                }
                val fontsDir = PlatformPaths.fontsDir
                var fontCount = 0
                if (fontsDir.exists()) {
                    fontsDir.listFiles()?.filter {
                        it.isFile && !CustomFontManager.BUILT_IN_FONTS.contains(it.nameWithoutExtension) &&
                            !isBundledFontFileName(it.name)
                    }?.forEach { file ->
                        stagedEntries["$PREFIX_FONTS${file.name}"] = file.readBytes()
                        fontCount++
                    }
                }
                val cookiesFile = File(PlatformPaths.appDataDir, "network/cookies.json")
                var cookieDomains = 0
                if (cookiesFile.exists()) {
                    val cookieBytes = cookiesFile.readBytes()
                    stagedEntries[ENTRY_COOKIES_JSON] = cookieBytes
                    cookieDomains = runCatching {
                        mapper.readTree(cookieBytes).size()
                    }.getOrDefault(0)
                }
                counts = counts.copy(
                    avatars = avatarCount,
                    customFonts = fontCount,
                    cookieDomains = cookieDomains,
                )
            }

            val checksums = linkedMapOf<String, String>()
            stagedEntries.forEach { (path, bytes) ->
                checksums[path] = sha256Hex(bytes)
            }

            val manifest = BackupManifest(
                formatVersion = CURRENT_BACKUP_FORMAT_VERSION,
                appVersion = AppConfig.APP_VERSION,
                schemaVersion = DatabaseFactory.CURRENT_SCHEMA_VERSION,
                createdAtEpochMs = System.currentTimeMillis(),
                kind = kind,
                options = options,
                counts = counts,
                checksums = checksums,
            )

            ZipOutputStream(FileOutputStream(targetTmp)).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zos.write(mapper.writeValueAsBytes(manifest))
                zos.closeEntry()

                for ((entryPath, bytes) in stagedEntries) {
                    zos.putNextEntry(ZipEntry(entryPath))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            moveReplacing(targetTmp, targetFile)
            return BackupInspection(
                filePath = targetFile.absolutePath,
                fileSizeBytes = targetFile.length(),
                manifest = manifest,
                hasSqliteDatabase = stagedEntries.containsKey(ENTRY_SQLITE_DB),
                hasPortableJson = stagedEntries.containsKey(ENTRY_PORTABLE_JSON),
                hasRepositories = stagedEntries.containsKey(ENTRY_REPOS_JSON),
                hasCredentialsInBackup = hasCreds,
                extensions = extensionsList,
                repositoryUrls = repoUrls,
            )
        } finally {
            if (targetTmp.exists()) targetTmp.delete()
            stagingDir.deleteRecursively()
        }
    }

    private suspend fun restoreBackupInternal(
        backupFile: File,
        options: RestoreOptions,
        reloadRuntimeComponents: Boolean,
    ): RestoreResult {
        if (!backupFile.exists() || !backupFile.isFile) {
            return RestoreResult(success = false, message = "Backup file does not exist.")
        }

        val stagingDir = Files.createTempDirectory("cs_backup_restore_").toFile()
        var rollbackFile: File? = null
        val warnings = mutableListOf<String>()

        try {
            val manifest = extractAndVerifyArchive(backupFile, stagingDir)
            if (manifest.formatVersion > CURRENT_BACKUP_FORMAT_VERSION) {
                return RestoreResult(
                    success = false,
                    message = "Backup format v${manifest.formatVersion} requires a newer version of CloudStream Desktop.",
                )
            }

            val stagedDb = File(stagingDir, ENTRY_SQLITE_DB)
            val stagedPortable = File(stagingDir, ENTRY_PORTABLE_JSON)
            if (options.restoreDatabase) {
                if (stagedDb.exists()) {
                    verifySqliteFile(stagedDb)
                } else if (!stagedPortable.exists()) {
                    return RestoreResult(
                        success = false,
                        message = "Backup archive does not contain a database payload.",
                    )
                }
            }

            // Capture rollback snapshot before modifying any live user data unless restoring a pre-restore rollback itself.
            if (!backupFile.name.startsWith("pre_restore_")) {
                val rollbackTarget = File(getAutoBackupsDir(), defaultBackupFileName(BackupKind.PRE_RESTORE))
                runCatching {
                    createBackupInternal(
                        targetFile = rollbackTarget,
                        options = BackupOptions(
                            includeDatabase = true,
                            includeCredentials = true,
                            includeRepositories = true,
                            includeExtensionBinaries = true,
                            includeAssetsAndCookies = true,
                        ),
                        kind = BackupKind.PRE_RESTORE,
                    )
                    pruneAutoBackups(getAutoBackupsDir(), "pre_restore_", MAX_AUTO_BACKUPS_PER_KIND)
                    rollbackFile = rollbackTarget
                }.onFailure { err ->
                    AppLogger.w("DesktopBackupEngine: Pre-restore snapshot warning: ${err.message}")
                }
            }

            // 1. Restore assets first so avatar path remapping resolves restored avatar files on disk cleanly.
            if (options.restoreAssetsAndCookies) {
                restoreDirectoryPayload(
                    sourceDir = File(stagingDir, PREFIX_AVATARS),
                    targetDir = File(PlatformPaths.appDataDir, "profiles/avatars"),
                    warnings = warnings,
                )
                restoreDirectoryPayload(
                    sourceDir = File(stagingDir, PREFIX_FONTS),
                    targetDir = PlatformPaths.fontsDir,
                    warnings = warnings,
                )
                val stagedCookies = File(stagingDir, ENTRY_COOKIES_JSON)
                if (stagedCookies.exists()) {
                    val targetCookies = File(PlatformPaths.appDataDir, "network/cookies.json")
                    targetCookies.parentFile?.mkdirs()
                    stagedCookies.copyTo(targetCookies, overwrite = true)
                }
            }

            // 2. Restore SQLite database under dbLock and reload in-memory singletons.
            if (options.restoreDatabase) {
                applyDatabaseRestore(
                    stagedDb = stagedDb,
                    stagedPortable = stagedPortable,
                    restoreCredentials = options.restoreCredentials,
                    reloadRuntimeComponents = reloadRuntimeComponents,
                )
            }

            // 3. Restore repositories & extension binaries.
            if (options.restoreRepositories || options.restoreExtensionBinaries) {
                applyExtensionsRestore(
                    stagingDir = stagingDir,
                    restoreRepositories = options.restoreRepositories,
                    restoreExtensionBinaries = options.restoreExtensionBinaries,
                    reloadRuntimeComponents = reloadRuntimeComponents,
                    warnings = warnings,
                )
            }

            if (reloadRuntimeComponents) {
                runCatching { CustomFontManager.refreshCache() }
            }

            return RestoreResult(
                success = true,
                message = "Backup restored cleanly.",
                rollbackBackupPath = rollbackFile?.absolutePath,
                warnings = warnings,
                countsRestored = manifest.counts,
            )
        } catch (t: Throwable) {
            AppLogger.e("DesktopBackupEngine: Restore failed; attempting automatic rollback", t)
            var rolledBack = false
            val rb = rollbackFile
            if (rb != null && rb.exists()) {
                rolledBack = runCatching {
                    restoreBackupInternal(
                        backupFile = rb,
                        options = RestoreOptions(),
                        reloadRuntimeComponents = reloadRuntimeComponents,
                    ).success
                }.getOrDefault(false)
            }
            val detail = if (rolledBack) {
                "Restore failed (${t.message}); automatically rolled back to pre-restore state."
            } else {
                "Restore failed: ${t.message}"
            }
            return RestoreResult(
                success = false,
                message = detail,
                rollbackBackupPath = rb?.absolutePath,
                warnings = warnings,
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private suspend fun applyDatabaseRestore(
        stagedDb: File,
        stagedPortable: File,
        restoreCredentials: Boolean,
        reloadRuntimeComponents: Boolean,
    ) {
        val dbToInstall = File(stagedDb.parentFile, "prepared_restore.db")
        if (stagedDb.exists()) {
            stagedDb.copyTo(dbToInstall, overwrite = true)
        } else {
            val export = mapper.readValue<PortableDatabaseExport>(stagedPortable)
            buildSqliteFromPortableExport(dbToInstall, export)
        }

        if (!restoreCredentials) {
            stripCredentialsFromDbFile(dbToInstall)
        }
        remapAvatarPathsInDbFile(dbToInstall)

        runCatching { ProfileManager.awaitPendingSaves() }
        DesktopDataStore.replaceDatabaseFromPreparedFile(dbToInstall)

        if (reloadRuntimeComponents) {
            runCatching { ProfileManager.init() }
            runCatching { AppearanceConfig.reloadFromDataStore() }
            runCatching { MetadataConfig.reloadFromDataStore() }
            runCatching { AccountManager.reloadFromDataStore() }
            runCatching { DesktopDownloadManager.loadTasksFromDatabase() }
            runCatching { loadClonedSites() }
            runCatching { ActiveProviderRepository.refreshProviders() }
        } else {
            runCatching { AccountManager.reloadFromDataStore() }
        }
        DesktopDataStore.notifyHistoryChanged(force = true)
        DesktopDataStore.notifyBookmarksChanged()
    }

    private suspend fun applyExtensionsRestore(
        stagingDir: File,
        restoreRepositories: Boolean,
        restoreExtensionBinaries: Boolean,
        reloadRuntimeComponents: Boolean,
        warnings: MutableList<String>,
    ) {
        val extDir = PlatformPaths.extensionsDir.apply { mkdirs() }
        if (reloadRuntimeComponents) {
            runCatching { SandboxedPluginManager.close() }
            runCatching { ExtensionLoader.unloadAllPlugins() }
        }

        if (restoreRepositories) {
            listOf(
                ENTRY_REPOS_JSON to "repos.json",
                ENTRY_REPO_CACHE_JSON to "repo_cache.json",
                ENTRY_PLUGINS_CACHE_JSON to "plugins_cache.json",
            ).forEach { (stagedRel, liveName) ->
                val src = File(stagingDir, stagedRel)
                if (src.exists()) {
                    val dst = File(extDir, liveName)
                    val tmp = File(extDir, "$liveName.${System.nanoTime()}.tmp")
                    src.copyTo(tmp, overwrite = true)
                    moveReplacing(tmp, dst)
                }
            }
        }

        if (restoreExtensionBinaries) {
            val filesRoot = File(stagingDir, PREFIX_EXTENSIONS_FILES)
            if (filesRoot.exists()) {
                val rootCanon = filesRoot.canonicalFile.toPath()
                val extCanon = extDir.canonicalFile.toPath()
                filesRoot.walkTopDown().filter { it.isFile }.forEach { srcFile ->
                    val rel = rootCanon.relativize(srcFile.canonicalFile.toPath())
                    val destFile = extCanon.resolve(rel).normalize().toFile()
                    if (!destFile.canonicalFile.toPath().startsWith(extCanon)) {
                        throw SecurityException("Blocked unsafe extension restore path: $rel")
                    }
                    destFile.parentFile?.mkdirs()
                    try {
                        val tmp = File(destFile.parentFile, "${destFile.name}.${System.nanoTime()}.tmp")
                        srcFile.copyTo(tmp, overwrite = true)
                        moveReplacing(tmp, destFile)
                        ExtensionLoader.clearFailedMarker(destFile)
                    } catch (e: Throwable) {
                        warnings.add("Could not replace '${destFile.name}' (file in use): ${e.message}")
                    }
                }
            }
        }

        runCatching { DesktopRepositoryManager.initialize() }
        if (restoreExtensionBinaries && reloadRuntimeComponents) {
            runCatching { ExtensionLoader.rescanAndLoadNewPlugins(extDir) }
        }
    }

    private fun restoreDirectoryPayload(sourceDir: File, targetDir: File, warnings: MutableList<String>) {
        if (!sourceDir.exists()) return
        targetDir.mkdirs()
        val srcRoot = sourceDir.canonicalFile.toPath()
        val dstRoot = targetDir.canonicalFile.toPath()
        sourceDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = srcRoot.relativize(src.canonicalFile.toPath())
            val dst = dstRoot.resolve(rel).normalize().toFile()
            if (!dst.canonicalFile.toPath().startsWith(dstRoot)) {
                throw SecurityException("Blocked unsafe asset restore path: $rel")
            }
            dst.parentFile?.mkdirs()
            try {
                val tmp = File(dst.parentFile, "${dst.name}.${System.nanoTime()}.tmp")
                src.copyTo(tmp, overwrite = true)
                moveReplacing(tmp, dst)
            } catch (e: Throwable) {
                warnings.add("Skipped asset '${dst.name}': ${e.message}")
            }
        }
    }

    private fun extractAndVerifyArchive(backupFile: File, targetDir: File): BackupManifest {
        val targetCanon = targetDir.canonicalFile.toPath()
        ZipFile(backupFile).use { zip ->
            val manifestEntry = zip.getEntry(ENTRY_MANIFEST)
                ?: throw IllegalArgumentException("Invalid backup archive: missing $ENTRY_MANIFEST")
            val manifest = zip.getInputStream(manifestEntry).use { input ->
                mapper.readValue<BackupManifest>(input)
            }

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val cleanName = entry.name.replace('\\', '/')
                if (cleanName.startsWith("/") || cleanName.contains("..") || cleanName.contains(":")) {
                    throw SecurityException("Unsafe path inside backup archive: ${entry.name}")
                }
                val outFile = targetCanon.resolve(cleanName).normalize().toFile()
                if (!outFile.canonicalFile.toPath().startsWith(targetCanon)) {
                    throw SecurityException("Path traversal attempt inside backup archive: ${entry.name}")
                }
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (cleanName != ENTRY_MANIFEST) {
                    val expectedSha = manifest.checksums[cleanName]
                    if (expectedSha != null) {
                        val actualSha = sha256Hex(outFile.readBytes())
                        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                            throw SecurityException("Checksum mismatch for '$cleanName' inside backup archive")
                        }
                    }
                }
            }

            manifest.checksums.keys.forEach { requiredPath ->
                val f = targetCanon.resolve(requiredPath).normalize().toFile()
                if (!f.exists()) {
                    throw IllegalStateException("Backup archive missing checksum-declared entry: $requiredPath")
                }
            }
            return manifest
        }
    }

    private fun readManifestOnly(backupFile: File): BackupManifest? {
        return ZipFile(backupFile).use { zip ->
            val entry = zip.getEntry(ENTRY_MANIFEST) ?: return null
            zip.getInputStream(entry).use { mapper.readValue<BackupManifest>(it) }
        }
    }

    private fun exportDatabaseContents(
        dbFile: File,
        includeCredentials: Boolean,
    ): Pair<PortableDatabaseExport, BackupCounts> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            val queries = DesktopDatabase(driver).cloudstreamDBQueries
            val allKv = queries.selectAllKeyValues().executeAsList()
                .filter { includeCredentials || !isCredentialKey(it.key) }
                .map { KeyValueExport(it.key, it.value_) }
            val bookmarks = queries.selectAllBookmarks().executeAsList().map {
                BookmarkExport(it.id, it.name, it.url, it.apiName, it.posterUrl, it.watchType ?: 0L, it.dateAdded ?: 0L, it.profileId ?: 0L)
            }
            val history = queries.selectAllWatchHistory().executeAsList().map {
                WatchHistoryExport(
                    it.parentId, it.episodeId, it.showName, it.showUrl, it.apiName, it.posterUrl,
                    it.episodeThumbnailUrl, it.screenshotUrl, it.episode, it.season, it.position,
                    it.duration, it.updateTime, it.episodeName, it.episodeDescription, it.profileId ?: 0L,
                )
            }
            val updates = queries.selectAllPluginUpdates().executeAsList().map {
                PluginUpdateExport(it.pluginName, it.version, it.iconUrl, it.timestamp)
            }
            val downloads = queries.selectAllDownloads().executeAsList().map {
                DownloadTaskExport(
                    it.id, it.canonicalKey, it.showName, it.showUrl, it.episodeTitle, it.posterUrl,
                    it.backdropUrl, it.season, it.episode, it.filePath, it.streamUrl, it.totalBytes,
                    it.downloadedBytes, it.status, it.quality, it.apiName, it.headersJson, it.dateAdded, it.dateCompleted,
                )
            }
            val bindings = queries.selectAllTrackerBindings().executeAsList().map {
                TrackerBindingExport(
                    it.profileId, it.trackerId, it.showKey, it.mappedSeason, it.remoteMediaId,
                    it.remoteTitle, it.bindMode, it.episodeOffset, it.expectedTotalEpisodes, it.updatedAt,
                )
            }
            val audits = queries.selectAllTrackerSyncAudits().executeAsList().map {
                TrackerSyncAuditExport(
                    it.profileId, it.trackerId, it.showName, it.remoteMediaId, it.episodeNum,
                    it.effectiveRemoteEpisode, it.oldProgress, it.newProgress, it.statusApplied,
                    it.state, it.reason, it.timestamp,
                )
            }
            val profileCount = parseProfileCount(allKv.firstOrNull { it.key == PREF_PROFILES }?.value)
            val clonedSiteCount = parseClonedSitesCount(allKv.firstOrNull { it.key == PreferenceKeys.USER_PROVIDER_API }?.value)

            val counts = BackupCounts(
                profiles = profileCount,
                bookmarks = bookmarks.size,
                watchHistoryEntries = history.size,
                watchHistoryShows = history.distinctBy { it.showUrl.ifBlank { it.parentId } }.size,
                trackerBindings = bindings.size,
                trackerSyncAudits = audits.size,
                downloadTasks = downloads.size,
                pluginUpdates = updates.size,
                settingsKeys = allKv.size,
                clonedSites = clonedSiteCount,
            )
            return PortableDatabaseExport(
                schemaVersion = DatabaseFactory.CURRENT_SCHEMA_VERSION,
                keyValues = allKv,
                bookmarks = bookmarks,
                watchHistory = history,
                pluginUpdates = updates,
                downloadTasks = downloads,
                trackerBindings = bindings,
                trackerSyncAudits = audits,
            ) to counts
        } finally {
            driver.close()
        }
    }

    private fun buildSqliteFromPortableExport(targetDb: File, export: PortableDatabaseExport) {
        if (targetDb.exists()) targetDb.delete()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${targetDb.absolutePath}", Properties())
        try {
            DesktopDatabase.Schema.create(driver)
            val queries = DesktopDatabase(driver).cloudstreamDBQueries
            queries.transaction {
                export.keyValues.forEach { queries.insertKeyValue(it.key, it.value) }
                export.bookmarks.forEach {
                    queries.insertBookmark(it.id, it.name, it.url, it.apiName, it.posterUrl, it.watchType, it.dateAdded, it.profileId)
                }
                export.watchHistory.forEach {
                    queries.insertWatchHistory(
                        it.parentId, it.episodeId, it.showName, it.showUrl, it.apiName, it.posterUrl,
                        it.episodeThumbnailUrl, it.screenshotUrl, it.episode, it.season, it.position,
                        it.duration, it.updateTime, it.episodeName, it.episodeDescription, it.profileId,
                    )
                }
                export.pluginUpdates.forEach {
                    queries.insertPluginUpdate(it.pluginName, it.version, it.iconUrl, it.timestamp)
                }
                export.downloadTasks.forEach {
                    queries.insertDownloadTask(
                        it.id, it.canonicalKey, it.showName, it.showUrl, it.episodeTitle, it.posterUrl,
                        it.backdropUrl, it.season, it.episode, it.filePath, it.streamUrl, it.totalBytes,
                        it.downloadedBytes, it.status, it.quality, it.apiName, it.headersJson, it.dateAdded, it.dateCompleted,
                    )
                }
                export.trackerBindings.forEach {
                    queries.upsertTrackerBinding(
                        it.profileId, it.trackerId, it.showKey, it.mappedSeason, it.remoteMediaId,
                        it.remoteTitle, it.bindMode, it.episodeOffset, it.expectedTotalEpisodes, it.updatedAt,
                    )
                }
                export.trackerSyncAudits.forEach {
                    queries.insertTrackerSyncAudit(
                        it.profileId, it.trackerId, it.showName, it.remoteMediaId, it.episodeNum,
                        it.effectiveRemoteEpisode, it.oldProgress, it.newProgress, it.statusApplied,
                        it.state, it.reason, it.timestamp,
                    )
                }
            }
            driver.execute(null, "PRAGMA user_version = ${DatabaseFactory.CURRENT_SCHEMA_VERSION};", 0)
        } finally {
            driver.close()
        }
    }

    private fun verifySqliteFile(dbFile: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            val result = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA quick_check(1);",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                },
                parameters = 0,
            ).value
            if (!result.equals("ok", ignoreCase = true)) {
                throw IllegalStateException("Corrupt backup SQLite database: $result")
            }
        } finally {
            driver.close()
        }
    }

    private fun stripCredentialsFromDbFile(dbFile: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            val queries = DesktopDatabase(driver).cloudstreamDBQueries
            val keysToDelete = queries.selectAllKeyValues().executeAsList()
                .map { it.key }
                .filter { isCredentialKey(it) }
            if (keysToDelete.isNotEmpty()) {
                queries.transaction {
                    keysToDelete.forEach { queries.deleteKeyValue(it) }
                }
                driver.execute(null, "VACUUM;", 0)
            }
        } finally {
            driver.close()
        }
    }

    private fun remapAvatarPathsInDbFile(dbFile: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            val queries = DesktopDatabase(driver).cloudstreamDBQueries
            val rawJson = queries.selectKeyValue(PREF_PROFILES).executeAsOneOrNull() ?: return
            // DesktopDataStore stores String keys as JSON-encoded strings.
            val profilesJson = runCatching { mapper.readValue<String>(rawJson) }.getOrElse { rawJson }
            val profiles = mapper.readValue<List<Profile>>(profilesJson)
            var changed = false
            val avatarsDir = File(PlatformPaths.appDataDir, "profiles/avatars")
            val updated = profiles.map { p ->
                val old = p.customAvatarPath
                if (old.isNullOrBlank()) {
                    p
                } else {
                    val normalized = old.replace('\\', '/')
                    val fileName = normalized.substringAfter("/profiles/avatars/", missingDelimiterValue = "")
                        .ifBlank { File(old).name }
                    val localCandidate = File(avatarsDir, fileName)
                    if (fileName.isNotBlank() && localCandidate.exists() && localCandidate.absolutePath != old) {
                        changed = true
                        p.copy(customAvatarPath = localCandidate.absolutePath)
                    } else {
                        p
                    }
                }
            }
            if (changed) {
                val innerJson = mapper.writeValueAsString(updated)
                val encodedJson = mapper.writeValueAsString(innerJson)
                queries.insertKeyValue(PREF_PROFILES, encodedJson)
            }
        } catch (t: Throwable) {
            AppLogger.w("DesktopBackupEngine: Avatar path remap warning: ${t.message}")
        } finally {
            driver.close()
        }
    }

    private fun collectExtensionsManifest(
        extDir: File,
        repos: List<RepositoryData>,
        binaryBundled: Boolean,
    ): List<BackedUpExtension> {
        if (!extDir.exists()) return emptyList()
        val extRoot = extDir.canonicalFile.toPath()
        val repoByName = repos.associateBy { sanitizeFolderKey(it.name) }
        val result = mutableListOf<BackedUpExtension>()
        extDir.walkTopDown()
            .filter {
                it.isFile &&
                    (it.extension.equals("jar", ignoreCase = true) || it.extension.equals("cs3", ignoreCase = true)) &&
                    !it.name.endsWith(".pending_delete", ignoreCase = true) &&
                    !it.name.endsWith(".uninstalled", ignoreCase = true) &&
                    !it.name.endsWith(".tmp", ignoreCase = true) &&
                    !it.name.endsWith(".bak", ignoreCase = true)
            }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relPath = extRoot.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
                val topFolder = relPath.substringBefore('/', missingDelimiterValue = "")
                val matchedRepo = repos.firstOrNull { it.name.equals(topFolder, ignoreCase = true) }
                    ?: repoByName[sanitizeFolderKey(topFolder)]
                val manifest = runCatching { DesktopRepositoryManager.readPluginManifest(file) }.getOrNull()
                result.add(
                    BackedUpExtension(
                        relativePath = relPath,
                        fileName = file.name,
                        sha256 = sha256Hex(file.readBytes()),
                        sizeBytes = file.length(),
                        repoName = matchedRepo?.name ?: topFolder.ifBlank { null },
                        repoUrl = matchedRepo?.url,
                        internalName = manifest?.get("internalName")?.toString(),
                        name = manifest?.get("name")?.toString(),
                        version = manifest?.get("version")?.toString()?.toIntOrNull(),
                        binaryBundled = binaryBundled,
                    ),
                )
            }
        return result
    }

    private fun readReposList(reposFile: File): List<RepositoryData> {
        return runCatching {
            val root = mapper.readTree(reposFile)
            if (!root.isArray) return emptyList()
            if (root.size() > 0 && root[0].isTextual) {
                mapper.readValue<List<String>>(root.toString()).map { RepositoryData(name = it, url = it) }
            } else {
                mapper.readValue<List<RepositoryData>>(root.toString())
            }
        }.getOrDefault(emptyList())
    }

    private fun readRepoUrls(reposFile: File): List<String> = readReposList(reposFile).map { it.url }

    private fun parseProfileCount(rawValue: String?): Int {
        if (rawValue.isNullOrBlank()) return 1
        return runCatching {
            val inner = runCatching { mapper.readValue<String>(rawValue) }.getOrElse { rawValue }
            mapper.readValue<List<Profile>>(inner).size
        }.getOrDefault(1)
    }

    private fun parseClonedSitesCount(rawValue: String?): Int {
        if (rawValue.isNullOrBlank()) return 0
        return runCatching {
            val inner = runCatching { mapper.readValue<String>(rawValue) }.getOrElse { rawValue }
            mapper.readValue<List<CustomSite>>(inner).size
        }.getOrDefault(0)
    }

    private fun isBundledFontFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.startsWith("plusjakartasans-") ||
            lower.startsWith("manrope-") ||
            lower.startsWith("dmsans-") ||
            lower.startsWith("inter-") ||
            lower.startsWith("nunito-") ||
            lower.startsWith("outfit-") ||
            lower.startsWith("poppins-") ||
            lower.startsWith("roboto-")
    }

    private fun sanitizeFolderKey(name: String): String =
        name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun pruneAutoBackups(dir: File, prefix: String, maxKeep: Int) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.extension.equals(BACKUP_EXTENSION, ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxKeep).forEach { old ->
            runCatching { old.delete() }
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
