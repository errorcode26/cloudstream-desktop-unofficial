package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.explore.ui.ExploreProviderDialog
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopMaterialFilterChip
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.GlobalContextMenuState
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.TorboxCloudLibraryViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.LibraryActionBar
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.LocalBookmarkCard
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.TorboxCloudLibraryView
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.TrackerLibraryCard
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.TrackerQuickEditBar
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.settings.LeafTab
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsSession
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

@Composable
fun ComposeLibraryScreen(
    onNavigate: (Config) -> Unit,
    viewModel: LibraryViewModel,
) {
    LaunchedEffect(Unit) {
        viewModel.onEvent(LibraryUiEvent.OnResumeLibrary)
    }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is LibraryUiEffect.Navigate -> onNavigate(effect.screen)
                is LibraryUiEffect.ShowToast -> {
                    if (effect.isError) AppToastManager.showError(effect.message)
                    else AppToastManager.showSuccess(effect.message)
                }
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val descriptorsById = remember(uiState.connectedTrackers) {
        uiState.connectedTrackers.associateBy { it.id }
    }
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val currentSection = uiState.selectedSection

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Top-level source switcher strictly separating Local Bookmarks from remote Tracker Lists & Torbox Cloud
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isCompact) 4.dp else DesktopDimens.TabInnerHorizontalPadding,
                        vertical = DesktopDimens.TabInnerVerticalPadding,
                    )
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopMaterialFilterChip(
                    selected = currentSection == LibrarySection.LocalBookmarks,
                    onClick = { viewModel.onEvent(LibraryUiEvent.OnSelectSection(LibrarySection.LocalBookmarks)) },
                    shape = chipShape,
                    leadingIcon = {
                        Icon(Icons.Default.Bookmarks, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = {
                        Text(
                            text = if (uiState.bookmarks.isNotEmpty()) "Local Bookmarks (${uiState.bookmarks.size})" else "Local Bookmarks",
                            fontWeight = if (currentSection == LibrarySection.LocalBookmarks) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                    ),
                )

                uiState.connectedTrackers.forEach { tracker ->
                    val target = LibrarySection.Tracker(tracker.id)
                    val selected = currentSection == target
                    val brandColor = Color(tracker.accentColorHex)
                    val count = uiState.trackerTotalCountByProvider[tracker.id]
                        ?: uiState.trackerEntriesByProvider[tracker.id]?.size
                    DesktopMaterialFilterChip(
                        selected = selected,
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnSelectSection(target)) },
                        shape = chipShape,
                        leadingIcon = {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = {
                            Text(
                                text = if (count != null && count > 0) "${tracker.displayName} ($count)" else tracker.displayName,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = brandColor,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                        ),
                    )
                }

                if (uiState.isTorboxConfigured) {
                    val selected = currentSection == LibrarySection.TorboxCloud
                    DesktopMaterialFilterChip(
                        selected = selected,
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnSelectSection(LibrarySection.TorboxCloud)) },
                        shape = chipShape,
                        leadingIcon = {
                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = {
                            Text(
                                text = "Torbox Cloud",
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                        ),
                    )
                }

                if (uiState.connectedTrackers.isEmpty()) {
                    DesktopTextButton(
                        onClick = {
                            SettingsSession.selectedLeaf = LeafTab.ACCOUNTS
                            SettingsSession.activeSubScreen = null
                            onNavigate(Config.Settings)
                        },
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("+ Connect Trackers")
                    }
                }
            }

            if (currentSection == LibrarySection.TorboxCloud) {
                val torboxViewModel = remember { TorboxCloudLibraryViewModel() }
                DisposableEffect(torboxViewModel) {
                    onDispose { torboxViewModel.dispose() }
                }
                TorboxCloudLibraryView(
                    onNavigate = onNavigate,
                    viewModel = torboxViewModel,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                val activeTabs = remember(currentSection) { uiState.watchTabsForActiveSection() }
                val activeCounts = uiState.activeTabCounts()
                val activeTotalCount = if (currentSection is LibrarySection.Tracker) {
                    uiState.activeTrackerTotalCount ?: 0
                } else {
                    uiState.bookmarks.size
                }
                val activeBrandColor = uiState.activeTrackerDescriptor?.let { Color(it.accentColorHex) }
                    ?: MaterialTheme.colorScheme.primary

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isCompact) 8.dp else DesktopDimens.TabInnerHorizontalPadding,
                            vertical = 4.dp,
                        )
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    activeTabs.forEach { tab ->
                        val count = activeCounts[tab] ?: 0
                        DesktopMaterialFilterChip(
                            selected = uiState.selectedTab == tab,
                            onClick = { viewModel.onEvent(LibraryUiEvent.OnSelectTab(tab)) },
                            shape = chipShape,
                            label = {
                                Text(
                                    text = if (activeTotalCount > 0) "${tab.label} ($count)" else tab.label,
                                    fontWeight = if (uiState.selectedTab == tab) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = activeBrandColor,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }

                LibraryActionBar(
                    uiState = uiState,
                    isCompact = isCompact,
                    onSearch = { viewModel.onEvent(LibraryUiEvent.OnSearchQueryChange(it)) },
                    onSortChange = { viewModel.onEvent(LibraryUiEvent.OnSortOptionChange(it)) },
                    onProviderChange = { viewModel.onEvent(LibraryUiEvent.OnProviderFilterChange(it)) },
                    onRefreshTrackers = { viewModel.onEvent(LibraryUiEvent.OnRefreshTrackers) },
                )

                when (currentSection) {
                    LibrarySection.LocalBookmarks -> {
                        LocalBookmarksContent(
                            uiState = uiState,
                            isCompact = isCompact,
                            onNavigate = onNavigate,
                            onEvent = viewModel::onEvent,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    is LibrarySection.Tracker -> {
                        TrackerSectionContent(
                            providerId = currentSection.providerId,
                            uiState = uiState,
                            descriptorsById = descriptorsById,
                            isCompact = isCompact,
                            onEvent = viewModel::onEvent,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    LibrarySection.TorboxCloud -> Unit
                }
            }
        }

        uiState.streamLookupTarget?.let { target ->
            val exploreItem = uiState.streamExploreItem
            if (exploreItem != null) {
                ExploreProviderDialog(
                    item = exploreItem,
                    matches = uiState.matchedResults,
                    isSearching = uiState.isSearchingMatches,
                    onDismissRequest = { viewModel.onEvent(LibraryUiEvent.OnDismissStreamPicker) },
                    onSelectMatch = { match ->
                        viewModel.onEvent(
                            LibraryUiEvent.OnSelectStreamMatch(
                                target = target,
                                match = match,
                            ),
                        )
                    },
                    onOpenAddonMode = { addonItem ->
                        viewModel.onEvent(
                            LibraryUiEvent.OnSelectStreamAddon(
                                target = target,
                                exploreItem = addonItem,
                            ),
                        )
                    },
                    onNavigateToExtensions = { tab ->
                        viewModel.onEvent(LibraryUiEvent.OnDismissStreamPicker)
                        onNavigate(Config.Extensions(initialTab = tab))
                    },
                )
            }
        }

        uiState.pendingRemoveTrackerEntry?.let { entry ->
            val providerName = descriptorsById[entry.providerId]?.displayName ?: entry.providerId
            CloudstreamAlertDialog(
                show = true,
                onDismissRequest = { viewModel.onEvent(LibraryUiEvent.OnDismissRemoveTrackerConfirm) },
                title = { Text("Remove from $providerName?") },
                text = { Text("Remove '${entry.title}' from your $providerName list? Your local bookmarks and watch history remain untouched.") },
                confirmButton = {
                    DesktopButton(
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnConfirmRemoveTrackerEntry) },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Remove Entry")
                    }
                },
                dismissButton = {
                    DesktopOutlinedButton(
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnDismissRemoveTrackerConfirm) },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun LocalBookmarksContent(
    uiState: LibraryUiState,
    isCompact: Boolean,
    onNavigate: (Config) -> Unit,
    onEvent: (LibraryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current

    when {
        uiState.bookmarks.isEmpty() -> {
            Column(
                modifier = modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No local bookmarks yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = theme.TextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(
                    text = "Bookmark any movie or show from an extension to keep it saved locally with your watch progress.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.TextMuted,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesktopButton(
                        onClick = { onNavigate(Config.Home) },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Browse Shows")
                    }
                    if (uiState.connectedTrackers.isEmpty()) {
                        DesktopOutlinedButton(
                            onClick = {
                                SettingsSession.selectedLeaf = LeafTab.ACCOUNTS
                                SettingsSession.activeSubScreen = null
                                onNavigate(Config.Settings)
                            },
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text("Connect Trackers")
                        }
                    }
                }
            }
        }
        uiState.filteredBookmarks.isEmpty() -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = if (uiState.searchQuery.isNotBlank()) {
                        "No local bookmarks matching '${uiState.searchQuery}'."
                    } else {
                        "No local bookmarks in ${uiState.selectedTab.label}."
                    },
                    color = theme.TextMuted,
                )
            }
        }
        else -> {
            val minSize = if (isCompact) 110.dp else uiState.posterWidthDp.dp
            val gridState = rememberLazyGridState()
            Box(modifier = modifier) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minSize),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = if (isCompact) 6.dp else 8.dp,
                        end = (if (isCompact) 6.dp else 8.dp) + 10.dp,
                        top = 8.dp,
                        bottom = DesktopDimens.WatermarkBottomInset + 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.filteredBookmarks, key = { it.id }) { bm ->
                        val isProviderMissing = bm.apiName !in uiState.installedProviderNames
                        LocalBookmarkCard(
                            bookmark = bm,
                            localProgress = uiState.localProgressByBookmarkId[bm.id],
                            linkedTrackers = uiState.linkedTrackersByBookmarkId[bm.id].orEmpty(),
                            hasConnectedTrackers = uiState.connectedTrackers.isNotEmpty(),
                            isProviderMissing = isProviderMissing,
                            onClick = { onEvent(LibraryUiEvent.OnOpenBookmark(bm)) },
                            onSecondaryClick = { bounds ->
                                val provider = uiState.providerMap[bm.apiName]
                                GlobalContextMenuState.showForBookmark(
                                    bounds = bounds,
                                    bookmark = bm,
                                    provider = provider,
                                    onClick = { onEvent(LibraryUiEvent.OnOpenBookmark(bm)) },
                                    onPlayClick = { onEvent(LibraryUiEvent.OnOpenBookmark(bm)) },
                                    onRemove = { onEvent(LibraryUiEvent.OnDeleteBookmark(bm.id)) },
                                    onChangeCategory = { newType -> onEvent(LibraryUiEvent.OnChangeWatchType(bm.id, newType)) },
                                    onReLink = { onEvent(LibraryUiEvent.OnStartReLink(bm)) },
                                    onSearchOtherProviders = { onEvent(LibraryUiEvent.OnSearchGlobal(bm.name)) },
                                )
                            },
                        )
                    }
                }

                DesktopVerticalScrollbar(
                    gridState = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TrackerSectionContent(
    providerId: String,
    uiState: LibraryUiState,
    descriptorsById: Map<String, com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor>,
    isCompact: Boolean,
    onEvent: (LibraryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current
    val descriptor = descriptorsById[providerId]
    val displayName = descriptor?.displayName ?: providerId.uppercase()
    val providerEntries = uiState.trackerEntriesByProvider[providerId].orEmpty()
    val trackersError = uiState.trackersError

    Column(modifier = modifier) {
        if (trackersError != null && providerEntries.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isCompact) 8.dp else DesktopDimens.TabInnerHorizontalPadding,
                        vertical = 4.dp,
                    ),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = trackersError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    DesktopOutlinedButton(
                        onClick = { onEvent(LibraryUiEvent.OnRefreshTrackers) },
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        AnimatedVisibility(visible = uiState.quickEdit != null) {
            uiState.quickEdit?.let { qe ->
                TrackerQuickEditBar(
                    quickEdit = qe,
                    descriptorsById = descriptorsById,
                    onEvent = onEvent,
                    modifier = Modifier.padding(
                        horizontal = if (isCompact) 8.dp else DesktopDimens.TabInnerHorizontalPadding,
                        vertical = 6.dp,
                    ),
                )
            }
        }

        when {
            uiState.isLoadingTrackers && providerEntries.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            trackersError != null && providerEntries.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(trackersError, color = MaterialTheme.colorScheme.error)
                        DesktopButton(
                            onClick = { onEvent(LibraryUiEvent.OnRefreshTrackers) },
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            providerEntries.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Your $displayName watchlist is empty.",
                            style = MaterialTheme.typography.titleMedium,
                            color = theme.TextPrimary,
                        )
                        DesktopOutlinedButton(
                            onClick = { onEvent(LibraryUiEvent.OnRefreshTrackers) },
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text("Refresh $displayName")
                        }
                    }
                }
            }
            uiState.filteredTrackerEntries.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (uiState.searchQuery.isNotBlank()) {
                            "No $displayName entries matching '${uiState.searchQuery}'."
                        } else {
                            "No $displayName entries in ${uiState.selectedTab.label}."
                        },
                        color = theme.TextMuted,
                    )
                }
            }
            else -> {
                val minSize = if (isCompact) 110.dp else uiState.posterWidthDp.dp
                val gridState = rememberLazyGridState()
                val hasMore = uiState.activeTrackerHasMore
                val isLoadingMore = uiState.isLoadingMoreTrackers
                LaunchedEffect(gridState, providerId, hasMore, isLoadingMore) {
                    snapshotFlow {
                        val layoutInfo = gridState.layoutInfo
                        val total = layoutInfo.totalItemsCount
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        total > 0 && lastVisible >= (total - 8).coerceAtLeast(0)
                    }
                        .distinctUntilChanged()
                        .collect { nearEnd ->
                            if (nearEnd && hasMore && !isLoadingMore) {
                                onEvent(LibraryUiEvent.OnLoadMoreTrackerEntries)
                            }
                        }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = minSize),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = if (isCompact) 6.dp else 8.dp,
                            end = (if (isCompact) 6.dp else 8.dp) + 10.dp,
                            top = 8.dp,
                            bottom = DesktopDimens.WatermarkBottomInset + 16.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(uiState.filteredTrackerEntries, key = { it.uniqueKey }) { entry ->
                            TrackerLibraryCard(
                                entry = entry,
                                descriptor = descriptor,
                                linkedBookmark = uiState.linkedBookmarkByTrackerKey[entry.uniqueKey],
                                isSelectedForEdit = uiState.quickEdit?.itemKey == entry.uniqueKey,
                                isIncrementing = uiState.incrementingTrackerKey == entry.uniqueKey,
                                onClick = { onEvent(LibraryUiEvent.OnOpenTrackerEntry(entry)) },
                                onQuickEdit = { onEvent(LibraryUiEvent.OnStartQuickEdit(entry)) },
                                onIncrementEpisode = { onEvent(LibraryUiEvent.OnIncrementTrackerEpisode(entry)) },
                            )
                        }

                        if (isLoadingMore || hasMore) {
                            item(
                                key = "tracker_pagination_footer",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.2.dp,
                                        )
                                    } else {
                                        DesktopOutlinedButton(
                                            onClick = { onEvent(LibraryUiEvent.OnLoadMoreTrackerEntries) },
                                            modifier = Modifier.height(38.dp),
                                        ) {
                                            val label = if (uiState.searchQuery.isNotBlank()) {
                                                "Load More (${providerEntries.size} loaded for search)"
                                            } else {
                                                "Load More (${providerEntries.size} loaded)"
                                            }
                                            Text(label)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DesktopVerticalScrollbar(
                        gridState = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
