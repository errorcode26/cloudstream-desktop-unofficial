package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonNextAiringEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AniListReleaseGuardTest {

    @Test
    fun `not yet released blocks completed status and any episode progress while allowing planning at 0`() {
        val bounds = AniListReleaseGuard.evaluate(
            mediaStatus = "NOT_YET_RELEASED",
            format = "TV",
            totalEpisodes = 12,
            nextAiring = SeasonNextAiringEpisode(episode = 1, timeUntilAiring = 604800),
        )

        assertTrue(bounds.isNotYetReleased)
        assertFalse(bounds.canMarkCompleted)
        assertEquals(0, bounds.maxAiredEpisode)
        assertEquals(0, bounds.ceilingEpisode)

        assertIs<GuardDecision.Allowed>(
            AniListReleaseGuard.validateStatusAndProgress(bounds, AniListStatusType.Planning, 0),
        )
        assertIs<GuardDecision.Blocked>(
            AniListReleaseGuard.validateStatusAndProgress(bounds, AniListStatusType.Completed, 0),
        )
        assertIs<GuardDecision.Blocked>(
            AniListReleaseGuard.validateStatusAndProgress(bounds, AniListStatusType.Watching, 1),
        )
        assertIs<EpisodeSyncCheck.Blocked>(
            AniListReleaseGuard.canSyncEpisode(bounds, rawEpisodeNumber = 1),
        )
    }

    @Test
    fun `releasing show allows aired episodes up to next minus one and blocks unaired or completed`() {
        val bounds = AniListReleaseGuard.evaluate(
            mediaStatus = "RELEASING",
            format = "TV",
            totalEpisodes = 12,
            nextAiring = SeasonNextAiringEpisode(episode = 7, timeUntilAiring = 86400),
        )

        assertTrue(bounds.isAiringOrHiatus)
        assertFalse(bounds.canMarkCompleted)
        assertEquals(6, bounds.maxAiredEpisode)
        assertEquals(6, bounds.ceilingEpisode)

        val ep6 = AniListReleaseGuard.canSyncEpisode(bounds, rawEpisodeNumber = 6)
        val allowedEp6 = assertIs<EpisodeSyncCheck.Allowed>(ep6)
        assertEquals(6, allowedEp6.effectiveEpisode)

        assertIs<EpisodeSyncCheck.Blocked>(
            AniListReleaseGuard.canSyncEpisode(bounds, rawEpisodeNumber = 7),
        )
        assertIs<GuardDecision.Blocked>(
            AniListReleaseGuard.validateStatusAndProgress(bounds, AniListStatusType.Completed, 6),
        )
        assertEquals(
            AniListStatusType.Watching,
            AniListReleaseGuard.nextStatusForManualEpisodeSync(bounds, AniListStatusType.Watching, 6),
        )
    }

    @Test
    fun `hiatus show also prevents completed status and enforces airing ceiling`() {
        val bounds = AniListReleaseGuard.evaluate(
            mediaStatus = "HIATUS",
            format = "TV",
            totalEpisodes = 24,
            nextAiring = SeasonNextAiringEpisode(episode = 10, timeUntilAiring = 400000),
        )

        assertFalse(bounds.canMarkCompleted)
        assertEquals(9, bounds.ceilingEpisode)
        assertIs<GuardDecision.Blocked>(
            AniListReleaseGuard.validateStatusAndProgress(bounds, AniListStatusType.Completed, 9),
        )
        assertIs<EpisodeSyncCheck.Blocked>(
            AniListReleaseGuard.canSyncEpisode(bounds, rawEpisodeNumber = 10),
        )
    }

    @Test
    fun `finished show marks completed on final episode and respects movie bounds`() {
        val tvFinished = AniListReleaseGuard.evaluate(
            mediaStatus = "FINISHED",
            format = "TV",
            totalEpisodes = 12,
            nextAiring = null,
        )
        assertTrue(tvFinished.canMarkCompleted)
        assertEquals(12, tvFinished.ceilingEpisode)
        assertEquals(
            AniListStatusType.Completed,
            AniListReleaseGuard.nextStatusForManualEpisodeSync(tvFinished, AniListStatusType.Watching, 12),
        )
        assertEquals(
            AniListStatusType.ReWatching,
            AniListReleaseGuard.nextStatusForManualEpisodeSync(tvFinished, AniListStatusType.ReWatching, 12),
        )

        val movieBounds = AniListReleaseGuard.evaluate(
            mediaStatus = "FINISHED",
            format = "MOVIE",
            totalEpisodes = 1,
            nextAiring = null,
        )
        assertIs<EpisodeSyncCheck.Allowed>(AniListReleaseGuard.canSyncEpisode(movieBounds, rawEpisodeNumber = 1))
        assertIs<EpisodeSyncCheck.Blocked>(AniListReleaseGuard.canSyncEpisode(movieBounds, rawEpisodeNumber = 2))
    }

    @Test
    fun `episode offset and special ep 0 checks apply cleanly against airing ceiling`() {
        val s2Airing = AniListReleaseGuard.evaluate(
            mediaStatus = "RELEASING",
            format = "TV",
            totalEpisodes = 12,
            nextAiring = SeasonNextAiringEpisode(episode = 4, timeUntilAiring = 50000),
        )

        assertIs<EpisodeSyncCheck.Blocked>(AniListReleaseGuard.canSyncEpisode(s2Airing, rawEpisodeNumber = 0))
        // Episode 15 with -12 offset => Ep 3 (aired, since next is 4)
        val ep15 = assertIs<EpisodeSyncCheck.Allowed>(
            AniListReleaseGuard.canSyncEpisode(s2Airing, rawEpisodeNumber = 15, episodeOffset = -12),
        )
        assertEquals(3, ep15.effectiveEpisode)

        // Episode 16 with -12 offset => Ep 4 (not aired yet)
        assertIs<EpisodeSyncCheck.Blocked>(
            AniListReleaseGuard.canSyncEpisode(s2Airing, rawEpisodeNumber = 16, episodeOffset = -12),
        )

        // Local unreleased block overrides even if remote says finished
        val finished = AniListReleaseGuard.evaluate("FINISHED", "TV", 12, null)
        assertIs<EpisodeSyncCheck.Blocked>(
            AniListReleaseGuard.canSyncEpisode(finished, rawEpisodeNumber = 5, localReleaseBlocked = true),
        )
    }
}
