package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.Episode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerEpisodeNavigatorTest {

    @Suppress("DEPRECATION_ERROR")
    private fun createEpisode(data: String, epNum: Int, season: Int = 1): Episode {
        return Episode(
            data = data,
            name = "Episode $epNum",
            season = season,
            episode = epNum,
        )
    }

    @Test
    fun testFindNextEpisodeByIndex() {
        val ep1 = createEpisode("ep1", 1)
        val ep2 = createEpisode("ep2", 2)
        val ep3 = createEpisode("ep3", 3)
        val episodes = listOf(ep1, ep2, ep3)

        val next = PlayerEpisodeNavigator.findNextEpisode(episodes, currentEpId = "ep1", currentEpNum = 1, currentSeasonNum = 1)
        assertEquals("ep2", next?.data)

        val last = PlayerEpisodeNavigator.findNextEpisode(episodes, currentEpId = "ep3", currentEpNum = 3, currentSeasonNum = 1)
        assertNull(last)
    }

    @Test
    fun testFindNextEpisodeByNumberFallback() {
        val ep1 = createEpisode("ep1_hash", 1)
        val ep2 = createEpisode("ep2_hash", 2)
        val episodes = listOf(ep1, ep2)

        // EpId does not match any data, fallback by episode number
        val next = PlayerEpisodeNavigator.findNextEpisode(episodes, currentEpId = "unknown_id", currentEpNum = 1, currentSeasonNum = 1)
        assertEquals("ep2_hash", next?.data)
    }

    @Test
    fun testFindPrevEpisode() {
        val ep1 = createEpisode("ep1", 1)
        val ep2 = createEpisode("ep2", 2)
        val episodes = listOf(ep1, ep2)

        val prev = PlayerEpisodeNavigator.findPrevEpisode(episodes, currentEpId = "ep2", currentEpNum = 2, currentSeasonNum = 1)
        assertEquals("ep1", prev?.data)

        val firstPrev = PlayerEpisodeNavigator.findPrevEpisode(episodes, currentEpId = "ep1", currentEpNum = 1, currentSeasonNum = 1)
        assertNull(firstPrev)
    }

    @Test
    fun testCheckEpisodeLockUnreleased() {
        val unreleasedEp = createEpisode("unreleased_100", 1)
        val status = PlayerEpisodeNavigator.checkEpisodeLock(
            episode = unreleasedEp,
            resolvedApiName = "TestProvider",
            lockUnreleased = true,
            hasCachedLinks = false,
        )
        assertTrue(status.isLocked)
    }
}
