package com.lagradost.cloudstream3.desktop.profile

import com.lagradost.cloudstream3.desktop.data.bookmarks.BookmarksRepositoryImpl
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileDataIsolationTest {

    private val originalProvider = DesktopDataStore.activeProfileProvider

    @BeforeEach
    fun setUp() {
        cleanStorage()
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.activeProfileProvider = originalProvider
        cleanStorage()
    }

    private fun cleanStorage() {
        try {
            DesktopDataStore.clearAllBookmarks(profileId = 0)
            DesktopDataStore.clearAllBookmarks(profileId = 1)
            DesktopDataStore.clearAllBookmarks(profileId = 2)
            DesktopDataStore.clearAllWatchHistory(profileId = 0)
            DesktopDataStore.clearAllWatchHistory(profileId = 1)
            DesktopDataStore.clearAllWatchHistory(profileId = 2)
        } catch (_: Exception) {}
    }

    @Test
    fun testBookmarkIsolationBetweenProfiles() {
        DesktopDataStore.activeProfileProvider = { 0 }
        val bookmark0 = DesktopBookmark(
            id = "movie_alpha",
            name = "Movie Alpha",
            url = "https://example.com/alpha",
            apiName = "TestProvider",
            posterUrl = null,
        )
        DesktopDataStore.addBookmark(bookmark0, profileId = 0)

        DesktopDataStore.activeProfileProvider = { 1 }
        val bookmark1 = DesktopBookmark(
            id = "movie_beta",
            name = "Movie Beta",
            url = "https://example.com/beta",
            apiName = "TestProvider",
            posterUrl = null,
        )
        DesktopDataStore.addBookmark(bookmark1, profileId = 1)

        val profile0Bookmarks = DesktopDataStore.getBookmarks(profileId = 0)
        val profile1Bookmarks = DesktopDataStore.getBookmarks(profileId = 1)

        assertEquals(1, profile0Bookmarks.size)
        assertEquals("movie_alpha", profile0Bookmarks.first().id)

        assertEquals(1, profile1Bookmarks.size)
        assertEquals("movie_beta", profile1Bookmarks.first().id)

        assertFalse(DesktopDataStore.isBookmarked("movie_beta", profileId = 0))
        assertTrue(DesktopDataStore.isBookmarked("movie_alpha", profileId = 0))

        assertFalse(DesktopDataStore.isBookmarked("movie_alpha", profileId = 1))
        assertTrue(DesktopDataStore.isBookmarked("movie_beta", profileId = 1))
    }

    @Test
    fun testWatchHistoryIsolationBetweenProfiles() {
        val history0 = WatchHistory(
            parentId = "p0_test_show_1",
            showName = "Profile 0 Show",
            showUrl = "https://example.com/show0",
            apiName = "TestProvider",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = "ep1",
            position = 500L,
            duration = 1000L,
            updateTime = 1000L,
        )
        DesktopDataStore.setLastWatched(history0)

        val history1 = WatchHistory(
            parentId = "p1_test_show_2",
            showName = "Profile 1 Show",
            showUrl = "https://example.com/show1",
            apiName = "TestProvider",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 2,
            season = 1,
            episodeId = "ep2",
            position = 800L,
            duration = 1200L,
            updateTime = 2000L,
        )
        DesktopDataStore.setLastWatched(history1)

        val profile0History = DesktopDataStore.getAllWatchHistory(profileId = 0)
        val profile1History = DesktopDataStore.getAllWatchHistory(profileId = 1)

        assertEquals(1, profile0History.size)
        assertEquals("Profile 0 Show", profile0History.first().showName)

        assertEquals(1, profile1History.size)
        assertEquals("Profile 1 Show", profile1History.first().showName)
    }

    @Test
    fun testProfileDeletionCascadesDataWipe() {
        val b2 = DesktopBookmark(
            id = "profile2_item",
            name = "Profile 2 Item",
            url = "https://example.com/item2",
            apiName = "TestProvider",
            posterUrl = null,
        )
        DesktopDataStore.addBookmark(b2, profileId = 2)

        val h2 = WatchHistory(
            parentId = "p2_item2",
            showName = "Profile 2 Show",
            showUrl = "https://example.com/item2",
            apiName = "TestProvider",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = "ep1",
            position = 100L,
            duration = 500L,
            updateTime = 3000L,
        )
        DesktopDataStore.setLastWatched(h2)

        assertEquals(1, DesktopDataStore.getBookmarks(profileId = 2).size)
        assertEquals(1, DesktopDataStore.getAllWatchHistory(profileId = 2).size)

        DesktopDataStore.clearAllBookmarks(profileId = 2)
        DesktopDataStore.clearAllWatchHistory(profileId = 2)

        assertEquals(0, DesktopDataStore.getBookmarks(profileId = 2).size)
        assertEquals(0, DesktopDataStore.getAllWatchHistory(profileId = 2).size)
    }

    @Test
    fun testBookmarksRepositoryReactiveOnProfileSwitch() = runBlocking {
        var activeId = 0
        DesktopDataStore.activeProfileProvider = { activeId }

        DesktopDataStore.addBookmark(
            DesktopBookmark("item_0", "Item Zero", "https://zero.com", "Test", null),
            profileId = 0,
        )
        DesktopDataStore.addBookmark(
            DesktopBookmark("item_1", "Item One", "https://one.com", "Test", null),
            profileId = 1,
        )

        val repo = BookmarksRepositoryImpl()

        withTimeout(5000) {
            repo.subscribeAll().first { it.containsKey("item_0") }
        }

        // Switch active profile to Profile 1
        activeId = 1
        DesktopDataStore.notifyBookmarksChanged()

        val profile1State = withTimeout(5000) {
            repo.subscribeAll().first { it.containsKey("item_1") }
        }
        assertTrue(profile1State.containsKey("item_1"), "After switching to profile 1, repository must reflect profile 1 bookmarks")
        assertFalse(profile1State.containsKey("item_0"), "Profile 0 bookmarks must no longer appear in profile 1")
    }
}
