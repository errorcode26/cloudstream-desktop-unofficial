package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.dialogs.SeasonSelectionDialog
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.launch

@Composable
fun DetailsEpisodeSection(
    provider: MainAPI,
    data: LoadResponse,
    showHistory: Map<String, WatchHistory>,
    latestHistory: WatchHistory?,
    isMovieLike: Boolean,
    isLoading: Boolean,
    uiState: DetailsUiState?,
    enableDownloadButtons: Boolean = true,
    onPlay: (Episode) -> Unit,
    onDownload: ((Episode) -> Unit)? = null,
    onToggleWatched: (Episode, Boolean) -> Unit,
    onToggleSeasonWatched: (List<Episode>, Boolean) -> Unit,
    onRemoveEpisodeWatched: (Episode) -> Unit,
    onToggleEpisodesStackedView: (Boolean) -> Unit,
    onSetEpisodeViewMode: (Int) -> Unit = {},
    selectedSeason: Int? = null,
    onSeasonChange: ((Int) -> Unit)? = null,
) {
    val isEpisodesStackedView = uiState?.isEpisodesStackedView == true
    val coroutineScope = rememberCoroutineScope()
    if (isMovieLike) return

    val hasEpisodes = when (data) {
        is TvSeriesLoadResponse -> data.episodes.isNotEmpty()
        is AnimeLoadResponse -> data.episodes.isNotEmpty()
        else -> false
    }
    if (!isLoading && !hasEpisodes) {
        DetailsEpisodeEmptyState(provider.name)
        return
    }

    val dubStatuses = remember(data) { EpisodeSectionState.resolveDubStatuses(data) }
    var selectedDub by remember(latestHistory?.episodeId, data) {
        mutableStateOf(EpisodeSectionState.resolveInitialDub(data, latestHistory, dubStatuses))
    }

    val seasons = remember(data) { EpisodeSectionState.resolveSeasons(data) }
    var localSelectedSeason by remember(latestHistory?.season, data, seasons) {
        mutableStateOf(EpisodeSectionState.resolveInitialSeason(data, latestHistory, seasons))
    }
    val effectiveSeason = remember(selectedSeason, localSelectedSeason, seasons) {
        val target = selectedSeason ?: localSelectedSeason
        if (target in seasons) target else (seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1)
    }
    val updateSeason: (Int) -> Unit = { newSeason ->
        localSelectedSeason = newSeason
        onSeasonChange?.invoke(newSeason)
    }
    var showSeasonModal by remember { mutableStateOf(false) }

    var isSortAscending by remember(data.url) { mutableStateOf(true) }
    var selectedEpisodeChunk by remember(data.url) { mutableStateOf(0) }
    val isAntiSpoiler by AppearanceConfig.antiSpoilerEnabled.collectAsState()
    val episodesScrollState = rememberLazyListState()

    if (isLoading) {
        DetailsEpisodeShimmer()
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 28.dp),
        ) {
            val isCompact = maxWidth < 600.dp
            val hPadding = if (isCompact) {
                12.dp
            } else if (maxWidth < 1100.dp) {
                24.dp
            } else {
                64.dp
            }

            val lockUnreleasedEpisodes by AppearanceConfig.lockUnreleasedEpisodes.collectAsState()
            val posterHoverGlowEnabled by AppearanceConfig.posterHoverGlowEnabled.collectAsState()
            val uiCardOpacity by AppearanceConfig.uiCardOpacity.collectAsState()
            val historyLookup = remember(showHistory) { EpisodeHistoryLookup(showHistory) }

            val rawEpisodes: List<Episode> = remember(data, selectedDub, uiState?.episodeThumbnailVersion) {
                EpisodeSectionState.resolveRawEpisodes(data, selectedDub)
            }

            val preChunkedEpisodes = remember(rawEpisodes, effectiveSeason, seasons, isSortAscending) {
                EpisodeSectionState.filterAndSortEpisodes(rawEpisodes, effectiveSeason, seasons, isSortAscending)
            }
            val targetEpisodeIndex = remember(preChunkedEpisodes, latestHistory) {
                EpisodeSectionState.calculateTargetEpisodeIndex(preChunkedEpisodes, latestHistory)
            }
            val chunks = remember(preChunkedEpisodes) { preChunkedEpisodes.chunked(20) }
            val currentMode = uiState?.episodeViewMode ?: if (isEpisodesStackedView) 1 else 0
            val safeChunkIndex = if (chunks.isEmpty()) 0 else selectedEpisodeChunk.coerceIn(0, chunks.size - 1)

            LaunchedEffect(effectiveSeason, selectedDub, isSortAscending, data.url, targetEpisodeIndex, currentMode) {
                if (currentMode == 0) {
                    if (targetEpisodeIndex >= 0 && preChunkedEpisodes.isNotEmpty()) {
                        episodesScrollState.scrollToItem(targetEpisodeIndex.coerceIn(0, preChunkedEpisodes.size - 1))
                    } else {
                        episodesScrollState.scrollToItem(0)
                    }
                } else {
                    val targetChunk = if (chunks.isNotEmpty()) (targetEpisodeIndex / 20).coerceIn(0, chunks.size - 1) else 0
                    selectedEpisodeChunk = targetChunk
                    episodesScrollState.scrollToItem(0)
                }
            }

            val allFilteredEpisodes = if (currentMode == 0) {
                preChunkedEpisodes
            } else {
                chunks.getOrNull(safeChunkIndex) ?: emptyList()
            }
            val seasonListState = rememberLazyListState()
            LaunchedEffect(effectiveSeason, seasons) {
                val targetIdx = seasons.indexOf(effectiveSeason)
                if (targetIdx >= 0) {
                    seasonListState.animateScrollToItem(targetIdx)
                }
            }

            val currentSeasonEpisodes = remember(rawEpisodes, effectiveSeason, seasons) {
                val filtered = if (seasons.size <= 1) {
                    rawEpisodes
                } else {
                    rawEpisodes.filter { ep ->
                        ep.season == effectiveSeason || (ep.season == null && effectiveSeason == seasons.first())
                    }
                }
                val resolved = if (filtered.isEmpty() && rawEpisodes.isNotEmpty()) rawEpisodes else filtered
                resolved.distinctBy { ep ->
                    if (ep.episode != null && ep.episode != 0) {
                        "ep:${ep.season ?: 1}:${ep.episode}"
                    } else {
                        "data:${ep.data.ifBlank { ep.name ?: ep.hashCode().toString() }}"
                    }
                }
            }
            val isSeasonWatched = remember(currentSeasonEpisodes, historyLookup) {
                EpisodeSectionState.isSeasonCompleted(currentSeasonEpisodes, historyLookup)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (isCompact) {
                    DetailsEpisodeCompactHeader(
                        hPadding = hPadding,
                        seasons = seasons,
                        effectiveSeason = effectiveSeason,
                        seasonListState = seasonListState,
                        uiState = uiState,
                        dubStatuses = dubStatuses,
                        selectedDub = selectedDub,
                        currentSeasonEpisodes = currentSeasonEpisodes,
                        isSeasonWatched = isSeasonWatched,
                        isAntiSpoiler = isAntiSpoiler,
                        isSortAscending = isSortAscending,
                        currentMode = currentMode,
                        onSeasonSelect = updateSeason,
                        onShowSeasonModal = { showSeasonModal = true },
                        onSelectDub = { selectedDub = it },
                        onToggleSeasonWatched = onToggleSeasonWatched,
                        onToggleSort = { isSortAscending = !isSortAscending },
                        onSetEpisodeViewMode = onSetEpisodeViewMode,
                    )
                } else {
                    DetailsEpisodeDesktopHeader(
                        hPadding = hPadding,
                        seasons = seasons,
                        effectiveSeason = effectiveSeason,
                        seasonListState = seasonListState,
                        uiState = uiState,
                        dubStatuses = dubStatuses,
                        selectedDub = selectedDub,
                        currentSeasonEpisodes = currentSeasonEpisodes,
                        isSeasonWatched = isSeasonWatched,
                        isAntiSpoiler = isAntiSpoiler,
                        isSortAscending = isSortAscending,
                        currentMode = currentMode,
                        onSeasonSelect = updateSeason,
                        onShowSeasonModal = { showSeasonModal = true },
                        onSelectDub = { selectedDub = it },
                        onToggleSeasonWatched = onToggleSeasonWatched,
                        onToggleSort = { isSortAscending = !isSortAscending },
                        onSetEpisodeViewMode = onSetEpisodeViewMode,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (allFilteredEpisodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        Text("No episodes available for this season", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column {
                        RenderEpisodesSection(
                            allFilteredEpisodes = allFilteredEpisodes,
                            isEpisodesStackedView = isEpisodesStackedView,
                            episodesScrollState = episodesScrollState,
                            latestHistory = latestHistory,
                            historyLookup = historyLookup,
                            lockUnreleasedEpisodes = lockUnreleasedEpisodes,
                            posterHoverGlowEnabled = posterHoverGlowEnabled,
                            uiCardOpacity = uiCardOpacity,
                            provider = provider,
                            data = data,
                            uiState = uiState,
                            enableDownloadButtons = enableDownloadButtons,
                            isAntiSpoiler = isAntiSpoiler,
                            coroutineScope = coroutineScope,
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggleWatched = onToggleWatched,
                            onToggleSeasonWatched = onToggleSeasonWatched,
                            onRemoveEpisodeWatched = onRemoveEpisodeWatched,
                        )

                        if (currentMode != 0 && chunks.size > 1) {
                            EpisodeChunkPaginationBar(
                                hPadding = hPadding,
                                chunks = chunks,
                                safeChunkIndex = safeChunkIndex,
                                onSelectChunk = { idx ->
                                    selectedEpisodeChunk = idx
                                    coroutineScope.launch { episodesScrollState.scrollToItem(0) }
                                },
                            )
                        }

                        if (currentMode == 0 && !isCompact) {
                            EpisodeCarouselNavButtons(
                                hPadding = hPadding,
                                episodesScrollState = episodesScrollState,
                                coroutineScope = coroutineScope,
                            )
                        }
                    }
                }
            }
        }

        val allEpisodesList = remember(data) {
            when (data) {
                is TvSeriesLoadResponse -> data.episodes
                is AnimeLoadResponse -> data.episodes.values.flatten()
                else -> emptyList()
            }
        }

        SeasonSelectionDialog(
            show = showSeasonModal,
            onDismissRequest = { showSeasonModal = false },
            seasons = seasons,
            selectedSeason = effectiveSeason,
            onSelectSeason = { updateSeason(it) },
            allEpisodesList = allEpisodesList,
            enrichedSeasonsMetadata = uiState?.enrichedSeasonsMetadata ?: emptyList(),
        )
    }
}
