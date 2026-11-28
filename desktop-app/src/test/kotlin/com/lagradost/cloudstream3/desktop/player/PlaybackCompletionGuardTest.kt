package com.lagradost.cloudstream3.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCompletionGuardTest {

    @Test
    fun `rejects short decoy clip even when played to EOF`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = null, priorPositionSec = 0L, priorDurationSec = 0L)

        for (sec in 0L..12L) {
            guard.recordTick(posSec = sec, durSec = 12L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertFalse(guard.isDurationValidForPersistence(12L))
        assertFalse(guard.isDurationValidForCompletion(12L))
        assertFalse(guard.canMarkCompleted(posSec = 12L, durSec = 12L, completionThreshold = 0.85f))
    }

    @Test
    fun `allows legitimate short-form anime when metadata confirms short duration`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 3, priorPositionSec = 0L, priorDurationSec = 0L)

        for (sec in 0L..160L) {
            guard.recordTick(posSec = sec, durSec = 175L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.isDurationValidForCompletion(175L))
        assertTrue(guard.canMarkCompleted(posSec = 160L, durSec = 175L, completionThreshold = 0.85f))
    }

    @Test
    fun `rejects truncated stream far shorter than expected metadata runtime`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        // 5-minute truncated playlist claiming 100% completion on a 24-minute episode
        for (sec in 0L..295L) {
            guard.recordTick(posSec = sec, durSec = 300L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertFalse(guard.isDurationValidForCompletion(300L))
        assertFalse(guard.canMarkCompleted(posSec = 295L, durSec = 300L, completionThreshold = 0.85f))
    }

    @Test
    fun `rejects scrub-to-end without genuine watched coverage`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        // Play first 5 seconds
        for (sec in 0L..5L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }
        // Scrub straight to 21:40 (1300s) and watch 10s
        for (sec in 1300L..1310L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertFalse(guard.canMarkCompleted(posSec = 1310L, durSec = 1440L, completionThreshold = 0.85f))
        val clamped = guard.clampPersistablePosition(posSec = 1310L, durSec = 1440L, completionThreshold = 0.85f)
        assertTrue(clamped < (1440L * 0.85f).toLong())
        assertEquals(1152L, clamped)
    }

    @Test
    fun `allows genuine viewing even with OP and ED skipped`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        // Skip OP cleanly to 90s, then watch through 1320s (83% unique coverage)
        guard.recordTick(posSec = 0L, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
        fakeNow += 1000L
        for (sec in 90L..1320L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.canMarkCompleted(posSec = 1320L, durSec = 1440L, completionThreshold = 0.85f))
    }

    @Test
    fun `allows completion when resumed from substantial verified prior progress`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 950L, priorDurationSec = 1440L)

        for (sec in 950L..1260L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.canMarkCompleted(posSec = 1260L, durSec = 1440L, completionThreshold = 0.85f))
    }

    @Test
    fun `blocks completion if stream errored without healthy recovery`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        for (sec in 0L..1260L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }
        guard.markError()

        assertFalse(guard.canMarkCompleted(posSec = 1260L, durSec = 1440L, completionThreshold = 0.85f))
        assertFalse(guard.canAutoTrackCompleted(posSec = 1260L, durSec = 1440L, completionThreshold = 0.80f))
    }

    @Test
    fun `auto track allows skipped viewing past threshold after minimum active playback`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        // Scrub near 80% (1152s) and actively play 12 seconds
        for (sec in 1152L..1164L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.canAutoTrackCompleted(posSec = 1164L, durSec = 1440L, completionThreshold = 0.80f))
    }

    @Test
    fun `auto track rejects instant scrub without minimum active playback`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 0L, priorDurationSec = 0L)

        for (sec in 1152L..1154L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertFalse(guard.canAutoTrackCompleted(posSec = 1154L, durSec = 1440L, completionThreshold = 0.80f))
    }

    @Test
    fun `allows next episode 80 percent completion across resumed 40 percent prior watch without clamping`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        // Session 1 stopped at 40% (576s / 1440s); session 2 resumes and watches to 80.2% (1155s)
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 576L, priorDurationSec = 1440L)

        for (sec in 576L..1155L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.canMarkCompleted(posSec = 1155L, durSec = 1440L, completionThreshold = 0.80f))
        assertEquals(1440L, guard.clampPersistablePosition(posSec = 1155L, durSec = 1440L, completionThreshold = 0.80f))
    }

    @Test
    fun `allows completion on short final resume segment from 76 percent past 80 percent`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 1094L, priorDurationSec = 1440L)

        // User watches only 62s in the final session to cross 80% before clicking Next Episode
        for (sec in 1094L..1156L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertTrue(guard.canMarkCompleted(posSec = 1156L, durSec = 1440L, completionThreshold = 0.80f))
    }

    @Test
    fun `rejects scrub to 80 percent even when resuming prior partial progress`() {
        var fakeNow = 1000L
        val guard = PlaybackCompletionGuard(clockMs = { fakeNow })
        guard.reset(isMovie = false, expectedDurationMinutes = 24, priorPositionSec = 576L, priorDurationSec = 1440L)

        for (sec in 576L..580L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }
        // Jump immediately to 1180s and watch 5s
        for (sec in 1180L..1185L) {
            guard.recordTick(posSec = sec, durSec = 1440L, isPaused = false, isBuffering = false, isPlayingPhase = true)
            fakeNow += 1000L
        }

        assertFalse(guard.canMarkCompleted(posSec = 1185L, durSec = 1440L, completionThreshold = 0.80f))
    }
}
