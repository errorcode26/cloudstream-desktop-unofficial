package com.lagradost.cloudstream3.desktop.backup

import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopTrackerStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DesktopBackupEngineTest {

    private lateinit var tempDirA: File
    private lateinit var tempDirB: File
    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDirA = Files.createTempDirectory("cs_backup_src_").toFile()
        tempDirB = Files.createTempDirectory("cs_backup_dst_").toFile()
        System.setProperty("cloudstream.data.dir", tempDirA.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        ProfileManager.resetForTesting()
    }

    @AfterEach
    fun teardown() {
        runBlocking { ProfileManager.awaitPendingSaves() }
        DesktopRepositoryManager.clearCaches()
        ProfileManager.resetForTesting()
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDirA.deleteRecursively()
        tempDirB.deleteRecursively()
    }

    private fun writeMinimalPluginArchive(target: File, internalName: String, displayName: String, version: Int) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"name":"$displayName","internalName":"$internalName","version":$version}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Dummy.class"))
            zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zos.closeEntry()
        }
    }

    @Test
    fun `full backup and restore across data dirs recovers profiles history bookmarks repos plugins cloned sites and bindings`() = runBlocking {
        // 1. Seed source directory A with realistic user data
        val avatarsDir = File(PlatformPaths.appDataDir, "profiles/avatars").apply { mkdirs() }
        val customAvatar = File(avatarsDir, "avatar_hero.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        ProfileManager.init()
        val p2 = ProfileManager.createProfile(
            name = "Kids Room",
            avatarColorIndex = 4,
            customAvatarPath = customAvatar.absolutePath,
            pinCode = "1234",
            isKids = true,
        )
        assertTrue(ProfileManager.switchProfile(p2.id, "1234"))

        DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, "secret-tb-key-123")
        DesktopDataStore.setKey("custom_theme_accent", "#FF5500")
        DesktopDataStore.setKey(
            PreferenceKeys.USER_PROVIDER_API,
            """[{"parentJavaClass":"DemoParent","name":"My Mirror","url":"https://mirror.example.invalid","lang":"en"}]""",
        )
        AccountManager.updateAccounts(
            TrackerIds.ANILIST,
            arrayOf(AuthData(user = AuthUser(id = 42, name = "Tester"), token = AuthToken("al-secret-token"))),
        )

        DesktopDataStore.addBookmark(
            DesktopBookmark("bm_1", "Saved Anime", "https://example.invalid/anime/1", "DemoApi", "https://img/p.jpg"),
            profileId = p2.id,
        )
        DesktopDataStore.setLastWatched(
            WatchHistory(
                parentId = "p${p2.id}_DemoApi_1",
                showName = "Saved Anime",
                showUrl = "https://example.invalid/anime/1",
                apiName = "DemoApi",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
                episode = 5,
                season = 1,
                episodeId = "ep-5",
                position = 650_000L,
                duration = 1_440_000L,
                updateTime = 1_700_000_000L,
            ),
        )
        DesktopTrackerStore.upsertBinding(
            profileId = p2.id.toLong(),
            trackerId = TrackerIds.ANILIST,
            showKey = "DemoApi|https://example.invalid/anime/1",
            mappedSeason = 1L,
            remoteMediaId = "154587",
            remoteTitle = "Frieren",
            bindMode = BindMode.USER_PINNED.name,
            episodeOffset = 0L,
            expectedTotalEpisodes = 28L,
            updatedAt = 1_700_000_000L,
        )

        DesktopRepositoryManager.initialize()
        DesktopRepositoryManager.saveRepository(
            RepositoryData(name = "Community Repo", url = "https://example.invalid/repo.json", iconUrl = null),
        )
        val extFile = File(PlatformPaths.extensionsDir, "Community Repo/DemoSource.cs3")
        writeMinimalPluginArchive(extFile, "DemoSource", "Demo Source", 7)

        val userFont = File(PlatformPaths.fontsDir, "MyCustomDisplay.ttf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val cookiesFile = File(PlatformPaths.appDataDir, "network/cookies.json").apply {
            parentFile?.mkdirs()
            writeText("""{"example.invalid":[]}""")
        }
        assertTrue(userFont.exists() && cookiesFile.exists())

        // Export archive outside both A & B dirs
        val backupArchive = Files.createTempFile("cs_full_backup_", ".${DesktopBackupEngine.BACKUP_EXTENSION}").toFile()
        try {
            val exported = DesktopBackupEngine.createBackup(backupArchive)
            assertEquals(2, exported.manifest.counts.profiles)
            assertEquals(1, exported.manifest.counts.bookmarks)
            assertEquals(1, exported.manifest.counts.watchHistoryEntries)
            assertEquals(1, exported.manifest.counts.trackerBindings)
            assertEquals(1, exported.manifest.counts.clonedSites)
            assertEquals(1, exported.manifest.counts.repositories)
            assertEquals(1, exported.manifest.counts.extensions)
            assertTrue(exported.hasCredentialsInBackup)

            // 2. Switch into clean empty directory B and restore full archive
            DesktopDataStore.resetForTesting()
            ProfileManager.resetForTesting()
            System.setProperty("cloudstream.data.dir", tempDirB.absolutePath)
            DesktopDataStore.init()
            ProfileManager.init()
            assertEquals(1, ProfileManager.profiles.value.size)

            val inspected = DesktopBackupEngine.inspectBackup(backupArchive)
            assertEquals(listOf("https://example.invalid/repo.json"), inspected.repositoryUrls)
            assertEquals(1, inspected.extensions.size)

            val restoreResult = DesktopBackupEngine.restoreBackup(
                backupFile = backupArchive,
                options = RestoreOptions(),
                reloadRuntimeComponents = false,
            )
            assertTrue(restoreResult.success, restoreResult.message)

            ProfileManager.init()
            assertEquals(2, ProfileManager.profiles.value.size)
            val restoredP2 = ProfileManager.profiles.value.first { it.name == "Kids Room" }
            assertEquals(p2.id, restoredP2.id)
            assertEquals(
                File(tempDirB, "profiles/avatars/avatar_hero.png").canonicalPath,
                File(restoredP2.customAvatarPath!!).canonicalPath,
                "Custom avatar path must remap to restored target directory",
            )
            assertEquals("#FF5500", DesktopDataStore.getKey<String>("custom_theme_accent"))
            assertEquals("secret-tb-key-123", DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY))
            assertEquals(1, DesktopDataStore.getBookmarks(restoredP2.id).size)
            val restoredHist = DesktopDataStore.getAllWatchHistory(restoredP2.id).single()
            assertEquals(650_000L, restoredHist.position)
            assertNotNull(DesktopTrackerStore.getBinding(restoredP2.id.toLong(), TrackerIds.ANILIST, "DemoApi|https://example.invalid/anime/1", 1L))

            DesktopRepositoryManager.initialize()
            assertEquals(listOf("https://example.invalid/repo.json"), DesktopRepositoryManager.getSavedRepositories().map { it.url })
            assertTrue(File(PlatformPaths.extensionsDir, "Community Repo/DemoSource.cs3").exists())
            assertTrue(File(PlatformPaths.fontsDir, "MyCustomDisplay.ttf").exists())
            assertTrue(File(PlatformPaths.appDataDir, "network/cookies.json").exists())

            // 3. Verify restoreCredentials=false strips sensitive keys even when restoring from full backup
            val redactedRestore = DesktopBackupEngine.restoreBackup(
                backupFile = backupArchive,
                options = RestoreOptions(restoreCredentials = false),
                reloadRuntimeComponents = false,
            )
            assertTrue(redactedRestore.success)
            assertNull(DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY))
            assertTrue(AccountManager.accounts(TrackerIds.ANILIST).isEmpty())
            assertEquals("#FF5500", DesktopDataStore.getKey<String>("custom_theme_accent"))
        } finally {
            backupArchive.delete()
        }
    }

    @Test
    fun `credential-excluded backup strips tokens and checksum tampering rejects malicious archive`() = runBlocking {
        DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, "top-secret-key")
        DesktopDataStore.setKey("safe_ui_pref", "enabled")

        val safeArchive = File(tempDirB, "no_creds.${DesktopBackupEngine.BACKUP_EXTENSION}")
        val inspection = DesktopBackupEngine.createBackup(
            targetFile = safeArchive,
            options = BackupOptions(includeCredentials = false, includeExtensionBinaries = false),
        )
        assertFalse(inspection.hasCredentialsInBackup)

        val inspectedAgain = DesktopBackupEngine.inspectBackup(safeArchive)
        assertFalse(inspectedAgain.hasCredentialsInBackup)

        // Tamper portable_export.json inside ZIP while leaving original manifest checksums intact
        val tamperedArchive = File(tempDirB, "tampered.${DesktopBackupEngine.BACKUP_EXTENSION}")
        ZipFile(safeArchive).use { srcZip ->
            ZipOutputStream(FileOutputStream(tamperedArchive)).use { dstZip ->
                val entries = srcZip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    dstZip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "db/portable_export.json") {
                        dstZip.write("{\"tampered\":true}".toByteArray())
                    } else {
                        srcZip.getInputStream(entry).use { it.copyTo(dstZip) }
                    }
                    dstZip.closeEntry()
                }
            }
        }

        val restoreFail = DesktopBackupEngine.restoreBackup(tamperedArchive, reloadRuntimeComponents = false)
        assertFalse(restoreFail.success)
        assertTrue(restoreFail.message.contains("Checksum", ignoreCase = true))

        // Zip-slip path traversal entry must also be blocked cleanly
        val zipSlipArchive = File(tempDirB, "zipslip.${DesktopBackupEngine.BACKUP_EXTENSION}")
        ZipOutputStream(FileOutputStream(zipSlipArchive)).use { zos ->
            zos.putNextEntry(ZipEntry("backup_manifest.json"))
            zos.write("""{"formatVersion":1}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }
        val zipSlipFail = DesktopBackupEngine.restoreBackup(zipSlipArchive, reloadRuntimeComponents = false)
        assertFalse(zipSlipFail.success)
    }

    @Test
    fun `daily auto backup pre update backup pruning and SettingsViewModel restore workflow work`() = runBlocking {
        DesktopDataStore.setKey("vm_pref_marker", "before_restore")

        // Create and verify daily auto backup is deduplicated within 24h
        val firstAuto = DesktopBackupEngine.createDailyAutoBackupIfDue()
        assertNotNull(firstAuto)
        val skippedAuto = DesktopBackupEngine.createDailyAutoBackupIfDue()
        assertNull(skippedAuto)

        // Pre-update rolling backup prunes beyond maxKeep = 5
        repeat(6) { idx ->
            DesktopBackupEngine.createPreUpdateBackup()
            delay(15)
        }
        val preUpdates = DesktopBackupEngine.getAutoBackupsDir().listFiles { f ->
            f.isFile && f.name.startsWith("pre_update_")
        }.orEmpty()
        assertEquals(5, preUpdates.size)

        val vm = SettingsViewModel()
        try {
            vm.onEvent(SettingsUiEvent.LoadBackupsList)
            repeat(40) {
                if (vm.uiState.value.backupState.availableBackups.isNotEmpty()) return@repeat
                delay(25)
            }
            assertTrue(vm.uiState.value.backupState.availableBackups.isNotEmpty())

            // Export manual backup via SettingsViewModel, mutate DB, and restore via Inspect + Confirm MVI events
            val manualPath = File(tempDirB, "vm_manual.${DesktopBackupEngine.BACKUP_EXTENSION}").absolutePath
            vm.onEvent(SettingsUiEvent.CreateBackup(manualPath, BackupOptions(includeExtensionBinaries = false)))
            repeat(40) {
                if (File(manualPath).exists() && !vm.uiState.value.backupState.isCreatingBackup) return@repeat
                delay(25)
            }
            assertTrue(File(manualPath).exists())

            DesktopDataStore.setKey("vm_pref_marker", "mutated_after_backup")
            vm.onEvent(SettingsUiEvent.InspectBackupFile(manualPath))
            repeat(40) {
                if (vm.uiState.value.backupState.pendingRestoreInspection != null) return@repeat
                delay(25)
            }
            assertNotNull(vm.uiState.value.backupState.pendingRestoreInspection)

            vm.onEvent(SettingsUiEvent.ConfirmRestoreBackup)
            repeat(60) {
                if (!vm.uiState.value.backupState.isRestoringBackup &&
                    vm.uiState.value.backupState.lastStatusMessage?.contains("restored", ignoreCase = true) == true
                ) {
                    return@repeat
                }
                delay(25)
            }
            assertEquals("before_restore", DesktopDataStore.getKey<String>("vm_pref_marker"))
        } finally {
            vm.dispose()
        }
    }
}
