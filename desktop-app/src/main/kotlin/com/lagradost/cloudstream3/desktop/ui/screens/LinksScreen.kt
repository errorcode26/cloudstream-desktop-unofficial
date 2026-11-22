package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.torrent.TorrentPlayability
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.DesktopBackButton
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.WindowControlsPill
import com.lagradost.cloudstream3.desktop.ui.screens.details.FloatingEpisodeCard
import com.lagradost.cloudstream3.desktop.ui.screens.details.matchesHistory
import com.lagradost.cloudstream3.desktop.ui.screens.links.LinksViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.links.StreamFilterHelper
import com.lagradost.cloudstream3.desktop.ui.screens.links.StreamLinkCard
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.FormatOption
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.LinksDialogHost
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.LinksHeaderBar
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.LinksScrapingProgress
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.LinksSourceFilterRow
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.LinksSpecQualityFilterRow
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.QualityOption
import com.lagradost.cloudstream3.desktop.ui.screens.links.components.SourceOption
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.LinksUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.LinksUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.StreamSpecFilter
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.VlcPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LinksSidePanel(
    provider: MainAPI,
    dataUrl: String,
    history: WatchHistory,
    loadResponse: LoadResponse?,
    enrichedActors: List<ActorData>? = null,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = remember { LinksViewModel() }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.dispose()
        }
    }

    val vlcPlayer = remember { VlcPlayer() }
    DisposableEffect(vlcPlayer) {
        onDispose {
            vlcPlayer.destroy()
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val links = uiState.links
    val statusText = uiState.statusText
    val isScraping = uiState.isScraping

    val playVideo = com.lagradost.cloudstream3.desktop.ui.LocalVideoPlayer.current
    val isVideoPlayerActive = com.lagradost.cloudstream3.desktop.ui.LocalVideoPlayerActive.current
    val selectedPlayer = uiState.preferredPlayer
    val isLaunchingPlayer = uiState.isLaunchingPlayer
    val playerLaunchError = uiState.playerLaunchError
    val currentPlayingUrl = uiState.currentPlayingUrl
    val selectedQuality = uiState.selectedQuality
    val selectedFormat = uiState.selectedFormat
    val selectedSource = uiState.selectedSource
    val isP2pEnabled = uiState.isP2pEnabled
    val selectedSpecFilter = uiState.selectedSpecFilter

    var showPriorityDialog by remember { mutableStateOf(false) }
    var pendingTorrentLink by remember { mutableStateOf<Pair<com.lagradost.cloudstream3.utils.ExtractorLink, Boolean>?>(null) }
    var showP2pDisclaimer by remember { mutableStateOf(false) }
    var showTorrServerInstall by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is LinksUiEffect.ShowToast -> {
                    AppToastManager.showInfo(effect.message)
                }
                is LinksUiEffect.LaunchVlc -> {
                    coroutineScope.launch {
                        val result = vlcPlayer.play(effect.link, effect.displayTitle, effect.subtitles, effect.startMs)
                        if (!result.isSuccess) {
                            viewModel.onEvent(LinksUiEvent.OnStatusTextChanged("Could not start player."))
                            viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished("Could not start player."))
                        } else {
                            viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null))
                        }
                    }
                }
                is LinksUiEffect.LaunchEmbeddedPlayer -> {
                    viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null))
                    playVideo(effect.launchData)
                }
            }
        }
    }

    val displayTitle = remember(history) {
        buildString {
            append(history.showName)
            if (history.season != null && history.episode != null) {
                append(" - S${history.season}E${history.episode}")
            } else if (history.episode != null) {
                append(" - E${history.episode}")
            }
        }
    }

    val handleConfirmedTorrentAction = remember(viewModel, displayTitle, history, loadResponse, currentPlayingUrl, enrichedActors, enrichedLogoUrl, enrichedBackdropUrl) {
        { targetLink: com.lagradost.cloudstream3.utils.ExtractorLink, isDownload: Boolean ->
            if (isDownload) {
                viewModel.onEvent(LinksUiEvent.OnSetLinkToDownload(targetLink))
            } else {
                viewModel.onEvent(
                    LinksUiEvent.OnPlayLink(
                        link = targetLink,
                        displayTitle = displayTitle,
                        history = history,
                        loadResponse = loadResponse,
                        currentPlayingUrl = currentPlayingUrl,
                        enrichedActors = enrichedActors,
                        enrichedLogoUrl = enrichedLogoUrl,
                        enrichedBackdropUrl = enrichedBackdropUrl,
                    ),
                )
            }
        }
    }

    val checkAndExecuteTorrent = remember(handleConfirmedTorrentAction) {
        { targetLink: com.lagradost.cloudstream3.utils.ExtractorLink, isDownload: Boolean ->
            when (DesktopTorrentEngine.checkTorrentPlayability(targetLink)) {
                TorrentPlayability.NEEDS_P2P_ENABLED -> {
                    pendingTorrentLink = targetLink to isDownload
                    showP2pDisclaimer = true
                }
                TorrentPlayability.NEEDS_TORRSERVER_INSTALL -> {
                    pendingTorrentLink = targetLink to isDownload
                    showTorrServerInstall = true
                }
                TorrentPlayability.READY -> {
                    handleConfirmedTorrentAction(targetLink, isDownload)
                }
            }
        }
    }

    val installedAddons by StremioAddonManager.addons.collectAsState()
    val addonNames = remember(installedAddons) { installedAddons.map { it.name } }
    val isAddonStream: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Boolean = remember(addonNames) {
        { link -> StreamFilterHelper.isAddonStream(link, addonNames) }
    }

    val getAddonName: (com.lagradost.cloudstream3.utils.ExtractorLink) -> String = remember(addonNames, provider.name) {
        { link -> StreamFilterHelper.getAddonName(link, provider.name, addonNames) }
    }

    val sourceTabs = remember(links, provider.name) {
        val pluginCount = links.count { !isAddonStream(it) }
        val addonLinks = links.filter { isAddonStream(it) }
        val addonGroups = addonLinks.groupBy { getAddonName(it) }

        val list = mutableListOf<SourceOption>()
        list.add(SourceOption(name = "All Sources", count = links.size, isAddon = false))
        if (pluginCount > 0) {
            list.add(SourceOption(name = provider.name, count = pluginCount, isAddon = false))
        }
        addonGroups.forEach { (addonName, aLinks) ->
            list.add(SourceOption(name = addonName, count = aLinks.size, isAddon = true))
        }
        list
    }

    val availableQualities = remember(links) {
        val count4k = links.count { com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(it) >= 2160 }
        val count1080 = links.count { com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(it) in 1080..2159 }
        val count720 = links.count { com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(it) in 720..1079 }
        val countSd = links.count { com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(it) in 1..719 }

        val list = mutableListOf<QualityOption>()
        if (count4k > 0) list.add(QualityOption(2160, "4K UHD", count4k))
        if (count1080 > 0) list.add(QualityOption(1080, "1080p FHD", count1080))
        if (count720 > 0) list.add(QualityOption(720, "720p HD", count720))
        if (countSd > 0) list.add(QualityOption(480, "SD (<720p)", countSd))
        list
    }

    val availableSpecs = remember(links) {
        val totalDebrid = links.count { link ->
            link.name.contains("⚡") || link.name.contains("[TB]") || link.name.contains("[RD]") ||
                link.name.contains("[AD]") || link.name.contains("[PM]") || link.name.contains("[DL]") ||
                link.name.contains("torbox", ignoreCase = true) || link.name.contains("real-debrid", ignoreCase = true) ||
                link.name.contains("debrid", ignoreCase = true)
        }
        val totalRemux = links.count { it.name.contains("remux", ignoreCase = true) }
        val totalBluRay = links.count { Regex("""\b(bluray|blu-ray|bdrip|brrip)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
        val totalWebDl = links.count { Regex("""\b(web-dl|webdl|webrip|web-rip)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }

        val list = mutableListOf<Pair<StreamSpecFilter, Int>>()
        list.add(StreamSpecFilter.ALL to links.size)
        if (totalDebrid > 0) list.add(StreamSpecFilter.DEBRID to totalDebrid)
        if (totalRemux > 0) list.add(StreamSpecFilter.REMUX to totalRemux)
        if (totalBluRay > 0) list.add(StreamSpecFilter.BLURAY to totalBluRay)
        if (totalWebDl > 0) list.add(StreamSpecFilter.WEBDL to totalWebDl)
        list
    }

    val availableFormats = remember(links) {
        val totalDirect = links.count { link ->
            val isHls = link.isM3u8 || link.name.contains("HLS", ignoreCase = true) || link.url.contains(".m3u8")
            val isDash = link.isDash || link.name.contains("DASH", ignoreCase = true) || link.url.contains(".mpd")
            val isTorrent = DesktopTorrentEngine.isTorrentLink(link)
            !isHls && !isDash && !isTorrent
        }
        val totalHls = links.count { link ->
            link.isM3u8 || link.name.contains("HLS", ignoreCase = true) || link.url.contains(".m3u8")
        }
        val totalDash = links.count { link ->
            link.isDash || link.name.contains("DASH", ignoreCase = true) || link.url.contains(".mpd")
        }
        val totalTorrent = links.count { link ->
            DesktopTorrentEngine.isTorrentLink(link)
        }

        val list = mutableListOf<FormatOption>()
        list.add(FormatOption(StreamFormatFilter.ALL, links.size))
        if (totalDirect > 0) list.add(FormatOption(StreamFormatFilter.DIRECT, totalDirect))
        if (totalHls > 0) list.add(FormatOption(StreamFormatFilter.HLS, totalHls))
        if (totalDash > 0) list.add(FormatOption(StreamFormatFilter.DASH, totalDash))
        if (totalTorrent > 0) list.add(FormatOption(StreamFormatFilter.TORRENT, totalTorrent))
        list
    }

    val filteredLinks = remember(links, selectedQuality, selectedFormat, selectedSource, selectedSpecFilter, isP2pEnabled) {
        val base = links.filter { link ->
            val sourceMatches = when {
                selectedSource == null || selectedSource == "All Sources" -> true
                selectedSource.equals(provider.name, ignoreCase = true) -> !isAddonStream(link)
                else -> isAddonStream(link) && (
                    selectedSource.equals("Addons", ignoreCase = true) ||
                    getAddonName(link).equals(selectedSource, ignoreCase = true) ||
                    link.source.contains(selectedSource, ignoreCase = true) ||
                    selectedSource.contains(link.source, ignoreCase = true)
                )
            }

            val effQual = com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(link)
            val qualityMatches = when (selectedQuality) {
                null -> true
                2160 -> effQual >= 2160
                1080 -> effQual in 1080..2159
                720 -> effQual in 720..1079
                480 -> effQual in 1..719
                else -> effQual == selectedQuality
            }

            val specMatches = when (selectedSpecFilter) {
                StreamSpecFilter.ALL -> true
                StreamSpecFilter.DEBRID -> link.name.contains("⚡") || link.name.contains("[TB]") || link.name.contains("[RD]") ||
                    link.name.contains("[AD]") || link.name.contains("[PM]") || link.name.contains("[DL]") ||
                    link.name.contains("torbox", ignoreCase = true) || link.name.contains("real-debrid", ignoreCase = true) ||
                    link.name.contains("debrid", ignoreCase = true)
                StreamSpecFilter.REMUX -> link.name.contains("remux", ignoreCase = true)
                StreamSpecFilter.BLURAY -> Regex("""\b(bluray|blu-ray|bdrip|brrip)\b""", RegexOption.IGNORE_CASE).containsMatchIn(link.name)
                StreamSpecFilter.WEBDL -> Regex("""\b(web-dl|webdl|webrip|web-rip)\b""", RegexOption.IGNORE_CASE).containsMatchIn(link.name)
            }

            val isHls = link.isM3u8 || link.name.contains("HLS", ignoreCase = true) || link.url.contains(".m3u8")
            val isDash = link.isDash || link.name.contains("DASH", ignoreCase = true) || link.url.contains(".mpd")
            val isTorrent = DesktopTorrentEngine.isTorrentLink(link)
            val isDirect = !isHls && !isDash && !isTorrent

            val formatMatches = when (selectedFormat) {
                StreamFormatFilter.ALL -> true
                StreamFormatFilter.DIRECT -> isDirect
                StreamFormatFilter.HLS -> isHls
                StreamFormatFilter.DASH -> isDash
                StreamFormatFilter.TORRENT -> isTorrent
            }
            sourceMatches && qualityMatches && formatMatches && specMatches
        }
        if (!DesktopTorrentEngine.isP2pReady && selectedFormat == StreamFormatFilter.ALL) {
            base.sortedBy { DesktopTorrentEngine.isTorrentLink(it) }
        } else {
            base
        }
    }

    LaunchedEffect(dataUrl) {
        val showTitle = history.showName.takeIf { it.isNotBlank() } ?: loadResponse?.name
        val isSeries = loadResponse?.type == com.lagradost.cloudstream3.TvType.TvSeries ||
            loadResponse is TvSeriesLoadResponse ||
            history.season != null ||
            history.episode != null
        viewModel.onEvent(
            LinksUiEvent.OnScrape(
                provider = provider,
                dataUrl = dataUrl,
                title = showTitle,
                isSeries = isSeries,
                season = history.season,
                episode = history.episode,
            ),
        )
    }

    val vlcState = vlcPlayer.state.collectAsState().value
    val isAnyPlaying = vlcState.isPlaying
    LaunchedEffect(vlcState.position) {
        val posMs = if (vlcState.isPlaying) vlcState.position else 0L
        val durMs = if (vlcState.isPlaying) vlcState.duration else 0L
        if (posMs > 0 && durMs > 0) {
            val posSec = posMs / 1000L
            if (kotlin.math.abs(posSec - uiState.lastVlcSavedPositionSec) >= 5) {
                viewModel.onEvent(LinksUiEvent.OnUpdateVlcSavedPosition(posSec))
                viewModel.onEvent(LinksUiEvent.OnSaveWatchPosition(history, posMs, durMs))
            }
        }
    }

    DisposableEffect(isAnyPlaying) {
        onDispose {
            if (!isAnyPlaying && vlcState.position > 0 && vlcState.duration > 0) {
                viewModel.onEvent(LinksUiEvent.OnSaveWatchPosition(history, vlcState.position, vlcState.duration))
            }
        }
    }

    LaunchedEffect(isAnyPlaying, isVideoPlayerActive) {
        if (!isAnyPlaying && !isVideoPlayerActive) {
            if (statusText == "Player started." || statusText.startsWith("Playing:")) {
                viewModel.onEvent(LinksUiEvent.OnStatusTextChanged("Ready — ${links.size} stream${if (links.size == 1) "" else "s"} available."))
            }
            viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null))
        }
    }

    LaunchedEffect(vlcState.error, uiState.embeddedError) {
        val errorMessage = vlcState.error ?: uiState.embeddedError
        if (errorMessage != null) {
            val autoPlay = uiState.autoPlayEnabled
            val currentIndex = filteredLinks.indexOfFirst { it.url == currentPlayingUrl }
            val isVlcError = vlcState.error != null
            if (autoPlay && isVlcError && currentIndex != -1 && currentIndex + 1 < filteredLinks.size) {
                val nextLink = filteredLinks[currentIndex + 1]
                viewModel.onEvent(LinksUiEvent.OnStatusTextChanged("Link failed. Auto-trying next: ${nextLink.name}"))
                viewModel.onEvent(LinksUiEvent.OnSetEmbeddedError(null))
                viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null))
                delay(800)
                viewModel.onEvent(
                    LinksUiEvent.OnPlayLink(
                        link = nextLink,
                        displayTitle = displayTitle,
                        history = history,
                        loadResponse = loadResponse,
                        currentPlayingUrl = currentPlayingUrl,
                        enrichedActors = enrichedActors,
                        enrichedLogoUrl = enrichedLogoUrl,
                        enrichedBackdropUrl = enrichedBackdropUrl,
                    ),
                )
            } else {
                viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(errorMessage))
                viewModel.onEvent(LinksUiEvent.OnStatusTextChanged("Playback failed: $errorMessage"))
                viewModel.onEvent(LinksUiEvent.OnSetEmbeddedError(null))
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if ((event.key == Key.Escape || event.key == Key.Back || event.key == Key.Backspace) && event.type == KeyEventType.KeyUp) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Release && event.button == PointerButton.Back) {
                            onClose()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                LinksHeaderBar(
                    history = history,
                    loadResponse = loadResponse,
                    streamCount = links.size,
                    onRefresh = {
                        val showTitle = history.showName.takeIf { it.isNotBlank() } ?: loadResponse?.name
                        val isSeries = loadResponse?.type == com.lagradost.cloudstream3.TvType.TvSeries ||
                            loadResponse is TvSeriesLoadResponse ||
                            history.season != null ||
                            history.episode != null
                        viewModel.onEvent(
                            LinksUiEvent.OnScrape(
                                provider = provider,
                                dataUrl = dataUrl,
                                title = showTitle,
                                forceRefresh = true,
                                isSeries = isSeries,
                                season = history.season,
                                episode = history.episode,
                            ),
                        )
                    },
                    onClose = onClose,
                )

                LinksScrapingProgress(
                    isScraping = isScraping,
                    isLaunchingPlayer = isLaunchingPlayer,
                    statusText = statusText,
                    onStopScraping = { viewModel.onEvent(LinksUiEvent.OnCancelScrape) },
                )

                LinksSourceFilterRow(
                    selectedPlayer = selectedPlayer,
                    onPlayerSelected = { viewModel.onEvent(LinksUiEvent.OnPreferredPlayerChanged(it)) },
                    sourceTabs = sourceTabs,
                    selectedSource = selectedSource,
                    onSourceSelected = { viewModel.onEvent(LinksUiEvent.OnFilterSource(it)) },
                    providerName = provider.name,
                    onOpenPriorityDialog = { showPriorityDialog = true },
                )

                LinksSpecQualityFilterRow(
                    totalLinkCount = links.size,
                    availableQualities = availableQualities,
                    selectedQuality = selectedQuality,
                    onQualitySelected = { viewModel.onEvent(LinksUiEvent.OnFilterQuality(it)) },
                    availableSpecs = availableSpecs,
                    selectedSpecFilter = selectedSpecFilter,
                    onSpecSelected = { viewModel.onEvent(LinksUiEvent.OnFilterSpec(it)) },
                    availableFormats = availableFormats,
                    selectedFormat = selectedFormat,
                    onFormatSelected = { viewModel.onEvent(LinksUiEvent.OnFilterFormat(it)) },
                )

                // Stream Link List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isScraping && filteredLinks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "No Streams Found",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "No playable links match the selected filter criteria.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(filteredLinks, key = { index, it -> "${it.name}-${it.url}-$index" }) { _, link ->
                        StreamLinkCard(
                            link = link,
                            providerName = provider.name,
                            isP2pEnabled = isP2pEnabled,
                            isPlaying = (currentPlayingUrl == link.url),
                            isBusy = isLaunchingPlayer && currentPlayingUrl != link.url,
                            onPlay = {
                                checkAndExecuteTorrent(link, false)
                            },
                            onDownload = {
                                checkAndExecuteTorrent(link, true)
                            },
                            onCopy = {
                                if (link.url.isNotBlank()) {
                                    val selection = java.awt.datatransfer.StringSelection(link.url)
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                                    viewModel.onEvent(LinksUiEvent.OnStatusTextChanged("URL copied to clipboard."))
                                }
                            },
                        )
                    }
                }
            }

            LinksDialogHost(
                linkToDownload = uiState.linkToDownload,
                onDismissDownloadDialog = { viewModel.onEvent(LinksUiEvent.OnSetLinkToDownload(null)) },
                displayTitle = displayTitle,
                history = history,
                loadResponse = loadResponse,
                providerName = provider.name,
                showP2pDisclaimer = showP2pDisclaimer,
                onDismissP2pDisclaimer = {
                    showP2pDisclaimer = false
                    pendingTorrentLink = null
                },
                onConfirmP2pDisclaimer = {
                    showP2pDisclaimer = false
                    viewModel.onEvent(LinksUiEvent.OnP2pEnabledChanged(true))
                    AppToastManager.showSuccess("P2P Torrent Streaming enabled")
                    if (!DesktopTorrentEngine.binary.isInstalled()) {
                        showTorrServerInstall = true
                    } else {
                        pendingTorrentLink?.let { (link, isDownload) ->
                            handleConfirmedTorrentAction(link, isDownload)
                        }
                        pendingTorrentLink = null
                    }
                },
                showTorrServerInstall = showTorrServerInstall,
                onDismissTorrServerInstall = {
                    showTorrServerInstall = false
                    pendingTorrentLink = null
                },
                onTorrServerInstalled = {
                    showTorrServerInstall = false
                    AppToastManager.showSuccess("TorrServer installed successfully")
                    pendingTorrentLink?.let { (link, isDownload) ->
                        handleConfirmedTorrentAction(link, isDownload)
                    }
                    pendingTorrentLink = null
                },
                showPriorityDialog = showPriorityDialog,
                onDismissPriorityDialog = { showPriorityDialog = false },
                playerLaunchError = playerLaunchError,
                onDismissPlayerLaunchError = { viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null)) },
            )
        }
    }
}

@Composable
fun ComposeLinksScreen(
    provider: MainAPI,
    dataUrl: String,
    history: WatchHistory,
    loadResponse: LoadResponse?,
    enrichedActors: List<ActorData>? = null,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    onBack: () -> Unit,
) {
    val isAmoled = LocalDesktopTheme.current.isAmoled
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    val allEpisodes = remember(loadResponse) {
        when (loadResponse) {
            is TvSeriesLoadResponse -> loadResponse.episodes
            is AnimeLoadResponse -> loadResponse.episodes.values.flatten()
            else -> emptyList()
        }
    }
    val activeEpisode = remember(dataUrl, history, allEpisodes) {
        allEpisodes.firstOrNull { it.matchesHistory(history) }
            ?: allEpisodes.firstOrNull { it.data == dataUrl }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if ((event.key == Key.Escape || event.key == Key.Back || event.key == Key.Backspace) && event.type == KeyEventType.KeyUp) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        val rootMaxWidth = maxWidth

        if (enrichedBackdropUrl != null && !performanceModeEnabled) {
            AsyncImage(
                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                    .data(enrichedBackdropUrl)
                    .size(1280, 720)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(45.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = if (isAmoled) 0.70f else 0.55f))
                        }
                    },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val panelWidth = if (rootMaxWidth >= 1000.dp) rootMaxWidth * 0.50f else (rootMaxWidth * 0.60f).coerceIn(480.dp, rootMaxWidth * 0.85f)
            val leftZoneWidth = rootMaxWidth - panelWidth

            if (leftZoneWidth >= 360.dp && loadResponse != null) {
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
                        response = loadResponse,
                        activeEpisode = activeEpisode,
                        activeHistory = history,
                        activeLinkData = Triple(provider, dataUrl, history),
                        uiState = null,
                        isAmoled = isAmoled,
                        cardWidth = minOf(560.dp, leftZoneWidth - 48.dp),
                        enrichedLogoUrl = enrichedLogoUrl,
                    )
                }
            }

            val drawerShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
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
                    .background(
                        if (isAmoled || performanceModeEnabled) {
                            Color(0xFF09090E).copy(alpha = 0.95f)
                        } else {
                            Color(0xFF0D0D17).copy(alpha = 0.80f)
                        },
                    ),
            ) {
                LinksSidePanel(
                    provider = provider,
                    dataUrl = dataUrl,
                    history = history,
                    loadResponse = loadResponse,
                    enrichedActors = enrichedActors,
                    enrichedLogoUrl = enrichedLogoUrl,
                    enrichedBackdropUrl = enrichedBackdropUrl,
                    onClose = onBack,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 7.dp)
                    .zIndex(100f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DesktopBackButton(onClick = onBack)
                WindowControlsPill(
                    isHome = false,
                    isCompact = rootMaxWidth < 600.dp,
                )
            }
        }
    }
}
