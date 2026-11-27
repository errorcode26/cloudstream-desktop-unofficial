package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.common.logging.AppLogger
import com.sun.jna.Pointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background event processing loop for the native MPV engine.
 * Dispatches property changes, log diagnostics, track updates, and error events.
 */
internal class MpvEventLoop(
    private val scope: CoroutineScope,
    private val playerState: PlayerState?,
    private val trackManager: MpvTrackManager,
    private val onPlaybackReady: () -> Unit,
    private val onPlaybackError: (String) -> Unit,
    private val onFinished: () -> Unit,
    private val onPositionChange: (positionMs: Long, durationMs: Long) -> Unit,
    private val onEventLoopReady: ((handle: Pointer) -> Unit)?,
    private val isLiveProvider: () -> Boolean,
    private val isPausedFlow: MutableStateFlow<Boolean>,
    private val volumeFlow: MutableStateFlow<Double>,
    private val speedFlow: MutableStateFlow<Double>,
    private val isDestroyed: AtomicBoolean,
) {
    companion object {
        private const val LIVE_STALL_MS = 12_000L
        private const val MIN_GENUINE_EOF_DURATION_SEC = 60.0
    }

    private fun isGenuineEndOfFile(posSec: Double, durSec: Double): Boolean {
        if (durSec < MIN_GENUINE_EOF_DURATION_SEC) return false
        return posSec >= durSec * 0.85 || (durSec > 120.0 && posSec >= durSec - 10.0)
    }

    @Volatile
    var lastPos = 0.0

    @Volatile
    var lastDur = 0.0

    @Volatile
    var hasEverPlayed = false

    @Volatile
    var waitingForTimePosReset = false

    @Volatile
    private var bufferingSince = 0L
    private var lastLiveRecoveryMs = 0L

    private var eventJob: Job? = null

    fun notifyLoadFile() {
        waitingForTimePosReset = true
        hasEverPlayed = false
    }

    fun stop() {
        eventJob?.cancel()
        eventJob = null
    }

    fun start(handle: Pointer) {
        onEventLoopReady?.invoke(handle)

        eventJob = scope.launch(Dispatchers.IO) {
            val lib = MpvLibrary.INSTANCE
            lib.mpv_observe_property(handle, 1L, "time-pos", 5) // Double
            lib.mpv_observe_property(handle, 2L, "duration", 5) // Double
            lib.mpv_observe_property(handle, 3L, "pause", 3) // Flag
            lib.mpv_observe_property(handle, 4L, "eof-reached", 3) // Flag
            lib.mpv_observe_property(handle, 5L, "volume", 5) // Double
            lib.mpv_observe_property(handle, 6L, "speed", 5) // Double
            lib.mpv_observe_property(handle, 7L, "core-idle", 3) // Flag
            lib.mpv_observe_property(handle, 8L, "paused-for-cache", 3) // Flag
            lib.mpv_observe_property(handle, 9L, "mute", 3) // Flag
            lib.mpv_observe_property(handle, 10L, "seekable", 3) // Flag

            var lastPositionPollMs = 0L
            var lastTrackPollMs = 0L
            var lastUiPositionEmit = 0L
            var diagnosticLogged = false
            var playbackStartedAt = 0L
            var hasFiredFinished = false
            var lastStreamErrorReason: String? = null

            while (isActive && !isDestroyed.get()) {
                try {
                    val eventPtr = lib.mpv_wait_event(handle, 0.05)
                    if (eventPtr != null) {
                        val event = MpvLibrary.MpvEvent(eventPtr)
                        when (event.event_id) {
                            1 -> { // MPV_EVENT_SHUTDOWN
                                AppLogger.i("DesktopMpvEngine", "MPV Engine shutting down (MPV_EVENT_SHUTDOWN)")
                                break
                            }
                            6 -> { // MPV_EVENT_START_FILE
                                AppLogger.i("DesktopMpvEngine", "Starting new media stream (MPV_EVENT_START_FILE)")
                                lastStreamErrorReason = null
                                waitingForTimePosReset = false
                                hasEverPlayed = false
                                playbackStartedAt = 0L
                                diagnosticLogged = false
                                hasFiredFinished = false
                                trackManager.reset()
                                playerState?._isAudioOnlyStream?.value = false
                                playerState?._fileSize?.value = 0L
                            }
                            7 -> { // MPV_EVENT_END_FILE
                                val endFilePtr = event.data
                                if (endFilePtr != null) {
                                    val endFile = MpvLibrary.MpvEventEndFile(endFilePtr)
                                    AppLogger.i("DesktopMpvEngine", "Media ended (reason=${endFile.reason}, error=${endFile.error})")
                                    val isLive = isLiveProvider()
                                    if (endFile.reason == 4) { // MPV_END_FILE_REASON_ERROR
                                        val errDesc = lastStreamErrorReason ?: when (endFile.error) {
                                            -2 -> "Failed to load stream (Dead link or HTTP Error)"
                                            -3 -> "Stream format unsupported"
                                            -4 -> "No audio/video streams found"
                                            -8 -> "Connection timed out"
                                            else -> "Stream playback error (code ${endFile.error})"
                                        }
                                        AppLogger.e("DesktopMpvEngine", "Stream error: $errDesc")
                                        onPlaybackError(errDesc)
                                    } else if (endFile.reason == 0) { // MPV_END_FILE_REASON_EOF
                                        if (isLive) {
                                            try {
                                                lib.mpv_command_string(handle, "seek 100 absolute-percent")
                                            } catch (e: Exception) {
                                                AppLogger.e("DesktopMpvEngine", "Live recovery failed", e)
                                            }
                                        } else if (!hasEverPlayed || lastDur < MIN_GENUINE_EOF_DURATION_SEC || (lastPos < 1.0 && lastStreamErrorReason != null)) {
                                            val errDesc = lastStreamErrorReason ?: "Stream ended before minimum valid playback (dur=${lastDur.toInt()}s)"
                                            AppLogger.e("DesktopMpvEngine", "Stream ended before valid playback: $errDesc")
                                            onPlaybackError(errDesc)
                                        } else {
                                            val isPremature = !isGenuineEndOfFile(lastPos, lastDur)
                                            if (isPremature) {
                                                val errDesc = lastStreamErrorReason ?: "Stream ended prematurely at ${lastPos.toInt()}s"
                                                AppLogger.w("DesktopMpvEngine", "Premature EOF at ${lastPos}s of ${lastDur}s: $errDesc")
                                                onPlaybackError(errDesc)
                                            } else if (!hasFiredFinished) {
                                                hasFiredFinished = true
                                                AppLogger.i("DesktopMpvEngine", "Stream reached genuine EOF.")
                                                onFinished()
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> { // MPV_EVENT_LOG_MESSAGE
                                val logData = event.data
                                if (logData != null) {
                                    val logMsg = MpvLibrary.MpvEventLogMessage(logData)
                                    val text = logMsg.text?.trimEnd('\r', '\n') ?: ""
                                    val level = logMsg.level?.lowercase()
                                    if (level in listOf("error", "fatal", "warn")) {
                                        val lower = text.lowercase()
                                        when {
                                            lower.contains("403") || lower.contains("forbidden") -> lastStreamErrorReason = "HTTP 403 Forbidden"
                                            lower.contains("404") || lower.contains("not found") -> lastStreamErrorReason = "HTTP 404 Not Found"
                                            lower.contains("401") || lower.contains("unauthorized") -> lastStreamErrorReason = "HTTP 401 Unauthorized"
                                            lower.contains("429") || lower.contains("too many requests") -> lastStreamErrorReason = "HTTP 429 Rate Limited"
                                            lower.contains("502") || lower.contains("bad gateway") -> lastStreamErrorReason = "HTTP 502 Bad Gateway"
                                            lower.contains("503") || lower.contains("service unavailable") -> lastStreamErrorReason = "HTTP 503 Service Unavailable"
                                            lower.contains("500") || lower.contains("internal server error") -> lastStreamErrorReason = "HTTP 500 Server Error"
                                            lower.contains("timed out") || lower.contains("timeout") -> lastStreamErrorReason = "Connection Timed Out"
                                            lower.contains("certificate") || lower.contains("tls") || lower.contains("ssl") -> lastStreamErrorReason = "SSL/TLS Handshake Error"
                                            lower.contains("connection refused") -> lastStreamErrorReason = "Connection Refused"
                                            lower.contains("could not resolve") || lower.contains("name resolution") -> lastStreamErrorReason = "DNS / Host Resolution Failed"
                                            lower.contains("invalid data") || lower.contains("unsupported") -> lastStreamErrorReason = "Unsupported Stream Format"
                                            lower.contains("dxgi_error_device_removed") || lower.contains("0x887a0005") -> {
                                                lastStreamErrorReason = "GPU Decoder Device Reset (Corrupted/Encrypted Bitstream)"
                                                if (!hasEverPlayed) {
                                                    AppLogger.e("DesktopMpvEngine", "Hardware decoder crashed before playback started: $lastStreamErrorReason")
                                                    onPlaybackError(lastStreamErrorReason)
                                                }
                                            }
                                            lower.contains("cabac_init_idc overflow") || (lower.contains("slice type") && lower.contains("too large")) -> {
                                                lastStreamErrorReason = "Encrypted Stream Bitstream Error"
                                            }
                                        }
                                    }
                                }
                            }
                            8 -> { // MPV_EVENT_FILE_LOADED
                                waitingForTimePosReset = false
                                lastTrackPollMs = System.currentTimeMillis()
                                trackManager.pollTracksAndChapters(handle)
                                val seekableStr = MpvLibrary.getPropertyString(handle, "seekable")
                                if (seekableStr != null) {
                                    playerState?._isSeekable?.value = (seekableStr == "yes")
                                }
                            }
                            21 -> { // MPV_EVENT_PLAYBACK_RESTART
                                waitingForTimePosReset = false
                                lastTrackPollMs = System.currentTimeMillis()
                                trackManager.pollTracksAndChapters(handle)

                                val isPausedForCache = MpvLibrary.getPropertyString(handle, "paused-for-cache") == "yes"
                                val isPausedByUser = MpvLibrary.getPropertyString(handle, "pause") == "yes"
                                if (!hasEverPlayed && !isPausedForCache && (lastPos > 0.1 || isPausedByUser)) {
                                    hasEverPlayed = true
                                    playbackStartedAt = System.currentTimeMillis()
                                    playerState?._isBuffering?.value = false
                                    playerState?._isProbing?.value = false
                                    onPlaybackReady()
                                }
                            }
                            22 -> { // MPV_EVENT_PROPERTY_CHANGE
                                val propPtr = event.data
                                if (propPtr != null) {
                                    val prop = MpvLibrary.MpvEventProperty(propPtr)
                                    val name = prop.name
                                    val propData = prop.data
                                    if (name != null && prop.format != 0 && propData != null) {
                                        when (name) {
                                            "time-pos" -> {
                                                if (prop.format == 5) {
                                                    val newPos = propData.getDouble(0)
                                                    if (newPos >= 0.0) lastPos = newPos
                                                    if (lastPos > 0.1) waitingForTimePosReset = false
                                                    val isPausedForCache = MpvLibrary.getPropertyString(handle, "paused-for-cache") == "yes"
                                                    if (!hasEverPlayed && lastPos > 0.1 && !isPausedForCache) {
                                                        hasEverPlayed = true
                                                        playbackStartedAt = System.currentTimeMillis()
                                                        playerState?._isBuffering?.value = false
                                                        playerState?._isProbing?.value = false
                                                        onPlaybackReady()
                                                    }

                                                    if (!diagnosticLogged && playbackStartedAt > 0 &&
                                                        System.currentTimeMillis() - playbackStartedAt > 30_000
                                                    ) {
                                                        diagnosticLogged = true
                                                        val dVo = MpvLibrary.getPropertyString(handle, "current-vo")
                                                        val dHwdec = MpvLibrary.getPropertyString(handle, "hwdec-current")
                                                        val dCodec = MpvLibrary.getPropertyString(handle, "video-codec")
                                                        val dFps = MpvLibrary.getPropertyString(handle, "estimated-vf-fps")
                                                        val dW = MpvLibrary.getPropertyString(handle, "width")
                                                        val dH = MpvLibrary.getPropertyString(handle, "height")
                                                        AppLogger.i("DesktopMpvEngine", "MPV 30s Diag -> VO: $dVo, HWDEC: $dHwdec, Codec: $dCodec, FPS: $dFps, Res: ${dW}x$dH")
                                                    }

                                                    val now = System.currentTimeMillis()
                                                    if (now - lastUiPositionEmit >= 100) {
                                                        lastUiPositionEmit = now
                                                        val posMs = (lastPos * 1000).toLong()
                                                        playerState?.updatePositionFromPlayer(posMs)
                                                        onPositionChange(posMs, (lastDur * 1000).toLong())
                                                    }
                                                }
                                            }
                                            "duration" -> {
                                                if (prop.format == 5) {
                                                    lastDur = propData.getDouble(0)
                                                    playerState?._durationMs?.value = (lastDur * 1000).toLong()
                                                }
                                            }
                                            "pause" -> {
                                                if (prop.format == 3) {
                                                    val paused = propData.getInt(0) != 0
                                                    isPausedFlow.value = paused
                                                    playerState?._isPaused?.value = paused
                                                }
                                            }
                                            "paused-for-cache" -> {
                                                if (prop.format == 3) {
                                                    val isBuffering = propData.getInt(0) != 0
                                                    playerState?._isBuffering?.value = isBuffering
                                                    if (isBuffering) {
                                                        if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                                                    } else {
                                                        bufferingSince = 0L
                                                    }
                                                    if (!isBuffering && !hasEverPlayed && lastPos > 0.05) {
                                                        hasEverPlayed = true
                                                        playbackStartedAt = System.currentTimeMillis()
                                                        playerState?._isBuffering?.value = false
                                                        playerState?._isProbing?.value = false
                                                        onPlaybackReady()
                                                    }
                                                }
                                            }
                                            "mute" -> {
                                                if (prop.format == 3) playerState?._isMuted?.value = propData.getInt(0) != 0
                                            }
                                            "volume" -> {
                                                if (prop.format == 5) {
                                                    val vol = propData.getDouble(0).toFloat()
                                                    volumeFlow.value = vol.toDouble()
                                                    playerState?._volume?.value = vol
                                                }
                                            }
                                            "speed" -> {
                                                if (prop.format == 5) {
                                                    val sp = propData.getDouble(0).toFloat()
                                                    speedFlow.value = sp.toDouble()
                                                    playerState?._playbackSpeed?.value = sp
                                                }
                                            }
                                            "core-idle" -> {
                                                if (prop.format == 3 && !hasEverPlayed) {
                                                    playerState?._isProbing?.value = propData.getInt(0) != 0
                                                }
                                            }
                                            "seekable" -> {
                                                if (prop.format == 3) {
                                                    val isSeekable = propData.getInt(0) != 0
                                                    playerState?._isSeekable?.value = isSeekable
                                                }
                                            }
                                            "eof-reached" -> {
                                                if (prop.format == 3) {
                                                    val isEof = propData.getInt(0) != 0
                                                    val isNearEnd = isGenuineEndOfFile(lastPos, lastDur)
                                                    if (isEof && hasEverPlayed && !hasFiredFinished && !waitingForTimePosReset && isNearEnd) {
                                                        hasFiredFinished = true
                                                        AppLogger.i("DesktopMpvEngine", "Stream reached genuine EOF (property) at ${lastPos}s of ${lastDur}s.")
                                                        onFinished()
                                                    } else if (!isEof && lastDur > 0 && lastPos < lastDur - 5.0) {
                                                        hasFiredFinished = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 100ms fallback position poll
                    val now = System.currentTimeMillis()
                    if (now - lastPositionPollMs >= 100L) {
                        lastPositionPollMs = now
                        val currentDur = MpvLibrary.getPropertyDouble(handle, "duration", -1.0)
                        if (currentDur > 0.0) {
                            lastDur = currentDur
                            playerState?._durationMs?.value = (currentDur * 1000).toLong()
                        }
                        val pollPos = MpvLibrary.getPropertyDouble(handle, "time-pos", -1.0)
                        if (pollPos >= 0.0) {
                            lastPos = pollPos
                            if (lastPos > 0.1) waitingForTimePosReset = false
                            val isPausedForCache = MpvLibrary.getPropertyString(handle, "paused-for-cache") == "yes"
                            if (!hasEverPlayed && lastPos > 0.1 && !isPausedForCache) {
                                hasEverPlayed = true
                                playbackStartedAt = System.currentTimeMillis()
                                playerState?._isBuffering?.value = false
                                playerState?._isProbing?.value = false
                                onPlaybackReady()
                            }
                        }

                        if (now - lastUiPositionEmit >= 100) {
                            lastUiPositionEmit = now
                            if (!waitingForTimePosReset && hasEverPlayed) {
                                val posMs = (lastPos * 1000).toLong()
                                playerState?.updatePositionFromPlayer(posMs)
                                onPositionChange(posMs, (lastDur * 1000).toLong())
                            }
                            MpvLibrary.getPropertyString(handle, "paused-for-cache")?.let { s ->
                                val isBuf = (s == "yes")
                                playerState?._isBuffering?.value = isBuf
                                if (isBuf) {
                                    if (bufferingSince == 0L) bufferingSince = now
                                } else {
                                    bufferingSince = 0L
                                }
                            }
                            val isLive = isLiveProvider()
                            if (isLive && !isPausedFlow.value && hasEverPlayed && bufferingSince > 0L) {
                                val stalledMs = now - bufferingSince
                                if (stalledMs >= LIVE_STALL_MS && now - lastLiveRecoveryMs > 30_000L) {
                                    lastLiveRecoveryMs = now
                                    bufferingSince = 0L
                                    AppLogger.w("DesktopMpvEngine", "Live stream stalled in buffering for ${stalledMs}ms: recovering at live edge")
                                    try {
                                        MpvLibrary.INSTANCE.mpv_command_string(handle, "seek 100 absolute-percent")
                                    } catch (e: Exception) {
                                        AppLogger.e("DesktopMpvEngine", "Live watchdog recovery failed", e)
                                    }
                                }
                            }
                            val isEofPolled = MpvLibrary.getPropertyString(handle, "eof-reached") == "yes"
                            val isNearEnd = isGenuineEndOfFile(lastPos, lastDur)
                            if (isEofPolled && hasEverPlayed && !hasFiredFinished && !waitingForTimePosReset && isNearEnd) {
                                hasFiredFinished = true
                                AppLogger.i("DesktopMpvEngine", "Stream reached genuine EOF (polled) at ${lastPos}s of ${lastDur}s.")
                                onFinished()
                            } else if (!isEofPolled && lastDur > 0 && lastPos < lastDur - 5.0) {
                                hasFiredFinished = false
                            }
                        }
                    }

                    // 2000ms periodic track & video stats poll
                    if (now - lastTrackPollMs >= 2000L) {
                        lastTrackPollMs = now
                        trackManager.pollTracksAndChapters(handle)
                        playerState?.let { ps ->
                            trackManager.pollVideoStats(handle, ps)
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Throwable) {
                    AppLogger.e("DesktopMpvEngine", "Event loop error: ${e.message}")
                    delay(100)
                }
            }
        }
    }
}
