package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTrackerStoreTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeTest
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_tracker_store_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
    }

    @AfterTest
    fun tearDown() {
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `auto sync defaults to false per profile and remains isolated`() {
        assertNull(DesktopDataStore.getProfileKey<Boolean>(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, profileId = 0))
        assertNull(DesktopDataStore.getProfileKey<Boolean>(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, profileId = 1))

        DesktopDataStore.setProfileKey(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, true, profileId = 1)
        assertEquals(true, DesktopDataStore.getProfileKey<Boolean>(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, profileId = 1))
        assertFalse(DesktopDataStore.getProfileKey<Boolean>(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, profileId = 0) ?: false)
        assertFalse(DesktopDataStore.getProfileKey<Boolean>(DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC, profileId = 2) ?: false)
    }

    @Test
    fun `bindings CRUD respects profile boundaries and updates cleanly`() = runBlocking {
        val showKey = "AnimeSite|https://example.org/show/steins-gate"
        DesktopTrackerStore.upsertBinding(
            profileId = 1L,
            trackerId = "anilist",
            showKey = showKey,
            mappedSeason = 1L,
            remoteMediaId = "9253",
            remoteTitle = "Steins;Gate",
            bindMode = "AUTO_VERIFIED",
            episodeOffset = 0L,
            expectedTotalEpisodes = 24L,
            updatedAt = 1000L,
        )
        DesktopTrackerStore.upsertBinding(
            profileId = 2L,
            trackerId = "anilist",
            showKey = showKey,
            mappedSeason = 1L,
            remoteMediaId = "9253",
            remoteTitle = "Profile 2 Steins;Gate",
            bindMode = "USER_PINNED",
            episodeOffset = 0L,
            expectedTotalEpisodes = 24L,
            updatedAt = 2000L,
        )

        val p1 = DesktopTrackerStore.getBinding(1L, "anilist", showKey, 1L)
        val p2 = DesktopTrackerStore.getBinding(2L, "anilist", showKey, 1L)
        assertNotNull(p1)
        assertNotNull(p2)
        assertEquals("Steins;Gate", p1.remoteTitle)
        assertEquals("Profile 2 Steins;Gate", p2.remoteTitle)

        // Update existing binding via UPSERT
        DesktopTrackerStore.upsertBinding(
            profileId = 1L,
            trackerId = "anilist",
            showKey = showKey,
            mappedSeason = 1L,
            remoteMediaId = "9253",
            remoteTitle = "Steins;Gate (Pinned)",
            bindMode = "USER_PINNED",
            episodeOffset = 1L,
            expectedTotalEpisodes = 24L,
            updatedAt = 3000L,
        )
        val updated = DesktopTrackerStore.getBinding(1L, "anilist", showKey, 1L)
        assertNotNull(updated)
        assertEquals("USER_PINNED", updated.bindMode)
        assertEquals(1L, updated.episodeOffset)
        assertEquals(1, DesktopTrackerStore.getBindingsForShow(1L, "anilist", showKey).size)
        assertEquals(1, DesktopTrackerStore.getAllBindingsForProfile(1L, "anilist").size)

        DesktopTrackerStore.deleteBinding(1L, "anilist", showKey, 1L)
        assertNull(DesktopTrackerStore.getBinding(1L, "anilist", showKey, 1L))
        assertNotNull(DesktopTrackerStore.getBinding(2L, "anilist", showKey, 1L))

        DesktopTrackerStore.deleteAllBindingsForProfile(2L)
        assertTrue(DesktopTrackerStore.getAllBindingsForProfile(2L, "anilist").isEmpty())
    }

    @Test
    fun `audit rows return exact unique rowids even on identical timestamps`() = runBlocking {
        val sameTs = 1_700_123_456_000L
        val row1 = DesktopTrackerStore.insertAuditRow(
            profileId = 1L,
            trackerId = "mal",
            showName = "Show A",
            remoteMediaId = "101",
            episodeNum = 1L,
            effectiveRemoteEpisode = 1L,
            oldProgress = 0L,
            newProgress = 1L,
            statusApplied = "Watching",
            state = "PENDING",
            reason = "Grace window",
            timestamp = sameTs,
        )
        val row2 = DesktopTrackerStore.insertAuditRow(
            profileId = 1L,
            trackerId = "mal",
            showName = "Show B",
            remoteMediaId = "102",
            episodeNum = 2L,
            effectiveRemoteEpisode = 2L,
            oldProgress = 1L,
            newProgress = 2L,
            statusApplied = "Watching",
            state = "PENDING",
            reason = "Grace window",
            timestamp = sameTs,
        )

        assertTrue(row1 > 0L, "Expected positive row1 but got $row1")
        assertTrue(row2 > 0L, "Expected positive row2 but got $row2")
        assertNotEquals(row1, row2)

        DesktopTrackerStore.updateAuditState(row1, "CANCELLED", "Cancelled by user")
        DesktopTrackerStore.updateAuditState(row2, "SENT", "Synced OK")

        val rows = DesktopTrackerStore.getAuditLog(1L, "mal")
        assertEquals(2, rows.size)
        assertEquals(row2, rows[0].id)
        assertEquals("SENT", rows[0].state)
        assertEquals(row1, rows[1].id)
        assertEquals("CANCELLED", rows[1].state)
    }
}
