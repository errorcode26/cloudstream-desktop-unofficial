package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeSectionStateTest {

    private val dummyApi = object : MainAPI() {
        override var name = "TestApi"
        override var mainUrl = "https://example.com"
    }

    private fun makeEpisode(season: Int?, episode: Int?, data: String = "data_$season-$episode"): Episode {
        return with(dummyApi) {
            newEpisode(data) {
                this.name = "Ep $episode"
                this.season = season
                this.episode = episode
            }
        }
    }

    @Test
    fun testResolveSeasons() = runBlocking {
        val episodes = listOf(
            makeEpisode(0, 1),
            makeEpisode(1, 1),
            makeEpisode(1, 2),
            makeEpisode(2, 1),
        )
        val tvResponse = with(dummyApi) {
            newTvSeriesLoadResponse(
                name = "Test Series",
                url = "https://example.com/series",
                type = TvType.TvSeries,
                episodes = episodes,
            )
        }

        val seasons = EpisodeSectionState.resolveSeasons(tvResponse)
        // Positive seasons sorted first (1, 2), then non-positive (0)
        assertEquals(listOf(1, 2, 0), seasons)
    }

    @Test
    fun testResolveInitialSeason() = runBlocking {
        val seasons = listOf(1, 2, 0)
        val tvResponse = with(dummyApi) {
            newTvSeriesLoadResponse(
                name = "Test Series",
                url = "https://example.com/series",
                type = TvType.TvSeries,
                episodes = emptyList<Episode>(),
            )
        }

        // Without history, defaults to first positive season (1)
        val defaultSeason = EpisodeSectionState.resolveInitialSeason(tvResponse, null, seasons)
        assertEquals(1, defaultSeason)

        // With history matching season 2
        val historySeason2 = WatchHistory(
            parentId = "parent_1",
            showName = "Test Series",
            showUrl = "https://example.com/series",
            apiName = "TestApi",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            season = 2,
            episode = 1,
            episodeId = "data_2-1",
            position = 1000L,
            duration = 5000L,
            updateTime = 0L,
            episodeName = "Ep 1",
            episodeDescription = null,
        )
        val historySeason = EpisodeSectionState.resolveInitialSeason(tvResponse, historySeason2, seasons)
        assertEquals(2, historySeason)
    }

    @Test
    fun testFilterAndSortEpisodes() {
        val episodes = listOf(
            makeEpisode(1, 3),
            makeEpisode(1, 1),
            makeEpisode(1, 2),
            makeEpisode(2, 1),
        )

        val filteredAsc = EpisodeSectionState.filterAndSortEpisodes(
            rawEpisodes = episodes,
            effectiveSeason = 1,
            seasons = listOf(1, 2),
            isSortAscending = true,
        )
        assertEquals(3, filteredAsc.size)
        assertEquals(1, filteredAsc[0].episode)
        assertEquals(2, filteredAsc[1].episode)
        assertEquals(3, filteredAsc[2].episode)

        val filteredDesc = EpisodeSectionState.filterAndSortEpisodes(
            rawEpisodes = episodes,
            effectiveSeason = 1,
            seasons = listOf(1, 2),
            isSortAscending = false,
        )
        assertEquals(3, filteredDesc.size)
        assertEquals(3, filteredDesc[0].episode)
        assertEquals(2, filteredDesc[1].episode)
        assertEquals(1, filteredDesc[2].episode)
    }

    @Test
    fun testCalculateTargetEpisodeIndex() {
        val episodes = listOf(
            makeEpisode(1, 1, data = "ep_1"),
            makeEpisode(1, 2, data = "ep_2"),
            makeEpisode(1, 3, data = "ep_3"),
        )

        // Null history defaults to index 0
        assertEquals(0, EpisodeSectionState.calculateTargetEpisodeIndex(episodes, null))

        // In-progress episode 1 -> target is index 0
        val inProgressHist = WatchHistory(
            parentId = "parent_1",
            showName = "Test Series",
            showUrl = "https://example.com/series",
            apiName = "TestApi",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            season = 1,
            episode = 1,
            episodeId = "ep_1",
            position = 1000L,
            duration = 5000L,
            updateTime = 0L,
            episodeName = "Ep 1",
            episodeDescription = null,
        )
        assertEquals(0, EpisodeSectionState.calculateTargetEpisodeIndex(episodes, inProgressHist))

        // Completed episode 1 (position >= 90% duration) -> target advances to next episode (index 1)
        val completedHist = WatchHistory(
            parentId = "parent_1",
            showName = "Test Series",
            showUrl = "https://example.com/series",
            apiName = "TestApi",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            season = 1,
            episode = 1,
            episodeId = "ep_1",
            position = 4800L,
            duration = 5000L,
            updateTime = 0L,
            episodeName = "Ep 1",
            episodeDescription = null,
        )
        assertEquals(1, EpisodeSectionState.calculateTargetEpisodeIndex(episodes, completedHist))
    }
}
