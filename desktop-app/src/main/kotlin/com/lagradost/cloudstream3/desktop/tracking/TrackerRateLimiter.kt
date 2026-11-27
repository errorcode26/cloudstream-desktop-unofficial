package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.delay
import kotlin.math.min

class TrackerRateLimitException(
    val providerName: String,
    val retryAfterSeconds: Long,
) : IllegalStateException(
    "$providerName rate limited (HTTP 429). Retry in ${retryAfterSeconds.coerceAtLeast(1L)}s.",
)

/**
 * Token-bucket pacing + HTTP 429 cooldown tracker for remote list APIs.
 * Prevents pagination/burst loops from exhausting per-second or per-minute quotas.
 */
class TrackerRateLimiter(
    private val providerName: String,
    private val minIntervalMs: Long,
    maxBurst: Int,
    private val defaultCooldownMs: Long = 60_000L,
    private val maxCooldownMs: Long = 180_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private val capacity: Double = maxBurst.coerceAtLeast(1).toDouble()
    private val stateLock = Any()

    private var availableTokens: Double = capacity
    private var lastRefillMs: Long = nowMs()
    private var consecutiveRateLimits: Int = 0
    private var cooldownUntilMs: Long = 0L
    private var lastCooldownLogMs: Long = 0L

    fun remainingCooldownSeconds(): Long = synchronized(stateLock) {
        val remaining = cooldownUntilMs - nowMs()
        if (remaining > 0L) (remaining + 999L) / 1000L else 0L
    }

    /**
     * Paces outbound calls through a token bucket. Throws [TrackerRateLimitException] immediately
     * when a server HTTP 429 cooldown window is active.
     */
    suspend fun acquireSlot() {
        val waitMs = synchronized(stateLock) {
            val now = nowMs()
            val cooldownLeft = cooldownUntilMs - now
            if (cooldownLeft > 0L) {
                throw TrackerRateLimitException(providerName, (cooldownLeft + 999L) / 1000L)
            }
            if (minIntervalMs <= 0L) return@synchronized 0L

            val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
            if (elapsed > 0L) {
                availableTokens = min(capacity, availableTokens + elapsed.toDouble() / minIntervalMs.toDouble())
                lastRefillMs = now
            }

            if (availableTokens >= 1.0) {
                availableTokens -= 1.0
                0L
            } else {
                val deficit = 1.0 - availableTokens
                availableTokens -= 1.0
                (deficit * minIntervalMs).toLong().coerceAtLeast(1L)
            }
        }
        if (waitMs > 0L) {
            sleepMs(waitMs)
        }
    }

    fun recordRateLimit(
        retryAfterHeader: String? = null,
        resetEpochSec: Long? = null,
    ): Long {
        val now = nowMs()
        val retryAfterSec = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        val resetWaitMs = resetEpochSec?.let { epoch -> (epoch * 1000L) - now }?.takeIf { it > 0L }

        val (clampedBackoffMs, shouldLog) = synchronized(stateLock) {
            consecutiveRateLimits = (consecutiveRateLimits + 1).coerceAtMost(6)
            val exponentialMs = defaultCooldownMs * (1L shl (consecutiveRateLimits - 1))
            val serverMs = when {
                retryAfterSec != null -> retryAfterSec * 1000L
                resetWaitMs != null -> resetWaitMs
                else -> exponentialMs
            }
            val clamped = serverMs.coerceIn(5_000L, maxCooldownMs)
            cooldownUntilMs = maxOf(cooldownUntilMs, now + clamped)
            availableTokens = 0.0
            lastRefillMs = now
            val logNow = now - lastCooldownLogMs >= 10_000L
            if (logNow) lastCooldownLogMs = now
            clamped to logNow
        }

        if (shouldLog) {
            AppLogger.w(TAG, "$providerName rate-limited (HTTP 429); backing off for ${(clampedBackoffMs / 1000L).coerceAtLeast(1L)}s")
        }
        return clampedBackoffMs
    }

    fun recordSuccess(
        rateLimitRemaining: Int? = null,
        resetEpochSec: Long? = null,
    ) {
        synchronized(stateLock) {
            consecutiveRateLimits = 0
            if (rateLimitRemaining != null && rateLimitRemaining <= 1 && resetEpochSec != null) {
                val now = nowMs()
                val untilResetMs = ((resetEpochSec * 1000L) - now).coerceIn(0L, 15_000L)
                if (untilResetMs > 0L) {
                    cooldownUntilMs = maxOf(cooldownUntilMs, now + untilResetMs)
                }
            }
        }
    }

    fun clear() {
        synchronized(stateLock) {
            cooldownUntilMs = 0L
            consecutiveRateLimits = 0
            availableTokens = capacity
            lastRefillMs = nowMs()
        }
    }

    companion object {
        private const val TAG = "TrackerRateLimiter"
    }
}
