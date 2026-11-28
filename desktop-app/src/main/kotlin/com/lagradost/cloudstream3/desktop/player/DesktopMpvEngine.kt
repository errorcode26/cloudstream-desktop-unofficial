package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
import com.lagradost.cloudstream3.desktop.player.ytdl.DesktopYtDlpBinary
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless controller for the native MPV core engine.
 * Decouples JNI pointer manipulation, background event loops, and hardware lifecycle from Compose.
 */
class DesktopMpvEngine(
    val scope: CoroutineScope,
    var playerState: PlayerState? = null,
    var onPlaybackReady: () -> Unit = {},
    var onPlaybackError: (String) -> Unit = {},
    var onFinished: () -> Unit = {},
    var onPositionChange: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    var onEventLoopReady: ((handle: Pointer) -> Unit)? = null,
    var isLive: Boolean = false,
) {
    @Volatile
    private var mpvHandle: Pointer? = null
    private val isDestroyed = AtomicBoolean(false)

    val nativeHandleValue: Long?
        get() = mpvHandle?.let { Pointer.nativeValue(it) }

    fun isHandleValid(): Boolean = mpvHandle != null && !isDestroyed.get()

    companion object {
        private const val LIVE_MARGIN_S = 6.0
    }

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _volume = MutableStateFlow(100.0)
    val volume = _volume.asStateFlow()

    private val _speed = MutableStateFlow(1.0)
    val speed = _speed.asStateFlow()

    private val trackManager = MpvTrackManager(playerState)
    private val eventLoop = MpvEventLoop(
        scope = scope,
        playerState = playerState,
        trackManager = trackManager,
        onPlaybackReady = { onPlaybackReady() },
        onPlaybackError = { err -> onPlaybackError(err) },
        onFinished = { onFinished() },
        onPositionChange = { pos, dur -> onPositionChange(pos, dur) },
        onEventLoopReady = onEventLoopReady,
        isLiveProvider = { isLive },
        isPausedFlow = _isPaused,
        volumeFlow = _volume,
        speedFlow = _speed,
        isDestroyed = isDestroyed,
    )

    fun updatePlayerState(state: PlayerState?) {
        playerState = state
        eventLoop.playerState = state
    }

    fun notifyLoadFile() {
        eventLoop.notifyLoadFile()
    }

    fun refreshTracksNow() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        trackManager.pollTracksAndChapters(handle)
    }

    /**
     * Initializes the native MPV instance and configures options before initialization.
     */
    fun createAndInitialize(
        canvasWid: Long,
        width: Int,
        height: Int,
        onPreInit: ((handle: Pointer, wid: Long, w: Int, h: Int) -> Unit)? = null,
        onPostInit: ((handle: Pointer) -> Unit)? = null,
    ): Pointer? {
        if (isDestroyed.get()) return null

        try {
            val lib = MpvLibrary.INSTANCE
            val handle = lib.mpv_create() ?: run {
                AppLogger.e("DesktopMpvEngine", "Failed to create MPV instance (mpv_create returned null)")
                onPlaybackError("Failed to initialize MPV Engine.")
                return null
            }
            mpvHandle = handle

            // Disable native UI since we draw our own Compose / WebView UI
            lib.mpv_set_option_string(handle, "osc", "no")
            lib.mpv_set_option_string(handle, "osd-level", "0")
            lib.mpv_set_option_string(handle, "osd-bar", "no")

            // Apply user preferences (including VO driver, scalers, tone-mapping, and shaders)
            PlayerConfig.applyMpvSettings(handle, lib)

            lib.mpv_set_option_string(handle, "wid", canvasWid.toString())
            lib.mpv_set_option_string(handle, "input-default-bindings", "no")
            lib.mpv_set_option_string(handle, "input-vo-keyboard", "no")
            lib.mpv_set_option_string(handle, "save-position-on-quit", "no")
            lib.mpv_set_option_string(handle, "resume-playback", "no")
            lib.mpv_set_option_string(handle, "keep-open", "yes")

            val ytdlBinary = DesktopYtDlpBinary()
            if (ytdlBinary.isInstalled()) {
                val binaryPath = ytdlBinary.getBinaryFile().absolutePath.replace("\\", "/")
                lib.mpv_set_option_string(handle, "ytdl", "yes")
                lib.mpv_set_option_string(handle, "script-opts", "ytdl_hook-ytdl_path=$binaryPath")
                AppLogger.i("DesktopMpvEngine", "Embedded yt-dlp hook enabled: $binaryPath")
            } else {
                lib.mpv_set_option_string(handle, "ytdl", "no")
            }
            lib.mpv_set_option_string(handle, "idle", "yes")

            onPreInit?.invoke(handle, canvasWid, width, height)

            AppLogger.i("DesktopMpvEngine", "Initializing embedded MPV Engine")
            val initCode = lib.mpv_initialize(handle)
            if (initCode < 0) {
                AppLogger.e("DesktopMpvEngine", "mpv_initialize failed with code: $initCode")
                destroy()
                onPlaybackError("MPV initialization failed (code $initCode).")
                return null
            }

            lib.mpv_request_log_messages(handle, "info")
            onPostInit?.invoke(handle)

            val actualVo = MpvLibrary.getPropertyString(handle, "current-vo")
            val actualHwdec = MpvLibrary.getPropertyString(handle, "hwdec-current")
            AppLogger.i("DesktopMpvEngine", "MPV Engine initialized successfully: vo=$actualVo, hwdec=$actualHwdec")

            eventLoop.start(handle)
            return handle
        } catch (e: Throwable) {
            AppLogger.e("DesktopMpvEngine", "Exception during MPV creation: ${e.message}", e)
            onPlaybackError("MPV Engine error: ${e.message}")
            return null
        }
    }

    fun executeCommand(command: String): Int {
        val handle = mpvHandle ?: return -1
        if (isDestroyed.get()) return -1
        return try {
            MpvLibrary.INSTANCE.mpv_command_string(handle, command)
        } catch (e: Throwable) {
            AppLogger.e("DesktopMpvEngine", "executeCommand error for '$command': ${e.message}")
            -1
        }
    }

    fun executeCommandArray(args: Array<String?>): Int {
        val handle = mpvHandle ?: return -1
        if (isDestroyed.get()) return -1
        return try {
            MpvLibrary.INSTANCE.mpv_command(handle, args)
        } catch (e: Throwable) {
            AppLogger.e("DesktopMpvEngine", "executeCommandArray error: ${e.message}")
            -1
        }
    }

    fun setPropertyString(property: String, value: String): Int {
        val handle = mpvHandle ?: return -1
        if (isDestroyed.get()) return -1
        return try {
            MpvLibrary.INSTANCE.mpv_set_property_string(handle, property, value)
        } catch (e: Throwable) {
            AppLogger.e("DesktopMpvEngine", "setPropertyString error for '$property': ${e.message}")
            -1
        }
    }

    fun getPropertyString(property: String): String? {
        val handle = mpvHandle ?: return null
        if (isDestroyed.get()) return null
        return try {
            MpvLibrary.getPropertyString(handle, property)
        } catch (e: Throwable) {
            null
        }
    }

    fun getPropertyDouble(property: String, def: Double = 0.0): Double {
        val handle = mpvHandle ?: return def
        if (isDestroyed.get()) return def
        return try {
            MpvLibrary.getPropertyDouble(handle, property, def)
        } catch (e: Throwable) {
            def
        }
    }

    fun configureYtDlpIfInstalled() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        val ytdlBinary = DesktopYtDlpBinary()
        if (ytdlBinary.isInstalled()) {
            val binaryPath = ytdlBinary.getBinaryFile().absolutePath.replace("\\", "/")
            setPropertyString("ytdl", "yes")
            setPropertyString("script-opts", "ytdl_hook-ytdl_path=$binaryPath")
            AppLogger.i("DesktopMpvEngine", "Dynamic yt-dlp hook applied: $binaryPath")
        }
    }

    fun loadFile(
        url: String,
        startPositionMs: Long = 0,
        headers: Map<String, String>? = null,
        subtitles: List<SubtitleFile> = emptyList(),
    ) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return

        scope.launch(Dispatchers.IO) {
            eventLoop.notifyLoadFile()

            headers?.forEach { (key, value) ->
                MpvLibrary.INSTANCE.mpv_set_property_string(handle, "http-header-fields", "$key: $value")
            }

            if (startPositionMs > 0) {
                MpvLibrary.INSTANCE.mpv_set_property_string(handle, "start", "${startPositionMs / 1000.0}")
            } else {
                MpvLibrary.INSTANCE.mpv_set_property_string(handle, "start", "none")
            }

            val command = "loadfile \"$url\" replace"
            MpvLibrary.INSTANCE.mpv_command_string(handle, command)

            val candidateLangs = LanguagePriorityHelper.getOrderedSubtitleLanguages()
            val subEnabled = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_ENABLED)
                ?: (DesktopDataStore.getKey<String>(PlayerConfig.PREF_PREFERRED_SUB_LANG) != "off")
            var hasSelectedOne = false

            subtitles.forEach { sub ->
                if (sub.url.isNotBlank()) {
                    val langTag = sub.langTag ?: sub.lang
                    val matchesPreferred = subEnabled && !hasSelectedOne && candidateLangs.any { prefLang ->
                        LanguageMatcher.matchesSubtitleTrack(lang = sub.langTag, title = sub.lang, name = sub.lang, prefLangCode = prefLang)
                    }
                    val flag = when {
                        !subEnabled -> "no"
                        matchesPreferred -> {
                            hasSelectedOne = true
                            "select"
                        }
                        else -> "auto"
                    }
                    val subCommand = "sub-add \"${sub.url}\" $flag \"${sub.lang}\" \"$langTag\""
                    MpvLibrary.INSTANCE.mpv_command_string(handle, subCommand)
                }
            }
        }
    }

    fun liveRange(): Pair<Double, Double>? {
        val handle = mpvHandle ?: return null
        val state = MpvLibrary.getPropertyString(handle, "demuxer-cache-state") ?: return null
        val ranges = Regex("""\{"start":(-?[\d.eE+-]+),"end":(-?[\d.eE+-]+)\}""").findAll(state.substringAfter("seekable-ranges", ""))
            .mapNotNull { m ->
                val s = m.groupValues[1].toDoubleOrNull()
                val e = m.groupValues[2].toDoubleOrNull()
                if (s != null && e != null && e > s) s to e else null
            }.toList()
        if (ranges.isEmpty()) return null
        val pos = eventLoop.lastPos
        return ranges.firstOrNull { pos >= it.first - 1.0 && pos <= it.second + 1.0 } ?: ranges.last()
    }

    private fun liveTarget(wanted: Double): Double {
        val (start, end) = liveRange() ?: return eventLoop.lastPos
        val low = start + 0.3
        val high = maxOf(low, if (end - LIVE_MARGIN_S > low) end - LIVE_MARGIN_S else (start + end) / 2.0)
        val target = wanted.coerceIn(low, high)
        if (target != wanted) {
            AppLogger.i("DesktopMpvEngine", "Live seek to ${"%.1f".format(wanted)} clamped to buffer reserve: ${"%.1f".format(target)} (range ${"%.1f".format(start)}..${"%.1f".format(end)})")
        }
        return target
    }

    fun seekTo(positionMs: Long) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            val target = if (isLive) liveTarget(positionMs / 1000.0) else positionMs / 1000.0
            val sec = String.format(Locale.ROOT, "%.3f", target)
            MpvLibrary.INSTANCE.mpv_command_string(handle, "seek $sec absolute")
        }
    }

    fun seekBy(offsetMs: Long) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            if (isLive) {
                val target = liveTarget(eventLoop.lastPos + offsetMs / 1000.0)
                val sec = String.format(Locale.ROOT, "%.3f", target)
                MpvLibrary.INSTANCE.mpv_command_string(handle, "seek $sec absolute")
            } else {
                val offsetSec = offsetMs / 1000.0
                MpvLibrary.INSTANCE.mpv_command_string(handle, "seek $offsetSec relative")
            }
        }
    }

    fun togglePause() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            val current = _isPaused.value
            MpvLibrary.INSTANCE.mpv_set_property_string(handle, "pause", if (current) "no" else "yes")
        }
    }

    fun pause() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            val res = MpvLibrary.INSTANCE.mpv_set_property_string(handle, "pause", "yes")
            if (res < 0) {
                MpvLibrary.INSTANCE.mpv_command_string(handle, "set pause yes")
            }
        }
    }

    fun play() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            val res = MpvLibrary.INSTANCE.mpv_set_property_string(handle, "pause", "no")
            if (res < 0) {
                MpvLibrary.INSTANCE.mpv_command_string(handle, "set pause no")
            }
        }
    }

    fun stop() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                MpvLibrary.INSTANCE.mpv_command_string(handle, "stop")
            } catch (e: Throwable) {
                AppLogger.w("DesktopMpvEngine", "Error stopping MPV: ${e.message}")
            }
        }
    }

    fun setSpeed(newSpeed: Double) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            MpvLibrary.INSTANCE.mpv_set_property_string(handle, "speed", newSpeed.toString())
        }
    }

    fun setVolume(newVolume: Double) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            MpvLibrary.INSTANCE.mpv_set_property_string(handle, "volume", newVolume.toString())
        }
    }

    fun toggleMute() {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            MpvLibrary.INSTANCE.mpv_command_string(handle, "cycle mute")
        }
    }

    fun setMute(isMuted: Boolean) {
        val handle = mpvHandle ?: return
        if (isDestroyed.get()) return
        scope.launch(Dispatchers.IO) {
            MpvLibrary.INSTANCE.mpv_set_property_string(handle, "mute", if (isMuted) "yes" else "no")
        }
    }

    fun destroy() {
        if (!isDestroyed.compareAndSet(false, true)) return

        val handle = mpvHandle
        mpvHandle = null
        eventLoop.stop()

        try {
            NativePlayerBridge.stopMpvSync()
        } catch (e: Throwable) {
            AppLogger.w("DesktopMpvEngine", "Error stopping WebView sync: ${e.message}")
        }

        if (handle != null) {
            Thread({
                try {
                    AppLogger.i("DesktopMpvEngine", "Terminating native MPV instance off EDT...")
                    MpvLibrary.INSTANCE.mpv_command_string(handle, "stop")
                    MpvLibrary.INSTANCE.mpv_terminate_destroy(handle)
                } catch (e: Throwable) {
                    AppLogger.w("DesktopMpvEngine", "Error during MPV destroy: ${e.message}")
                }
            }, "cs3-mpv-destroy").apply {
                isDaemon = true
                start()
            }
        }
    }
}
