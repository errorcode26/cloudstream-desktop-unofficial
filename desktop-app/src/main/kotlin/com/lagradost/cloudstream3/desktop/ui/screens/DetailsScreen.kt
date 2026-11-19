package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.request.crossfade
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.components.DesktopBackButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.DesktopThemeColors.*
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.WindowControlsPill
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.details.*
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ComposeDetailsScreen(
    onNavigate: (Config) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailsViewModel,
    autoPlay: Boolean = false,
) {
    val provider = viewModel.provider
    LaunchedEffect(viewModel) {
        viewModel.onEvent(DetailsUiEvent.OnLoad)
    }

    DisposableEffect(viewModel) {
        val unregister = com.lagradost.cloudstream3.desktop.ui.GlobalRefreshHandler.register {
            viewModel.onEvent(DetailsUiEvent.OnRefresh)
        }
        onDispose { unregister() }
    }

    val uiState by viewModel.uiState.collectAsState()
    val fetchFailed = uiState.fetchFailed
    val showHistory = uiState.watchHistory

    val response = uiState.response
    val fakeData = uiState.fakeData
    val isLoading = uiState.isLoading
    val error = uiState.error
    val isPanelOpen = uiState.isPanelOpen
    val enrichmentPhase = uiState.enrichmentPhase
    val activeLinkData = uiState.activeLinkData
    val screenshots = uiState.screenshots
    val heroBackgroundBlurEnabled by AppearanceConfig.heroBackgroundBlurEnabled.collectAsState()
    val heroBackdropBlurRadius by AppearanceConfig.heroBackdropBlurRadius.collectAsState()
    val heroBackdropDarkening by AppearanceConfig.heroBackdropDarkening.collectAsState()
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()

    val playVideo = com.lagradost.cloudstream3.desktop.ui.LocalVideoPlayer.current

    val handlePlay: (com.lagradost.cloudstream3.Episode) -> Unit = remember(viewModel) {
        { ep -> viewModel.onEvent(DetailsUiEvent.OnPlayEpisode(ep)) }
    }

    val handleDownload: (com.lagradost.cloudstream3.Episode) -> Unit = remember(viewModel) {
        { ep -> viewModel.onEvent(DetailsUiEvent.OnDownloadEpisode(ep)) }
    }

    val handleToggleWatched: (com.lagradost.cloudstream3.Episode, Boolean) -> Unit = remember(viewModel) {
        { ep, isWatched -> viewModel.onEvent(DetailsUiEvent.OnToggleEpisodeWatched(ep, isWatched)) }
    }

    val handleToggleSeasonWatched: (List<com.lagradost.cloudstream3.Episode>, Boolean) -> Unit = remember(viewModel) {
        { episodes, isWatched -> viewModel.onEvent(DetailsUiEvent.OnToggleSeasonWatched(episodes, isWatched)) }
    }
    val handleRemoveEpisodeWatched: (com.lagradost.cloudstream3.Episode) -> Unit = remember(viewModel) {
        { ep -> viewModel.onEvent(DetailsUiEvent.OnRemoveEpisodeWatched(ep)) }
    }
    val handleToggleEpisodesStackedView: (Boolean) -> Unit = remember(viewModel) {
        { isStacked -> viewModel.onEvent(DetailsUiEvent.OnToggleEpisodesStackedView(isStacked)) }
    }
    val handleSetEpisodeViewMode: (Int) -> Unit = remember(viewModel) {
        { viewMode -> viewModel.onEvent(DetailsUiEvent.OnSetEpisodeViewMode(viewMode)) }
    }

    LaunchedEffect(viewModel.effectFlow) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is DetailsUiEffect.NavigateToPlayer -> playVideo(effect.launchData)
                is DetailsUiEffect.ShowErrorDialog -> viewModel.onEvent(DetailsUiEvent.OnShowPlaybackError(effect.message))
                is DetailsUiEffect.ShowToast -> com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showInfo(effect.message)
            }
        }
    }

    com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog(
        show = uiState.playbackError != null,
        onDismissRequest = { viewModel.onEvent(DetailsUiEvent.OnDismissPlaybackError) },
        title = { Text("Playback Failed") },
        text = { Text(uiState.playbackError ?: "Unknown error") },
        confirmButton = {
            TextButton(onClick = { viewModel.onEvent(DetailsUiEvent.OnDismissPlaybackError) }) {
                Text("OK")
            }
        },
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        LaunchedEffect(response, uiState.hasAutoPlayed) {
            if (!uiState.hasAutoPlayed && response != null) {
                if (autoPlay) {
                    viewModel.onEvent(DetailsUiEvent.OnRequestAutoPlay)
                } else {
                    // Mark as handled so it never triggers if the state recombines
                    viewModel.onEvent(DetailsUiEvent.OnMarkAutoPlayHandled)
                }
            }
        }
        val screensaverEnabled by AppearanceConfig.screensaverEnabled.collectAsState()
        val screenshotsList = screenshots ?: emptyList()
        var currentScreenshotIndex by remember { mutableStateOf(-1) }

        LaunchedEffect(screensaverEnabled, screenshotsList) {
            if (screenshotsList.isEmpty()) {
                currentScreenshotIndex = -1
                return@LaunchedEffect
            }
            if (currentScreenshotIndex < 0) {
                currentScreenshotIndex = 0
            }
            if (screensaverEnabled) {
                while (isActive) {
                    kotlinx.coroutines.delay(10_000)
                    currentScreenshotIndex = (currentScreenshotIndex + 1) % screenshotsList.size
                }
            }
        }

        val baseBgUrl = remember(response?.backgroundPosterUrl, response?.posterUrl, uiState.enrichedBackdropUrl, provider, viewModel.preloadedBg, viewModel.preloadedPoster) {
            uiState.enrichedBackdropUrl?.takeIf { it.isNotBlank() }
                ?: provider.fixUrlNull(response?.backgroundPosterUrl)?.takeIf { it.isNotBlank() }
                ?: provider.fixUrlNull(response?.posterUrl)?.takeIf { it.isNotBlank() }
                ?: viewModel.preloadedBg?.takeIf { it.isNotBlank() }
                ?: viewModel.preloadedPoster?.takeIf { it.isNotBlank() }
        }

        val activeBgUrl = if (currentScreenshotIndex >= 0 && screenshotsList.isNotEmpty()) {
            screenshotsList[currentScreenshotIndex]
        } else {
            baseBgUrl
        }

        val hazeState = remember { HazeState() }
        val isAmoled = LocalDesktopTheme.current.isAmoled

        val isDrawerOpen = isPanelOpen || activeLinkData != null

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(isDrawerOpen) {
            if (isDrawerOpen) {
                try { focusRequester.requestFocus() } catch (_: Throwable) {}
            }
        }

        DisposableEffect(isDrawerOpen) {
            if (!isDrawerOpen) return@DisposableEffect onDispose {}

            val listener = java.awt.event.AWTEventListener { event ->
                if (event is java.awt.event.MouseEvent && event.id == java.awt.event.MouseEvent.MOUSE_RELEASED && event.button == 4) {
                    viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel)
                    event.consume()
                }
            }
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                listener,
                java.awt.AWTEvent.MOUSE_EVENT_MASK,
            )
            onDispose {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if ((event.key == Key.Escape || event.key == Key.Back || event.key == Key.Backspace) && event.type == KeyEventType.KeyUp) {
                        if (isDrawerOpen) {
                            viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                .pointerInput(isDrawerOpen) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Release && event.button == PointerButton.Back) {
                                if (isDrawerOpen) {
                                    viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                },
        ) {
            val rootMaxWidth = maxWidth
            val currentStage = remember(isLoading, response, fetchFailed) {
                when {
                    fetchFailed && response == null -> com.lagradost.cloudstream3.desktop.ui.components.ScreenStage.ERROR
                    response != null -> com.lagradost.cloudstream3.desktop.ui.components.ScreenStage.CONTENT
                    isLoading -> com.lagradost.cloudstream3.desktop.ui.components.ScreenStage.LOADING
                    else -> com.lagradost.cloudstream3.desktop.ui.components.ScreenStage.ERROR
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
            ) {
                if (heroBackgroundBlurEnabled && !performanceModeEnabled && activeBgUrl != null) {
                    androidx.compose.animation.Crossfade(
                        targetState = activeBgUrl,
                        animationSpec = androidx.compose.animation.core.tween(2000),
                        label = "global_backdrop_crossfade",
                        modifier = Modifier.fillMaxSize(),
                    ) { targetBgUrl ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            coil3.compose.AsyncImage(
                                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                                    .data(targetBgUrl)
                                    .size(640, 360)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(heroBackdropBlurRadius.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                            )
                            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = heroBackdropDarkening)))
                        }
                    }
                }
                val enableDownloadButtons = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(com.lagradost.common.storage.DesktopDataStore.PREF_ENABLE_DOWNLOAD_BUTTONS) ?: true

                com.lagradost.cloudstream3.desktop.ui.components.ScreenStateCrossfade(
                    stage = currentStage,
                    modifier = Modifier.fillMaxSize(),
                    loadingContent = {
                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsLoadingView(
                            onBack = onBack,
                            preloadedName = viewModel.preloadedName,
                            preloadedPoster = viewModel.preloadedPoster,
                            preloadedBg = viewModel.preloadedBg,
                            providerName = provider.name,
                        )
                    },
                    content = {
                        val activeData = response ?: fakeData
                        if (activeData != null) {
                            val handleBack = {
                                if (isPanelOpen) {
                                    viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel)
                                } else {
                                    onBack()
                                }
                            }
                            DetailsContent(
                                onNavigate = onNavigate,
                                onBack = handleBack,
                                provider = provider,
                                data = activeData,
                                screenshots = screenshots,
                                enrichmentPhase = enrichmentPhase,
                                isLoading = false,
                                onPlay = handlePlay,
                                onDownload = handleDownload,
                                enableDownloadButtons = enableDownloadButtons,
                                onToggleWatched = handleToggleWatched,
                                onToggleSeasonWatched = handleToggleSeasonWatched,
                                onRemoveEpisodeWatched = handleRemoveEpisodeWatched,
                                onToggleEpisodesStackedView = handleToggleEpisodesStackedView,
                                onSetEpisodeViewMode = handleSetEpisodeViewMode,
                                dynamicColorEnabled = heroBackgroundBlurEnabled,
                                uiState = uiState,
                                showHistory = showHistory,
                                activeBgUrl = activeBgUrl,
                                onEvent = viewModel::onEvent,
                            )
                        }
                    },
                    errorContent = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = LocalDesktopTheme.current.SurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                                shadowElevation = 0.dp,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .widthIn(max = 560.dp)
                                    .fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Error",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Failed to load details from ${provider.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    if (!error.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = error,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.onEvent(DetailsUiEvent.OnRefresh) },
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Retry")
                                        }
                                        Button(
                                            onClick = onBack,
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text("Go Back")
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = isPanelOpen,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isAmoled) 0.70f else 0.55f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel) },
                ) {
                    val panelWidth = if (rootMaxWidth >= 1000.dp) rootMaxWidth * 0.50f else (rootMaxWidth * 0.60f).coerceIn(480.dp, rootMaxWidth * 0.85f)
                    val leftZoneWidth = rootMaxWidth - panelWidth
                    if (leftZoneWidth >= 360.dp && activeLinkData != null) {
                        val activeHistory = activeLinkData.third
                        val allEpisodes = remember(response) {
                            when (response) {
                                is com.lagradost.cloudstream3.TvSeriesLoadResponse -> response.episodes
                                is com.lagradost.cloudstream3.AnimeLoadResponse -> response.episodes.values.flatten()
                                else -> emptyList()
                            }
                        }
                        val activeEpisode = remember(activeLinkData, allEpisodes) {
                            allEpisodes.firstOrNull { it.matchesHistory(activeHistory) }
                                ?: allEpisodes.firstOrNull { it.data == activeLinkData.second }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(leftZoneWidth)
                                .align(Alignment.CenterStart)
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FloatingEpisodeCard(
                                provider = provider,
                                response = response,
                                activeEpisode = activeEpisode,
                                activeHistory = activeHistory,
                                activeLinkData = activeLinkData,
                                uiState = uiState,
                                isAmoled = isAmoled,
                                cardWidth = minOf(560.dp, leftZoneWidth - 48.dp),
                            )
                        }
                    }
                }
            }

            if (activeLinkData != null) {
                val panelWidth = if (rootMaxWidth >= 1000.dp) rootMaxWidth * 0.50f else (rootMaxWidth * 0.60f).coerceIn(480.dp, rootMaxWidth * 0.85f)
                val drawerShape = RoundedCornerShape(20.dp)
                val offsetX by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (isPanelOpen) 0.dp else panelWidth + 20.dp,
                    animationSpec = tween(300),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = offsetX)
                        .fillMaxHeight()
                        .width(panelWidth)
                        .padding(top = 56.dp, bottom = 16.dp, end = 16.dp)
                        .shadow(28.dp, shape = drawerShape, ambientColor = Color.Transparent, spotColor = Color.Black.copy(alpha = 0.5f))
                        .clip(drawerShape)
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.18f),
                                    Color.White.copy(alpha = 0.05f),
                                ),
                            ),
                            shape = drawerShape,
                        )
                        .then(if (!isAmoled && !performanceModeEnabled) Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 40.dp
                            backgroundColor = Color(0xFF0D0D17)
                        }
                        else Modifier)
                        .background(
                            if (isAmoled || performanceModeEnabled) {
                                Color(0xFF09090E).copy(alpha = 0.95f)
                            } else {
                                Color(0xFF0D0D17).copy(alpha = 0.70f)
                            },
                        ),
                ) {
                    activeLinkData.let { (linkProvider, linkUrl, linkHistory) ->
                        val currentSeason = linkHistory.season ?: uiState.selectedSeason
                        val seasonCast = if (currentSeason != null && currentSeason > 0) {
                            uiState.seasonCredits[currentSeason]
                        } else {
                            null
                        }
                        val hasAnimeDualCast = uiState.enrichedActors?.any { it.voiceActor != null } == true
                        val effectiveActors = if (hasAnimeDualCast) uiState.enrichedActors else (seasonCast ?: uiState.enrichedActors ?: response?.actors)
                        LinksSidePanel(
                            provider = linkProvider,
                            dataUrl = linkUrl,
                            history = linkHistory,
                            loadResponse = response, // Passed from ComposeDetailsScreen
                            enrichedActors = effectiveActors,
                            enrichedLogoUrl = uiState.enrichedLogoUrl,
                            enrichedBackdropUrl = uiState.enrichedBackdropUrl,
                            onClose = { viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel) },
                        )
                    }
                }
            }

            // Hoisted Floating Top Action Layer (Corner-anchored, remains visible and clickable above scrim & drawer)
            if (currentStage != com.lagradost.cloudstream3.desktop.ui.components.ScreenStage.LOADING) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 7.dp)
                        .zIndex(100f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val handleBack = {
                        if (isDrawerOpen) {
                            viewModel.onEvent(DetailsUiEvent.OnCloseLinksPanel)
                        } else {
                            onBack()
                        }
                    }
                    DesktopBackButton(onClick = handleBack)

                    WindowControlsPill(
                        isHome = false,
                        isCompact = rootMaxWidth < 600.dp,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DetailsContent(
    onNavigate: (Config) -> Unit,
    onBack: () -> Unit,
    provider: MainAPI,
    data: LoadResponse,
    screenshots: List<String>?,
    enrichmentPhase: com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase,
    isLoading: Boolean = false,
    onPlay: (com.lagradost.cloudstream3.Episode) -> Unit,
    onDownload: ((com.lagradost.cloudstream3.Episode) -> Unit)? = null,
    enableDownloadButtons: Boolean = false,
    onToggleWatched: (com.lagradost.cloudstream3.Episode, Boolean) -> Unit,
    onToggleSeasonWatched: (List<com.lagradost.cloudstream3.Episode>, Boolean) -> Unit,
    onRemoveEpisodeWatched: (com.lagradost.cloudstream3.Episode) -> Unit,
    onToggleEpisodesStackedView: (Boolean) -> Unit,
    onSetEpisodeViewMode: ((Int) -> Unit)? = null,
    dynamicColorEnabled: Boolean = false,
    uiState: com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState? = null,
    showHistory: Map<String, com.lagradost.common.storage.WatchHistory> = emptyMap(),
    activeBgUrl: String? = null,
    onEvent: (DetailsUiEvent) -> Unit = {},
) {
    val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val latestHistory = remember(data.url, uiState?.watchHistory) {
        uiState?.watchHistory?.values?.maxByOrNull { it.updateTime }
    }

    var trailersExpanded by remember { mutableStateOf(true) }
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }

    val isMovieLike = remember(data) {
        data is com.lagradost.cloudstream3.MovieLoadResponse || data is com.lagradost.cloudstream3.TorrentLoadResponse || data is com.lagradost.cloudstream3.LiveStreamLoadResponse ||
            (data is com.lagradost.cloudstream3.TvSeriesLoadResponse && data.episodes.size == 1 && data.type == com.lagradost.cloudstream3.TvType.Movie) ||
            (data is com.lagradost.cloudstream3.AnimeLoadResponse && data.episodes.values.sumOf { it.size } == 1 && data.type == com.lagradost.cloudstream3.TvType.AnimeMovie)
    }

    val availableSeasons = remember(data) {
        val rawList = when (data) {
            is com.lagradost.cloudstream3.TvSeriesLoadResponse -> data.episodes.mapNotNull { it.season }.distinct()
            is com.lagradost.cloudstream3.AnimeLoadResponse -> data.episodes.values.flatten().mapNotNull { it.season }.distinct()
            else -> emptyList()
        }
        val list = rawList.filter { it > 0 }.sorted() + rawList.filter { it <= 0 }.sorted()
        if (list.isEmpty() && (data is com.lagradost.cloudstream3.TvSeriesLoadResponse || data is com.lagradost.cloudstream3.AnimeLoadResponse)) {
            listOf(1)
        } else {
            list
        }
    }
    val currentSeason = (
        uiState?.selectedSeason?.takeIf { it in availableSeasons }
            ?: latestHistory?.season?.takeIf { it in availableSeasons }
            ?: availableSeasons.firstOrNull { it > 0 }
            ?: availableSeasons.firstOrNull()
            ?: 1
        )

    val detailsSectionOrder by AppearanceConfig.detailsSectionOrder.collectAsState()
    val detailsDisabledSections by AppearanceConfig.detailsDisabledSections.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        val isWindowCompact = viewportWidth < 600.dp
        val isLinksPanelOpen = uiState?.isPanelOpen == true
        val backdropDimModifier = if (isLinksPanelOpen) {
            Modifier.blur(45.dp).graphicsLayer { alpha = 0.20f }
        } else {
            Modifier
        }

        DetailsBackdrop(
            provider = provider,
            data = data,
            scrollState = scrollState,
            enrichmentPhase = enrichmentPhase,
            modifier = Modifier.fillMaxSize(),
            dynamicColorEnabled = dynamicColorEnabled,
            uiState = uiState,
            activeBgUrl = activeBgUrl,
        )

        val remoteIcons by com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.remotePluginIcons.collectAsState()

        val heroAction: @Composable (Modifier) -> Unit = { modifier ->
            com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsPlayButton(
                modifier = modifier,
                data = data,
                provider = provider,
                latestHistory = latestHistory,
                onPlay = onPlay,
            )
        }

        val downloadAction: (@Composable (Modifier) -> Unit)? = if (enableDownloadButtons && onDownload != null && isMovieLike) {
            { modifier ->
                com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsDownloadButton(
                    modifier = modifier,
                    data = data,
                    provider = provider,
                    latestHistory = latestHistory,
                    onDownload = onDownload,
                )
            }
        } else {
            null
        }

        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isLinksPanelOpen) Modifier.blur(45.dp).graphicsLayer { alpha = 0.20f } else Modifier),
            contentPadding = PaddingValues(
                bottom = 32.dp,
            ),
        ) {
            item(key = "HeroAndTabs") {
                val heroMinHeight = if (isWindowCompact) {
                    null
                } else if (isMovieLike) {
                    minOf(740.dp, maxOf(500.dp, viewportHeight * 0.68f))
                } else {
                    minOf(720.dp, maxOf(480.dp, viewportHeight * 0.65f))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .run {
                            if (heroMinHeight != null) this.heightIn(min = heroMinHeight) else this
                        }
                        .then(backdropDimModifier),
                    contentAlignment = if (isWindowCompact) Alignment.TopStart else Alignment.BottomStart,
                ) {
                    DetailsMetadata(
                        provider = provider,
                        data = data,
                        heroAction = heroAction,
                        downloadAction = downloadAction,
                        enrichmentPhase = enrichmentPhase,
                        isLoading = isLoading,
                        uiState = uiState,
                        screenshots = screenshots,
                        onPhotosClick = {
                            coroutineScope.launch { scrollState.animateScrollToItem(1) }
                        },
                        onCastClick = {
                            coroutineScope.launch { scrollState.animateScrollToItem(2) }
                        },
                        onActorClick = { actor ->
                            val searchName = actor.voiceActor?.name?.takeIf { it.isNotBlank() } ?: actor.actor.name
                            onNavigate(Config.Person(name = searchName, image = actor.actor.image, tmdbId = null))
                        },
                        onTrailerClick = { url ->
                            val trailer = uiState?.enrichedTrailers?.find { it.url == url }
                                ?: com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerData(id = url, name = "${data.name} Official Trailer", url = url)
                            onEvent(DetailsUiEvent.OnSelectTrailer(trailer))
                        },
                        onEvent = onEvent,
                    )
                }
            }

            detailsSectionOrder.filter { it !in detailsDisabledSections }.forEach { sectionKey ->
                when (sectionKey) {
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.EPISODES -> {
                        if (!isMovieLike) {
                            item(key = "Episodes") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsEpisodeSection(
                                            provider = provider,
                                            data = data,
                                            showHistory = showHistory,
                                            latestHistory = latestHistory,
                                            isMovieLike = isMovieLike,
                                            isLoading = isLoading,
                                            uiState = uiState,
                                            enableDownloadButtons = enableDownloadButtons,
                                            onPlay = onPlay,
                                            onDownload = onDownload,
                                            onToggleWatched = onToggleWatched,
                                            onToggleSeasonWatched = onToggleSeasonWatched,
                                            onRemoveEpisodeWatched = onRemoveEpisodeWatched,
                                            onToggleEpisodesStackedView = onToggleEpisodesStackedView,
                                            onSetEpisodeViewMode = onSetEpisodeViewMode ?: {},
                                            selectedSeason = currentSeason,
                                            onSeasonChange = { onEvent(DetailsUiEvent.OnSelectSeason(it)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.CAST -> {
                        item(key = "Cast") {
                            BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                    com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsCastSection(
                                        data = data,
                                        provider = provider,
                                        uiState = uiState,
                                        onActorClick = { actor ->
                                            val searchName = actor.voiceActor?.name?.takeIf { it.isNotBlank() } ?: actor.actor.name
                                            onNavigate(Config.Person(name = searchName, image = actor.actor.image, tmdbId = null))
                                        },
                                        onNavigate = onNavigate,
                                        horizontalPadding = hPadding,
                                        selectedSeason = uiState?.selectedSeason ?: currentSeason,
                                        seasonCredits = uiState?.seasonCredits,
                                        onSeasonChange = { onEvent(DetailsUiEvent.OnSelectSeason(it)) },
                                        availableSeasons = availableSeasons,
                                    )
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.INFO -> {
                        if (com.lagradost.cloudstream3.desktop.ui.screens.details.hasDetailsStats(uiState, data)) {
                            item(key = "Info") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    val isMovie = data.type == TvType.Movie || data.type == TvType.AnimeMovie
                                    val sectionTitle = if (isMovie) "Movie Details" else "Show Details"
                                    Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                        Text(
                                            sectionTitle,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = hPadding).padding(bottom = 16.dp),
                                        )
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsStatsSection(
                                            data = data,
                                            uiState = uiState,
                                            modifier = Modifier.padding(horizontal = hPadding),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.NETWORKS -> {
                        if (com.lagradost.cloudstream3.desktop.ui.screens.details.hasNetworks(uiState)) {
                            item(key = "Networks") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsNetworksSection(
                                            uiState = uiState,
                                            modifier = Modifier.padding(horizontal = hPadding),
                                            onCompanyClick = { comp ->
                                                onNavigate(
                                                    Config.Studio(
                                                        name = comp.name,
                                                        companyId = comp.id.takeIf { it > 0 },
                                                        logoUrl = comp.logoUrl,
                                                        originCountry = comp.originCountry,
                                                        isNetwork = true,
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.STUDIOS -> {
                        if (com.lagradost.cloudstream3.desktop.ui.screens.details.hasStudios(uiState)) {
                            item(key = "Studios") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsStudiosSection(
                                            uiState = uiState,
                                            modifier = Modifier.padding(horizontal = hPadding),
                                            onCompanyClick = { comp ->
                                                onNavigate(
                                                    Config.Studio(
                                                        name = comp.name,
                                                        companyId = comp.id.takeIf { it > 0 },
                                                        logoUrl = comp.logoUrl,
                                                        originCountry = comp.originCountry,
                                                        isNetwork = comp.isNetwork,
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.TRAILERS -> {
                        val enrichedTrailers = uiState?.enrichedTrailers ?: emptyList()
                        if (enrichedTrailers.isNotEmpty()) {
                            item(key = "Trailers") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsTrailersSection(
                                            trailers = enrichedTrailers,
                                            trailersExpanded = trailersExpanded,
                                            onToggleExpand = { trailersExpanded = !trailersExpanded },
                                            onTrailerClick = { url ->
                                                val trailer = enrichedTrailers.find { it.url == url }
                                                    ?: com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerData(id = url, name = "${data.name} Official Trailer", url = url)
                                                onEvent(DetailsUiEvent.OnSelectTrailer(trailer))
                                            },
                                            horizontalPadding = hPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.SCREENSHOTS -> {
                        if (!screenshots.isNullOrEmpty()) {
                            item(key = "Screenshots") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsScreenshotsSection(
                                            screenshots = screenshots,
                                            onThumbnailClick = { lightboxIndex = it },
                                            horizontalPadding = hPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.COLLECTION -> {
                        val collName = uiState?.enrichedCollectionName
                        val collBg = uiState?.enrichedCollectionBackdrop
                        val collItems = uiState?.enrichedCollectionItems ?: emptyList()
                        if (!collName.isNullOrBlank()) {
                            item(key = "Collection") {
                                Box(modifier = Modifier.animateItem().fillMaxWidth().padding(top = 48.dp).then(backdropDimModifier)) {
                                    com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsCollectionSection(
                                        collName = collName,
                                        collBg = collBg,
                                        collItems = collItems,
                                        provider = provider,
                                        onNavigate = onNavigate,
                                    )
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.RECOMMENDATIONS -> {
                        val validRecs = data.recommendations?.filterIsInstance<com.lagradost.cloudstream3.SearchResponse>()
                            ?.filter { com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(it.apiName) != null } ?: emptyList()
                        if (validRecs.isNotEmpty()) {
                            item(key = "Recommendations") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp).then(backdropDimModifier)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsRecommendationsSection(
                                            validRecs = validRecs,
                                            onNavigate = onNavigate,
                                            horizontalPadding = hPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey.REVIEWS -> {
                        if (uiState?.enrichedReviews?.isNotEmpty() == true) {
                            item(key = "Reviews") {
                                BoxWithConstraints(modifier = Modifier.animateItem().fillMaxWidth()) {
                                    val hPadding = if (maxWidth < 1100.dp) 24.dp else 64.dp
                                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp).then(backdropDimModifier)) {
                                        com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsReviewsSection(
                                            reviews = uiState.enrichedReviews,
                                            horizontalPadding = hPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "Spacer") {
                Spacer(modifier = Modifier.height(96.dp))
            }
        }

        DesktopVerticalScrollbar(
            listState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )

        // Fullscreen screenshot lightbox — must be at outer Box level to cover the whole window
        val lightboxScreenshots = screenshots
        var lastValidLightboxIndex by remember { mutableStateOf(0) }
        LaunchedEffect(lightboxIndex) {
            lightboxIndex?.let {
                lastValidLightboxIndex = it
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = lightboxIndex != null && !lightboxScreenshots.isNullOrEmpty(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.95f),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing), targetScale = 0.97f),
        ) {
            if (!lightboxScreenshots.isNullOrEmpty()) {
                val safeIndex = (lightboxIndex ?: lastValidLightboxIndex).coerceIn(0, lightboxScreenshots.lastIndex)
                com.lagradost.cloudstream3.desktop.ui.screens.details.ScreenshotLightbox(
                    screenshots = lightboxScreenshots,
                    initialIndex = safeIndex,
                    onDismiss = { lightboxIndex = null },
                )
            }
        }

        if (uiState?.pendingExternalUrl != null) {
            com.lagradost.cloudstream3.desktop.utils.ExternalLinkConfirmationDialog(
                url = uiState.pendingExternalUrl,
                onDismiss = { onEvent(DetailsUiEvent.OnSetPendingExternalUrl(null)) },
            )
        }

        com.lagradost.cloudstream3.desktop.ui.screens.details.dialogs.TrailerPlayerDialog(
            trailer = uiState?.activeTrailer,
            onDismissRequest = { onEvent(DetailsUiEvent.OnSelectTrailer(null)) },
        )
    }
}
