package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.player.interactor.SavePlaybackProgress
import com.lagradost.cloudstream3.desktop.player.PlaybackCompletionGuard
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.tracking.SyncCompletionRequest
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncEngine
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.details.EnrichedDetailsCache
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerPhase
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiState
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddedPlayerViewModel(
    private val savePlaybackProgress: SavePlaybackProgress = SavePlaybackProgress(),
    private val trackerSyncEngine: TrackerSyncEngine? = runCatching { AppContainerHolder.container.trackerSyncEngine }.getOrNull(),
    private val trackingRepository: TrackingRepository? = runCatching { AppContainerHolder.container.trackingRepository }.getOrNull(),
) : BaseMviViewModel<PlayerUiState, PlayerUiEvent, PlayerUiEffect>(
    initialState = PlayerUiState(),
) {
    val playerState = PlayerState()
    private var saveJob: Job? = null
    private var timeoutJob: Job? = null
    private var countdownJob: Job? = null
    private val scraper = PlayerStreamScraper(viewModelScope)

    private val linkRetries = mutableMapOf<String, Int>()
    private val completionGuard = PlaybackCompletionGuard()
    private val autoSyncedEpisodeKeys = ConcurrentHashMap.newKeySet<String>()
    private val hasShownNowPlayingToast = AtomicBoolean(false)
    private var lastSavedPositionSec = 0L
    private var autoPlayTimeoutMs: Long = 20_000L
    private var inactivityLimit: Int = 3
    private var scrapeFlowControl: String? = null

    private val scrapeCoordinator = PlayerScrapeCoordinator(
        scope = viewModelScope,
        scraper = scraper,
        getState = { uiState.value },
        updateState = { updater -> updateState(updater) },
        updatePhase = { phase, failed -> updatePhase(phase, failed) },
        onPlaybackError = { url, reason -> handlePlaybackError(url, reason) },
        getScrapeFlowControl = { scrapeFlowControl },
    )

    private val sleepTimer = PlayerSleepTimer(
        scope = viewModelScope,
        onTick = { remaining -> updateState { copy(sleepTimerRemainingSec = remaining) } },
        onModeChanged = { mode, remaining -> updateState { copy(sleepTimerMode = mode, sleepTimerRemainingSec = remaining) } },
        onTimerExpired = {
            flushCurrentEpisodeProgress(markCompletedIfNearEnd = false)
            playerState.pause()
            sendEffect(PlayerUiEffect.ShowToast("Playback paused: Sleep timer expired"))
        },
    )

    companion object {
        private const val MAX_PROBE_RETRIES = 0
        private const val MAX_PLAYING_RETRIES = 2
    }

    init {
        PlayerDiagnosticsHolder.register(playerState)

        trackerSyncEngine?.let { engine ->
            viewModelScope.launch {
                engine.playerNotices.collect { msg ->
                    sendEffect(PlayerUiEffect.ShowToast(msg))
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val autoPlay = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY) ?: true
            val timeoutStr = DesktopDataStore.getKey<String>(PlayerConfig.PREF_AUTO_PLAY_TIMEOUT) ?: "20000"
            autoPlayTimeoutMs = timeoutStr.toLongOrNull() ?: 20_000L
            inactivityLimit = DesktopDataStore.getKey<Int>(PlayerConfig.PREF_INACTIVITY_EPISODE_LIMIT)
                ?: DesktopDataStore.getKey<String>(PlayerConfig.PREF_INACTIVITY_EPISODE_LIMIT)?.toIntOrNull()
                ?: 3
            scrapeFlowControl = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SCRAPE_FLOW_CONTROL)
            updateState { copy(autoPlayEnabled = autoPlay) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            playerState.positionMs.collect { posMs ->
                val currentPosSec = posMs / 1000L
                val durSec = playerState.durationMs.value / 1000L
                val isPaused = playerState.isPaused.value
                val currentData = uiState.value.launchData

                if (currentPosSec <= 0L && lastSavedPositionSec > 0L) {
                    return@collect
                }

                completionGuard.recordTick(
                    posSec = currentPosSec,
                    durSec = durSec,
                    isPaused = isPaused,
                    isBuffering = playerState.isBuffering.value,
                    isPlayingPhase = uiState.value.phase is PlayerPhase.Playing,
                )

                if (kotlin.math.abs(currentPosSec - lastSavedPositionSec) >= 5) {
                    lastSavedPositionSec = currentPosSec
                    if (currentData != null && durSec > 0L) {
                        buildSafeProgressUpdate(
                            baseHistory = currentData.history,
                            posSec = currentPosSec,
                            durSec = durSec,
                            completionThreshold = 0.85f,
                        )?.let { (safeHistory, allowCompletion) ->
                            savePosition(safeHistory, forceNotify = allowCompletion, allowCompletion = allowCompletion)
                        }
                        dispatchAutoTrackerCompletionIfEligible(currentData, currentPosSec, durSec)
                    }
                }

                val percentage = if (durSec > 0) currentPosSec.toFloat() / durSec.toFloat() else 0f
                if (
                    percentage >= 0.88f &&
                    completionGuard.isDurationValidForCompletion(durSec) &&
                    completionGuard.hasMinimumActivePlayback(durSec) &&
                    uiState.value.hasNextEpisode &&
                    uiState.value.autoPlayEnabled
                ) {
                    triggerBackgroundPreScrape()
                } else if (percentage < 0.85f && scraper.isPreScrapeActive) {
                    scraper.cancelPreScrape()
                }

                DiscordRpcCoordinator.updatePlaying(
                    launchData = currentData,
                    positionSeconds = currentPosSec,
                    durationSeconds = durSec,
                    isPaused = isPaused,
                )
            }
        }

        DiscordRpcCoordinator.attachObservers(
            scope = viewModelScope,
            playerState = playerState,
            launchDataFlow = uiState.map { it.launchData },
            getLaunchData = { uiState.value.launchData },
        )

        viewModelScope.launch(Dispatchers.IO) {
            playerState.isPaused.collect { paused ->
                if (paused) {
                    val currentData = uiState.value.launchData ?: return@collect
                    val currentPosSec = playerState.positionMs.value / 1000L
                    val durSec = playerState.durationMs.value / 1000L
                    buildSafeProgressUpdate(
                        baseHistory = currentData.history,
                        posSec = currentPosSec,
                        durSec = durSec,
                        completionThreshold = 0.85f,
                    )?.let { (safeHistory, allowCompletion) ->
                        savePosition(safeHistory, forceNotify = true, allowCompletion = allowCompletion)
                    }
                    if (durSec > 0L) {
                        dispatchAutoTrackerCompletionIfEligible(currentData, currentPosSec, durSec)
                    }
                }
            }
        }
    }

    override fun dispose() {
        scraper.cancelPreScrape()
        countdownJob?.cancel()
        timeoutJob?.cancel()
        com.lagradost.cloudstream3.desktop.discord.DiscordRpcManager.onPlayerStopped()
        PlayerDiagnosticsHolder.unregister(playerState)
        val currentData = uiState.value.launchData
        val currentDurSec = playerState.durationMs.value / 1000L
        val currentPosSec = playerState.positionMs.value / 1000L
        val showName = currentData?.history?.showName?.trim().orEmpty()
        val isValidName = showName.isNotBlank() && !showName.equals("Unknown Channel", ignoreCase = true) && !showName.equals("Unknown", ignoreCase = true)
        if (currentData != null && isValidName && currentDurSec > 0 && currentPosSec > 0) {
            dispatchAutoTrackerCompletionIfEligible(currentData, currentPosSec, currentDurSec)
            val screenshotPath = "${com.lagradost.common.platform.PlatformPaths.appDataDir.absolutePath}/screenshots/history_${currentData.history.parentId}.jpg"
            val hasNextEpisode = uiState.value.hasNextEpisode
            val nextEpisodeData = uiState.value.nextEpisodeData
            val safeUpdate = buildSafeProgressUpdate(
                baseHistory = currentData.history,
                posSec = currentPosSec,
                durSec = currentDurSec,
                completionThreshold = 0.85f,
                screenshotUrl = "file:///$screenshotPath",
            )
            if (safeUpdate != null) {
                val (updatedHistory, allowCompletion) = safeUpdate
                com.lagradost.cloudstream3.desktop.utils.appScope.launch(Dispatchers.IO) {
                    try {
                        File(screenshotPath).parentFile?.mkdirs()
                        playerState.takeScreenshot(screenshotPath)
                        WatchHistoryCoordinator.saveWithNextEpisodeQueue(
                            history = updatedHistory,
                            hasNextEpisode = hasNextEpisode,
                            nextEpisode = nextEpisodeData,
                            saveProgress = savePlaybackProgress,
                            forceNotify = true,
                            allowCompletion = allowCompletion,
                        )
                    } catch (e: Exception) {
                        AppLogger.e("EmbeddedPlayerViewModel", "Failed to save history or screenshot on dispose", e)
                    }
                }
            }
        }

        val currentLinksToCache = (uiState.value.nextEpisodeLinks.ifEmpty { currentData?.links.orEmpty() }).distinctBy { it.url }
        val currentSubsToCache = (uiState.value.nextEpisodeSubtitles.ifEmpty { currentData?.subtitles.orEmpty() }).distinctBy { it.url }
        if (currentData != null && currentLinksToCache.isNotEmpty()) {
            val epId = currentData.history.episodeId
            val showUrl = currentData.loadResponse?.url ?: currentData.history.showUrl
            val keys = buildList {
                epId?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (isEmpty()) {
                    showUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                    currentData.history.parentId.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.distinct()
            LinkCache.addLinks(keys, currentLinksToCache, currentSubsToCache)
        }

        playerState.detachMpv()
        sleepTimer.cancel()
        scrapeCoordinator.cancelAll()
        saveJob?.cancel()
        countdownJob?.cancel()
        timeoutJob?.cancel()
        scraper.cancelPreScrape()
        updateState { copy(launchData = null, phase = PlayerPhase.Idle, failedLinks = emptyMap()) }
        super.dispose()
    }

    override fun handleEvent(event: PlayerUiEvent) {
        when (event) {
            is PlayerUiEvent.OnInit -> init(event.launchData)
            is PlayerUiEvent.OnLoadEpisode -> loadEpisode(event.episode)
            is PlayerUiEvent.OnLoadNextEpisode -> loadNextEpisode()
            is PlayerUiEvent.OnLoadPrevEpisode -> loadPrevEpisode()
            is PlayerUiEvent.OnPlayLoadedEpisode -> playLoadedEpisode()
            is PlayerUiEvent.OnCancelLoading -> cancelLoading()
            is PlayerUiEvent.OnCancelScraping -> cancelScraping()
            is PlayerUiEvent.OnSavePosition -> savePosition(event.history)
            is PlayerUiEvent.OnSelectShader -> selectShader(event.shaderName)
            is PlayerUiEvent.OnPlaybackError -> handlePlaybackError(event.failedUrl, event.reason)
            is PlayerUiEvent.OnLinkChange -> handleLinkChange(event.url)
            is PlayerUiEvent.OnPlaybackReady -> handlePlaybackReady()
            is PlayerUiEvent.OnPlaybackFinished -> handlePlaybackFinished()
            is PlayerUiEvent.OnCancelCountdown -> cancelCountdown()
            is PlayerUiEvent.OnUserInteraction -> onUserInteraction()
            is PlayerUiEvent.OnSetSleepTimer -> sleepTimer.setSleepTimer(event.mode)
            is PlayerUiEvent.OnContinueFromInactivity -> continueFromInactivity()
            is PlayerUiEvent.OnDismissInactivity -> dismissInactivity()
            is PlayerUiEvent.OnRetryPlayback -> retryPlayback()
        }
    }

    private fun onUserInteraction() {
        if (uiState.value.consecutiveAutoplayCount != 0) {
            updateState { copy(consecutiveAutoplayCount = 0) }
        }
    }

    private fun continueFromInactivity() {
        updateState { copy(consecutiveAutoplayCount = 0, showInactivityPrompt = false) }
        NativePlayerBridge.safePostMessage("""{"type":"hide_inactivity_prompt"}""")
        loadNextEpisode()
    }

    private fun dismissInactivity() {
        updateState { copy(showInactivityPrompt = false) }
        NativePlayerBridge.safePostMessage("""{"type":"hide_inactivity_prompt"}""")
        sendEffect(PlayerUiEffect.ClosePlayer)
    }

    private fun retryPlayback() {
        linkRetries.clear()
        val currentData = uiState.value.launchData ?: return
        val epId = currentData.history.episodeId
        if (epId != null) {
            LinkCache.remove(epId)
        }
        updateState {
            copy(
                failedLinks = emptyMap(),
                nextEpisodeError = null,
            )
        }
        val apiName = currentData.loadResponse?.apiName ?: currentData.history.apiName
        val provider = APIHolder.getApiFromNameNull(apiName)
        val targetEp = currentData.episodes.find { it.data == epId }
        if (provider != null && epId != null) {
            updatePhase(PlayerPhase.Scraping, emptyMap())
            scrapeCoordinator.startScrape(provider, epId, currentData, targetEp)
        } else if (currentData.links.isNotEmpty()) {
            val best = PlayerStreamSelector.pickBestActiveLink(currentData.links, emptySet(), currentData.startPositionMs)
            if (best != null) {
                updatePhase(PlayerPhase.Probing(best, false), emptyMap())
            } else {
                updatePhase(PlayerPhase.Idle, emptyMap())
            }
        }
    }

    private fun updatePhase(phase: PlayerPhase, newFailedLinks: Map<String, String>? = null) {
        countdownJob?.cancel()

        if (phase is PlayerPhase.Probing) {
            val isNewProbing = uiState.value.phase !is PlayerPhase.Probing ||
                (uiState.value.phase as? PlayerPhase.Probing)?.link?.url != phase.link.url ||
                phase.isRetry
            if (isNewProbing) {
                hasShownNowPlayingToast.set(false)
                timeoutJob?.cancel()
                val timedOutUrl = phase.link.url
                val baseTimeoutMs = autoPlayTimeoutMs
                val isP2p = DesktopTorrentEngine.isTorrentLink(phase.link) || phase.link.url.contains("127.0.0.1:8091")
                val isLocalLoopback = (phase.link.url.contains("127.0.0.1") || phase.link.url.contains("localhost")) &&
                    !phase.link.url.contains("/proxy") && !phase.link.url.contains("/dash")
                val isProxied = phase.link.url.contains("/proxy") || phase.link.url.contains("/dash")
                val isTimeoutDisabled = baseTimeoutMs <= 0L && !phase.isRetry && !isP2p
                val timeoutMs = when {
                    phase.isRetry -> 3_500L
                    isP2p || isLocalLoopback -> 45_000L
                    isProxied -> if (baseTimeoutMs > 0L) maxOf(20_000L, baseTimeoutMs) else 0L
                    else -> baseTimeoutMs
                }
                if (!isTimeoutDisabled) timeoutJob = viewModelScope.launch {
                    delay(timeoutMs)
                    if (uiState.value.phase is PlayerPhase.Probing && (uiState.value.phase as? PlayerPhase.Probing)?.link?.url == timedOutUrl) {
                        AppLogger.w("EmbeddedPlayerViewModel", "Stream connection timed out after ${timeoutMs}ms, advancing to next candidate: ${timedOutUrl.take(80)}")
                        handlePlaybackError(timedOutUrl, "Connection Timed Out (${timeoutMs / 1000}s)")
                    }
                }
            }
        } else {
            timeoutJob?.cancel()
        }

        updateState {
            val links = launchData?.links.orEmpty()
            val newLaunch = if (phase is PlayerPhase.Probing) {
                launchData?.copy(initialIndex = links.indexOf(phase.link).coerceAtLeast(0))
            } else {
                launchData
            }
            copy(
                phase = phase,
                launchData = newLaunch,
                countdownToNextEpisode = null,
                failedLinks = newFailedLinks ?: failedLinks,
            )
        }
    }

    private fun handlePlaybackReady() {
        timeoutJob?.cancel()
        val currentPhase = uiState.value.phase
        if (currentPhase is PlayerPhase.Probing && !currentPhase.isInitial) {
            if (hasShownNowPlayingToast.compareAndSet(false, true)) {
                val qualStr = if (currentPhase.link.quality > 0) " (${QualityDataHelper.formatQuality(currentPhase.link.quality)})" else ""
                sendEffect(PlayerUiEffect.ShowToast("Now playing: ${currentPhase.link.name}$qualStr"))
            }
        }
        updateState {
            if (currentPhase is PlayerPhase.Probing) {
                copy(phase = PlayerPhase.Playing(currentPhase.link, currentPhase.stillScraping))
            } else {
                this
            }
        }
    }

    private fun handlePlaybackFinished() {
        timeoutJob?.cancel()
        val state = uiState.value
        val durSec = playerState.durationMs.value / 1000L
        val posSec = playerState.positionMs.value / 1000L
        val currentUrl = when (val p = state.phase) {
            is PlayerPhase.Playing -> p.link.url
            is PlayerPhase.Probing -> p.link.url
            else -> null
        }

        val effectiveThreshold = minOf(0.85f, autoSyncThresholdRatio())
        val isPremature = !completionGuard.isDurationValidForCompletion(durSec) ||
            (durSec > 0L && posSec < (durSec * effectiveThreshold).toLong())
        if (isPremature) {
            if (currentUrl != null) {
                val candidateLinks = state.nextEpisodeLinks.ifEmpty { state.launchData?.links ?: emptyList() }
                val remainingCandidates = candidateLinks.filter { it.url != currentUrl && it.url !in state.failedLinks }
                if (remainingCandidates.isNotEmpty()) {
                    AppLogger.w("EmbeddedPlayerViewModel", "Premature or invalid stream termination at ${posSec}s (dur=${durSec}s). Failing over to remaining candidate streams...")
                    handlePlaybackError(currentUrl, "Stream ended prematurely at ${posSec}s")
                    return
                } else {
                    AppLogger.e("EmbeddedPlayerViewModel", "Stream terminated prematurely at ${posSec}s of ${durSec}s with no candidate streams remaining.")
                    handlePlaybackError(currentUrl, "Stream interrupted prematurely (${posSec}s / ${durSec}s)")
                    return
                }
            }
        }

        val isVerifiedCompletion = completionGuard.canMarkCompleted(posSec, durSec, 0.85f)
        flushCurrentEpisodeProgress(markCompletedIfNearEnd = isVerifiedCompletion)
        state.launchData?.let { data ->
            dispatchAutoTrackerCompletionIfEligible(data, posSec, durSec)
        }
        if (!isVerifiedCompletion) {
            AppLogger.i(
                "EmbeddedPlayerViewModel",
                "EOF reached at ${posSec}s/${durSec}s without verified watch coverage (watched=${completionGuard.uniqueWatchedSeconds()}s); suppressing auto-completion and auto-next.",
            )
            return
        }
        if (state.sleepTimerMode == "END_OF_EPISODE") {
            playerState.pause()
            updateState { copy(sleepTimerMode = "OFF", sleepTimerRemainingSec = null) }
            NativePlayerBridge.safePostMessage("""{"type":"sync_sleep_timer","value":"off"}""")
            NativePlayerBridge.safePostMessage("""{"type":"cancel_countdown"}""")
            NativePlayerBridge.safePostMessage("""{"type":"show_toast","message":"Playback paused: Sleep timer reached end of episode"}""")
            sendEffect(PlayerUiEffect.ShowToast("Playback paused: Sleep timer reached end of episode"))
            return
        }

        val autoPlay = state.autoPlayEnabled
        val hasNext = state.hasNextEpisode
        val isOffline = state.launchData?.history?.apiName in listOf("Offline", "Local")
        val episodesUnknown = !isOffline && state.episodes.isEmpty() && state.launchData?.history?.episode != null
        if ((hasNext || episodesUnknown) && autoPlay) {
            val limit = inactivityLimit
            if (limit > 0 && state.consecutiveAutoplayCount >= limit) {
                playerState.pause()
                updateState { copy(showInactivityPrompt = true, countdownToNextEpisode = null) }
                NativePlayerBridge.safePostMessage("""{"type":"show_inactivity_prompt"}""")
                return
            }
            updateState { copy(consecutiveAutoplayCount = consecutiveAutoplayCount + 1) }
            triggerBackgroundPreScrape()
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        scraper.cancelPreScrape()
        updateState { copy(countdownToNextEpisode = null) }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        triggerBackgroundPreScrape()
        countdownJob = viewModelScope.launch {
            var ticks = 5L

            while (ticks > 0) {
                updateState { copy(countdownToNextEpisode = ticks.toInt()) }
                delay(1000)
                ticks--
            }
            updateState { copy(countdownToNextEpisode = null) }
            handleEvent(PlayerUiEvent.OnLoadNextEpisode)
        }
    }

    private fun triggerBackgroundPreScrape() {
        val state = uiState.value
        if (!state.autoPlayEnabled || !state.hasNextEpisode) return
        val nextEp = state.nextEpisodeData ?: return
        scraper.preScrapeNextEpisode(nextEp, state.launchData)
    }

    private fun handlePlaybackError(failedUrl: String, reason: String) {
        val currentState = uiState.value
        val currentPhase = currentState.phase

        val currentLink = when (currentPhase) {
            is PlayerPhase.Probing -> currentPhase.link
            is PlayerPhase.Playing -> currentPhase.link
            else -> null
        }
        if (currentLink != null && currentLink.url != failedUrl) {
            AppLogger.d("EmbeddedPlayerViewModel", "Ignoring error for stale link: $failedUrl (current is ${currentLink.url})")
            return
        }
        completionGuard.markError()

        val isFatal = PlayerErrorDiagnostics.isFatalPlaybackError(reason)
        val isProbing = currentPhase is PlayerPhase.Probing
        val maxRetries = if (isProbing) MAX_PROBE_RETRIES else MAX_PLAYING_RETRIES

        val retries = linkRetries.getOrDefault(failedUrl, 0)
        if (!isFatal && retries < maxRetries) {
            linkRetries[failedUrl] = retries + 1
            AppLogger.w("EmbeddedPlayerViewModel", "Retrying link ($retries/$maxRetries): $failedUrl")
            if (currentLink != null) {
                sendEffect(PlayerUiEffect.ShowToast("Stream error ($reason). Reconnecting (${retries + 1}/$maxRetries)..."))
                val startPos = playerState.positionMs.value.takeIf { it > 0L }
                    ?: currentState.launchData?.startPositionMs ?: 0L
                updateState {
                    copy(
                        launchData = launchData?.copy(startPositionMs = startPos),
                        reloadNonce = reloadNonce + 1,
                    )
                }
                updatePhase(PlayerPhase.Probing(currentLink, currentState.isScrapingLinks, isInitial = false, isRetry = true), currentState.failedLinks)
                return
            }
        }

        AppLogger.e("EmbeddedPlayerViewModel", "Playback error on $failedUrl: $reason (fatal=$isFatal)")
        val newFailed = currentState.failedLinks + (failedUrl to reason)
        val links = currentState.nextEpisodeLinks.ifEmpty { currentState.launchData?.links ?: emptyList() }
        val currentPos = playerState.positionMs.value
        val startPos = if (currentPos > 0L) currentPos else (currentState.launchData?.startPositionMs ?: 0L)
        val next = PlayerStreamSelector.pickBestActiveLink(links, newFailed.keys, startPos)

        if (next != null) {
            val failedLinkObj = links.find { it.url == failedUrl }
            val cleanReason = PlayerErrorDiagnostics.sanitizePlaybackErrorReason(reason)
            val rawName = failedLinkObj?.name?.trim() ?: "Source"
            val cleanName = if (rawName.length > 36) rawName.take(34) + "…" else rawName
            val nextIndex = links.indexOf(next) + 1
            val totalLinks = links.size
            sendEffect(PlayerUiEffect.ShowToast("$cleanName failed ($cleanReason). Trying mirror candidate $nextIndex of $totalLinks..."))

            updateState {
                copy(
                    launchData = launchData?.copy(startPositionMs = startPos),
                    failedLinks = newFailed,
                )
            }
            viewModelScope.launch {
                delay(1000L)
                updatePhase(PlayerPhase.Probing(next, uiState.value.isScrapingLinks, isInitial = false), newFailed)
            }
        } else if (currentState.isScrapingLinks) {
            updatePhase(PlayerPhase.Scraping, newFailed)
        } else {
            AppLogger.e("EmbeddedPlayerViewModel", "All sources exhausted ($reason). Entering in-player diagnostic failure state.")
            val showName = currentState.launchData?.history?.showName ?: "Media"
            val epNum = currentState.launchData?.history?.episode?.let { "E$it" } ?: ""
            val providerName = currentState.launchData?.loadResponse?.apiName ?: currentState.launchData?.history?.apiName ?: "Provider"
            val totalCandidates = links.size
            val diagReport = PlayerErrorDiagnostics.buildDiagnosticsReport(
                showName = showName,
                epNum = epNum,
                providerName = providerName,
                reason = reason,
                links = links,
                failedLinks = newFailed,
            )
            updatePhase(
                PlayerPhase.Exhausted(
                    reason = "All $totalCandidates sources failed ($reason)",
                    failedLinks = newFailed,
                    diagnostics = diagReport,
                ),
                newFailed,
            )
            if (playerState.positionMs.value <= 0L) {
                saveJob?.cancel()
                val parentId = currentState.launchData?.history?.parentId
                if (!parentId.isNullOrBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        DesktopDataStore.removeWatchHistory(parentId)
                    }
                }
            }
        }
    }

    private fun handleLinkChange(url: String) {
        linkRetries.clear()
        val currentState = uiState.value
        val link = currentState.launchData?.links?.find { it.url == url }
        if (link != null) {
            val qualStr = if (link.quality > 0) " (${QualityDataHelper.formatQuality(link.quality)})" else ""
            sendEffect(PlayerUiEffect.ShowToast("Switching to ${link.name}$qualStr..."))
            val currentPos = playerState.positionMs.value
            val startPos = if (currentPos > 0L) currentPos else currentState.launchData.startPositionMs
            updateState {
                copy(launchData = launchData?.copy(startPositionMs = startPos))
            }
            updatePhase(PlayerPhase.Probing(link, currentState.isScrapingLinks, isInitial = false), emptyMap())
        }
    }

    private fun selectShader(shaderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(
                PlayerConfig.PREF_ACTIVE_SHADER,
                shaderName,
            )
        }
    }

    private fun resetCompletionGuard(data: VideoLaunchData) {
        val isMovie = data.loadResponse is com.lagradost.cloudstream3.MovieLoadResponse ||
            data.loadResponse?.type == com.lagradost.cloudstream3.TvType.Movie ||
            data.loadResponse?.type == com.lagradost.cloudstream3.TvType.AnimeMovie ||
            data.history.episode == null
        completionGuard.reset(
            isMovie = isMovie,
            expectedDurationMinutes = data.loadResponse?.duration,
            priorPositionSec = data.startPositionMs / 1000L,
            priorDurationSec = data.history.duration,
        )
    }

    private fun buildSafeProgressUpdate(
        baseHistory: WatchHistory,
        posSec: Long,
        durSec: Long,
        completionThreshold: Float,
        screenshotUrl: String? = baseHistory.screenshotUrl,
    ): Pair<WatchHistory, Boolean>? {
        if (!completionGuard.isDurationValidForPersistence(durSec)) return null
        if (!completionGuard.hasMinimumActivePlayback(durSec)) return null
        val canComplete = completionGuard.canMarkCompleted(posSec, durSec, completionThreshold)
        val safePosSec = if (canComplete) {
            durSec
        } else {
            completionGuard.clampPersistablePosition(posSec, durSec, completionThreshold)
        }
        if (safePosSec <= 0L) return null
        return baseHistory.copy(
            position = safePosSec,
            duration = durSec,
            screenshotUrl = screenshotUrl,
            updateTime = System.currentTimeMillis(),
        ) to canComplete
    }

    private fun savePosition(history: WatchHistory, forceNotify: Boolean = false, allowCompletion: Boolean = false) {
        val name = history.showName.trim()
        if (name.isBlank() || name.equals("Unknown Channel", ignoreCase = true) || name.equals("Unknown", ignoreCase = true)) {
            return
        }
        if (!completionGuard.isDurationValidForPersistence(history.duration) || history.position <= 0L) {
            return
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            WatchHistoryCoordinator.saveWithNextEpisodeQueue(
                history = history,
                hasNextEpisode = uiState.value.hasNextEpisode,
                nextEpisode = uiState.value.nextEpisodeData,
                saveProgress = savePlaybackProgress,
                forceNotify = forceNotify,
                allowCompletion = allowCompletion,
            )
        }
    }

    private fun flushCurrentEpisodeProgress(markCompletedIfNearEnd: Boolean = false) {
        val currentData = uiState.value.launchData ?: return
        val currentDurSec = playerState.durationMs.value / 1000L
        val currentPosSec = playerState.positionMs.value / 1000L
        if (currentDurSec <= 0 && currentData.history.duration <= 0) return

        val durSec = if (currentDurSec > 0) currentDurSec else currentData.history.duration
        val posSec = if (currentPosSec > 0) currentPosSec else currentData.history.position
        val threshold = if (markCompletedIfNearEnd) 0.80f else 0.85f
        val (updatedHistory, allowCompletion) = buildSafeProgressUpdate(
            baseHistory = currentData.history,
            posSec = posSec,
            durSec = durSec,
            completionThreshold = threshold,
        ) ?: return

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            WatchHistoryCoordinator.saveWithNextEpisodeQueue(
                history = updatedHistory,
                hasNextEpisode = uiState.value.hasNextEpisode,
                nextEpisode = uiState.value.nextEpisodeData,
                saveProgress = savePlaybackProgress,
                forceNotify = true,
                allowCompletion = allowCompletion,
            )
        }
        dispatchAutoTrackerCompletionIfEligible(currentData, posSec, durSec)
    }

    private fun autoSyncThresholdRatio(): Float =
        ((trackingRepository?.getAutoSyncThresholdPercent() ?: 80).coerceIn(80, 90)) / 100f

    private fun dispatchAutoTrackerCompletionIfEligible(
        data: VideoLaunchData,
        posSec: Long,
        durSec: Long,
    ) {
        val engine = trackerSyncEngine ?: return
        val thresholdPct = autoSyncThresholdRatio()
        if (!completionGuard.canAutoTrackCompleted(posSec, durSec, thresholdPct)) return

        val showUrl = data.loadResponse?.url?.takeIf { it.isNotBlank() } ?: data.history.showUrl
        val cachedState = if (showUrl.isNotBlank()) EnrichedDetailsCache.get(showUrl) else null
        val isMovieType = data.loadResponse is com.lagradost.cloudstream3.MovieLoadResponse ||
            data.loadResponse?.type in setOf(TvType.Movie, TvType.AnimeMovie)
        val epNum = data.history.episode ?: if (isMovieType) 1 else null
        if (epNum == null || epNum <= 0) return

        val seasonNum = (data.history.season ?: cachedState?.selectedSeason ?: 1).coerceAtLeast(1)
        val sessionKey = "${data.history.parentId}:$seasonNum:$epNum"
        if (!autoSyncedEpisodeKeys.add(sessionKey)) return

        val tvType = data.loadResponse?.type
            ?: cachedState?.response?.type
            ?: if (isMovieType) TvType.Movie else TvType.Anime

        engine.enqueueCompletionForConnectedTrackers(
            SyncCompletionRequest(
                apiName = data.loadResponse?.apiName?.takeIf { it.isNotBlank() } ?: data.history.apiName,
                showUrl = showUrl,
                showTitle = data.loadResponse?.name?.takeIf { it.isNotBlank() } ?: data.history.showName,
                mappedSeason = seasonNum,
                episodeNum = epNum,
                year = data.resolvedYear ?: cachedState?.enrichedYear ?: data.loadResponse?.year,
                tvType = tvType,
                completionVerified = true,
                canonicalSeed = data.canonicalSeed ?: cachedState?.canonicalSeed,
                resolvedAnilistId = data.resolvedAnilistId ?: cachedState?.anilistId,
                resolvedMalId = data.resolvedMalId ?: cachedState?.malId,
                resolvedTmdbId = cachedState?.tmdbId,
                resolvedImdbId = cachedState?.imdbId,
                hasMultipleSeasons = data.hasMultipleSeasons,
            ),
        )
    }

    private fun init(initialData: VideoLaunchData) {
        linkRetries.clear()
        lastSavedPositionSec = initialData.startPositionMs / 1000L
        scrapeCoordinator.cancelAll()
        countdownJob?.cancel()
        timeoutJob?.cancel()
        scraper.cancelPreScrape()

        val isFinished = initialData.history.duration > 0 && initialData.history.position >= initialData.history.duration - 15
        val adjustedData = if (isFinished) {
            initialData.copy(
                startPositionMs = 0L,
                history = initialData.history.copy(position = 0L),
            )
        } else {
            initialData
        }
        resetCompletionGuard(adjustedData)

        val showUrl = adjustedData.loadResponse?.url ?: adjustedData.history.showUrl
        val cachedState = if (showUrl.isNotBlank()) EnrichedDetailsCache.get(showUrl) else null
        val hydratedData = if (cachedState != null) {
            adjustedData.copy(
                enrichedLogoUrl = adjustedData.enrichedLogoUrl?.takeIf { it.isNotBlank() } ?: cachedState.enrichedLogoUrl,
                enrichedBackdropUrl = adjustedData.enrichedBackdropUrl?.takeIf { it.isNotBlank() } ?: cachedState.enrichedBackdropUrl,
                enrichedActors = adjustedData.enrichedActors?.takeIf { it.isNotEmpty() } ?: cachedState.enrichedActors,
                loadResponse = adjustedData.loadResponse ?: cachedState.response,
            )
        } else {
            adjustedData
        }

        updateState { copy(launchData = hydratedData, phase = PlayerPhase.Idle, failedLinks = emptyMap()) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentLaunch = uiState.value.launchData ?: hydratedData
                val currentShowUrl = currentLaunch.loadResponse?.url ?: currentLaunch.history.showUrl
                if (currentShowUrl.isBlank()) return@launch

                var effectiveResp = currentLaunch.loadResponse
                if (effectiveResp == null) {
                    val apiName = currentLaunch.history.apiName
                    val provider = APIHolder.allProviders.firstOrNull {
                        it.name == apiName && it.mainUrl.isNotBlank() && currentShowUrl.startsWith(it.mainUrl)
                    } ?: APIHolder.getApiFromNameNull(apiName)

                    if (provider != null) {
                        val res = SafePluginInvoker.invokeOrNull(
                            tag = "HistoryLaunch:${provider.name}",
                            timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                        ) {
                            provider.load(currentShowUrl)
                        }
                        if (res is LoadResponse) {
                            effectiveResp = res
                            updateState {
                                val curr = launchData
                                if (curr != null) {
                                    copy(launchData = curr.copy(loadResponse = res))
                                } else {
                                    this
                                }
                            }
                        }
                    }
                }

                if (effectiveResp != null) {
                    val activeLaunch = uiState.value.launchData ?: hydratedData
                    val missingLogo = activeLaunch.enrichedLogoUrl.isNullOrBlank() && effectiveResp.logoUrl.isNullOrBlank()
                    val missingCast = activeLaunch.enrichedActors.isNullOrEmpty() && effectiveResp.actors.isNullOrEmpty()
                    val missingBackdrop = activeLaunch.enrichedBackdropUrl.isNullOrBlank() && effectiveResp.backgroundPosterUrl.isNullOrBlank()

                    if (missingLogo || missingCast || missingBackdrop) {
                        com.lagradost.cloudstream3.desktop.metadata.MetadataPipeline.enrich(
                            loaded = effectiveResp,
                            url = currentShowUrl,
                            fetchCast = true,
                            callbacks = com.lagradost.cloudstream3.desktop.metadata.MetadataEnrichmentCallbacks(
                                onLogoLoaded = { logo ->
                                    updateState {
                                        val curr = launchData
                                        if (curr != null && (curr.enrichedLogoUrl.isNullOrBlank() || curr.enrichedLogoUrl != logo)) {
                                            copy(launchData = curr.copy(enrichedLogoUrl = logo))
                                        } else {
                                            this
                                        }
                                    }
                                },
                                onBackdropLoaded = { backdrop ->
                                    updateState {
                                        val curr = launchData
                                        if (curr != null && (curr.enrichedBackdropUrl.isNullOrBlank() || curr.enrichedBackdropUrl != backdrop)) {
                                            copy(launchData = curr.copy(enrichedBackdropUrl = backdrop))
                                        } else {
                                            this
                                        }
                                    }
                                },
                                onActorsLoaded = { actors ->
                                    updateState {
                                        val curr = launchData
                                        if (curr != null && (curr.enrichedActors.isNullOrEmpty() || curr.enrichedActors != actors)) {
                                            copy(launchData = curr.copy(enrichedActors = actors))
                                        } else {
                                            this
                                        }
                                    }
                                },
                                onMetadataLoaded = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, duration, _, actors, _, _ ->
                                    if (duration != null && duration > 0) {
                                        completionGuard.updateExpectedDurationMinutes(duration)
                                    }
                                    if (!actors.isNullOrEmpty()) {
                                        updateState {
                                            val curr = launchData
                                            if (curr != null && curr.enrichedActors.isNullOrEmpty()) {
                                                copy(launchData = curr.copy(enrichedActors = actors))
                                            } else {
                                                this
                                            }
                                        }
                                    }
                                },
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("EmbeddedPlayerViewModel", "Background metadata hydration failed: ${e.message}")
            }
        }

        val epId = hydratedData.history.episodeId
        if (hydratedData.links.isEmpty() && epId != null) {
            val apiName = hydratedData.loadResponse?.apiName ?: hydratedData.history.apiName
            val showUrl = hydratedData.loadResponse?.url ?: hydratedData.history.showUrl
            val provider = APIHolder.allProviders.firstOrNull {
                it.name == apiName && it.mainUrl.isNotBlank() && showUrl.startsWith(it.mainUrl)
            } ?: APIHolder.getApiFromNameNull(apiName)
            if (provider != null) {
                val targetEp = provider.newEpisode(epId) {
                    this.name = hydratedData.history.episodeName ?: hydratedData.history.showName
                    this.posterUrl = hydratedData.history.episodeThumbnailUrl ?: hydratedData.history.posterUrl
                    this.description = hydratedData.history.episodeDescription
                    this.season = hydratedData.history.season
                    this.episode = hydratedData.history.episode
                }
                updateState {
                    copy(
                        phase = PlayerPhase.Scraping,
                        targetEpisodeData = targetEp,
                        nextEpisodeLinks = emptyList(),
                        nextEpisodeSubtitles = hydratedData.subtitles,
                    )
                }
                scrapeCoordinator.startScrape(provider, epId, hydratedData, targetEp)
            } else {
                AppLogger.e("EmbeddedPlayerViewModel", "Provider not found for apiName='$apiName'. Cannot scrape links.")
                sendEffect(PlayerUiEffect.ShowError("Plugin not found — cannot load video."))
                sendEffect(PlayerUiEffect.ClosePlayer)
            }
        } else if (hydratedData.links.isNotEmpty()) {
            val selected = hydratedData.links.getOrNull(hydratedData.initialIndex)
                ?: PlayerStreamSelector.pickBestActiveLink(hydratedData.links, emptySet(), hydratedData.startPositionMs)
            if (selected != null) {
                updatePhase(PlayerPhase.Probing(selected, false))
            } else {
                updatePhase(PlayerPhase.Idle)
            }
        }
    }

    private fun loadEpisode(episode: Episode) {
        val currentTarget = uiState.value.targetEpisodeData
        val currentPhase = uiState.value.phase
        if (currentTarget?.data == episode.data && (currentPhase is PlayerPhase.Scraping || currentPhase is PlayerPhase.Probing)) {
            AppLogger.d("EmbeddedPlayerViewModel", "loadEpisode: already loading episode ${episode.data}, ignoring redundant dispatch")
            return
        }
        linkRetries.clear()
        val currentData = uiState.value.launchData ?: return

        val lockUnreleased = AppearanceConfig.lockUnreleasedEpisodes.value
        val hasCachedLinks = LinkCache.get(episode.data) != null
        val resolvedApiName = currentData.loadResponse?.apiName ?: currentData.history.apiName
        val lockStatus = PlayerEpisodeNavigator.checkEpisodeLock(
            episode = episode,
            resolvedApiName = resolvedApiName,
            lockUnreleased = lockUnreleased,
            hasCachedLinks = hasCachedLinks,
        )
        if (lockStatus.isLocked) {
            sendEffect(PlayerUiEffect.ShowToast(lockStatus.message ?: "Episode is locked."))
            return
        }

        flushCurrentEpisodeProgress(markCompletedIfNearEnd = true)
        lastSavedPositionSec = 0L

        countdownJob?.cancel()
        scrapeCoordinator.cancelAll()
        playerState.pause()
        playerState.reset()

        val isOffline = currentData.history.apiName in listOf("Offline", "Local") || File(episode.data).exists()
        if (isOffline) {
            val file = File(episode.data)
            if (file.exists()) {
                viewModelScope.launch(Dispatchers.IO) {
                    val offlineLink = newExtractorLink(
                        source = "Downloaded (Offline)",
                        name = episode.name ?: file.name,
                        url = file.absolutePath,
                        type = if (file.name.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                    val newLaunchData = currentData.copy(
                        links = listOf(offlineLink),
                        initialIndex = 0,
                        title = episode.name ?: file.name,
                        startPositionMs = 0L,
                        history = currentData.history.copy(
                            showUrl = file.absolutePath,
                            episodeId = file.absolutePath,
                            episode = episode.episode,
                            season = episode.season,
                            position = 0L,
                            duration = 0L,
                            updateTime = System.currentTimeMillis(),
                            episodeName = episode.name,
                        ),
                    )
                    resetCompletionGuard(newLaunchData)
                    updateState {
                        copy(
                            launchData = newLaunchData,
                            phase = PlayerPhase.Probing(offlineLink, stillScraping = false),
                            targetEpisodeData = null,
                            nextEpisodeLinks = listOf(offlineLink),
                            nextEpisodeError = null,
                        )
                    }
                }
                return
            }
        }

        updateState {
            copy(
                phase = PlayerPhase.Scraping,
                nextEpisodeError = null,
                nextEpisodeLinks = emptyList(),
                nextEpisodeSubtitles = emptyList(),
                targetEpisodeData = episode,
                failedLinks = emptyMap(),
            )
        }

        val isTorbox = currentData.history.apiName.equals("Torbox", ignoreCase = true)
        if (isTorbox) {
            viewModelScope.launch(Dispatchers.IO) {
                loadTorboxEpisode(episode, currentData)
            }
            return
        }

        val apiName = currentData.loadResponse?.apiName ?: currentData.history.apiName
        val provider = APIHolder.getApiFromNameNull(apiName)

        if (provider != null) {
            scrapeCoordinator.startScrape(provider, episode.data, currentData, episode)
        } else {
            updateState {
                copy(
                    targetEpisodeData = null,
                    phase = PlayerPhase.Idle,
                )
            }
        }
    }

    private suspend fun loadTorboxEpisode(episode: Episode, currentData: VideoLaunchData) {
        val apiKey = DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY)?.trim().orEmpty()
        if (apiKey.isBlank()) {
            AppLogger.e("EmbeddedPlayerViewModel", "Torbox API key not configured")
            sendEffect(PlayerUiEffect.ShowToast("Torbox API key not configured"))
            updateState { copy(targetEpisodeData = null, phase = PlayerPhase.Idle) }
            return
        }

        val resolved = PlayerEpisodeNavigator.resolveTorboxStream(apiKey, episode, currentData)
        if (resolved != null) {
            val (activeLink, videoTitle) = resolved
            val pastHistory = DesktopDataStore.getEpisodeWatched(
                parentId = currentData.history.parentId,
                episodeId = episode.data,
            )
            val startPos = if (pastHistory != null && pastHistory.duration > 0 && pastHistory.position < pastHistory.duration - 15) {
                pastHistory.position * 1000L
            } else {
                0L
            }
            val newHistory = currentData.history.copy(
                showUrl = activeLink.url,
                episodeId = episode.data,
                episode = episode.episode,
                season = episode.season,
                position = startPos / 1000L,
                duration = pastHistory?.duration ?: 0L,
                updateTime = System.currentTimeMillis(),
                episodeName = videoTitle,
            )
            val newLaunchData = currentData.copy(
                links = listOf(activeLink),
                initialIndex = 0,
                title = videoTitle,
                startPositionMs = startPos,
                history = newHistory,
            )
            resetCompletionGuard(newLaunchData)
            updateState {
                copy(
                    launchData = newLaunchData,
                    phase = PlayerPhase.Probing(activeLink, stillScraping = false),
                    targetEpisodeData = null,
                    nextEpisodeLinks = listOf(activeLink),
                    nextEpisodeError = null,
                    failedLinks = emptyMap(),
                )
            }
        } else {
            AppLogger.e("EmbeddedPlayerViewModel", "Failed to resolve Torbox stream for episode ${episode.data}")
            sendEffect(PlayerUiEffect.ShowToast("Failed to resolve stream for ${episode.name ?: "episode"}"))
            updateState {
                copy(
                    targetEpisodeData = null,
                    phase = PlayerPhase.Idle,
                )
            }
        }
    }

    private fun cancelLoading() {
        scrapeCoordinator.cancelLoading()
    }

    private fun cancelScraping() {
        scrapeCoordinator.cancelScraping()
    }

    private fun playLoadedEpisode() {
        linkRetries.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val currentData = uiState.value.launchData ?: return@launch
            val epData = uiState.value.targetEpisodeData ?: return@launch
            val currentLinks = uiState.value.nextEpisodeLinks
            if (currentLinks.isEmpty()) return@launch

            val pastHistory = DesktopDataStore.getEpisodeWatched(
                parentId = currentData.history.parentId,
                episodeId = epData.data,
            )

            val startPos = if (pastHistory != null && pastHistory.duration > 0 && pastHistory.position < pastHistory.duration - 15) {
                pastHistory.position * 1000L
            } else {
                0L
            }

            val newHistory = currentData.history.copy(
                episodeId = epData.data,
                episode = epData.episode,
                season = epData.season,
                position = startPos / 1000L,
                duration = pastHistory?.duration ?: 0L,
                episodeName = epData.name,
                episodeThumbnailUrl = epData.posterUrl ?: currentData.history.posterUrl,
                episodeDescription = epData.description ?: currentData.history.episodeDescription,
            )

            val newLaunchData = currentData.copy(
                links = currentLinks,
                subtitles = uiState.value.nextEpisodeSubtitles,
                history = newHistory,
                initialIndex = 0,
                startPositionMs = startPos,
                title = buildString {
                    append(newHistory.showName)
                    if (newHistory.season != null && newHistory.episode != null) {
                        append(" - S${newHistory.season}E${newHistory.episode}")
                    } else if (newHistory.episode != null) {
                        append(" - E${newHistory.episode}")
                    }
                },
            )
            resetCompletionGuard(newLaunchData)

            val best = PlayerStreamSelector.pickBestActiveLink(currentLinks, uiState.value.failedLinks.keys, startPos)

            updateState {
                copy(
                    nextEpisodeError = null,
                    targetEpisodeData = null,
                    nextEpisodeLinks = emptyList(),
                    nextEpisodeSubtitles = emptyList(),
                    launchData = newLaunchData,
                )
            }
            if (best != null) {
                updatePhase(PlayerPhase.Probing(best, false))
            } else {
                updatePhase(PlayerPhase.Idle)
            }
        }
    }

    private fun loadNextEpisode() {
        if (uiState.value.showInactivityPrompt) return
        linkRetries.clear()
        val episodes = uiState.value.episodes
        val currentData = uiState.value.launchData ?: return
        val currentEpId = currentData.history.episodeId
        val currentEpNum = currentData.history.episode
        val currentSeasonNum = currentData.history.season

        val nextEpisode = PlayerEpisodeNavigator.findNextEpisode(
            episodes = episodes,
            currentEpId = currentEpId,
            currentEpNum = currentEpNum,
            currentSeasonNum = currentSeasonNum,
        )

        if (nextEpisode != null) {
            val lockUnreleased = AppearanceConfig.lockUnreleasedEpisodes.value
            val apiName = currentData.loadResponse?.apiName ?: currentData.history.apiName
            val hasCachedLinks = LinkCache.get(nextEpisode.data) != null
            val lockStatus = PlayerEpisodeNavigator.checkEpisodeLock(
                episode = nextEpisode,
                resolvedApiName = apiName,
                lockUnreleased = lockUnreleased,
                hasCachedLinks = hasCachedLinks,
            )
            if (lockStatus.isLocked) {
                updateState {
                    copy(
                        phase = PlayerPhase.Idle,
                        countdownToNextEpisode = null,
                    )
                }
                sendEffect(PlayerUiEffect.ShowToast(lockStatus.message ?: "Next episode is locked."))
                return
            }
            loadEpisode(nextEpisode)
        } else {
            updateState {
                copy(
                    phase = PlayerPhase.Idle,
                    countdownToNextEpisode = null,
                )
            }
            sendEffect(PlayerUiEffect.ShowToast("No next episode found"))
        }
    }

    private fun loadPrevEpisode() {
        linkRetries.clear()
        val episodes = uiState.value.episodes
        val currentData = uiState.value.launchData ?: return
        val currentEpId = currentData.history.episodeId
        val currentEpNum = currentData.history.episode
        val currentSeasonNum = currentData.history.season

        val prevEpisode = PlayerEpisodeNavigator.findPrevEpisode(
            episodes = episodes,
            currentEpId = currentEpId,
            currentEpNum = currentEpNum,
            currentSeasonNum = currentSeasonNum,
        )

        if (prevEpisode != null) {
            loadEpisode(prevEpisode)
        } else {
            updateState {
                copy(
                    phase = PlayerPhase.Idle,
                    countdownToNextEpisode = null,
                )
            }
            sendEffect(PlayerUiEffect.ShowToast("No previous episode found"))
        }
    }
}
