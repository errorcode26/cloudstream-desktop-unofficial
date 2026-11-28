package com.lagradost.cloudstream3.desktop.player

import java.util.BitSet
import kotlin.math.abs

/**
 * Verifies genuine active playback progress per episode session so broken short clips,
 * truncated manifests, stream errors, and timeline scrubbing cannot falsely complete
 * local watch history or trigger remote tracker writes.
 */
class PlaybackCompletionGuard(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MIN_VALID_SERIES_DURATION_SEC = 120L
        const val MIN_VALID_MOVIE_DURATION_SEC = 900L
        const val MIN_HISTORY_PERSIST_DURATION_SEC = 60L
        const val MIN_SHORT_METADATA_DURATION_SEC = 30L
        private const val MIN_TRUNCATED_RATIO = 0.45f
        private const val MIN_UNIQUE_COVERAGE_RATIO = 0.45f
        private const val MIN_PRIOR_RESUME_RATIO = 0.45f
        private const val MAX_PLAYBACK_BUCKET_SEC = 86_400L
        private const val MAX_TICK_STEP_SEC = 12L
        private const val DEFAULT_MIN_AUTO_TRACK_ACTIVE_SEC = 10L
    }

    private var isMovie: Boolean = false
    private var expectedDurationSec: Long = 0L
    private var priorPositionSec: Long = 0L
    private var priorDurationSec: Long = 0L
    private var sessionEndedWithError: Boolean = false

    private val watchedBuckets = BitSet()
    private var observedDurationSec: Long = 0L
    private var lastTrackedPosSec: Long = -1L
    private var lastTickWallMs: Long = 0L
    private var activeSessionWatchedSec: Long = 0L

    fun reset(
        isMovie: Boolean = false,
        expectedDurationMinutes: Int? = null,
        priorPositionSec: Long = 0L,
        priorDurationSec: Long = 0L,
    ) {
        this.isMovie = isMovie
        this.expectedDurationSec = (expectedDurationMinutes?.takeIf { it > 0 } ?: 0) * 60L
        this.priorPositionSec = priorPositionSec.coerceAtLeast(0L)
        this.priorDurationSec = priorDurationSec.coerceAtLeast(0L)
        this.sessionEndedWithError = false

        watchedBuckets.clear()
        observedDurationSec = 0L
        lastTrackedPosSec = -1L
        lastTickWallMs = 0L
        activeSessionWatchedSec = 0L
    }

    fun markError() {
        sessionEndedWithError = true
        lastTrackedPosSec = -1L
        lastTickWallMs = 0L
    }

    fun updateExpectedDurationMinutes(minutes: Int?) {
        val sec = (minutes?.takeIf { it > 0 } ?: 0) * 60L
        if (sec > 0L) {
            expectedDurationSec = sec
        }
    }

    /**
     * Records a telemetry tick from the active player. Increments watched coverage
     * only during continuous forward playback without pausing, buffering, or seeking.
     */
    fun recordTick(
        posSec: Long,
        durSec: Long,
        isPaused: Boolean,
        isBuffering: Boolean,
        isPlayingPhase: Boolean,
    ) {
        if (durSec <= 0L || posSec < 0L) return
        if (durSec > observedDurationSec) {
            observedDurationSec = durSec
        }

        val nowMs = clockMs()
        if (!isPlayingPhase || isPaused || isBuffering) {
            lastTrackedPosSec = posSec
            lastTickWallMs = nowMs
            return
        }

        // First active sample sets anchor without crediting resumed prior offsets.
        if (lastTrackedPosSec < 0L) {
            lastTrackedPosSec = posSec
            lastTickWallMs = nowMs
            return
        }

        val deltaMediaSec = posSec - lastTrackedPosSec
        if (deltaMediaSec <= 0L) {
            if (deltaMediaSec < 0L) {
                lastTrackedPosSec = posSec
                lastTickWallMs = nowMs
            }
            return
        }

        val elapsedWallSec = ((nowMs - lastTickWallMs) / 1000.0).coerceAtLeast(0.0)
        // Reject seeks/scrub jumps that advance faster than max playback speed (<= 3.5x + jitter slack).
        val maxCredibleAdvance = (elapsedWallSec * 3.5 + 2.0)
        if (deltaMediaSec <= MAX_TICK_STEP_SEC && deltaMediaSec.toDouble() <= maxCredibleAdvance) {
            val startBucket = (lastTrackedPosSec + 1L).coerceIn(0L, MAX_PLAYBACK_BUCKET_SEC)
            val endBucket = posSec.coerceIn(0L, MAX_PLAYBACK_BUCKET_SEC)
            for (sec in startBucket..endBucket) {
                watchedBuckets.set(sec.toInt())
            }
            activeSessionWatchedSec += deltaMediaSec
            sessionEndedWithError = false
        }

        lastTrackedPosSec = posSec
        lastTickWallMs = nowMs
    }

    fun isDurationValidForPersistence(durSec: Long): Boolean {
        if (durSec <= 0L) return false
        if (expectedDurationSec in 1L until MIN_HISTORY_PERSIST_DURATION_SEC) {
            return durSec >= (expectedDurationSec * 0.50f).toLong().coerceAtLeast(10L)
        }
        return durSec >= MIN_HISTORY_PERSIST_DURATION_SEC
    }

    fun isDurationValidForCompletion(durSec: Long): Boolean {
        if (durSec <= 0L) return false
        if (expectedDurationSec > 0L) {
            val minFromExpected = (expectedDurationSec * MIN_TRUNCATED_RATIO).toLong()
                .coerceAtLeast(MIN_SHORT_METADATA_DURATION_SEC)
            return durSec >= minFromExpected
        }
        val minHardFloor = if (isMovie) MIN_VALID_MOVIE_DURATION_SEC else MIN_VALID_SERIES_DURATION_SEC
        return durSec >= minHardFloor
    }

    fun hasMinimumActivePlayback(durSec: Long): Boolean {
        if (activeSessionWatchedSec > 0L) return true
        return priorDurationSec > 0L && durSec > 0L && abs(priorDurationSec - durSec) <= 15L && priorPositionSec > 0L
    }

    fun canMarkCompleted(
        posSec: Long,
        durSec: Long,
        completionThreshold: Float = 0.85f,
    ): Boolean {
        if (sessionEndedWithError) return false
        if (!isDurationValidForCompletion(durSec)) return false
        if (posSec < 0L || durSec <= 0L) return false

        val pct = posSec.toFloat() / durSec.toFloat()
        if (pct < completionThreshold) return false

        val uniqueWatchedSec = watchedBuckets.cardinality().toLong()
        val uniqueCoverage = uniqueWatchedSec.toFloat() / durSec.toFloat()
        val fullWatchFloor = minOf(120L, (durSec * 0.40f).toLong().coerceAtLeast(10L))
        if (uniqueCoverage >= MIN_UNIQUE_COVERAGE_RATIO && activeSessionWatchedSec >= fullWatchFloor) {
            return true
        }

        // Valid prior progress on the same media credits already-watched offset alongside current session playback.
        val hasSameMediaPrior = priorDurationSec >= MIN_HISTORY_PERSIST_DURATION_SEC &&
            priorPositionSec > 0L &&
            abs(priorDurationSec - durSec) <= 60L
        if (!hasSameMediaPrior || posSec <= priorPositionSec) {
            return false
        }

        val cappedPriorSec = priorPositionSec.coerceAtMost(durSec)
        var overlapWithPriorSec = 0L
        var bucketIdx = watchedBuckets.nextSetBit(1)
        while (bucketIdx >= 0 && bucketIdx <= cappedPriorSec) {
            overlapWithPriorSec++
            bucketIdx = watchedBuckets.nextSetBit(bucketIdx + 1)
        }
        val combinedCoveredSec = cappedPriorSec + (uniqueWatchedSec - overlapWithPriorSec).coerceAtLeast(0L)
        val combinedCoverage = combinedCoveredSec.toFloat() / durSec.toFloat()
        val priorRatio = priorPositionSec.toFloat() / priorDurationSec.toFloat()
        val resumeWatchFloor = minOf(20L, (durSec * 0.05f).toLong().coerceAtLeast(10L))

        return activeSessionWatchedSec >= resumeWatchFloor &&
            (combinedCoverage >= MIN_UNIQUE_COVERAGE_RATIO || priorRatio >= MIN_PRIOR_RESUME_RATIO)
    }

    /**
     * Evaluates completion eligibility for remote tracker auto-sync. Requires a valid stream duration,
     * no fatal playback error, reaching [completionThreshold], and minimum active playback so
     * skipping OP/ED or mid-episode scenes still syncs while instant accidental clicks do not.
     */
    fun canAutoTrackCompleted(
        posSec: Long,
        durSec: Long,
        completionThreshold: Float = 0.80f,
        minActiveWatchSec: Long = DEFAULT_MIN_AUTO_TRACK_ACTIVE_SEC,
    ): Boolean {
        if (sessionEndedWithError) return false
        if (!isDurationValidForCompletion(durSec)) return false
        if (posSec < 0L || durSec <= 0L) return false

        val pct = posSec.toFloat() / durSec.toFloat()
        if (pct < completionThreshold) return false

        val activeFloor = minOf(minActiveWatchSec, (durSec * 0.15f).toLong().coerceAtLeast(5L))
        if (activeSessionWatchedSec >= activeFloor) return true

        val wasSubstantialResume = priorDurationSec >= MIN_HISTORY_PERSIST_DURATION_SEC &&
            (priorPositionSec.toFloat() / priorDurationSec.toFloat()) >= MIN_PRIOR_RESUME_RATIO
        return wasSubstantialResume && activeSessionWatchedSec >= 5L && posSec > priorPositionSec
    }

    /**
     * Prevents unverified seek-ahead positions (>= completionThreshold without watch coverage)
     * from masquerading as near-complete progress in local Continue Watching.
     */
    fun clampPersistablePosition(
        posSec: Long,
        durSec: Long,
        completionThreshold: Float = 0.85f,
    ): Long {
        if (durSec <= 0L || posSec <= 0L) return 0L
        if (canMarkCompleted(posSec, durSec, completionThreshold)) {
            return durSec
        }
        val safePartialCap = ((durSec * (completionThreshold - 0.05f)).toLong()).coerceAtLeast(0L)
        return minOf(posSec, safePartialCap)
    }

    fun activeWatchedSeconds(): Long = activeSessionWatchedSec
    fun uniqueWatchedSeconds(): Long = watchedBuckets.cardinality().toLong()
}
