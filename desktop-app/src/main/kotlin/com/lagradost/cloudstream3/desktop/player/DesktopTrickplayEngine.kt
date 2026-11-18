package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.logging.AppLogger
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DesktopTrickplayEngine(private val scope: CoroutineScope) {
    private var shadowHandle: Pointer? = null
    private var headlessHwnd: WinDef.HWND? = null
    private val isRunning = AtomicBoolean(false)
    private val isFileLoaded = AtomicBoolean(false)
    private val seekSignal = Channel<Unit>(Channel.CONFLATED)
    private val engineLock = Mutex()
    private val lruCache = object : LinkedHashMap<Long, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean {
            return size > 128
        }
    }

    fun start(url: String, headers: Map<String, String>? = null) {
        if (url.isBlank()) return
        stop()

        val lib = MpvLibrary.instanceOrNull
        if (lib == null) {
            AppLogger.d("DesktopTrickplayEngine", "Native MPV library not available, skipping trickplay shadow engine.")
            return
        }
        val handle = try {
            lib.mpv_create()
        } catch (e: Throwable) {
            AppLogger.e("DesktopTrickplayEngine", "Failed to create shadow MPV instance: ${e.message}")
            null
        } ?: return

        try {
            // Headless and low-overhead configuration with active video pipeline
            if (com.lagradost.common.platform.PlatformPaths.currentOS == com.lagradost.common.platform.PlatformPaths.OS.WINDOWS) {
                try {
                    val createdHwnd = User32.INSTANCE.CreateWindowEx(
                        0,
                        "STATIC",
                        "cs3_trickplay_headless",
                        WinUser.WS_POPUP,
                        -32000,
                        -32000,
                        160,
                        90,
                        null,
                        null,
                        null,
                        null,
                    )
                    headlessHwnd = createdHwnd
                    if (createdHwnd != null) {
                        val widVal = Pointer.nativeValue(createdHwnd.pointer)
                        lib.mpv_set_option_string(handle, "wid", widVal.toString())
                        lib.mpv_set_option_string(handle, "vo", "gpu")
                        lib.mpv_set_option_string(handle, "gpu-api", "d3d11")
                    } else {
                        lib.mpv_set_option_string(handle, "vo", "gpu")
                        lib.mpv_set_option_string(handle, "force-window", "no")
                    }
                } catch (e: Throwable) {
                    AppLogger.w("DesktopTrickplayEngine", "Headless HWND creation fallback: ${e.message}")
                    lib.mpv_set_option_string(handle, "vo", "gpu")
                    lib.mpv_set_option_string(handle, "force-window", "no")
                }
            } else {
                lib.mpv_set_option_string(handle, "vo", "gpu")
                lib.mpv_set_option_string(handle, "force-window", "no")
            }

            lib.mpv_set_option_string(handle, "ao", "null")
            lib.mpv_set_option_string(handle, "audio", "no")
            lib.mpv_set_option_string(handle, "sub", "no")
            lib.mpv_set_option_string(handle, "pause", "yes")
            lib.mpv_set_option_string(handle, "keep-open", "yes")
            lib.mpv_set_option_string(handle, "idle", "yes")
            lib.mpv_set_option_string(handle, "load-scripts", "no")
            lib.mpv_set_option_string(handle, "ytdl", "no")
            lib.mpv_set_option_string(handle, "cache", "yes")
            lib.mpv_set_option_string(handle, "cache-secs", "10")
            lib.mpv_set_option_string(handle, "demuxer-readahead-secs", "10")
            lib.mpv_set_option_string(handle, "cache-pause", "no")
            lib.mpv_set_option_string(handle, "demuxer-max-bytes", "33554432") // 32MiB
            lib.mpv_set_option_string(handle, "vd-lavc-threads", "1")
            lib.mpv_set_option_string(handle, "vf", "scale=240:-2")
            lib.mpv_set_option_string(handle, "screenshot-format", "jpg")
            lib.mpv_set_option_string(handle, "screenshot-jpeg-quality", "72")
            lib.mpv_set_option_string(handle, "hr-seek", "no")

            val initCode = lib.mpv_initialize(handle)
            if (initCode < 0) {
                AppLogger.e("DesktopTrickplayEngine", "Failed to initialize shadow MPV instance: code $initCode")
                lib.mpv_terminate_destroy(handle)
                return
            }

            headers?.forEach { (k, v) ->
                if (k.equals("user-agent", ignoreCase = true)) {
                    lib.mpv_set_property_string(handle, "user-agent", v)
                } else if (k.equals("referer", ignoreCase = true) || k.equals("referrer", ignoreCase = true)) {
                    lib.mpv_set_property_string(handle, "referrer", v)
                }
            }
            val headerList = headers?.entries?.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
            if (!headerList.isNullOrBlank()) {
                lib.mpv_set_property_string(handle, "http-header-fields", headerList)
            }

            shadowHandle = handle
            isRunning.set(true)
            isFileLoaded.set(false)

            // Start event listener loop
            scope.launch(Dispatchers.IO) {
                while (isRunning.get()) {
                    val eventPtr = lib.mpv_wait_event(handle, 0.05) ?: continue
                    val event = MpvLibrary.MpvEvent(eventPtr)
                    when (event.event_id) {
                        8 -> { // MPV_EVENT_FILE_LOADED
                            isFileLoaded.set(true)
                            AppLogger.i("DesktopTrickplayEngine", "Shadow MPV stream loaded successfully")
                        }
                        21 -> { // MPV_EVENT_PLAYBACK_RESTART
                            seekSignal.trySend(Unit)
                        }
                        7 -> { // MPV_EVENT_END_FILE
                            val endFilePtr = event.data
                            val reason = if (endFilePtr != null) MpvLibrary.MpvEventEndFile(endFilePtr).reason else -1
                            AppLogger.w("DesktopTrickplayEngine", "Shadow MPV end file event (reason=$reason)")
                        }
                    }
                }
            }

            val safeUrl = url.replace("\\", "/")
            val loadCmd = "loadfile \"$safeUrl\" replace"
            val loadRes = lib.mpv_command_string(handle, loadCmd)
            AppLogger.i("DesktopTrickplayEngine", "Shadow MPV loading stream (res=$loadRes): $safeUrl")
        } catch (e: Throwable) {
            AppLogger.e("DesktopTrickplayEngine", "Exception starting shadow MPV: ${e.message}", e)
            try {
                lib.mpv_terminate_destroy(handle)
            } catch (_: Throwable) {}
        }
    }

    suspend fun requestThumbnail(timeMs: Long): String? = withContext(Dispatchers.IO) {
        if (!isRunning.get()) return@withContext null
        val bucket = (timeMs / 2000L) * 2000L

        synchronized(lruCache) {
            lruCache[bucket]?.let { return@withContext it }
        }

        engineLock.withLock {
            if (!isRunning.get()) return@withContext null

            // Re-check cache under lock
            synchronized(lruCache) {
                lruCache[bucket]?.let { return@withContext it }
            }

            val handle = shadowHandle ?: return@withContext null
            val lib = MpvLibrary.instanceOrNull ?: return@withContext null

            // Await file load if initial probe is still settling
            if (!isFileLoaded.get()) {
                val waitStart = System.currentTimeMillis()
                while (!isFileLoaded.get() && isRunning.get() && System.currentTimeMillis() - waitStart < 3500) {
                    delay(50)
                }
                if (!isFileLoaded.get()) {
                    AppLogger.w("DesktopTrickplayEngine", "Shadow MPV file not yet loaded after timeout, skipping seek")
                    return@withContext null
                }
            }

            val timeSec = (bucket / 1000.0).toString()
            val tempFile = try {
                File.createTempFile("cs3_thumb_", ".jpg")
            } catch (e: Throwable) {
                AppLogger.e("DesktopTrickplayEngine", "Failed to create temp file: ${e.message}")
                return@withContext null
            }
            val tempPath = tempFile.absolutePath.replace("\\", "/")

            try {
                // Drain any stale restart events from initial load or prior seeks
                while (seekSignal.tryReceive().isSuccess) {}

                // Seek to keyframe
                val seekCmd = "seek $timeSec absolute+keyframes"
                lib.mpv_command_string(handle, seekCmd)

                // Await decoder restart with a 2000ms timeout
                withTimeoutOrNull(2000) {
                    seekSignal.receive()
                }

                if (!isRunning.get()) return@withContext null

                // Capture snapshot
                val snapCmd = "screenshot-to-file \"$tempPath\" video"
                val snapRes = lib.mpv_command_string(handle, snapCmd)
                if (snapRes < 0) {
                    AppLogger.w("DesktopTrickplayEngine", "screenshot-to-file returned error: $snapRes")
                }

                // Wait up to 800ms for the frame file to land on disk
                val fileStart = System.currentTimeMillis()
                while (isRunning.get() && (!tempFile.exists() || tempFile.length() == 0L) && (System.currentTimeMillis() - fileStart < 800)) {
                    delay(25)
                }

                if (tempFile.exists() && tempFile.length() > 0L) {
                    val bytes = tempFile.readBytes()
                    val base64 = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
                    synchronized(lruCache) {
                        lruCache[bucket] = base64
                    }
                    AppLogger.i("DesktopTrickplayEngine", "Captured real frame at ${timeSec}s (${bytes.size} bytes)")
                    return@withContext base64
                } else {
                    AppLogger.w("DesktopTrickplayEngine", "Frame file not created or empty for ${timeSec}s: $tempPath")
                }
            } catch (e: Throwable) {
                AppLogger.e("DesktopTrickplayEngine", "Error capturing thumbnail at ${timeSec}s: ${e.message}")
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            null
        }
    }

    fun stop() {
        isRunning.set(false)
        isFileLoaded.set(false)
        val handle = shadowHandle
        shadowHandle = null
        if (handle != null) {
            try {
                MpvLibrary.instanceOrNull?.mpv_terminate_destroy(handle)
                AppLogger.i("DesktopTrickplayEngine", "Shadow MPV instance terminated cleanly")
            } catch (e: Throwable) {
                AppLogger.e("DesktopTrickplayEngine", "Error terminating shadow MPV: ${e.message}")
            }
        }
        val h = headlessHwnd
        headlessHwnd = null
        if (h != null) {
            try {
                User32.INSTANCE.DestroyWindow(h)
            } catch (_: Throwable) {}
        }
        synchronized(lruCache) {
            lruCache.clear()
        }
    }
}
