package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStoresAndMigrationTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeTest
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        DesktopDataStore.isWorkerMode = false
        DesktopDataStore.onPersistKeyOverride = null
        DesktopDataStore.onRemoveKeyOverride = null
        tempDir = Files.createTempDirectory("cs_stores_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        DesktopDataStore.onPersistKeyOverride = null
        DesktopDataStore.onRemoveKeyOverride = null
        DesktopDataStore.isWorkerMode = false
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `legacy datastore json migrates bookmarks history updates and custom keys to SQLite`() {
        val dataDir = PlatformPaths.dataDir
        val legacyJson = File(dataDir, "datastore.json")

        val bookmarksJson = DesktopDataStore.mapper.writeValueAsString(
            listOf(
                DesktopBookmark("bm_1", "Legacy Movie", "https://example.org/movie/1", "DemoApi", "https://img/1.jpg", 1, 100L),
            ),
        )
        val historyJson = DesktopDataStore.mapper.writeValueAsString(
            listOf(
                WatchHistory(
                    parentId = "p0_DemoApi_1",
                    showName = "Legacy Series",
                    showUrl = "https://example.org/series/1",
                    apiName = "DemoApi",
                    posterUrl = null,
                    episodeThumbnailUrl = null,
                    screenshotUrl = null,
                    episode = 2,
                    season = 1,
                    episodeId = "ep2",
                    position = 500L,
                    duration = 1500L,
                    updateTime = 200L,
                ),
            ),
        )
        val updatesJson = DesktopDataStore.mapper.writeValueAsString(
            listOf(PluginUpdateRecord("DemoPlugin", 3, "https://img/icon.png", 300L)),
        )

        legacyJson.writeText(
            DesktopDataStore.mapper.writeValueAsString(
                mapOf(
                    "user_bookmarks" to bookmarksJson,
                    "user_watch_history" to historyJson,
                    "plugin_updates_history_v2" to updatesJson,
                    "custom_setting_key" to DesktopDataStore.mapper.writeValueAsString("hello_world"),
                ),
            ),
        )

        DesktopDataStore.init()

        assertFalse(legacyJson.exists(), "Legacy datastore.json should be renamed after migration")
        assertTrue(File(dataDir, "datastore.json.bak").exists(), "Backup file should exist after migration")

        val bookmarks = DesktopBookmarkStore.getBookmarks(profileId = 0)
        assertEquals(1, bookmarks.size)
        assertEquals("Legacy Movie", bookmarks.first().name)

        val history = DesktopWatchHistoryStore.getAllWatchHistory(profileId = 0)
        assertEquals(1, history.size)
        assertEquals("Legacy Series", history.first().showName)

        val updates = DesktopDataStore.getUpdatesHistory()
        assertEquals(1, updates.size)
        assertEquals("DemoPlugin", updates.first().pluginName)

        assertEquals("hello_world", DesktopDataStore.getKey("custom_setting_key", String::class.java))
    }

    @Test
    fun `DesktopBookmarkStore isolates profiles deduplicates and handles legacy profile 0 IDs`() {
        DesktopDataStore.init()

        assertEquals("https://example.org/a", DesktopBookmarkStore.normalizeUrl("  https://example.org/a/  "))
        assertEquals(
            DesktopBookmarkStore.bookmarkId("ApiA", "https://example.org/a"),
            DesktopBookmarkStore.bookmarkId("ApiA", "https://example.org/a/"),
        )

        DesktopBookmarkStore.addBookmark(DesktopBookmark("p0_show1", "Show One", "https://example.org/1", "ApiA", null), profileId = 0)
        DesktopBookmarkStore.addBookmark(DesktopBookmark("show2", "   ", "https://example.org/2", "ApiA", null), profileId = 0)
        DesktopBookmarkStore.addBookmark(DesktopBookmark("show1", "Other Profile Show", "https://example.org/1b", "ApiA", null), profileId = 1)

        val p0 = DesktopBookmarkStore.getBookmarks(profileId = 0)
        assertEquals(listOf("show1"), p0.map { it.id })
        assertTrue(DesktopBookmarkStore.isBookmarked("show1", profileId = 0))
        assertTrue(DesktopBookmarkStore.isBookmarked("p0_show1", profileId = 0))

        // Insert raw unprefixed row to exercise profile 0 legacy fallback query & delete
        DatabaseFactory.database.cloudstreamDBQueries.insertBookmark(
            "legacy_unprefixed", "Legacy Unprefixed", "https://example.org/legacy", "ApiA", null, 0L, 10L, 0L,
        )
        assertTrue(DesktopBookmarkStore.isBookmarked("legacy_unprefixed", profileId = 0))
        DesktopBookmarkStore.removeBookmark("legacy_unprefixed", profileId = 0)
        assertFalse(DesktopBookmarkStore.isBookmarked("legacy_unprefixed", profileId = 0))

        DesktopBookmarkStore.removeBookmark("show1", profileId = 0)
        assertFalse(DesktopBookmarkStore.isBookmarked("show1", profileId = 0))
        assertTrue(DesktopBookmarkStore.isBookmarked("show1", profileId = 1))
    }

    @Test
    fun `DesktopWatchHistoryStore supports batch write clamping partial delete and migration`() {
        DesktopDataStore.init()

        val parent0 = DesktopWatchHistoryStore.watchHistoryId("SrcApi", "https://example.org/show/alpha", profileId = 0)
        val epParent = DesktopWatchHistoryStore.watchHistoryId("SrcApi", "https://example.org/show/alpha", season = 1, episode = 2, episodeData = "e2", profileId = 0)
        assertNotEquals(parent0, epParent)

        // Unknown / zero-progress entries must be filtered out
        DesktopWatchHistoryStore.setLastWatched(
            WatchHistory(parent0, "Unknown", "https://example.org/show/alpha", "SrcApi", null, null, null, 1, 1, "e0", 100L, 1000L),
        )
        assertTrue(DesktopWatchHistoryStore.getAllWatchHistory(profileId = 0).isEmpty())

        DesktopWatchHistoryStore.setMultipleLastWatched(
            listOf(
                WatchHistory(parent0, "Alpha", "https://example.org/show/alpha", "SrcApi", null, null, null, 1, 1, "e1", 1500L, 1000L, updateTime = 100L),
                WatchHistory(parent0, "Alpha", "https://example.org/show/alpha", "SrcApi", null, null, null, 2, 1, "e2", 400L, 1000L, updateTime = 200L),
                WatchHistory(parent0, "Alpha", "https://example.org/show/alpha", "SrcApi", null, null, null, 3, 1, "e3", 200L, 1000L, updateTime = 300L),
                WatchHistory(parent0, "Unknown Channel", "https://example.org/bad", "SrcApi", null, null, null, 1, 1, "bad", 100L, 500L),
            ),
        )

        val e1 = DesktopWatchHistoryStore.getEpisodeWatched(parent0, "e1")
        assertNotNull(e1)
        assertEquals(1000L, e1.position, "Position must clamp to duration")

        assertEquals(3, DesktopWatchHistoryStore.getWatchHistoryByParent(parent0).size)
        assertEquals("e3", DesktopWatchHistoryStore.getLatestWatchHistoryForShow("https://example.org/show/alpha")?.episodeId)
        assertNotNull(DesktopWatchHistoryStore.getLastWatched(parent0))

        // Delete episode 1 by season/episode match & episode 2 by ID list
        DesktopWatchHistoryStore.removeEpisodeWatched(parent0, episodeId = "mismatch", season = 1, episode = 1)
        assertNull(DesktopWatchHistoryStore.getEpisodeWatched(parent0, "e1"))

        DesktopWatchHistoryStore.removeMultipleEpisodesWatched(parent0, listOf("e2"))
        assertNull(DesktopWatchHistoryStore.getEpisodeWatched(parent0, "e2"))
        assertNotNull(DesktopWatchHistoryStore.getEpisodeWatched(parent0, "e3"))

        // Add bookmark and migrate both history + bookmark to a new provider/URL
        val bmId = DesktopBookmarkStore.bookmarkId("SrcApi", "https://example.org/show/alpha")
        DesktopBookmarkStore.addBookmark(DesktopBookmark(bmId, "Alpha", "https://example.org/show/alpha", "SrcApi", null), profileId = 0)

        val migrated = DesktopWatchHistoryStore.migrateWatchHistoryAndBookmark(
            oldApiName = "SrcApi",
            oldUrl = "https://example.org/show/alpha",
            newApiName = "DstApi",
            newUrl = "https://mirror.example.org/show/alpha",
            newName = "Alpha Migrated",
            newPosterUrl = "https://img.example.org/poster.jpg",
            profileId = 0,
        )
        assertTrue(migrated)

        val newParent = DesktopWatchHistoryStore.watchHistoryId("DstApi", "https://mirror.example.org/show/alpha", profileId = 0)
        assertTrue(DesktopWatchHistoryStore.getWatchHistoryByParent(parent0).isEmpty())
        val migratedEp = DesktopWatchHistoryStore.getEpisodeWatched(newParent, "e3")
        assertNotNull(migratedEp)
        assertEquals("Alpha Migrated", migratedEp.showName)

        val newBmId = DesktopBookmarkStore.bookmarkId("DstApi", "https://mirror.example.org/show/alpha")
        assertFalse(DesktopBookmarkStore.isBookmarked(bmId, profileId = 0))
        assertTrue(DesktopBookmarkStore.isBookmarked(newBmId, profileId = 0))

        DesktopWatchHistoryStore.removeWatchHistory(newParent)
        assertTrue(DesktopWatchHistoryStore.getWatchHistoryByParent(newParent).isEmpty())
    }

    @Test
    fun `DesktopDataStore key overrides worker mode trusted plugins and verification domain rules work`() = runBlocking {
        DesktopDataStore.init()

        val recordedWrites = CopyOnWriteArrayList<Pair<String, String>>()
        val recordedRemovals = CopyOnWriteArrayList<String>()
        DesktopDataStore.onPersistKeyOverride = { k, v -> recordedWrites.add(k to v) }
        DesktopDataStore.onRemoveKeyOverride = { k -> recordedRemovals.add(k) }

        DesktopDataStore.setRawJson("raw_k", "\"raw_v\"")
        assertEquals("raw_v", DesktopDataStore.getKey("raw_k", String::class.java))
        assertTrue(DesktopDataStore.containsKey("raw_k"))
        assertEquals(listOf("raw_k"), DesktopDataStore.getAllKeysWithPrefix("raw_"))

        DesktopDataStore.removeKey("raw_k")
        assertFalse(DesktopDataStore.containsKey("raw_k"))
        assertEquals(listOf("raw_k" to "\"raw_v\""), recordedWrites)
        assertEquals(listOf("raw_k"), recordedRemovals)

        DesktopDataStore.onPersistKeyOverride = null
        DesktopDataStore.onRemoveKeyOverride = null

        // Trusted plugin normalization
        DesktopDataStore.setPluginTrusted("Demo", true)
        assertTrue(DesktopDataStore.isPluginTrusted("Demo"))
        assertTrue(DesktopDataStore.isPluginTrusted("com.example.DemoProvider-jvm"))
        DesktopDataStore.setPluginTrusted("Demo", false)
        assertFalse(DesktopDataStore.isPluginTrusted("com.example.DemoProvider"))

        DesktopDataStore.setPluginTrusted("com.example.ExactPlugin-jvm", true)
        assertTrue(DesktopDataStore.isPluginTrusted("com.example.ExactPlugin"))
        DesktopDataStore.setPluginTrusted("com.example.ExactPlugin-jvm", false)
        assertFalse(DesktopDataStore.isPluginTrusted("com.example.ExactPlugin"))

        // Verification domain silencing + persistent ignore
        DesktopDataStore.silenceDomainForSession("www.example.org")
        assertTrue(DesktopDataStore.isDomainCfSilenced("example.org"))
        assertFalse(DesktopDataStore.isDomainCfIgnored("example.org"))
        DesktopDataStore.setDomainCfIgnored("www.example.org", true)
        assertTrue(DesktopDataStore.isDomainCfIgnored("example.org"))
        DesktopDataStore.setDomainCfIgnored("www.example.org", false)
        assertFalse(DesktopDataStore.isDomainCfSilenced("example.org"))

        // Worker mode reads directly from in-memory cache
        DesktopDataStore.enableWorkerMode()
        assertTrue(DesktopDataStore.isWorkerMode)
        DesktopDataStore.setKey("worker_k", 42)
        assertEquals(42, DesktopDataStore.getKey<Int>("worker_k"))
        assertEquals(42, DesktopDataStore.getKey("worker_k", Int::class.java))
        DesktopDataStore.removeKey("worker_k")
        assertNull(DesktopDataStore.getKey<Int>("worker_k"))

        // Update history + unread flag
        DesktopDataStore.isWorkerMode = false
        DesktopDataStore.addUpdateHistory(listOf(PluginUpdateRecord("PlugA", 2, null, 1000L)))
        assertEquals(1, DesktopDataStore.getUpdatesHistory().size)
        DesktopDataStore.setUnreadUpdates(true)
        assertTrue(DesktopDataStore.hasUnreadUpdates())
        DesktopDataStore.clearUpdatesHistory()
        assertTrue(DesktopDataStore.getUpdatesHistory().isEmpty())

        // Audit pruning in DesktopTrackerStore (retains latest N rows per trackerId)
        val insertedIds = (1..205).map { idx ->
            DesktopTrackerStore.insertAuditRow(
                profileId = 0L,
                trackerId = "anilist",
                showName = "Show $idx",
                remoteMediaId = "$idx",
                episodeNum = idx.toLong(),
                effectiveRemoteEpisode = idx.toLong(),
                oldProgress = 0,
                newProgress = 1,
                statusApplied = "Watching",
                state = "SENT",
                reason = "ok",
                timestamp = idx.toLong() * 1000L,
            )
        }
        assertTrue(insertedIds.all { it > 0L })
        DesktopTrackerStore.pruneAudit("anilist")
        val audit = DesktopTrackerStore.getAuditLog(0L, "anilist")
        assertTrue(audit.size < 205)
        assertTrue(audit.any { it.showName == "Show 205" })
    }
}
