package com.lagradost.cloudstream3.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.desktop.player.ipc.PlayerInboundEvent
import com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.*
import java.io.File

private val playerObjectMapper = jacksonObjectMapper()

private val EMPTY_LIST_FLOW = flowOf(emptyList<Nothing>())
private val FALSE_FLOW = flowOf(false)
private val MINUS_ONE_FLOW = flowOf(-1)
private val NONE_FLOW = flowOf("None")
private val NULL_STRING_FLOW = flowOf<String?>(null)
private val NULL_SKIP_INTERVAL_FLOW = flowOf<com.lagradost.cloudstream3.desktop.player.skip.SkipInterval?>(null)
private val ZERO_LONG_FLOW = flowOf(0L)

@Composable
fun ComposeNativeWebPlayer(
    modifier: Modifier = Modifier.fillMaxSize(),
    link: ExtractorLink?,
    title: String? = null,
    seriesPosterUrl: String? = null,
    subtitles: List<com.lagradost.cloudstream3.SubtitleFile> = emptyList(),
    startPositionMs: Long,
    shouldPauseForResume: Boolean = false,
    onPlaybackReady: () -> Unit,
    onPlaybackError: (String) -> Unit,
    onFinished: () -> Unit,
    onPositionChange: (Long, Long) -> Unit,
    onCloseRequest: () -> Unit,
    isExiting: Boolean = false,
    onSkipScraping: (() -> Unit)? = null,
    onFullscreenToggle: (() -> Unit)? = null,
    playerState: com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState? = null,
    links: List<ExtractorLink> = emptyList(),
    currentLinkIndex: Int = 0,
    episodes: List<com.lagradost.cloudstream3.Episode> = emptyList(),
    currentEpisodeId: String? = null,
    currentEpisodeNumber: Int? = null,
    currentSeasonNumber: Int? = null,
    isLoading: Boolean = false,
    loadingStatusText: String? = null,
    isProbing: Boolean = false,
    isScraping: Boolean = false,
    failedLinks: Map<Int, String> = emptyMap(),
    backdropUrl: String? = null,
    logoUrl: String? = null,
    onLinkChange: ((String) -> Unit)? = null,
    onEpisodeChange: ((String) -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onCancelCountdown: (() -> Unit)? = null,
    onReplayEpisode: (() -> Unit)? = null,
    onUserInteraction: (() -> Unit)? = null,
    onSetSleepTimer: ((String) -> Unit)? = null,
    onContinueFromInactivity: (() -> Unit)? = null,
    plot: String? = null,
    year: Int? = null,
    tags: List<String>? = null,
    contentRating: String? = null,
    rating: Double? = null,
    actors: List<com.lagradost.cloudstream3.ActorData> = emptyList(),
    isAnime: Boolean = false,
    isLive: Boolean = false,
    isExhausted: Boolean = false,
    exhaustionReason: String? = null,
    exhaustionDiagnostics: String? = null,
    reloadKey: Int = 0,
    onRetryPlayback: (() -> Unit)? = null,
    parentId: String? = null,
    providerName: String? = null,
) {
    val scope = rememberCoroutineScope()
    val persistentSubtitles = remember { mutableStateListOf<String>() }
    val window = com.lagradost.cloudstream3.desktop.ui.LocalComposeWindow.current

    val primaryColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val accentColorHex = remember(primaryColor) {
        String.format("#%02X%02X%02X", (primaryColor.red * 255).toInt(), (primaryColor.green * 255).toInt(), (primaryColor.blue * 255).toInt())
    }
    val accentColorRgb = remember(primaryColor) {
        "${(primaryColor.red * 255).toInt()}, ${(primaryColor.green * 255).toInt()}, ${(primaryColor.blue * 255).toInt()}"
    }

    val currentOnPlaybackReady by rememberUpdatedState(onPlaybackReady)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnCloseRequest by rememberUpdatedState(onCloseRequest)
    val currentOnFullscreenToggle by rememberUpdatedState(onFullscreenToggle)

    val watchHistoryList by produceState<List<com.lagradost.common.storage.WatchHistory>>(initialValue = emptyList(), parentId) {
        if (parentId != null) {
            value = withContext(Dispatchers.IO) {
                com.lagradost.common.storage.DesktopDataStore.getWatchHistoryByParent(parentId)
            }
            com.lagradost.common.storage.DesktopDataStore.historyUpdates.collect {
                value = withContext(Dispatchers.IO) {
                    com.lagradost.common.storage.DesktopDataStore.getWatchHistoryByParent(parentId)
                }
            }
        }
    }

    val episodeHistoryLookup = remember(watchHistoryList) {
        com.lagradost.cloudstream3.desktop.ui.screens.details.EpisodeHistoryLookup(
            watchHistoryList.associateBy { it.episodeId ?: "" },
        )
    }

    var isUiReady by remember { mutableStateOf(false) }
    var currentHandleValue by remember { mutableStateOf(0L) }
    val globalUiScale by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.globalUiScale.collectAsState()
    LaunchedEffect(globalUiScale, isUiReady) {
        if (isUiReady) {
            NativePlayerBridge.syncUiScale(globalUiScale)
        }
    }
    val audioTracks by (playerState?.audioTracks ?: EMPTY_LIST_FLOW).collectAsState(emptyList())
    val subtitleTracks by (playerState?.subtitleTracks ?: EMPTY_LIST_FLOW).collectAsState(emptyList())
    val videoTracks by (playerState?.videoTracks ?: EMPTY_LIST_FLOW).collectAsState(emptyList())
    val isAudioOnlyStream by (playerState?.isAudioOnlyStream ?: FALSE_FLOW).collectAsState(false)
    val isAudioMode by (playerState?.isAudioMode ?: FALSE_FLOW).collectAsState(false)
    val chapters by (playerState?.chapters ?: EMPTY_LIST_FLOW).collectAsState(emptyList())
    val currentChapterIndex by (playerState?.currentChapterIndex ?: MINUS_ONE_FLOW).collectAsState(-1)
    val isBuffering by (playerState?.isBuffering ?: FALSE_FLOW).collectAsState(false)

    val proxyAudioTracks by com.lagradost.player.impl.proxy.LocalStreamProxyState.lazyAudioTracks.collectAsState()
    val proxySubtitleTracks by com.lagradost.player.impl.proxy.LocalStreamProxyState.lazySubtitleTracks.collectAsState()
    val proxyVideoTracks by com.lagradost.player.impl.proxy.LocalStreamProxyState.lazyVideoTracks.collectAsState()

    val currentIsLoading by rememberUpdatedState(isLoading)
    val currentLoadingStatusText by rememberUpdatedState(loadingStatusText)
    val activeShader by (playerState?.activeShader ?: NONE_FLOW).collectAsState("None")
    val activeLazyVideoTrackUrl by (playerState?.activeLazyVideoTrackUrl ?: NULL_STRING_FLOW).collectAsState(null)
    val activeLazyAudioTrackUrl by (playerState?.activeLazyAudioTrackUrl ?: NULL_STRING_FLOW).collectAsState(null)
    val activeSkipInterval by (playerState?.activeSkipInterval ?: NULL_SKIP_INTERVAL_FLOW).collectAsState(null)
    val skipIntervals by (playerState?.skipIntervals ?: EMPTY_LIST_FLOW).collectAsState(emptyList())
    val resolution by (playerState?.resolution ?: NULL_STRING_FLOW).collectAsState(null)
    val liveFileSize by (playerState?.fileSize ?: ZERO_LONG_FLOW).collectAsState(0L)
    var subtitleSettingsVersion by remember { mutableStateOf(0L) }
    var activeSubtitleOverrideEnabled by remember {
        mutableStateOf(
            com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(
                PlayerConfig.PREF_ENABLE_SUB_OVERRIDE,
            ) ?: true,
        )
    }

    LaunchedEffect(playerState) {
        com.lagradost.common.storage.DesktopDataStore.keyUpdates.collect { key ->
            if (key.startsWith("player_sub_") || key == PlayerConfig.PREF_ENABLE_SUB_OVERRIDE) {
                when (key) {
                    PlayerConfig.PREF_ENABLE_SUB_OVERRIDE -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(key) ?: true
                        activeSubtitleOverrideEnabled = v
                        playerState?.setSubtitleOverrideEnabled(v)
                    }
                    PlayerConfig.PREF_SUB_COLOR -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "#FFFFFF"
                        playerState?.setMpvProperty("sub-color", v)
                    }
                    PlayerConfig.PREF_SUB_SIZE -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "45"
                        playerState?.setMpvProperty("sub-font-size", v)
                    }
                    PlayerConfig.PREF_SUB_FONT -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key)
                        playerState?.setSubtitleFont(v)
                    }
                    PlayerConfig.PREF_SUB_BG -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "#00000000"
                        playerState?.setSubtitleBackground(v)
                    }
                    PlayerConfig.PREF_SUB_BORDER_COLOR -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "#000000"
                        playerState?.setMpvProperty("sub-border-color", v)
                    }
                    PlayerConfig.PREF_SUB_BORDER_SIZE -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "3"
                        playerState?.setMpvProperty("sub-border-size", v)
                    }
                    PlayerConfig.PREF_SUB_SHADOW_COLOR -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "#00000000"
                        playerState?.setMpvProperty("sub-shadow-color", v)
                    }
                    PlayerConfig.PREF_SUB_SHADOW_OFFSET -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "0"
                        playerState?.setMpvProperty("sub-shadow-offset", v)
                    }
                    PlayerConfig.PREF_SUB_BLUR -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "0"
                        playerState?.setMpvProperty("sub-blur", v)
                    }
                    PlayerConfig.PREF_SUB_BOLD -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "no"
                        playerState?.setMpvProperty("sub-bold", v)
                    }
                    PlayerConfig.PREF_SUB_ITALIC -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "no"
                        playerState?.setMpvProperty("sub-italic", v)
                    }
                    PlayerConfig.PREF_SUB_POS -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<String>(key) ?: "100"
                        playerState?.setSubtitlePosition(v.toIntOrNull() ?: 100)
                    }
                    PlayerConfig.PREF_SUB_REMOVE_CAPTIONS -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(key) ?: false
                        playerState?.setSubFilterSdh(v)
                    }
                    PlayerConfig.PREF_SUB_REMOVE_BLOAT -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(key) ?: true
                        playerState?.setSubFilterBloat(v)
                    }
                    PlayerConfig.PREF_SUB_UPPERCASE -> {
                        val v = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(key) ?: false
                        playerState?.setSubUppercase(v)
                    }
                }
                subtitleSettingsVersion++
            }
        }
    }

    var hasAutoSelectedQuality by remember(link) { mutableStateOf(false) }

    val trickplayEngine = remember(link) {
        if (link != null) {
            runCatching {
                DesktopTrickplayEngine(scope).apply {
                    val validated = com.lagradost.player.impl.PlayerLinkHandler.validate(link, title).getOrNull()
                    val targetUrl = validated?.url ?: link.url
                    val targetHeaders = validated?.headers ?: link.headers
                    start(targetUrl, targetHeaders)
                }
            }.getOrNull()
        } else {
            null
        }
    }
    val currentTrickplayEngine by rememberUpdatedState(trickplayEngine)

    DisposableEffect(trickplayEngine) {
        onDispose {
            trickplayEngine?.stop()
        }
    }

    val resolvedImdbId = remember(parentId, currentEpisodeId) {
        parentId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: currentEpisodeId?.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")
    }

    val autoFetchedSubtitles by (playerState?.autoFetchedSubtitles ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState(emptyList())
    val autoFetchedSubtitleTracks = remember(autoFetchedSubtitles) {
        autoFetchedSubtitles.map { LazyTrackPayload(it.url, it.name, it.language) }
    }
    LaunchedEffect(title, parentId, currentSeasonNumber, currentEpisodeNumber, episodes, currentEpisodeId, isUiReady) {
        if (!isUiReady) return@LaunchedEffect
        val ep = episodes.find { it.data == currentEpisodeId }
        val s = currentSeasonNumber ?: ep?.season
        val e = currentEpisodeNumber ?: ep?.episode
        playerState?.autoFetchSubtitles(
            title = title,
            parentId = parentId,
            season = s,
            episode = e,
            year = year,
            imdbId = resolvedImdbId,
        )
    }

    LaunchedEffect(proxyVideoTracks, link) {
        if (!hasAutoSelectedQuality && proxyVideoTracks.isNotEmpty() && link?.quality != null && link.quality != com.lagradost.cloudstream3.utils.Qualities.Unknown.value) {
            val targetRes = link.quality
            val match = proxyVideoTracks.find { it.name.contains("${targetRes}p") }
                ?: proxyVideoTracks.minByOrNull {
                    val res = it.name.substringBefore("p").toIntOrNull() ?: Int.MAX_VALUE
                    kotlin.math.abs(res - targetRes)
                }
            if (match != null) {
                playerState?.loadLazyVideoTrack(
                    com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState.LazyTrack(match.url, match.name, match.language, match.bitrate),
                )
            }
            hasAutoSelectedQuality = true
        }
    }

    fun pushSyncStateToWebView() {
        try {
            val payload = PlayerSyncStateBuilder.buildPlayerUiSyncState(
                plot = plot,
                year = year,
                tags = tags,
                contentRating = contentRating,
                rating = rating,
                isProbing = isProbing,
                isScraping = isScraping,
                backdropUrl = backdropUrl,
                logoUrl = logoUrl,
                currentLinkIndex = currentLinkIndex,
                failedLinks = failedLinks,
                links = links,
                liveFileSize = liveFileSize,
                providerName = providerName,
                episodes = episodes,
                currentEpisodeId = currentEpisodeId,
                currentEpisodeNumber = currentEpisodeNumber,
                currentSeasonNumber = currentSeasonNumber,
                seriesPosterUrl = seriesPosterUrl,
                episodeHistoryLookup = episodeHistoryLookup,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                videoTracks = videoTracks,
                proxyAudioTracks = proxyAudioTracks,
                proxySubtitleTracks = proxySubtitleTracks,
                proxyVideoTracks = proxyVideoTracks,
                autoFetchedSubtitleTracks = autoFetchedSubtitleTracks,
                startPositionMs = startPositionMs,
                title = title,
                activeShader = activeShader,
                activeLazyVideoTrackUrl = activeLazyVideoTrackUrl,
                activeLazyAudioTrackUrl = activeLazyAudioTrackUrl,
                resolution = resolution,
                activeSubtitleOverrideEnabled = activeSubtitleOverrideEnabled,
                isLive = isLive,
                isAudioOnlyStream = isAudioOnlyStream,
                isAudioMode = isAudioMode,
                chapters = chapters,
                currentChapterIndex = currentChapterIndex,
                activeSkipInterval = activeSkipInterval,
                skipIntervals = skipIntervals,
                actors = actors,
                isAnime = isAnime,
                isExhausted = isExhausted,
                exhaustionReason = exhaustionReason,
                exhaustionDiagnostics = exhaustionDiagnostics,
                resolvedImdbId = resolvedImdbId,
            )

            val wrapper = MetadataUpdatePayloadWrapper(value = payload)
            NativePlayerBridge.postMessage(playerObjectMapper.writeValueAsString(wrapper))
        } catch (e: Throwable) {
            com.lagradost.common.logging.AppLogger.e("pushSyncStateToWebView error: ${e.message}")
        }
    }

    fun pushMetadataToWebView() {
        try {
            val vol = playerState?._volume?.value ?: 100f
            val isMuted = playerState?._isMuted?.value ?: false
            val isBuf = playerState?._isBuffering?.value == true

            val payload = PlayerSyncStateBuilder.buildAppStateUpdatePayload(
                volume = vol,
                isMuted = isMuted,
                isBuffering = isBuf,
                isLoading = currentIsLoading,
                loadingStatusText = currentLoadingStatusText,
                fileSizeBytes = playerState?._fileSize?.value ?: 0L,
            )
            NativePlayerBridge.postMessage(playerObjectMapper.writeValueAsString(payload))
        } catch (e: Throwable) {
            com.lagradost.common.logging.AppLogger.e("pushMetadataToWebView error: ${e.message}")
        }
    }

    LaunchedEffect(isUiReady, isLoading, links, currentLinkIndex, episodes, currentEpisodeId, currentEpisodeNumber, currentSeasonNumber, audioTracks, subtitleTracks, videoTracks, chapters, currentChapterIndex, proxyAudioTracks, proxySubtitleTracks, proxyVideoTracks, autoFetchedSubtitleTracks, loadingStatusText, isProbing, isScraping, failedLinks, backdropUrl, logoUrl, title, activeShader, activeLazyVideoTrackUrl, activeLazyAudioTrackUrl, activeSkipInterval, skipIntervals, resolution, liveFileSize, plot, year, tags, contentRating, rating, activeSubtitleOverrideEnabled, subtitleSettingsVersion, isLive, isAudioOnlyStream, isAudioMode, actors, isAnime, isExhausted, exhaustionReason, exhaustionDiagnostics, watchHistoryList) {
        if (isUiReady) {
            if (isScraping && !isProbing && !isExhausted && !isLoading) {
                kotlinx.coroutines.delay(250L)
            }
            pushSyncStateToWebView()
        }
    }

    LaunchedEffect(episodes) {
        if (episodes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val imageLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
                    episodes.forEach { ep ->
                        val url = ep.posterUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                        val resolved = if (url.startsWith("//")) "https:$url" else url
                        val request = coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                            .data(resolved)
                            .build()
                        imageLoader.enqueue(request)
                    }
                } catch (e: Throwable) { com.lagradost.common.logging.AppLogger.d("ComposeNativeWebPlayer", "Episode thumbnail prefetch note: ${e.message}") }
            }
        }
    }

    val toastMessage by (playerState?.toastMessage ?: flowOf(null)).collectAsState(null)
    LaunchedEffect(toastMessage) {
        if (isUiReady && toastMessage != null) {
            val payload = mapOf(
                "type" to "show_toast",
                "message" to toastMessage,
            )
            NativePlayerBridge.postMessage(playerObjectMapper.writeValueAsString(payload))
        }
    }

    val p2pTelemetry by com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.telemetry.collectAsState()
    LaunchedEffect(isUiReady, p2pTelemetry) {
        if (isUiReady) {
            val payload = P2pStatsUpdatePayload(
                active = p2pTelemetry.active,
                downloadSpeed = p2pTelemetry.downloadSpeed,
                uploadSpeed = p2pTelemetry.uploadSpeed,
                peers = p2pTelemetry.peers,
                seeds = p2pTelemetry.seeds,
                preloadedBytes = p2pTelemetry.preloadedBytes,
                totalBytes = p2pTelemetry.totalBytes,
                progressPercent = p2pTelemetry.progressPercent,
                statusText = p2pTelemetry.statusText,
            )
            try {
                NativePlayerBridge.postMessage(playerObjectMapper.writeValueAsString(payload))
            } catch (e: Throwable) {
                com.lagradost.common.logging.AppLogger.e("pushP2pTelemetry error: ${e.message}")
            }
        }
    }

    LaunchedEffect(isLoading, isBuffering, loadingStatusText) {
        if (isUiReady && playerState?.getNativeHandleValue() != null) {
            pushMetadataToWebView()
        }
    }

    val inboundDispatcher = remember(window, playerState, resolvedImdbId, scope) {
        PlayerInboundDispatcher(
            scope = scope,
            window = window,
            playerState = playerState,
            persistentSubtitles = persistentSubtitles,
            resolvedImdbId = resolvedImdbId,
            getGlobalUiScale = { globalUiScale },
            getAutoFetchedSubtitleTracks = { autoFetchedSubtitleTracks },
            getTrickplayEngine = { currentTrickplayEngine },
            pushMetadataToWebView = ::pushMetadataToWebView,
            pushSyncStateToWebView = ::pushSyncStateToWebView,
            onUiReady = {
                isUiReady = true
                pushMetadataToWebView()
                pushSyncStateToWebView()
                NativePlayerBridge.syncUiScale(globalUiScale)
                val nativeHandle = playerState?.getNativeHandleValue() ?: currentHandleValue
                if (nativeHandle != 0L) {
                    NativePlayerBridge.startMpvSync(nativeHandle)
                }
                NativePlayerBridge.focusWebView()
            },
            onFullscreenToggle = currentOnFullscreenToggle,
            onCloseRequest = currentOnCloseRequest,
            onRetryPlayback = onRetryPlayback,
            onLinkChange = onLinkChange,
            onEpisodeChange = onEpisodeChange,
            onNextEpisode = onNextEpisode,
            onCancelCountdown = onCancelCountdown,
            onReplayEpisode = onReplayEpisode,
            onUserInteraction = onUserInteraction,
            onSetSleepTimer = onSetSleepTimer,
            onContinueFromInactivity = onContinueFromInactivity,
            onSkipScraping = onSkipScraping,
        )
    }

    BaseMpvPlayer(
        modifier = modifier,
        link = link,
        reloadKey = reloadKey,
        title = title,
        subtitles = subtitles,
        startPositionMs = startPositionMs,
        shouldPauseForResume = shouldPauseForResume,
        onPlaybackReady = {
            NativePlayerBridge.executeScript("if (window.__dismissProbingOverlay) window.__dismissProbingOverlay();")
            currentOnPlaybackReady()
        },
        onPlaybackError = currentOnPlaybackError,
        onFinished = currentOnFinished,
        onPositionChange = { posMs, durMs ->
            currentOnPositionChange(posMs, durMs)
        },
        onCloseRequest = currentOnCloseRequest,
        isExiting = isExiting,
        isLive = isLive,
        onFullscreenToggle = currentOnFullscreenToggle,
        playerState = playerState,
        onEventLoopReady = { h ->
            playerState?.updateAudioFilters()
            if (isUiReady) {
                NativePlayerBridge.startMpvSync(com.sun.jna.Pointer.nativeValue(h))
            }
        },
        onPreInitialize = { handle, canvasWid, w, h ->
            NativePlayerBridge.shutdownWebView2Warmup()
            val childHwnd = NativePlayerBridge.safeInitWebView(canvasWid, w, h)
            NativePlayerBridge.syncUiScale(globalUiScale)
            MpvLibrary.INSTANCE.mpv_set_option_string(handle, "wid", childHwnd.toString())
            val voDriver = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_VO_DRIVER) ?: "gpu"
            MpvLibrary.INSTANCE.mpv_set_option_string(handle, "vo", voDriver)
            val gpuApi = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_GPU_API) ?: "d3d11"
            MpvLibrary.INSTANCE.mpv_set_option_string(handle, "gpu-api", gpuApi)

            val delaySec = com.lagradost.common.storage.DesktopDataStore.getKey<Float>(PlayerConfig.PREF_AUDIO_DELAY) ?: 0f
            MpvLibrary.INSTANCE.mpv_set_option_string(handle, "audio-delay", delaySec.toString())

            val audioMax = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUDIO_VOLUME_MAX) ?: false
            if (audioMax) {
                MpvLibrary.INSTANCE.mpv_set_option_string(handle, "volume-max", "200")
            }
        },
        onPostInitialize = { handle ->
            currentHandleValue = com.sun.jna.Pointer.nativeValue(handle)
            val webView2DataDir = File(System.getProperty("java.io.tmpdir"), "CloudStreamWebView2")
            webView2DataDir.mkdirs()
            val tempFile = File(webView2DataDir, "cloudstream_controls.html")

            val htmlTemplate = NativePlayerBridge.loadPlayerUiResource("/player-ui/player.html")
            val cssContent = NativePlayerBridge.loadPlayerUiResource("/player-ui/player.css")
            val jsContent = NativePlayerBridge.loadPlayerUiResource("/player-ui/player.js")

            val rawInitialBackdrop = backdropUrl ?: (episodes.find { it.data == currentEpisodeId }?.posterUrl ?: "")
            val initialBackdropUrl = com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(rawInitialBackdrop, isBackdrop = true) ?: rawInitialBackdrop
            val initialBackdropClass = if (initialBackdropUrl.isNotEmpty()) "loaded" else ""
            val rawInitialLogo = logoUrl ?: ""
            val initialLogoUrl = com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(rawInitialLogo, isBackdrop = false) ?: rawInitialLogo
            val hasLocalLogo = initialLogoUrl.isNotEmpty() && initialLogoUrl.startsWith("file:")
            val initialLogoStyle = if (hasLocalLogo) "display: block;" else "display: none;"
            val initialTitleStyle = if (hasLocalLogo) "display: none;" else "display: block;"

            val activeEp = episodes.find { it.data == currentEpisodeId }
            val initialTitle = (title ?: "").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            val initialSubtitle = if (activeEp != null) {
                val s = activeEp.season ?: 1
                val ep = activeEp.episode
                val epTitle = activeEp.name?.ifEmpty { "Episode $ep" } ?: "Episode $ep"
                "S$s:E$ep • $epTitle"
            } else {
                ""
            }
            val initialSubtitleStyle = if (initialSubtitle.isNotEmpty()) "display: block;" else "display: none;"

            val dynamicFontsCss = com.lagradost.cloudstream3.desktop.ui.theme.CustomFontManager.getDynamicFontFaceCss()
            val finalCss = if (dynamicFontsCss.isNotEmpty()) "$dynamicFontsCss\n$cssContent" else cssContent
            val initialTheme = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_PLAYER_UI_THEME) ?: "modern_default"

            val badgesBaseUrl = com.lagradost.cloudstream3.desktop.ui.badges.BadgeManager.getBadgesBaseUri()
            val injectedJs = "window.__BADGES_BASE_URL__ = '$badgesBaseUrl';\n$jsContent"

            val htmlContent = htmlTemplate
                .replace("/* CSS_INJECT */", finalCss)
                .replace("/* JS_INJECT */", injectedJs)
                .replace("{{ACCENT_COLOR}}", accentColorHex)
                .replace("{{ACCENT_COLOR_RGB}}", accentColorRgb)
                .replace("{{INITIAL_BACKDROP_URL}}", initialBackdropUrl)
                .replace("{{INITIAL_BACKDROP_CLASS}}", initialBackdropClass)
                .replace("{{INITIAL_LOGO_URL}}", initialLogoUrl)
                .replace("{{INITIAL_LOGO_STYLE}}", initialLogoStyle)
                .replace("{{INITIAL_TITLE}}", initialTitle)
                .replace("{{INITIAL_TITLE_STYLE}}", initialTitleStyle)
                .replace("{{INITIAL_SUBTITLE}}", initialSubtitle)
                .replace("{{INITIAL_SUBTITLE_STYLE}}", initialSubtitleStyle)
                .replace("{{INITIAL_PLAYER_THEME}}", initialTheme)

            if (htmlContent.isNotEmpty() && htmlTemplate.isNotEmpty()) {
                tempFile.writeText(htmlContent, Charsets.UTF_8)
            } else {
                com.lagradost.common.logging.AppLogger.e("[NativePlayer] player-ui resources not found!")
            }

            NativePlayerBridge.setEventListener(object : NativePlayerBridge.NativePlayerEventListener {
                override fun onPlayerEvent(type: String, value: String) {
                    if (type != "message") return
                    val rootNode = try {
                        playerObjectMapper.readTree(value)
                    } catch (t: Throwable) {
                        null
                    }
                    val event = PlayerInboundEvent.fromJson(rootNode, value)
                    inboundDispatcher.dispatch(event)
                }
            })

            if (tempFile.exists()) {
                val uri = tempFile.absoluteFile.toURI().toString()
                val validWebViewUrl = if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
                    uri.replaceFirst("file:/", "file:///")
                } else {
                    uri
                }
                com.lagradost.common.logging.AppLogger.i("Player:Web", "Loading controls HTML in WebView2: $validWebViewUrl (size=${tempFile.length()} bytes)")
                NativePlayerBridge.loadUrl(validWebViewUrl)
            }
        },
        videoRenderer = { videoCanvas, _ ->
            DisposableEffect(Unit) {
                val componentListener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) {
                        NativePlayerBridge.resizeWebViewForComponent(e.component)
                    }

                    override fun componentMoved(e: ComponentEvent) {
                        NativePlayerBridge.resizeWebViewForComponent(e.component)
                    }
                }
                val propertyListener = java.beans.PropertyChangeListener { evt ->
                    if (evt.propertyName == "graphicsConfiguration") {
                        NativePlayerBridge.resizeWebViewForComponent(videoCanvas)
                    }
                }
                videoCanvas.addComponentListener(componentListener)
                videoCanvas.addPropertyChangeListener("graphicsConfiguration", propertyListener)
                NativePlayerBridge.resizeWebViewForComponent(videoCanvas)
                onDispose {
                    videoCanvas.removeComponentListener(componentListener)
                    videoCanvas.removePropertyChangeListener("graphicsConfiguration", propertyListener)

                    if (isExiting) {
                        videoCanvas.isVisible = false
                        videoCanvas.removeComponentListener(componentListener)
                        videoCanvas.removePropertyChangeListener("graphicsConfiguration", propertyListener)
                        NativePlayerBridge.resizeWebView(0, 0)
                        NativePlayerBridge.stopMpvSync()
                        NativePlayerBridge.setEventListener(null)

                        if (com.lagradost.cloudstream3.desktop.ui.PipState.isPipMode.value) {
                            com.lagradost.cloudstream3.desktop.ui.PipState.setPipMode(false)
                        }

                        NativePlayerBridge.safeDestroyWebView()
                    }
                }
            }

            LaunchedEffect(isExiting) {
                if (isExiting) {
                    videoCanvas.isVisible = false
                    videoCanvas.bounds = java.awt.Rectangle(0, 0, 0, 0)
                    NativePlayerBridge.resizeWebView(0, 0)
                }
            }

            SwingPanel(
                background = androidx.compose.ui.graphics.Color.Black,
                factory = { videoCanvas },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
