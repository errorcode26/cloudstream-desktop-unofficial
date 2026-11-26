package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackerEntryState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackersUiState
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackerRegistryAndProviderTest {

    @Test
    fun `registry provides anilist mal and simkl providers and keeps only trakt planned`() {
        val anilist = TrackerRegistry.get(TrackerIds.ANILIST)
        assertNotNull(anilist)
        assertEquals("AL", anilist.descriptor.shortTag)
        assertEquals("AniList", anilist.descriptor.displayName)
        assertEquals("https://anilist.co/anime/171018", anilist.webUrl("171018"))

        val mal = TrackerRegistry.get(TrackerIds.MAL)
        assertNotNull(mal)
        assertEquals("MAL", mal.descriptor.shortTag)
        assertEquals("MyAnimeList", mal.descriptor.displayName)
        assertEquals("https://myanimelist.net/anime/52991", mal.webUrl("52991"))

        val simkl = TrackerRegistry.get(TrackerIds.SIMKL)
        assertNotNull(simkl)
        assertEquals("SK", simkl.descriptor.shortTag)
        assertEquals("SIMKL", simkl.descriptor.displayName)
        assertTrue(simkl.descriptor.supportsTvAndMovies)
        assertEquals("https://simkl.com/anime/4478", simkl.webUrl("anime:4478"))
        assertEquals("https://simkl.com/tv/1234", simkl.webUrl("show:1234"))
        assertEquals("https://simkl.com/movies/53536", simkl.webUrl("movie:53536"))

        assertFalse(TrackerRegistry.plannedTrackers.any { it.id == TrackerIds.MAL })
        assertFalse(TrackerRegistry.plannedTrackers.any { it.id == TrackerIds.SIMKL })
        assertTrue(TrackerRegistry.plannedTrackers.any { it.id == TrackerIds.TRAKT })
    }

    @Test
    fun `anilist and mal restrict to anime while simkl supports tv movies and anime`() {
        val anilist = AniListTrackerProvider()
        val mal = MalTrackerProvider()
        val simkl = SimklTrackerProvider()

        assertTrue(anilist.isEligibleForMedia(TvType.Anime, hasAnimeSeed = false, hasExistingBinding = false))
        assertFalse(anilist.isEligibleForMedia(TvType.Movie, hasAnimeSeed = false, hasExistingBinding = false))
        assertTrue(anilist.isEligibleForMedia(TvType.TvSeries, hasAnimeSeed = true, hasExistingBinding = false))
        assertTrue(anilist.isEligibleForMedia(TvType.TvSeries, hasAnimeSeed = false, hasExistingBinding = true))

        assertTrue(mal.isEligibleForMedia(TvType.Anime, hasAnimeSeed = false, hasExistingBinding = false))
        assertTrue(mal.isEligibleForMedia(TvType.AnimeMovie, hasAnimeSeed = false, hasExistingBinding = false))
        assertFalse(mal.isEligibleForMedia(TvType.Movie, hasAnimeSeed = false, hasExistingBinding = false))
        assertFalse(mal.isEligibleForMedia(TvType.TvSeries, hasAnimeSeed = false, hasExistingBinding = false))
        assertTrue(mal.isEligibleForMedia(TvType.TvSeries, hasAnimeSeed = true, hasExistingBinding = false))

        assertTrue(simkl.isEligibleForMedia(TvType.Anime, hasAnimeSeed = false, hasExistingBinding = false))
        assertTrue(simkl.isEligibleForMedia(TvType.Movie, hasAnimeSeed = false, hasExistingBinding = false))
        assertTrue(simkl.isEligibleForMedia(TvType.TvSeries, hasAnimeSeed = false, hasExistingBinding = false))
        assertFalse(simkl.isEligibleForMedia(TvType.Live, hasAnimeSeed = false, hasExistingBinding = false))
        assertFalse(simkl.isEligibleForMedia(TvType.Podcast, hasAnimeSeed = false, hasExistingBinding = false))

        with(AniListTrackerProvider) {
            assertEquals(AniListStatusType.Watching, UnifiedWatchStatus.WATCHING.toAniListStatusType())
            assertEquals(AniListStatusType.Planning, UnifiedWatchStatus.PLAN_TO_WATCH.toAniListStatusType())
            assertEquals(AniListStatusType.ReWatching, UnifiedWatchStatus.REWATCHING.toAniListStatusType())
            assertEquals(UnifiedWatchStatus.PAUSED, AniListStatusType.Paused.toUnifiedStatus())
        }
    }

    @Test
    fun `strictLocalReleaseCeiling computes minimum released ceiling across multiple trackers with offsets`() {
        val bindingAl = TrackerBinding(
            profileId = 1L,
            trackerId = TrackerIds.ANILIST,
            showKey = "k",
            mappedSeason = 1L,
            remoteMediaId = "101",
            remoteTitle = "Show S1",
            bindMode = BindMode.USER_PINNED,
            episodeOffset = 0L,
            expectedTotalEpisodes = 12L,
            updatedAt = 1L,
        )
        val bindingMal = bindingAl.copy(
            trackerId = TrackerIds.MAL,
            mappedSeason = 2L,
            remoteMediaId = "202",
            episodeOffset = -12L,
        )

        val alEntry = DetailsTrackerEntryState(
            descriptor = TrackerDescriptor(TrackerIds.ANILIST, "AniList", "AL", 0xFF02A9FFL, 0xFF38BDF8L),
            isConnected = true,
            isEligible = true,
            boundEntry = bindingAl,
            editOffset = 0,
            releaseBounds = ReleaseBounds(ceilingEpisode = 8),
        )
        val malEntry = DetailsTrackerEntryState(
            descriptor = TrackerDescriptor(TrackerIds.MAL, "MyAnimeList", "MAL", 0xFF2E51A2L, 0xFF60A5FAL),
            isConnected = true,
            isEligible = true,
            boundEntry = bindingMal,
            editOffset = -12,
            releaseBounds = ReleaseBounds(ceilingEpisode = 5),
        )

        val trackersState = DetailsTrackersUiState(
            entries = mapOf(
                TrackerIds.ANILIST to alEntry,
                TrackerIds.MAL to malEntry,
            ),
            isDrawerOpen = true,
            activeProviderId = TrackerIds.MAL,
        )

        assertEquals(8, trackersState.strictLocalReleaseCeiling)
        assertEquals(2, trackersState.availableTrackers.size)
        assertEquals(TrackerIds.MAL, trackersState.activeEntry?.descriptor?.id)
    }
}
