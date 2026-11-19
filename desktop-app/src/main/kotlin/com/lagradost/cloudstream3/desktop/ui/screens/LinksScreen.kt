package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.torrent.TorrentPlayability
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
import com.lagradost.cloudstream3.desktop.ui.components.desktopDragScroll
import com.lagradost.cloudstream3.desktop.ui.components.P2pTorrentDisclaimerDialog
import com.lagradost.cloudstream3.desktop.ui.components.TorrServerInstallRequiredDialog
import com.lagradost.cloudstream3.desktop.ui.screens.links.LinksViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.links.StreamLinkCard
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.LinksUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.LinksUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.links.dialogs.DownloadConfirmationDialog
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
    loadResponse: com.lagradost.cloudstream3.LoadResponse?,
    enrichedActors: List<com.lagradost.cloudstream3.ActorData>? = null,
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
    var showPriorityDialog by remember { mutableStateOf(false) }
    var selectedSpecFilter by remember { mutableStateOf(StreamSpecFilter.ALL) }
    var p2pDisclaimerTargetAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var torrServerInstallTargetAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is LinksUiEffect.ShowToast -> {
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showInfo(effect.message)
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

    val installedAddons by StremioAddonManager.addons.collectAsState()
    val addonNames = remember(installedAddons) { installedAddons.map { it.name } }
    val isAddonStream: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Boolean = remember(addonNames) {
        { link -> com.lagradost.cloudstream3.desktop.ui.screens.links.StreamFilterHelper.isAddonStream(link, addonNames) }
    }

    val getAddonName: (com.lagradost.cloudstream3.utils.ExtractorLink) -> String = remember(addonNames, provider.name) {
        { link -> com.lagradost.cloudstream3.desktop.ui.screens.links.StreamFilterHelper.getAddonName(link, provider.name, addonNames) }
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
            loadResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse ||
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
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val epTitle = remember(history, loadResponse) {
                            (loadResponse as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.episodes?.find {
                                it.episode == history.episode && (history.season == null || it.season == history.season)
                            }?.name
                        }
                        val epSubtitle = if (history.season != null && history.episode != null) {
                            if (!epTitle.isNullOrBlank()) "Season ${history.season} • Episode ${history.episode}: $epTitle"
                            else "Season ${history.season} • Episode ${history.episode}"
                        } else if (history.episode != null) {
                            if (!epTitle.isNullOrBlank()) "Episode ${history.episode}: $epTitle"
                            else "Episode ${history.episode}"
                        } else {
                            "Movie"
                        }
                        Text(
                            text = history.showName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DesktopUi.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = epSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        ),
                    ) {
                        Text(
                            text = "${links.size} Streams",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = {
                            val showTitle = history.showName.takeIf { it.isNotBlank() } ?: loadResponse?.name
                            val isSeries = loadResponse?.type == com.lagradost.cloudstream3.TvType.TvSeries ||
                                loadResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse ||
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
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Streams",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                HorizontalDivider(color = DesktopUi.Divider.copy(alpha = 0.5f))

                // Scraping Progress & Slim Status
                if (isScraping) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = DesktopUi.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = { viewModel.onEvent(LinksUiEvent.OnCancelScrape) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("Stop", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isLaunchingPlayer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopUi.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Unified Desktop Control & Source Filter Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Player Selector Toggle (MPV / VLC)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DesktopUi.Divider.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("mpv" to "MPV", "vlc" to "VLC").forEach { (id, label) ->
                                val isSel = selectedPlayer == id
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { viewModel.onEvent(LinksUiEvent.OnPreferredPlayerChanged(id)) },
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else DesktopUi.TextMuted,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Plugin vs Addons Source Tabs (Single Tabs, Zero Server Clutter)
                    val sourceScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(sourceScrollState)
                            .desktopDragScroll(sourceScrollState),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sourceTabs.forEach { tab ->
                            val isSel = (tab.name == "All Sources" && selectedSource == null) ||
                                (selectedSource != null && selectedSource.equals(tab.name, ignoreCase = true))
                            val isPluginTab = !tab.isAddon && tab.name != "All Sources"
                            val sourceLogoUrl = remember(tab.name) {
                                if (isPluginTab) {
                                    DesktopRepositoryManager.getPluginIcon(provider.name)
                                } else if (tab.isAddon) {
                                    DesktopRepositoryManager.getPluginIcon(tab.name)
                                        ?: StremioAddonManager.addons.value.firstOrNull {
                                            it.name.equals(tab.name, ignoreCase = true) ||
                                                tab.name.contains(it.name, ignoreCase = true) ||
                                                it.name.contains(tab.name, ignoreCase = true)
                                        }?.logoUrl
                                } else {
                                    null
                                }
                            }

                            DesktopFilterPill(
                                label = tab.name,
                                count = tab.count,
                                selected = isSel,
                                onClick = {
                                    viewModel.onEvent(LinksUiEvent.OnFilterSource(if (tab.name == "All Sources" || isSel) null else tab.name))
                                },
                                leadingIcon = {
                                    if (sourceLogoUrl != null) {
                                        AsyncImage(
                                            model = sourceLogoUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            contentScale = ContentScale.Fit,
                                        )
                                    } else if (tab.isAddon) {
                                        Icon(
                                            Icons.Default.Extension,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSel) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.7f),
                                        )
                                    } else if (isPluginTab) {
                                        Icon(
                                            Icons.Default.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                        )
                                    }
                                },
                                accentColor = if (tab.isAddon) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Priority Settings Icon Button
                    IconButton(
                        onClick = { showPriorityDialog = true },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Source Priorities",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Quality & Protocol & Spec Filter Strip
                if (availableQualities.isNotEmpty() || availableSpecs.size > 1 || availableFormats.size > 1) {
                    val filterScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp)
                            .horizontalScroll(filterScrollState)
                            .desktopDragScroll(filterScrollState),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (availableQualities.isNotEmpty()) {
                            DesktopFilterPill(
                                label = "All Qualities",
                                count = links.size,
                                selected = selectedQuality == null,
                                onClick = { viewModel.onEvent(LinksUiEvent.OnFilterQuality(null)) },
                                accentColor = DesktopUi.Accent,
                            )

                            availableQualities.forEach { option ->
                                val isSel = selectedQuality == option.qualityValue
                                val pillAccent = when {
                                    option.qualityValue >= 2160 -> Color(0xFFF59E0B)
                                    option.qualityValue in 1080..2159 -> Color(0xFF3B82F6)
                                    option.qualityValue in 720..1079 -> Color(0xFF06B6D4)
                                    option.qualityValue in 480..719 -> Color(0xFF94A3B8)
                                    else -> DesktopUi.Accent
                                }
                                DesktopFilterPill(
                                    label = option.label,
                                    count = option.count,
                                    selected = isSel,
                                    onClick = { viewModel.onEvent(LinksUiEvent.OnFilterQuality(if (isSel) null else option.qualityValue)) },
                                    accentColor = pillAccent,
                                )
                            }
                        }

                        if (availableSpecs.size > 1) {
                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(Color.White.copy(alpha = 0.15f)),
                            )

                            availableSpecs.filter { it.first != StreamSpecFilter.ALL }.forEach { (spec, count) ->
                                val isSel = selectedSpecFilter == spec
                                val pillAccent = when (spec) {
                                    StreamSpecFilter.ALL -> MaterialTheme.colorScheme.primary
                                    StreamSpecFilter.DEBRID -> Color(0xFFF59E0B)
                                    StreamSpecFilter.REMUX -> Color(0xFF8B5CF6)
                                    StreamSpecFilter.BLURAY -> Color(0xFF3B82F6)
                                    StreamSpecFilter.WEBDL -> Color(0xFF10B981)
                                }
                                DesktopFilterPill(
                                    label = spec.label,
                                    count = count,
                                    selected = isSel,
                                    onClick = { selectedSpecFilter = if (isSel) StreamSpecFilter.ALL else spec },
                                    accentColor = pillAccent,
                                )
                            }
                        }

                        if (availableFormats.size > 1) {
                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(Color.White.copy(alpha = 0.15f)),
                            )

                            availableFormats.forEach { formatOpt ->
                                val isSel = selectedFormat == formatOpt.format
                                DesktopFilterPill(
                                    label = formatOpt.format.label,
                                    count = formatOpt.count,
                                    selected = isSel,
                                    onClick = { viewModel.onEvent(LinksUiEvent.OnFilterFormat(formatOpt.format)) },
                                    accentColor = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

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
                                val playAction: () -> Unit = {
                                    viewModel.onEvent(
                                        LinksUiEvent.OnPlayLink(
                                            link = link,
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
                                when (DesktopTorrentEngine.checkTorrentPlayability(link)) {
                                    TorrentPlayability.NEEDS_P2P_ENABLED -> {
                                        p2pDisclaimerTargetAction = playAction
                                    }
                                    TorrentPlayability.NEEDS_TORRSERVER_INSTALL -> {
                                        torrServerInstallTargetAction = playAction
                                    }
                                    TorrentPlayability.READY -> {
                                        playAction()
                                    }
                                }
                            },
                            onDownload = {
                                val downloadAction: () -> Unit = {
                                    viewModel.onEvent(LinksUiEvent.OnSetLinkToDownload(link))
                                }
                                when (DesktopTorrentEngine.checkTorrentPlayability(link)) {
                                    TorrentPlayability.NEEDS_P2P_ENABLED -> {
                                        p2pDisclaimerTargetAction = downloadAction
                                    }
                                    TorrentPlayability.NEEDS_TORRSERVER_INSTALL -> {
                                        torrServerInstallTargetAction = downloadAction
                                    }
                                    TorrentPlayability.READY -> {
                                        downloadAction()
                                    }
                                }
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

            // Download Confirmation Dialog
            DownloadConfirmationDialog(
                show = uiState.linkToDownload != null,
                onDismiss = { viewModel.onEvent(LinksUiEvent.OnSetLinkToDownload(null)) },
                link = uiState.linkToDownload,
                displayTitle = displayTitle,
                history = history,
                loadResponse = loadResponse,
                providerName = provider.name,
                onConfirmDownload = {
                    val targetLink = uiState.linkToDownload ?: return@DownloadConfirmationDialog
                    viewModel.onEvent(LinksUiEvent.OnSetLinkToDownload(null))
                    com.lagradost.cloudstream3.desktop.downloader.DesktopDownloadManager.enqueue(
                        canonicalKey = history.showUrl,
                        showName = history.showName,
                        showUrl = history.showUrl,
                        episodeTitle = history.episodeId,
                        posterUrl = history.posterUrl,
                        backdropUrl = loadResponse?.backgroundPosterUrl,
                        season = history.season,
                        episode = history.episode,
                        link = targetLink,
                        apiName = provider.name,
                    )
                },
            )

            // P2P Torrent Disclaimer Dialog
            P2pTorrentDisclaimerDialog(
                show = p2pDisclaimerTargetAction != null,
                onDismiss = { p2pDisclaimerTargetAction = null },
                onConfirm = {
                    val action = p2pDisclaimerTargetAction
                    p2pDisclaimerTargetAction = null
                    viewModel.onEvent(LinksUiEvent.OnP2pEnabledChanged(true))
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showSuccess("P2P Torrent Streaming enabled")
                    if (!DesktopTorrentEngine.binary.isInstalled()) {
                        torrServerInstallTargetAction = action
                    } else {
                        action?.invoke()
                    }
                },
            )

            // TorrServer Installation Required Dialog
            TorrServerInstallRequiredDialog(
                show = torrServerInstallTargetAction != null,
                onDismiss = { torrServerInstallTargetAction = null },
                onInstalled = {
                    val action = torrServerInstallTargetAction
                    torrServerInstallTargetAction = null
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showSuccess("TorrServer installed successfully")
                    action?.invoke()
                },
            )

            com.lagradost.cloudstream3.desktop.ui.screens.player.SourcePriorityDialog(
                show = showPriorityDialog,
                onDismissRequest = { showPriorityDialog = false },
            )

            com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog(
                show = playerLaunchError != null,
                onDismissRequest = { viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null)) },
                title = { Text("Player error") },
                text = { Text(playerLaunchError ?: "") },
                confirmButton = {
                    TextButton(onClick = { viewModel.onEvent(LinksUiEvent.OnPlayerLaunchFinished(null)) }) { Text("OK") }
                },
            )
        }
    }
}

internal data class SourceOption(
    val name: String,
    val count: Int,
    val isAddon: Boolean = false,
)

internal enum class StreamSpecFilter(val label: String) {
    ALL("All"),
    DEBRID("⚡ Debrid"),
    REMUX("REMUX"),
    BLURAY("BluRay"),
    WEBDL("WEB-DL"),
}

@Composable
private fun DesktopFilterPill(
    label: String,
    count: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    accentColor: Color = DesktopUi.Accent,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val containerColor = when {
        selected -> accentColor.copy(alpha = 0.22f)
        isHovered -> Color.White.copy(alpha = 0.08f)
        else -> Color(0xFF1E222D).copy(alpha = 0.65f)
    }

    val borderColor = when {
        selected -> accentColor.copy(alpha = 0.75f)
        isHovered -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.10f)
    }

    val textColor = when {
        selected -> Color.White
        isHovered -> Color.White
        else -> Color.White.copy(alpha = 0.85f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(32.dp)
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                leadingIcon()
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = textColor,
            )

            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) accentColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                    )
                }
            }
        }
    }
}
