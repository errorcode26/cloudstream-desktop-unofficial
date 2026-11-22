package com.lagradost.cloudstream3.desktop.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.desktop.player.ipc.PlayerInboundEvent
import com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Window
import java.io.File

class PlayerInboundDispatcher(
    private val scope: CoroutineScope,
    private val window: Window?,
    private val playerState: PlayerState?,
    private val persistentSubtitles: MutableList<String>,
    private val resolvedImdbId: String?,
    private val getGlobalUiScale: () -> Float,
    private val getAutoFetchedSubtitleTracks: () -> List<LazyTrackPayload>,
    private val getTrickplayEngine: () -> DesktopTrickplayEngine?,
    private val pushMetadataToWebView: () -> Unit,
    private val pushSyncStateToWebView: () -> Unit,
    private val onUiReady: () -> Unit,
    private val onFullscreenToggle: (() -> Unit)?,
    private val onCloseRequest: () -> Unit,
    private val onRetryPlayback: (() -> Unit)?,
    private val onLinkChange: ((String) -> Unit)?,
    private val onEpisodeChange: ((String) -> Unit)?,
    private val onNextEpisode: (() -> Unit)?,
    private val onCancelCountdown: (() -> Unit)?,
    private val onReplayEpisode: (() -> Unit)?,
    private val onUserInteraction: (() -> Unit)?,
    private val onSetSleepTimer: ((String) -> Unit)?,
    private val onContinueFromInactivity: (() -> Unit)?,
    private val onSkipScraping: (() -> Unit)?,
) {
    private val playerObjectMapper = jacksonObjectMapper()

    fun dispatch(event: PlayerInboundEvent) {
        when (event) {
            is PlayerInboundEvent.ClientError -> {
                AppLogger.e("Player:Web", event.message)
            }
            is PlayerInboundEvent.ClientLog -> {
                AppLogger.i("Player:JS", event.message)
            }
            is PlayerInboundEvent.UiReady -> {
                onUiReady()
            }
            is PlayerInboundEvent.SelectShader -> {
                playerState?.setShader(event.name)
            }
            is PlayerInboundEvent.SearchSubtitles -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val effectiveImdbId = event.imdbId?.takeIf { it.isNotBlank() } ?: resolvedImdbId
                        AppLogger.i("Player:Web", "Searching online subtitles: query='${event.query}', lang='${event.lang}', season=${event.season}, episode=${event.episode}, imdbId=$effectiveImdbId")
                        val allResults = SubtitleExtractionService.searchSubtitles(event.query, event.lang, event.season, event.episode, effectiveImdbId)
                        AppLogger.i("Player:Web", "Subtitle search complete: returned ${allResults.size} tracks")

                        val json = playerObjectMapper.writeValueAsString(
                            mapOf("type" to "subtitle_search_results", "results" to allResults),
                        )
                        NativePlayerBridge.postMessage(json)
                    } catch (e: Exception) {
                        AppLogger.e("Player:Web", "searchSubtitles error: ${e.message}", e)
                    }
                }
            }
            is PlayerInboundEvent.DownloadSubtitle -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val idPrefix = event.idPrefix.takeIf { it.isNotBlank() }
                        val data = event.data.takeIf { it.isNotBlank() }
                        val name = event.name
                        val lang = event.lang
                        val source = event.source

                        AppLogger.i("Player:Web", "Received downloadSubtitle event: idPrefix=$idPrefix, name=$name, lang=$lang, source=$source, dataLen=${data?.length}")

                        if (idPrefix == null || data == null) {
                            AppLogger.w("Player:Web", "downloadSubtitle aborted: idPrefix=$idPrefix, data=$data")
                            return@launch
                        }

                        AppLogger.i("Player:Web", "Downloading subtitle '$name' from provider prefix '$idPrefix'...")
                        val safeUrl = SubtitleExtractionService.downloadAndExtractSubtitle(
                            idPrefix = idPrefix,
                            data = data,
                            name = name,
                            lang = lang,
                            source = source,
                        )

                        if (safeUrl != null) {
                            if (!persistentSubtitles.contains(safeUrl)) {
                                persistentSubtitles.add(safeUrl)
                            }
                            val cleanPath = safeUrl.replace("\\", "/")
                            val cleanName = name.replace("\"", "").replace("\n", "").trim()
                            val cleanLang = lang.replace("\"", "").trim()

                            AppLogger.i("Player:Web", "Applying subtitle to MPV: path='$cleanPath', name='$cleanName', lang='$cleanLang'")
                            playerState?.executeCommand("sub-add \"$cleanPath\" select \"$cleanName\" \"$cleanLang\"")
                            playerState?.setMpvProperty("sub-visibility", "yes")

                            delay(250)
                            pushMetadataToWebView()

                            val toastJson = playerObjectMapper.writeValueAsString(
                                mapOf("type" to "show_toast", "message" to "Loaded subtitle: $cleanName"),
                            )
                            NativePlayerBridge.postMessage(toastJson)
                        } else {
                            AppLogger.w("Player:Web", "Failed to extract/download subtitle for '$name'")
                            val errToastJson = playerObjectMapper.writeValueAsString(
                                mapOf("type" to "show_toast", "message" to "Failed to download subtitle: $name"),
                            )
                            NativePlayerBridge.postMessage(errToastJson)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("Player:Web", "downloadSubtitle error: ${e.message}", e)
                    }
                }
            }
            is PlayerInboundEvent.SetSubsourceApiKey -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val key = event.apiKey.trim()
                        DesktopDataStore.setKey(
                            PlayerConfig.PREF_SUBSOURCE_API_KEY,
                            key,
                        )
                        AppLogger.i("Player:Web", "Updated SubSource API key (len=${key.length})")
                        val msg = if (key.isNotBlank()) "SubSource API key saved" else "SubSource API key cleared"
                        val toastJson = playerObjectMapper.writeValueAsString(
                            mapOf("type" to "show_toast", "message" to msg),
                        )
                        NativePlayerBridge.postMessage(toastJson)
                    } catch (e: Exception) {
                        AppLogger.e("Player:Web", "Failed to save SubSource API key: ${e.message}", e)
                    }
                }
            }
            is PlayerInboundEvent.OpenLocalSubtitlePicker -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val chosenFile = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.open(
                            title = "Select Subtitle File",
                            category = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.Category.SUBTITLES,
                            allowedExtensions = listOf("srt", "vtt", "ass", "ssa", "sub"),
                        )
                        if (chosenFile != null && chosenFile.exists()) {
                            val cleanPath = chosenFile.absolutePath.replace("\\", "/")
                            val cleanName = chosenFile.nameWithoutExtension.replace("\"", "").replace("\n", "").trim()
                            if (!persistentSubtitles.contains(cleanPath)) {
                                persistentSubtitles.add(cleanPath)
                            }
                            AppLogger.i("Player:Web", "Applying local subtitle to MPV: '$cleanPath' ($cleanName)")
                            playerState?.executeCommand("sub-add \"$cleanPath\" select \"$cleanName\"")
                            playerState?.setMpvProperty("sub-visibility", "yes")

                            delay(250)
                            pushMetadataToWebView()

                            val toastJson = playerObjectMapper.writeValueAsString(
                                mapOf("type" to "show_toast", "message" to "Loaded subtitle: ${chosenFile.name}"),
                            )
                            NativePlayerBridge.postMessage(toastJson)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("Player:Web", "openLocalSubtitlePicker error: ${e.message}", e)
                    }
                }
            }
            is PlayerInboundEvent.LoadLocalSubtitleFile -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val file = File(event.path)
                        if (file.exists() && file.length() > 0L) {
                            val cleanPath = file.absolutePath.replace("\\", "/")
                            val cleanName = file.nameWithoutExtension.replace("\"", "").replace("\n", "").trim()
                            if (!persistentSubtitles.contains(cleanPath)) {
                                persistentSubtitles.add(cleanPath)
                            }
                            AppLogger.i("Player:Web", "Applying dropped subtitle to MPV: '$cleanPath' ($cleanName)")
                            playerState?.executeCommand("sub-add \"$cleanPath\" select \"$cleanName\"")
                            playerState?.setMpvProperty("sub-visibility", "yes")

                            delay(250)
                            pushMetadataToWebView()

                            val toastJson = playerObjectMapper.writeValueAsString(
                                mapOf("type" to "show_toast", "message" to "Loaded subtitle: ${file.name}"),
                            )
                            NativePlayerBridge.postMessage(toastJson)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("Player:Web", "loadLocalSubtitleFile error: ${e.message}", e)
                    }
                }
            }
            is PlayerInboundEvent.SkipInterval -> {
                playerState?.skipCurrentInterval()
            }
            is PlayerInboundEvent.SeekTo -> {
                playerState?.notifySeekTo(event.positionMs.toLong())
            }
            is PlayerInboundEvent.SeekBy -> {
                playerState?.notifySeekBy(event.deltaMs.toLong())
            }
            is PlayerInboundEvent.SeekLive -> {
                playerState?.seekLive()
            }
            is PlayerInboundEvent.TogglePlay -> {
                val isPaused = playerState?.isPaused?.value ?: false
                AppLogger.i("BaseMpvPlayer: Received togglePlay event. isPaused=$isPaused")
                if (isPaused) {
                    playerState.play()
                } else {
                    playerState?.pause()
                }
            }
            is PlayerInboundEvent.Play -> {
                AppLogger.i("BaseMpvPlayer: Received play event")
                playerState?.play()
            }
            is PlayerInboundEvent.Pause -> {
                AppLogger.i("BaseMpvPlayer: Received pause event")
                playerState?.pause()
            }
            is PlayerInboundEvent.ToggleMute -> {
                val currentMuted = playerState?.isMuted?.value ?: false
                val nextMuted = event.forcedState ?: !currentMuted
                playerState?.setMute(nextMuted)
                pushMetadataToWebView()
            }
            is PlayerInboundEvent.SetVolume -> {
                playerState?.setVolume(event.volume.toFloat())
            }
            is PlayerInboundEvent.SetSpeed -> {
                playerState?.setSpeed(event.speed.toFloat())
            }
            is PlayerInboundEvent.SetSubDelay -> {
                playerState?.executeCommand("add sub-delay ${event.delaySeconds}")
            }
            is PlayerInboundEvent.CycleSubtitles -> {
                playerState?.cycleSubtitles()
            }
            is PlayerInboundEvent.ToggleSubVisibility -> {
                playerState?.toggleSubtitleVisibility()
            }
            is PlayerInboundEvent.ToggleLoop -> {
                val loopVal = if (event.loop) "inf" else "no"
                playerState?.setMpvProperty("loop-file", loopVal)
            }
            is PlayerInboundEvent.ToggleAudioMode -> {
                playerState?.toggleAudioMode(event.enabled)
                pushSyncStateToWebView()
                NativePlayerBridge.syncUiScale(getGlobalUiScale())
            }
            is PlayerInboundEvent.SetPauseInfoMode -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_PAUSE_INFO_MODE, event.mode)
                    pushMetadataToWebView()
                }
            }
            is PlayerInboundEvent.SetPauseShowCast -> {
                scope.launch(Dispatchers.IO) {
                    val currentVal = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_PAUSE_SHOW_CAST) ?: true
                    val nextVal = event.forcedState ?: !currentVal
                    DesktopDataStore.setKey(PlayerConfig.PREF_PAUSE_SHOW_CAST, nextVal)
                    pushMetadataToWebView()
                }
            }
            is PlayerInboundEvent.Screenshot -> {
                playerState?.executeCommand("screenshot video")
                val dirName = com.lagradost.common.platform.PlatformPaths.screenshotsDir.name
                playerState?.showToast("Screenshot saved to $dirName")
            }
            is PlayerInboundEvent.TogglePip -> {
                val nextPip = !com.lagradost.cloudstream3.desktop.ui.PipState.isPipMode.value
                com.lagradost.cloudstream3.desktop.ui.PipState.setPipMode(nextPip)
                NativePlayerBridge.executeScript("if(window.setPipUi) window.setPipUi($nextPip);")
            }
            is PlayerInboundEvent.StartWindowDrag -> {
                if (window != null) {
                    val hwnd = com.sun.jna.Native.getComponentID(window)
                    val hWin = com.sun.jna.platform.win32.WinDef.HWND(Pointer(hwnd))
                    com.lagradost.cloudstream3.desktop.init.ExtUser32.INSTANCE.ReleaseCapture()
                    com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(
                        hWin,
                        0x0112,
                        com.sun.jna.platform.win32.WinDef.WPARAM(0xF012),
                        com.sun.jna.platform.win32.WinDef.LPARAM(0),
                    )
                }
            }
            is PlayerInboundEvent.StartWindowResize -> {
                if (window != null) {
                    val hwnd = com.sun.jna.Native.getComponentID(window)
                    val hWin = com.sun.jna.platform.win32.WinDef.HWND(Pointer(hwnd))
                    com.lagradost.cloudstream3.desktop.init.ExtUser32.INSTANCE.ReleaseCapture()

                    val hitTest = when (event.direction) {
                        "left" -> 1
                        "right" -> 2
                        "top" -> 3
                        "top-left" -> 4
                        "top-right" -> 5
                        "bottom" -> 6
                        "bottom-left" -> 7
                        "bottom-right" -> 8
                        else -> 8
                    }

                    com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(
                        hWin,
                        0x0112,
                        com.sun.jna.platform.win32.WinDef.WPARAM((0xF000 + hitTest).toLong()),
                        com.sun.jna.platform.win32.WinDef.LPARAM(0),
                    )
                }
            }
            is PlayerInboundEvent.ToggleFullscreen -> {
                scope.launch(Dispatchers.Main) {
                    onFullscreenToggle?.invoke()
                }
            }
            is PlayerInboundEvent.FocusWebView -> {
                NativePlayerBridge.focusWebView()
            }
            is PlayerInboundEvent.ExitPlayer -> {
                scope.launch(Dispatchers.Main) {
                    onCloseRequest()
                }
            }
            is PlayerInboundEvent.RetryPlayback -> {
                scope.launch(Dispatchers.Main) {
                    onRetryPlayback?.invoke()
                }
            }
            is PlayerInboundEvent.CopyDiagnostics -> {
                scope.launch(Dispatchers.Main) {
                    com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler.copyToClipboard(event.text)
                    val toastJson = playerObjectMapper.writeValueAsString(
                        mapOf("type" to "show_toast", "message" to "Diagnostics copied to clipboard"),
                    )
                    NativePlayerBridge.postMessage(toastJson)
                }
            }
            is PlayerInboundEvent.ChangeLink -> {
                scope.launch(Dispatchers.Main) {
                    if (event.linkUrl.isNotEmpty()) onLinkChange?.invoke(event.linkUrl)
                }
            }
            is PlayerInboundEvent.LoadEpisode -> {
                scope.launch(Dispatchers.Main) {
                    onEpisodeChange?.invoke(event.episodeId)
                }
            }
            is PlayerInboundEvent.SetAudioTrack -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.setAudioTrack(event.id)
                }
            }
            is PlayerInboundEvent.SetVideoTrack -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.setVideoTrack(event.id)
                }
            }
            is PlayerInboundEvent.NextChapter -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.nextChapter()
                }
            }
            is PlayerInboundEvent.PreviousChapter -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.previousChapter()
                }
            }
            is PlayerInboundEvent.SeekToChapter -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.seekToChapter(event.index)
                }
            }
            is PlayerInboundEvent.LazyAudioTrack -> {
                val track = com.lagradost.player.impl.proxy.LocalStreamProxyState.lazyAudioTracks.value.find { it.url == event.url }
                if (track != null) {
                    scope.launch(Dispatchers.IO) {
                        playerState?.loadLazyAudioTrack(
                            PlayerState.LazyTrack(track.url, track.name, track.language),
                        )
                    }
                }
            }
            is PlayerInboundEvent.LazySubtitleTrack -> {
                val proxyTrack = com.lagradost.player.impl.proxy.LocalStreamProxyState.lazySubtitleTracks.value.find { it.url == event.url }
                val lazyTrack = if (proxyTrack != null) {
                    PlayerState.LazyTrack(proxyTrack.url, proxyTrack.name, proxyTrack.language)
                } else {
                    getAutoFetchedSubtitleTracks().find { it.url == event.url }?.let {
                        PlayerState.LazyTrack(it.url, it.name, it.language ?: "en")
                    }
                }
                if (lazyTrack != null) {
                    scope.launch(Dispatchers.IO) {
                        playerState?.loadLazySubtitleTrack(lazyTrack)
                    }
                }
            }
            is PlayerInboundEvent.LazyVideoTrack -> {
                val track = com.lagradost.player.impl.proxy.LocalStreamProxyState.lazyVideoTracks.value.find { it.url == event.url }
                if (track != null) {
                    scope.launch(Dispatchers.IO) {
                        playerState?.loadLazyVideoTrack(
                            PlayerState.LazyTrack(track.url, track.name, track.language, track.bitrate),
                        )
                    }
                }
            }
            is PlayerInboundEvent.SetSubtitleTrack -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.setSubtitleTrack(event.id)
                }
            }
            is PlayerInboundEvent.SetSubtitleFont -> {
                scope.launch(Dispatchers.IO) {
                    val font = event.fontName?.takeIf { it.isNotBlank() } ?: "Inter"
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_FONT, font)
                    playerState?.setSubtitleFont(font)
                }
            }
            is PlayerInboundEvent.SetSubtitleOverrideEnabled -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_ENABLE_SUB_OVERRIDE, event.enabled)
                    playerState?.setSubtitleOverrideEnabled(event.enabled)
                }
            }
            is PlayerInboundEvent.SetSubFilterCaptions -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_REMOVE_CAPTIONS, event.enabled)
                    playerState?.setSubFilterSdh(event.enabled)
                }
            }
            is PlayerInboundEvent.SetSubFilterBloat -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_REMOVE_BLOAT, event.enabled)
                    playerState?.setSubFilterBloat(event.enabled)
                }
            }
            is PlayerInboundEvent.SetSubUppercase -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_UPPERCASE, event.enabled)
                    playerState?.setSubUppercase(event.enabled)
                }
            }
            is PlayerInboundEvent.ResetSubtitleSettings -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_FONT, "Inter")
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_COLOR)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_SIZE)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_BG)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_BORDER_COLOR)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_BORDER_SIZE)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_SHADOW_COLOR)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_SHADOW_OFFSET)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_BLUR)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_BOLD)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_ITALIC)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_ENABLE_SUB_OVERRIDE)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_REMOVE_CAPTIONS)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_REMOVE_BLOAT)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_UPPERCASE)
                    DesktopDataStore.removeKey(PlayerConfig.PREF_SUB_POS)

                    playerState?.setSubtitleFont("Inter")
                    playerState?.setSubtitleOverrideEnabled(true)
                    playerState?.setSubFilterSdh(false)
                    playerState?.setSubFilterBloat(true)
                    playerState?.setSubUppercase(false)
                    playerState?.setSubtitlePosition(100)
                    playerState?.setMpvProperty("sub-color", "#FFFFFF")
                    playerState?.setMpvProperty("sub-font-size", "45")
                    val (defMpvBg, defBorderStyle) = PlayerConfig.toMpvBackgroundColor(null)
                    playerState?.setMpvProperty("sub-back-color", defMpvBg)
                    playerState?.setMpvProperty("sub-border-style", defBorderStyle)
                    playerState?.setMpvProperty("sub-border-color", "#000000")
                    playerState?.setMpvProperty("sub-border-size", "3")
                    playerState?.setMpvProperty("sub-shadow-color", "#000000")
                    playerState?.setMpvProperty("sub-shadow-offset", "0")
                    playerState?.setMpvProperty("sub-blur", "0")
                    playerState?.setMpvProperty("sub-bold", "no")
                    playerState?.setMpvProperty("sub-italic", "no")
                    pushSyncStateToWebView()
                    NativePlayerBridge.syncUiScale(getGlobalUiScale())
                }
            }
            is PlayerInboundEvent.SetSubtitleBackground -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_BG, event.backgroundKey)
                    playerState?.setSubtitleBackground(event.backgroundKey)
                }
            }
            is PlayerInboundEvent.SetSubtitleBorderColor -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_BORDER_COLOR, event.color)
                    playerState?.setMpvProperty("sub-border-color", event.color)
                }
            }
            is PlayerInboundEvent.SetSubtitleBorderSize -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_BORDER_SIZE, event.size)
                    playerState?.setMpvProperty("sub-border-size", event.size)
                }
            }
            is PlayerInboundEvent.SetSubtitleShadowColor -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_SHADOW_COLOR, event.color)
                    playerState?.setMpvProperty("sub-shadow-color", event.color)
                }
            }
            is PlayerInboundEvent.SetSubtitleShadowOffset -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_SHADOW_OFFSET, event.offset)
                    playerState?.setMpvProperty("sub-shadow-offset", event.offset)
                }
            }
            is PlayerInboundEvent.SetSubtitleBlur -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_BLUR, event.blur)
                    playerState?.setMpvProperty("sub-blur", event.blur)
                }
            }
            is PlayerInboundEvent.SetSubtitleBold -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_BOLD, event.bold)
                    playerState?.setMpvProperty("sub-bold", event.bold)
                }
            }
            is PlayerInboundEvent.SetSubtitleItalic -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SUB_ITALIC, event.italic)
                    playerState?.setMpvProperty("sub-italic", event.italic)
                }
            }
            is PlayerInboundEvent.ToggleInterpolation -> {
                playerState?.setInterpolation(event.enabled)
            }
            is PlayerInboundEvent.ToggleDeband -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_DEBAND, event.enabled)
                    playerState?.setMpvProperty("deband", if (event.enabled) "yes" else "no")
                }
            }
            is PlayerInboundEvent.SetDebandQuality -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_DEBAND_QUALITY, event.quality)
                    when (event.quality) {
                        "light" -> {
                            playerState?.setMpvProperty("deband", "yes")
                            playerState?.setMpvProperty("deband-iterations", "1")
                            playerState?.setMpvProperty("deband-threshold", "32")
                            playerState?.setMpvProperty("deband-range", "8")
                            playerState?.setMpvProperty("deband-grain", "16")
                        }
                        "medium" -> {
                            playerState?.setMpvProperty("deband", "yes")
                            playerState?.setMpvProperty("deband-iterations", "2")
                            playerState?.setMpvProperty("deband-threshold", "48")
                            playerState?.setMpvProperty("deband-range", "16")
                            playerState?.setMpvProperty("deband-grain", "32")
                        }
                        "strong" -> {
                            playerState?.setMpvProperty("deband", "yes")
                            playerState?.setMpvProperty("deband-iterations", "4")
                            playerState?.setMpvProperty("deband-threshold", "64")
                            playerState?.setMpvProperty("deband-range", "24")
                            playerState?.setMpvProperty("deband-grain", "48")
                        }
                        else -> {
                            playerState?.setMpvProperty("deband", "no")
                        }
                    }
                }
            }
            is PlayerInboundEvent.SetAudioNormalization -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_NORMALIZATION, event.enabled)
                    playerState?.updateAudioFilters()
                }
            }
            is PlayerInboundEvent.SetAudioNormStrength -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_NORM_STRENGTH, event.strength)
                    playerState?.updateAudioFilters()
                }
            }
            is PlayerInboundEvent.SetAudioSpatial -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_SPATIAL, event.enabled)
                    playerState?.updateAudioFilters()
                }
            }
            is PlayerInboundEvent.SetAudioEqPreset -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_EQ_PRESET, event.preset)
                    playerState?.updateAudioFilters()
                }
            }
            is PlayerInboundEvent.SetAudioVolumeMax -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_VOLUME_MAX, event.enabled)
                    playerState?.setMpvProperty("volume-max", if (event.enabled) "200" else "100")
                    if (!event.enabled && (playerState?.volume?.value ?: 0f) > 100f) {
                        playerState?.setVolume(100f)
                    }
                    pushMetadataToWebView()
                }
            }
            is PlayerInboundEvent.SetAudioDelay -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_DELAY, event.delaySec)
                    playerState?.setMpvProperty("audio-delay", event.delaySec.toString())
                }
            }
            is PlayerInboundEvent.ToggleAutoPlay -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_AUTO_PLAY, event.enabled)
                }
            }
            is PlayerInboundEvent.SetPrefShowEndTime -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SHOW_END_TIME, event.enabled)
                }
            }
            is PlayerInboundEvent.SetPrefShowClock -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SHOW_CLOCK, event.enabled)
                }
            }
            is PlayerInboundEvent.SetPrefShowServerQuality -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_SHOW_SERVER_QUALITY, event.enabled)
                }
            }
            is PlayerInboundEvent.SetPlayerTheme -> {
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(PlayerConfig.PREF_PLAYER_UI_THEME, event.theme)
                }
            }
            is PlayerInboundEvent.LoadNextEpisode -> {
                scope.launch(Dispatchers.Main) {
                    onNextEpisode?.invoke()
                }
            }
            is PlayerInboundEvent.CancelCountdown -> {
                scope.launch(Dispatchers.Main) {
                    onCancelCountdown?.invoke()
                }
            }
            is PlayerInboundEvent.ReplayEpisode -> {
                scope.launch(Dispatchers.Main) {
                    onReplayEpisode?.invoke()
                }
            }
            is PlayerInboundEvent.SetMpvProperty -> {
                scope.launch(Dispatchers.IO) {
                    playerState?.setMpvProperty(event.property, event.value)
                    when (event.property) {
                        "sub-color" -> DesktopDataStore.setKey(PlayerConfig.PREF_SUB_COLOR, event.value)
                        "sub-font-size" -> DesktopDataStore.setKey(PlayerConfig.PREF_SUB_SIZE, event.value)
                        "tone-mapping" -> DesktopDataStore.setKey(PlayerConfig.PREF_HDR_TONE_MAPPING, event.value)
                        "hdr-compute-peak" -> DesktopDataStore.setKey(PlayerConfig.PREF_HDR_COMPUTE_PEAK, event.value == "yes")
                        "dither" -> DesktopDataStore.setKey(PlayerConfig.PREF_DITHERING, event.value)
                    }
                }
            }
            is PlayerInboundEvent.SetSubtitlePosition -> {
                playerState?.setSubtitlePosition(event.pos)
            }
            is PlayerInboundEvent.UserInteraction -> {
                scope.launch(Dispatchers.Main) {
                    onUserInteraction?.invoke()
                }
            }
            is PlayerInboundEvent.SetSleepTimer -> {
                scope.launch(Dispatchers.Main) {
                    onSetSleepTimer?.invoke(event.value)
                }
            }
            is PlayerInboundEvent.ContinueFromInactivity -> {
                scope.launch(Dispatchers.Main) {
                    onContinueFromInactivity?.invoke()
                }
            }
            is PlayerInboundEvent.SkipScraping -> {
                scope.launch(Dispatchers.Main) {
                    onSkipScraping?.invoke()
                }
            }
            is PlayerInboundEvent.RequestSeekThumbnail -> {
                scope.launch(Dispatchers.IO) {
                    val activeEngine = getTrickplayEngine()
                    val dataUri = activeEngine?.requestThumbnail(event.timeMs)
                    if (dataUri != null) {
                        val payload = mapOf(
                            "type" to "seek_thumbnail_response",
                            "timeMs" to event.timeMs,
                            "dataUri" to dataUri,
                        )
                        NativePlayerBridge.safePostMessage(playerObjectMapper.writeValueAsString(payload))
                    }
                }
            }
            is PlayerInboundEvent.Unknown -> {
                AppLogger.w("Player:IPC", "Unhandled or unknown inbound player event: ${event.type} -> ${event.rawValue}")
            }
        }
    }
}
