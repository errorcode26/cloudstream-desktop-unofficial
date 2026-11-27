package com.lagradost.cloudstream3.desktop.profile

import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ProfileManagerTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_profile_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        ProfileManager.resetForTesting()
    }

    @AfterEach
    fun teardown() {
        runBlocking { ProfileManager.awaitPendingSaves() }
        ProfileManager.resetForTesting()
        DesktopDataStore.activeProfileProvider = { 0 }
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `profile lifecycle persists across re-init handles trimmed PINs and cascades data cleanup`() = runBlocking {
        ProfileManager.init()
        assertEquals(1, ProfileManager.profiles.value.size)
        assertEquals(0, ProfileManager.activeProfile.value.id)

        val secondary = ProfileManager.createProfile(
            name = "Kids Room",
            avatarColorIndex = 3,
            pinCode = " 4321 ",
            isKids = true,
        )
        assertTrue(secondary.id > 0)
        assertTrue(ProfileManager.verifyPin(secondary.id, "4321"))
        assertFalse(ProfileManager.verifyPin(secondary.id, "0000"))

        // Switch fails on wrong PIN and succeeds on padded valid PIN
        assertFalse(ProfileManager.switchProfile(secondary.id, "9999"))
        assertEquals(0, ProfileManager.activeProfile.value.id)

        assertTrue(ProfileManager.switchProfile(secondary.id, " 4321 "))
        assertEquals(secondary.id, ProfileManager.activeProfile.value.id)
        assertEquals(secondary.id, DesktopDataStore.activeProfileProvider())

        // Profile-scoped bookmark written while active is cascaded cleanly on delete
        DesktopDataStore.addBookmark(
            DesktopBookmark(
                id = "profile_sec_bm",
                name = "Cascade Movie",
                url = "https://example.invalid/m",
                apiName = "TestApi",
                posterUrl = null,
            ),
            profileId = secondary.id,
        )
        assertEquals(1, DesktopDataStore.getBookmarks(secondary.id).size)

        ProfileManager.setAutoSignIn(true)
        assertTrue(ProfileManager.autoSignIn.value)
        assertFalse(ProfileManager.isPickerOnStartup.value)

        val updated = secondary.copy(name = "Living Room", pinCode = null)
        ProfileManager.updateProfile(updated)
        assertEquals("Living Room", ProfileManager.activeProfile.value.name)

        ProfileManager.triggerWelcomeToast(updated)
        assertEquals(updated.id, ProfileManager.welcomeToast.value?.id)
        ProfileManager.dismissWelcomeToast()
        assertNull(ProfileManager.welcomeToast.value)

        // Ensure SQLite writes flush before re-initializing from fresh store
        ProfileManager.awaitPendingSaves()
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        ProfileManager.resetForTesting()
        ProfileManager.init()

        assertEquals(2, ProfileManager.profiles.value.size)
        assertEquals(secondary.id, ProfileManager.activeProfile.value.id)
        assertTrue(ProfileManager.autoSignIn.value)

        // Secondary profile cascades cleanly; last remaining profile (0) cannot be deleted
        assertTrue(ProfileManager.deleteProfile(secondary.id))
        assertFalse(ProfileManager.deleteProfile(0))
        ProfileManager.awaitPendingSaves()

        assertEquals(1, ProfileManager.profiles.value.size)
        assertEquals(0, ProfileManager.activeProfile.value.id)
        assertTrue(DesktopDataStore.getBookmarks(secondary.id).isEmpty())
    }
}
