package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.components.DesktopFilterChip
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.desktopDragScroll
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun RenderEpisodesSection(
    allFilteredEpisodes: List<Episode>,
    isEpisodesStackedView: Boolean,
    episodesScrollState: LazyListState,
    latestHistory: WatchHistory?,
    historyLookup: EpisodeHistoryLookup,
    lockUnreleasedEpisodes: Boolean,
    posterHoverGlowEnabled: Boolean,
    uiCardOpacity: Float,
    provider: MainAPI,
    data: LoadResponse,
    uiState: DetailsUiState?,
    enableDownloadButtons: Boolean,
    isAntiSpoiler: Boolean,
    coroutineScope: CoroutineScope,
    onPlay: (Episode) -> Unit,
    onDownload: ((Episode) -> Unit)?,
    onToggleWatched: (Episode, Boolean) -> Unit,
    onToggleSeasonWatched: (List<Episode>, Boolean) -> Unit,
    onRemoveEpisodeWatched: (Episode) -> Unit,
) {
    val handleMarkPreviousWatched: (Episode) -> Unit = { targetEp ->
        val targetIdx = allFilteredEpisodes.indexOfFirst { it.data == targetEp.data }
        if (targetIdx > 0) {
            val epsToMark = allFilteredEpisodes.take(targetIdx + 1)
            onToggleSeasonWatched(epsToMark, true)
        }
    }

    val currentMode = uiState?.episodeViewMode ?: if (isEpisodesStackedView) 1 else 0
    val isPanelOpen = uiState?.isPanelOpen == true
    val activeHistory = uiState?.activeLinkData?.third

    if (currentMode == 1 || currentMode == 2) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 600.dp
            val hPadding = if (isCompact) {
                12.dp
            } else if (maxWidth < 1100.dp) {
                24.dp
            } else {
                64.dp
            }
            val desiredWidth = if (isCompact) 280f else 340f
            val columns = if (currentMode == 2) 1 else maxOf(1, kotlin.math.round(maxWidth.value / desiredWidth).toInt())
            val gapDp = if (isCompact) 10.dp else 14.dp
            val totalGapDp = gapDp * (columns - 1)
            val cardWidth = if (currentMode == 2) maxWidth else (maxWidth - (hPadding * 2) - totalGapDp - 1.dp) / columns

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding),
                horizontalArrangement = Arrangement.spacedBy(gapDp),
                verticalArrangement = Arrangement.spacedBy(gapDp),
                maxItemsInEachRow = columns,
            ) {
                allFilteredEpisodes.forEachIndexed { index, ep ->
                    key(if (ep.data.isNotBlank()) "${ep.data}_$index" else "ep_$index") {
                        val isLatest = latestHistory != null && ep.matchesHistory(latestHistory)
                        val history = historyLookup.find(ep)
                        val canMarkPrevious = index > 0 && (ep.episode ?: (index + 1)) > 1
                        val isActive = isPanelOpen && activeHistory != null && ep.matchesHistory(activeHistory)
                        val isDimmed = isPanelOpen && !isActive

                        if (currentMode == 2) {
                            EpisodeListItem(
                                ep = ep,
                                isLatest = isLatest,
                                history = history,
                                provider = provider,
                                data = data,
                                uiState = uiState,
                                isAntiSpoiler = isAntiSpoiler,
                                thumbnailVersion = uiState?.episodeThumbnailVersion ?: 0,
                                lockUnreleasedEpisodes = lockUnreleasedEpisodes,
                                uiCardOpacity = uiCardOpacity,
                                modifier = Modifier
                                    .width(cardWidth)
                                    .padding(bottom = 12.dp)
                                    .then(
                                        if (isDimmed) Modifier.blur(6.dp).graphicsLayer { alpha = 0.35f }
                                        else if (isActive) Modifier.zIndex(25f)
                                        else Modifier,
                                    ),
                                enableDownloadButtons = enableDownloadButtons,
                                onPlay = onPlay,
                                onDownload = onDownload,
                                onToggleWatched = onToggleWatched,
                                onRemoveEpisodeWatched = onRemoveEpisodeWatched,
                                onMarkPreviousWatched = if (canMarkPrevious) handleMarkPreviousWatched else null,
                            )
                        } else {
                            EpisodeCard(
                                ep = ep,
                                isLatest = isLatest,
                                history = history,
                                provider = provider,
                                data = data,
                                uiState = uiState,
                                isAntiSpoiler = isAntiSpoiler,
                                thumbnailVersion = uiState?.episodeThumbnailVersion ?: 0,
                                lockUnreleasedEpisodes = lockUnreleasedEpisodes,
                                posterHoverGlowEnabled = posterHoverGlowEnabled,
                                modifier = Modifier
                                    .width(cardWidth)
                                    .then(
                                        if (isDimmed) Modifier.blur(6.dp).graphicsLayer { alpha = 0.35f }
                                        else if (isActive) Modifier.zIndex(25f)
                                        else Modifier,
                                    ),
                                enableDownloadButtons = enableDownloadButtons,
                                onPlay = onPlay,
                                onDownload = onDownload,
                                onToggleWatched = onToggleWatched,
                                onRemoveEpisodeWatched = onRemoveEpisodeWatched,
                                onMarkPreviousWatched = if (canMarkPrevious) handleMarkPreviousWatched else null,
                            )
                        }
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 600.dp
            val hPadding = if (isCompact) {
                12.dp
            } else if (maxWidth < 1100.dp) {
                24.dp
            } else {
                64.dp
            }
            val itemSpacing = if (isCompact) 10.dp else 16.dp

            val cardWidth = remember(maxWidth) {
                val netWidth = maxWidth - (hPadding * 2)
                when {
                    maxWidth < 600.dp -> (netWidth * 0.85f).coerceIn(280.dp, 340.dp)
                    maxWidth < 1100.dp -> ((netWidth - itemSpacing * 2) / 2.3f).coerceIn(320.dp, 390.dp)
                    maxWidth < 1600.dp -> ((netWidth - itemSpacing * 3) / 3.4f).coerceIn(360.dp, 440.dp)
                    maxWidth < 2200.dp -> ((netWidth - itemSpacing * 4) / 4.4f).coerceIn(380.dp, 460.dp)
                    else -> ((netWidth - itemSpacing * 5) / 5.4f).coerceIn(400.dp, 480.dp)
                }
            }
            LazyRow(
                state = episodesScrollState,
                contentPadding = PaddingValues(horizontal = hPadding, vertical = if (isCompact) 6.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier.fillMaxWidth().desktopDragScroll(episodesScrollState),
            ) {
                itemsIndexed(allFilteredEpisodes, key = { index, ep ->
                    if (ep.data.isNotBlank()) "${ep.data}_$index" else "ep_$index"
                }) { index, ep ->
                    val isLatest = latestHistory != null && ep.matchesHistory(latestHistory)
                    val history = historyLookup.find(ep)
                    val canMarkPrevious = index > 0 && (ep.episode ?: (index + 1)) > 1
                    val isActive = isPanelOpen && activeHistory != null && ep.matchesHistory(activeHistory)
                    val isDimmed = isPanelOpen && !isActive

                    EpisodeCard(
                        ep = ep,
                        isLatest = isLatest,
                        history = history,
                        provider = provider,
                        data = data,
                        uiState = uiState,
                        isAntiSpoiler = isAntiSpoiler,
                        thumbnailVersion = uiState?.episodeThumbnailVersion ?: 0,
                        lockUnreleasedEpisodes = lockUnreleasedEpisodes,
                        posterHoverGlowEnabled = posterHoverGlowEnabled,
                        modifier = Modifier
                            .width(cardWidth)
                            .then(
                                if (isDimmed) Modifier.blur(6.dp).graphicsLayer { alpha = 0.35f }
                                else if (isActive) Modifier.zIndex(25f)
                                else Modifier,
                            ),
                        enableDownloadButtons = enableDownloadButtons,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggleWatched = onToggleWatched,
                        onRemoveEpisodeWatched = onRemoveEpisodeWatched,
                        onMarkPreviousWatched = if (canMarkPrevious) handleMarkPreviousWatched else null,
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeChunkPaginationBar(
    hPadding: Dp,
    chunks: List<List<Episode>>,
    safeChunkIndex: Int,
    onSelectChunk: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chunks.forEachIndexed { index, list ->
            val isSelected = safeChunkIndex == index
            val startEp = list.firstOrNull()?.episode ?: (index * 20 + 1)
            val endEp = list.lastOrNull()?.episode ?: ((index + 1) * 20)

            DesktopFilterChip(
                text = "$startEp - $endEp",
                isSelected = isSelected,
                onClick = { onSelectChunk(index) },
                height = 36.dp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun EpisodeCarouselNavButtons(
    hPadding: Dp,
    episodesScrollState: LazyListState,
    coroutineScope: CoroutineScope,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Scroll Left",
                onClick = { coroutineScope.launch { episodesScrollState.animateScrollBy(-600f) } },
            )
            DesktopIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Scroll Right",
                onClick = { coroutineScope.launch { episodesScrollState.animateScrollBy(600f) } },
            )
        }
    }
}
