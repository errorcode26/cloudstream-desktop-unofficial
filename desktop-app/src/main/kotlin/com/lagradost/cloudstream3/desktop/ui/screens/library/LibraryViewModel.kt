package com.lagradost.cloudstream3.desktop.ui.screens.library

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.RemoveBookmark
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.ToggleBookmark
import com.lagradost.cloudstream3.desktop.domain.category.interactor.SetItemCategory
import com.lagradost.cloudstream3.desktop.domain.history.repository.WatchHistoryRepository
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.explore.relink.ReLinkSearchCoordinator
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.EpisodeSyncCheck
import com.lagradost.cloudstream3.desktop.tracking.GuardDecision
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.tracking.TrackerStatusUpdate
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.tracking.adjustedProgressForStatus
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryStreamTarget
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryStreamTargetKind
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerBindingRecord
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerQuickEdit
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryWatchTab
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LocalBookmarkProgress
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LocalTrackerLinkInfo
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.SortOption
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.normalizeLibraryEpochMillis
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.toUnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LibraryViewModel(
    private val getBookmarks: GetBookmarks = AppContainerHolder.container.getBookmarks,
    private val toggleBookmark: ToggleBookmark = AppContainerHolder.container.toggleBookmark,
    private val removeBookmark: RemoveBookmark = AppContainerHolder.container.removeBookmark,
    private val setItemCategory: SetItemCategory = AppContainerHolder.container.setItemCategory,
    private val watchHistoryRepository: WatchHistoryRepository = AppContainerHolder.container.watchHistoryRepository,
    private val trackingRepository: TrackingRepository = AppContainerHolder.container.trackingRepository,
    private val registry: TrackerRegistry = TrackerRegistry,
) : BaseMviViewModel<LibraryUiState, LibraryUiEvent, LibraryUiEffect>(
    initialState = LibraryUiState(
        selectedSection = consumeRequestedSection() ?: LibrarySection.LocalBookmarks,
    ),
) {
    companion object {
        private const val TRACKER_PAGE_SIZE = 50

        @Volatile
        private var pendingRequestedSection: LibrarySection? = null

        fun requestSection(section: LibrarySection) {
            pendingRequestedSection = section
        }

        private fun consumeRequestedSection(): LibrarySection? {
            val section = pendingRequestedSection
            pendingRequestedSection = null
            return section
        }
    }

    private var streamLookupJob: Job? = null
    private var trackerFetchJob: Job? = null
    private var trackerLoadMoreJob: Job? = null
    private var bindingRefreshJob: Job? = null
    private val selfSyncAuditCounts = ConcurrentHashMap<String, AtomicInteger>()

    init {
        refreshTorboxConfig()

        viewModelScope.launch {
            getBookmarks.subscribeAll().collect { bookmarksMap ->
                val allList = bookmarksMap.values.toList()
                val installed = APIHolder.allProviders.map { it.name }.toSet()
                val provMap = APIHolder.allProviders.associateBy { it.name }
                updateState {
                    val availableProvs = allList.map { it.apiName }.distinct().sorted()
                    val nextSelectedProv = if (selectedProvider in availableProvs) selectedProvider else null
                    copy(
                        bookmarks = allList,
                        availableProviders = availableProvs,
                        selectedProvider = nextSelectedProv,
                        installedProviderNames = installed,
                        providerMap = provMap,
                    ).recomputeLibraryState()
                }
                refreshBindingsIndex()
            }
        }

        viewModelScope.launch {
            watchHistoryRepository.subscribeAll()
                .flowOn(Dispatchers.IO)
                .collect { allHistory ->
                    val progressMap = computeLocalBookmarkProgress(allHistory)
                    updateState {
                        copy(localProgressByBookmarkId = progressMap).recomputeLibraryState()
                    }
                }
        }

        viewModelScope.launch {
            trackingRepository.accounts.collect { accounts ->
                val connected = registry.allConnected(accounts)
                val connectedDescriptors = connected.map { it.descriptor }
                val prevConnectedIds = uiState.value.connectedTrackers.map { it.id }.toSet()
                val nextConnectedIds = connectedDescriptors.map { it.id }.toSet()

                var activeTrackerToFetch: String? = null
                updateState {
                    val nextSection = when (val sec = selectedSection) {
                        is LibrarySection.Tracker -> if (sec.providerId in nextConnectedIds) sec else LibrarySection.LocalBookmarks
                        else -> sec
                    }
                    val nextTab = if (nextSection is LibrarySection.Tracker &&
                        nextSection.providerId == TrackerIds.SIMKL &&
                        selectedTab == LibraryWatchTab.REWATCHING
                    ) {
                        LibraryWatchTab.WATCHING
                    } else {
                        selectedTab
                    }
                    if (nextSection is LibrarySection.Tracker) {
                        val activeProv = connected.firstOrNull { it.id == nextSection.providerId }
                        val shouldFetch = activeProv != null && (
                            prevConnectedIds != nextConnectedIds ||
                                trackerQueryKeyByProvider[nextSection.providerId] == null ||
                                activeProv.requiresLibraryRefresh()
                            )
                        if (shouldFetch) {
                            activeTrackerToFetch = nextSection.providerId
                        }
                    }
                    copy(
                        connectedTrackers = connectedDescriptors,
                        selectedSection = nextSection,
                        selectedTab = nextTab,
                        trackerEntriesByProvider = trackerEntriesByProvider.filterKeys { it in nextConnectedIds },
                        trackerNextCursorByProvider = trackerNextCursorByProvider.filterKeys { it in nextConnectedIds },
                        trackerHasMoreByProvider = trackerHasMoreByProvider.filterKeys { it in nextConnectedIds },
                        trackerTotalCountByProvider = trackerTotalCountByProvider.filterKeys { it in nextConnectedIds },
                        trackerStatusCountsByProvider = trackerStatusCountsByProvider.filterKeys { it in nextConnectedIds },
                        trackerLoadedAllByProvider = trackerLoadedAllByProvider.filterKeys { it in nextConnectedIds },
                        trackerQueryKeyByProvider = trackerQueryKeyByProvider.filterKeys { it in nextConnectedIds },
                        quickEdit = quickEdit?.takeIf { it.entry.providerId in nextConnectedIds },
                    ).recomputeLibraryState()
                }
                refreshBindingsIndex()
                activeTrackerToFetch?.let { providerId ->
                    refreshTrackerSection(providerId = providerId, forceReset = true)
                }
            }
        }

        viewModelScope.launch {
            trackingRepository.bindingsVersion.collect {
                refreshBindingsIndex()
            }
        }

        viewModelScope.launch {
            trackingRepository.auditVersion.drop(1).collect { version ->
                if (version > 0L) {
                    handleExternalTrackerAuditUpdate()
                }
            }
        }

        viewModelScope.launch {
            AppearanceConfig.posterWidthDp.collect { width ->
                updateState { copy(posterWidthDp = width) }
            }
        }
    }

    override fun handleEvent(event: LibraryUiEvent) {
        when (event) {
            is LibraryUiEvent.OnSelectSection -> selectSection(event.section)
            is LibraryUiEvent.OnRefreshTorboxStatus -> refreshTorboxConfig()
            is LibraryUiEvent.OnResumeLibrary -> {
                refreshTorboxConfig()
                refreshActiveTrackerIfNeeded()
            }
            is LibraryUiEvent.OnSelectTab -> {
                updateState {
                    copy(selectedTab = event.tab, quickEdit = null).recomputeLibraryState()
                }
                maybeReloadActiveTrackerForFilterChange(tabChanged = true)
            }
            is LibraryUiEvent.OnSearchQueryChange -> updateState {
                copy(searchQuery = event.query).recomputeLibraryState()
            }
            is LibraryUiEvent.OnSortOptionChange -> {
                updateState {
                    copy(sortOption = event.sortOption).recomputeLibraryState()
                }
                maybeReloadActiveTrackerForFilterChange(tabChanged = false)
            }
            is LibraryUiEvent.OnProviderFilterChange -> updateState {
                copy(selectedProvider = event.provider).recomputeLibraryState()
            }
            is LibraryUiEvent.OnRefreshTrackers -> refreshTrackers(force = true)
            is LibraryUiEvent.OnLoadMoreTrackerEntries -> loadMoreActiveTrackerEntries()
            is LibraryUiEvent.OnOpenBookmark -> openBookmark(event.bookmark)
            is LibraryUiEvent.OnDeleteBookmark -> deleteBookmark(event.bookmarkId)
            is LibraryUiEvent.OnChangeWatchType -> changeWatchType(event.bookmarkId, event.newType)
            is LibraryUiEvent.OnStartReLink -> startBookmarkReLink(event.bookmark)
            is LibraryUiEvent.OnOpenTrackerEntry -> openTrackerEntry(event.entry)
            is LibraryUiEvent.OnFindStreamForTracker -> startTrackerStreamLookup(event.entry)
            is LibraryUiEvent.OnIncrementTrackerEpisode -> incrementTrackerEpisode(event.entry)
            is LibraryUiEvent.OnSelectStreamMatch -> selectStreamMatch(event.target, event.match)
            is LibraryUiEvent.OnSelectStreamAddon -> selectStreamAddon(event.target, event.exploreItem)
            is LibraryUiEvent.OnDismissStreamPicker -> dismissStreamPicker()
            is LibraryUiEvent.OnSearchGlobal -> searchGlobal()
            is LibraryUiEvent.OnStartQuickEdit -> startQuickEdit(event.entry)
            is LibraryUiEvent.OnSwitchQuickEditTracker -> Unit
            is LibraryUiEvent.OnDismissQuickEdit -> updateState { copy(quickEdit = null) }
            is LibraryUiEvent.OnEditQuickStatus -> updateState {
                val qe = quickEdit ?: return@updateState this
                val clamped = adjustedProgressForStatus(
                    currentProgress = qe.progress,
                    targetStatus = event.status,
                    isMovie = qe.entry.isMovie,
                    ceiling = qe.entry.releaseBounds.ceilingEpisode,
                )
                copy(quickEdit = qe.copy(status = event.status, progress = clamped, errorMessage = null))
            }
            is LibraryUiEvent.OnEditQuickProgress -> updateState {
                val qe = quickEdit ?: return@updateState this
                val ceiling = qe.entry.releaseBounds.ceilingEpisode
                val clamped = event.progress.coerceAtLeast(0).let { p -> if (ceiling != null) minOf(p, ceiling) else p }
                copy(quickEdit = qe.copy(progress = clamped, errorMessage = null))
            }
            is LibraryUiEvent.OnEditQuickScore -> updateState {
                val qe = quickEdit ?: return@updateState this
                copy(quickEdit = qe.copy(score100 = event.score100.coerceIn(0, 100), errorMessage = null))
            }
            is LibraryUiEvent.OnToggleQuickFavorite -> toggleQuickFavorite()
            is LibraryUiEvent.OnSaveQuickEdit -> saveQuickEdit()
            is LibraryUiEvent.OnRequestRemoveTrackerEntry -> updateState { copy(pendingRemoveTrackerEntry = event.entry) }
            is LibraryUiEvent.OnDismissRemoveTrackerConfirm -> updateState { copy(pendingRemoveTrackerEntry = null) }
            is LibraryUiEvent.OnConfirmRemoveTrackerEntry -> confirmRemoveTrackerEntry()
            is LibraryUiEvent.OnDismissError -> updateState { copy(showError = null, trackersError = null) }
        }
    }

    private fun selectSection(section: LibrarySection) {
        var trackerIdToLoad: String? = null
        updateState {
            val target = when (section) {
                LibrarySection.TorboxCloud -> if (isTorboxConfigured) section else LibrarySection.LocalBookmarks
                is LibrarySection.Tracker -> if (connectedTrackers.any { it.id == section.providerId }) section else LibrarySection.LocalBookmarks
                LibrarySection.LocalBookmarks -> section
            }
            val nextTab = if (target is LibrarySection.Tracker &&
                target.providerId == TrackerIds.SIMKL &&
                selectedTab == LibraryWatchTab.REWATCHING
            ) {
                LibraryWatchTab.WATCHING
            } else {
                selectedTab
            }
            if (target is LibrarySection.Tracker) {
                val prov = registry.get(target.providerId)
                val queryKey = "${nextTab.filterStatus?.name ?: "ALL"}:${sortOption.name}"
                val prevKey = trackerQueryKeyByProvider[target.providerId]
                val loadedAll = trackerLoadedAllByProvider[target.providerId] == true
                if (prevKey == null || prov?.requiresLibraryRefresh() == true || (!loadedAll && prevKey != queryKey)) {
                    trackerIdToLoad = target.providerId
                }
            }
            copy(
                selectedSection = target,
                selectedTab = nextTab,
                quickEdit = null,
            ).recomputeLibraryState()
        }
        trackerIdToLoad?.let { refreshTrackerSection(providerId = it, forceReset = true) }
    }

    private fun refreshTorboxConfig() {
        val reqSection = consumeRequestedSection()
        viewModelScope.launch(Dispatchers.IO) {
            val torboxConfigured = runCatching {
                DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY)?.trim()?.isNotBlank() == true
            }.getOrDefault(false)
            updateState {
                val desiredSection = when (reqSection) {
                    LibrarySection.TorboxCloud -> if (torboxConfigured) LibrarySection.TorboxCloud else LibrarySection.LocalBookmarks
                    is LibrarySection.Tracker -> if (connectedTrackers.any { it.id == reqSection.providerId }) reqSection else selectedSection
                    LibrarySection.LocalBookmarks -> LibrarySection.LocalBookmarks
                    null -> if (!torboxConfigured && selectedSection == LibrarySection.TorboxCloud) LibrarySection.LocalBookmarks else selectedSection
                }
                copy(
                    isTorboxConfigured = torboxConfigured,
                    selectedSection = desiredSection,
                ).recomputeLibraryState()
            }
        }
    }

    private fun refreshBindingsIndex() {
        bindingRefreshJob?.cancel()
        bindingRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val connected = registry.allConnected(trackingRepository)
            if (connected.isEmpty()) {
                updateState {
                    if (verifiedBindings.isEmpty()) this
                    else copy(verifiedBindings = emptyList()).recomputeLibraryState()
                }
                return@launch
            }
            val records = mutableListOf<LibraryTrackerBindingRecord>()
            runCatching {
                connected.forEach { provider ->
                    val bindings = trackingRepository.getAllBindingsForProfile(provider.id)
                    bindings.forEach { binding ->
                        if (binding.bindMode != BindMode.BLOCKED && binding.remoteMediaId.isNotBlank()) {
                            records += LibraryTrackerBindingRecord(
                                trackerId = provider.id,
                                showKey = binding.showKey,
                                remoteMediaId = binding.remoteMediaId,
                            )
                        }
                    }
                }
            }
            updateState {
                copy(verifiedBindings = records).recomputeLibraryState()
            }
        }
    }

    fun refreshTrackers(force: Boolean = false) {
        val activeSection = uiState.value.selectedSection as? LibrarySection.Tracker ?: return
        refreshTrackerSection(providerId = activeSection.providerId, forceReset = force)
    }

    private fun maybeReloadActiveTrackerForFilterChange(tabChanged: Boolean) {
        val state = uiState.value
        val sec = state.selectedSection as? LibrarySection.Tracker ?: return
        val pId = sec.providerId
        val prov = registry.get(pId)
        val needsRefresh = prov?.requiresLibraryRefresh() == true
        if (!needsRefresh && state.trackerLoadedAllByProvider[pId] == true) return
        val hasMore = state.trackerHasMoreByProvider[pId] == true
        if (needsRefresh || tabChanged || hasMore) {
            refreshTrackerSection(providerId = pId, forceReset = true)
        }
    }

    private fun refreshActiveTrackerIfNeeded() {
        val state = uiState.value
        val sec = state.selectedSection as? LibrarySection.Tracker ?: return
        val pId = sec.providerId
        val prov = registry.get(pId) ?: return
        if (!prov.isConnected(trackingRepository)) return
        val prevKey = state.trackerQueryKeyByProvider[pId]
        if (prevKey == null || prov.requiresLibraryRefresh()) {
            refreshTrackerSection(providerId = pId, forceReset = true)
        }
    }

    private fun handleExternalTrackerAuditUpdate() {
        val connected = registry.allConnected(trackingRepository)
        val dirtyExternalIds = connected
            .filter { prov ->
                val selfCount = selfSyncAuditCounts[prov.id]
                val isSelfAudit = if (selfCount != null && selfCount.get() > 0) {
                    selfCount.updateAndGet { prev -> if (prev > 0) prev - 1 else 0 }
                    true
                } else {
                    false
                }
                !isSelfAudit && prov.requiresLibraryRefresh()
            }
            .map { it.id }
            .toSet()

        if (dirtyExternalIds.isEmpty()) return

        var activeDirtyId: String? = null
        updateState {
            val sec = selectedSection as? LibrarySection.Tracker
            if (sec != null && sec.providerId in dirtyExternalIds) {
                activeDirtyId = sec.providerId
            }
            copy(
                trackerLoadedAllByProvider = trackerLoadedAllByProvider - dirtyExternalIds,
                trackerQueryKeyByProvider = trackerQueryKeyByProvider - dirtyExternalIds,
            )
        }
        activeDirtyId?.let { id ->
            refreshTrackerSection(providerId = id, forceReset = true)
        }
    }

    private fun markSelfAuditPending(providerId: String) {
        selfSyncAuditCounts.computeIfAbsent(providerId) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun refreshTrackerSection(providerId: String, forceReset: Boolean = false) {
        val provider = registry.get(providerId) ?: return
        if (!provider.isConnected(trackingRepository)) return
        if (!forceReset && uiState.value.isLoadingTrackers) return

        val stateSnapshot = uiState.value
        val statusFilter = stateSnapshot.selectedTab.filterStatus
        val sortKey = stateSnapshot.sortOption.name
        val queryKey = "${statusFilter?.name ?: "ALL"}:$sortKey"

        trackerLoadMoreJob?.cancel()
        trackerFetchJob?.cancel()
        updateState {
            copy(
                isLoadingTrackers = true,
                isLoadingMoreTrackers = false,
                trackersError = null,
            )
        }
        val currentJob = viewModelScope.launch(Dispatchers.IO) {
            val result = provider.fetchLibraryPage(
                repository = trackingRepository,
                statusFilter = statusFilter,
                sortKey = sortKey,
                cursor = null,
                pageSize = TRACKER_PAGE_SIZE,
            )
            result.fold(
                onSuccess = { page ->
                    refreshBindingsIndex()
                    val loadedAll = !page.supportsServerTabFilter || (statusFilter == null && !page.hasMore)
                    updateState {
                        val nextEntries = trackerEntriesByProvider + (providerId to page.entries)
                        val nextCursors = trackerNextCursorByProvider + (providerId to page.nextCursor)
                        val nextHasMore = trackerHasMoreByProvider + (providerId to page.hasMore)
                        val nextTotals = if (page.totalCount != null) {
                            trackerTotalCountByProvider + (providerId to page.totalCount)
                        } else {
                            trackerTotalCountByProvider
                        }
                        val nextStatusCounts = if (page.statusCounts != null) {
                            trackerStatusCountsByProvider + (providerId to page.statusCounts)
                        } else {
                            trackerStatusCountsByProvider
                        }
                        val nextLoadedAll = trackerLoadedAllByProvider + (providerId to loadedAll)
                        val nextQueryKeys = trackerQueryKeyByProvider + (providerId to queryKey)

                        val activeEdit = quickEdit?.let { qe ->
                            nextEntries[qe.entry.providerId]?.firstOrNull { it.uniqueKey == qe.entry.uniqueKey }?.let { fresh ->
                                qe.copy(entry = fresh)
                            }
                        }
                        copy(
                            isLoadingTrackers = false,
                            isLoadingMoreTrackers = false,
                            trackersError = null,
                            trackerEntriesByProvider = nextEntries,
                            trackerNextCursorByProvider = nextCursors,
                            trackerHasMoreByProvider = nextHasMore,
                            trackerTotalCountByProvider = nextTotals,
                            trackerStatusCountsByProvider = nextStatusCounts,
                            trackerLoadedAllByProvider = nextLoadedAll,
                            trackerQueryKeyByProvider = nextQueryKeys,
                            quickEdit = activeEdit,
                        ).recomputeLibraryState()
                    }
                },
                onFailure = { err ->
                    val message = "${provider.descriptor.displayName}: ${err.message ?: "failed"}"
                    updateState {
                        copy(
                            isLoadingTrackers = false,
                            isLoadingMoreTrackers = false,
                            trackersError = message,
                        )
                    }
                },
            )
        }
        trackerFetchJob = currentJob
    }

    private fun loadMoreActiveTrackerEntries() {
        val state = uiState.value
        val activeSection = state.selectedSection as? LibrarySection.Tracker ?: return
        val providerId = activeSection.providerId
        if (state.isLoadingTrackers || state.isLoadingMoreTrackers) return
        if (state.trackerHasMoreByProvider[providerId] != true) return
        val cursor = state.trackerNextCursorByProvider[providerId] ?: return
        val provider = registry.get(providerId) ?: return

        val statusFilter = state.selectedTab.filterStatus
        val sortKey = state.sortOption.name

        updateState { copy(isLoadingMoreTrackers = true) }
        trackerLoadMoreJob?.cancel()
        trackerLoadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            val result = provider.fetchLibraryPage(
                repository = trackingRepository,
                statusFilter = statusFilter,
                sortKey = sortKey,
                cursor = cursor,
                pageSize = TRACKER_PAGE_SIZE,
            )
            result.fold(
                onSuccess = { page ->
                    val loadedAll = !page.supportsServerTabFilter || (statusFilter == null && !page.hasMore)
                    updateState {
                        val existing = trackerEntriesByProvider[providerId].orEmpty()
                        val seenKeys = existing.mapTo(HashSet(existing.size + page.entries.size)) { it.uniqueKey }
                        val mergedList = ArrayList<UnifiedLibraryEntry>(existing.size + page.entries.size).apply {
                            addAll(existing)
                            for (item in page.entries) {
                                if (seenKeys.add(item.uniqueKey)) add(item)
                            }
                        }
                        copy(
                            isLoadingMoreTrackers = false,
                            trackerEntriesByProvider = trackerEntriesByProvider + (providerId to mergedList),
                            trackerNextCursorByProvider = trackerNextCursorByProvider + (providerId to page.nextCursor),
                            trackerHasMoreByProvider = trackerHasMoreByProvider + (providerId to page.hasMore),
                            trackerLoadedAllByProvider = if (loadedAll) {
                                trackerLoadedAllByProvider + (providerId to true)
                            } else {
                                trackerLoadedAllByProvider
                            },
                        ).recomputeLibraryState()
                    }
                },
                onFailure = { err ->
                    updateState { copy(isLoadingMoreTrackers = false) }
                    sendEffect(
                        LibraryUiEffect.ShowToast(
                            "${provider.descriptor.displayName}: ${err.message ?: "failed to load more"}",
                            isError = true,
                        ),
                    )
                },
            )
        }
    }

    private fun canonicalSeedForBookmark(bookmark: DesktopBookmark): CanonicalMediaSeed? {
        val links = uiState.value.linkedTrackersByBookmarkId[bookmark.id].orEmpty()
        val alId = links.firstOrNull { it.providerId == TrackerIds.ANILIST }?.remoteMediaId?.toIntOrNull()
        val malId = links.firstOrNull { it.providerId == TrackerIds.MAL }?.remoteMediaId?.toIntOrNull()
        if (alId == null && malId == null) return null
        return CanonicalMediaSeed(
            origin = MediaSeedOrigin.EXPLORE,
            anilistId = alId,
            malId = malId,
        )
    }

    private fun canonicalSeedForTracker(
        entry: UnifiedLibraryEntry,
        exploreItem: ExploreItem? = null,
    ): CanonicalMediaSeed? {
        val alId = if (entry.providerId == TrackerIds.ANILIST) entry.remoteMediaId.toIntOrNull() else exploreItem?.anilistId
        val malId = if (entry.providerId == TrackerIds.MAL) entry.remoteMediaId.toIntOrNull() else entry.malId ?: exploreItem?.malId
        if (alId == null && malId == null) return null
        return CanonicalMediaSeed(
            origin = MediaSeedOrigin.EXPLORE,
            anilistId = alId,
            malId = malId,
        )
    }

    private fun openBookmark(bookmark: DesktopBookmark) {
        val provider = APIHolder.allProviders.firstOrNull {
            it.name == bookmark.apiName && it.mainUrl.isNotBlank() && bookmark.url.startsWith(it.mainUrl)
        } ?: APIHolder.getApiFromNameNull(bookmark.apiName)
        if (provider != null) {
            sendEffect(
                LibraryUiEffect.Navigate(
                    Config.Details(
                        providerName = provider.name,
                        url = bookmark.url,
                        preloadedName = bookmark.name,
                        preloadedPoster = bookmark.posterUrl,
                        canonicalSeed = canonicalSeedForBookmark(bookmark),
                    ),
                ),
            )
        } else {
            startBookmarkReLink(bookmark)
        }
    }

    private fun startBookmarkReLink(bookmark: DesktopBookmark) {
        val target = LibraryStreamTarget(
            kind = LibraryStreamTargetKind.BOOKMARK_RELINK,
            title = bookmark.name,
            posterUrl = bookmark.posterUrl,
            bookmark = bookmark,
            canonicalSeed = canonicalSeedForBookmark(bookmark),
        )
        startStreamLookup(target)
    }

    private fun openTrackerEntry(entry: UnifiedLibraryEntry) {
        val linkedBookmark = uiState.value.linkedBookmarkByTrackerKey[entry.uniqueKey]
        if (linkedBookmark != null) {
            val provider = APIHolder.allProviders.firstOrNull {
                it.name == linkedBookmark.apiName && it.mainUrl.isNotBlank() && linkedBookmark.url.startsWith(it.mainUrl)
            } ?: APIHolder.getApiFromNameNull(linkedBookmark.apiName)
            if (provider != null) {
                sendEffect(
                    LibraryUiEffect.Navigate(
                        Config.Details(
                            providerName = provider.name,
                            url = linkedBookmark.url,
                            preloadedName = linkedBookmark.name.ifBlank { entry.title },
                            preloadedPoster = linkedBookmark.posterUrl ?: entry.posterUrl,
                            canonicalSeed = canonicalSeedForTracker(entry),
                        ),
                    ),
                )
                return
            }
        }
        startTrackerStreamLookup(entry)
    }

    private fun startTrackerStreamLookup(entry: UnifiedLibraryEntry) {
        val seed = canonicalSeedForTracker(entry)
        val target = LibraryStreamTarget(
            kind = LibraryStreamTargetKind.TRACKER_ENTRY,
            title = entry.title,
            posterUrl = entry.posterUrl,
            isMovie = entry.isMovie,
            year = entry.seasonYear,
            trackerEntry = entry,
            canonicalSeed = seed,
        )
        startStreamLookup(target)
    }

    private fun startStreamLookup(target: LibraryStreamTarget) {
        val seed = target.canonicalSeed
        val initialExplore = ExploreItem(
            id = target.bookmark?.id ?: target.trackerEntry?.uniqueKey ?: target.title,
            type = if (target.isMovie) "movie" else "series",
            name = target.title,
            posterUrl = target.posterUrl,
            releaseYear = target.year?.toString(),
            anilistId = seed?.anilistId,
            malId = seed?.malId,
        )
        updateState {
            copy(
                streamLookupTarget = target,
                streamExploreItem = initialExplore,
                isSearchingMatches = true,
                matchedResults = emptyList(),
            )
        }
        streamLookupJob?.cancel()
        streamLookupJob = viewModelScope.launch(Dispatchers.IO) {
            launch {
                try {
                    val enriched = ReLinkSearchCoordinator.resolveExploreItem(
                        rawTitle = target.title,
                        posterUrl = target.posterUrl,
                        isSeriesHint = !target.isMovie,
                    )
                    updateState { copy(streamExploreItem = enriched) }
                } catch (e: Exception) {
                    AppLogger.d("Library: metadata enrichment skipped: ${e.message}")
                }
            }
            try {
                ReLinkSearchCoordinator.searchRealProvidersProgressive(
                    searchTitle = target.title,
                    onUpdate = { matches ->
                        updateState { copy(matchedResults = matches) }
                    },
                )
            } catch (e: Exception) {
                AppLogger.e("Library: provider search failed", e)
            } finally {
                updateState { copy(isSearchingMatches = false) }
            }
        }
    }

    private fun selectStreamMatch(
        target: LibraryStreamTarget,
        match: ProviderMatch,
    ) {
        val exploreSnapshot = uiState.value.streamExploreItem
        dismissStreamPicker()
        val bookmark = target.bookmark
        val seed = target.canonicalSeed ?: target.trackerEntry?.let { canonicalSeedForTracker(it, exploreSnapshot) }
        if (target.kind == LibraryStreamTargetKind.BOOKMARK_RELINK && bookmark != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    DesktopDataStore.migrateWatchHistoryAndBookmark(
                        oldApiName = bookmark.apiName,
                        oldUrl = bookmark.url,
                        newApiName = match.providerName,
                        newUrl = match.searchResponse.url,
                        newName = match.searchResponse.name,
                        newPosterUrl = match.searchResponse.posterUrl ?: bookmark.posterUrl,
                    )
                    val newId = DesktopDataStore.bookmarkId(match.providerName, match.searchResponse.url)
                    val updated = DesktopBookmark(
                        id = newId,
                        apiName = match.providerName,
                        url = match.searchResponse.url,
                        name = match.searchResponse.name,
                        posterUrl = match.searchResponse.posterUrl ?: bookmark.posterUrl,
                        watchType = bookmark.watchType,
                        dateAdded = bookmark.dateAdded,
                    )
                    toggleBookmark.saveBookmark(updated)
                    if (bookmark.id != newId) {
                        removeBookmark.await(bookmark.id)
                    }
                    sendEffect(
                        LibraryUiEffect.Navigate(
                            Config.Details(
                                providerName = match.providerName,
                                url = match.searchResponse.url,
                                preloadedName = match.displayTitle,
                                preloadedPoster = match.searchResponse.posterUrl ?: bookmark.posterUrl,
                                canonicalSeed = seed,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    AppLogger.e("LibraryViewModel: Failed to save re-linked bookmark: ${e.message}")
                }
            }
        } else {
            sendEffect(
                LibraryUiEffect.Navigate(
                    Config.Details(
                        providerName = match.providerName,
                        url = match.searchResponse.url,
                        preloadedName = match.displayTitle,
                        preloadedPoster = match.searchResponse.posterUrl ?: target.posterUrl,
                        canonicalSeed = seed,
                    ),
                ),
            )
        }
    }

    private fun selectStreamAddon(
        target: LibraryStreamTarget,
        exploreItem: ExploreItem,
    ) {
        dismissStreamPicker()
        val stremioUrl = "stremio://${exploreItem.type}/${exploreItem.id}"
        val newApiName = "Stremio"
        val bookmark = target.bookmark
        val seed = target.canonicalSeed ?: target.trackerEntry?.let { canonicalSeedForTracker(it, exploreItem) }
        if (target.kind == LibraryStreamTargetKind.BOOKMARK_RELINK && bookmark != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    DesktopDataStore.migrateWatchHistoryAndBookmark(
                        oldApiName = bookmark.apiName,
                        oldUrl = bookmark.url,
                        newApiName = newApiName,
                        newUrl = stremioUrl,
                        newName = exploreItem.name,
                        newPosterUrl = exploreItem.posterUrl ?: bookmark.posterUrl,
                    )
                    val newId = DesktopDataStore.bookmarkId(newApiName, stremioUrl)
                    val updated = DesktopBookmark(
                        id = newId,
                        apiName = newApiName,
                        url = stremioUrl,
                        name = exploreItem.name,
                        posterUrl = exploreItem.posterUrl ?: bookmark.posterUrl,
                        watchType = bookmark.watchType,
                        dateAdded = bookmark.dateAdded,
                    )
                    toggleBookmark.saveBookmark(updated)
                    if (bookmark.id != newId) {
                        removeBookmark.await(bookmark.id)
                    }
                    sendEffect(
                        LibraryUiEffect.Navigate(
                            Config.Details(
                                providerName = newApiName,
                                url = stremioUrl,
                                preloadedName = exploreItem.name,
                                preloadedPoster = exploreItem.posterUrl ?: bookmark.posterUrl,
                                canonicalSeed = seed,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    AppLogger.e("LibraryViewModel: Failed to transfer bookmark to Addon: ${e.message}")
                }
            }
        } else {
            sendEffect(
                LibraryUiEffect.Navigate(
                    Config.Details(
                        providerName = newApiName,
                        url = stremioUrl,
                        preloadedName = exploreItem.name,
                        preloadedPoster = exploreItem.posterUrl ?: target.posterUrl,
                        canonicalSeed = seed,
                    ),
                ),
            )
        }
    }

    private fun dismissStreamPicker() {
        streamLookupJob?.cancel()
        updateState {
            copy(
                streamLookupTarget = null,
                streamExploreItem = null,
                isSearchingMatches = false,
                matchedResults = emptyList(),
            )
        }
    }

    private fun incrementTrackerEpisode(entry: UnifiedLibraryEntry) {
        if (uiState.value.incrementingTrackerKey != null) return
        val provider = registry.get(entry.providerId) ?: return
        val targetEpisode = entry.progress + 1
        when (val epCheck = provider.canSyncEpisode(entry.releaseBounds, rawEpisodeNumber = targetEpisode)) {
            is EpisodeSyncCheck.Blocked -> {
                sendEffect(LibraryUiEffect.ShowToast(epCheck.reason, isError = true))
                return
            }
            is EpisodeSyncCheck.Allowed -> Unit
        }
        val nextStatus = provider.nextStatusForManualEpisodeSync(
            bounds = entry.releaseBounds,
            currentStatus = entry.status,
            effectiveEpisode = targetEpisode,
        )

        updateState { copy(incrementingTrackerKey = entry.uniqueKey) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider.updateRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = entry.remoteMediaId,
                update = TrackerStatusUpdate(
                    status = nextStatus,
                    progress = targetEpisode,
                    score100 = null,
                ),
                bounds = entry.releaseBounds,
            )
            markSelfAuditPending(provider.id)
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = provider.id,
                showName = entry.title,
                remoteMediaId = entry.remoteMediaId,
                episodeNum = targetEpisode.toLong(),
                effectiveRemoteEpisode = targetEpisode.toLong(),
                oldProgress = entry.progress.toLong(),
                newProgress = if (ok) targetEpisode.toLong() else entry.progress.toLong(),
                statusApplied = nextStatus.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "+1 Episode from ${provider.descriptor.displayName} Library" else "${provider.descriptor.displayName} rejected increment",
                timestamp = System.currentTimeMillis(),
            )
            if (ok) {
                val updatedEntry = entry.copy(
                    progress = targetEpisode,
                    status = nextStatus,
                    updatedAt = System.currentTimeMillis() / 1000L,
                )
                updateState {
                    val updatedList = trackerEntriesByProvider[provider.id].orEmpty().map {
                        if (it.uniqueKey == entry.uniqueKey) updatedEntry else it
                    }
                    val adjustedCounts = trackerStatusCountsByProvider[provider.id]?.let { oldMap ->
                        if (entry.status != nextStatus) {
                            oldMap.toMutableMap().apply {
                                val oldCount = this[entry.status] ?: 0
                                if (oldCount > 0) this[entry.status] = oldCount - 1
                                this[nextStatus] = (this[nextStatus] ?: 0) + 1
                            }
                        } else {
                            oldMap
                        }
                    }
                    copy(
                        incrementingTrackerKey = null,
                        trackerEntriesByProvider = trackerEntriesByProvider + (provider.id to updatedList),
                        trackerStatusCountsByProvider = if (adjustedCounts != null) {
                            trackerStatusCountsByProvider + (provider.id to adjustedCounts)
                        } else {
                            trackerStatusCountsByProvider
                        },
                    ).recomputeLibraryState()
                }
                sendEffect(LibraryUiEffect.ShowToast("Updated '${entry.title}' to Ep $targetEpisode (${nextStatus.label})"))
            } else {
                updateState { copy(incrementingTrackerKey = null) }
                sendEffect(LibraryUiEffect.ShowToast("Failed to update '${entry.title}' on ${provider.descriptor.displayName}", isError = true))
            }
        }
    }

    private fun startQuickEdit(entry: UnifiedLibraryEntry) {
        val descriptor = descriptorFor(entry.providerId) ?: return
        updateState {
            copy(
                quickEdit = LibraryTrackerQuickEdit(
                    itemKey = entry.uniqueKey,
                    entry = entry,
                    descriptor = descriptor,
                    availableEntries = listOf(entry),
                    status = entry.status,
                    progress = entry.progress,
                    score100 = entry.score100,
                    isFavourite = entry.isFavourite,
                ),
            )
        }
    }

    private fun descriptorFor(providerId: String): TrackerDescriptor? =
        registry.get(providerId)?.descriptor
            ?: uiState.value.connectedTrackers.firstOrNull { it.id == providerId }

    private fun toggleQuickFavorite() {
        val qe = uiState.value.quickEdit ?: return
        val provider = registry.get(qe.entry.providerId) ?: return
        if (!provider.descriptor.supportsFavorites) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider.toggleFavorite(trackingRepository, qe.entry.remoteMediaId) == true
            if (ok) {
                val nextFav = !qe.isFavourite
                updateState {
                    val updatedList = trackerEntriesByProvider[provider.id].orEmpty().map {
                        if (it.uniqueKey == qe.entry.uniqueKey) it.copy(isFavourite = nextFav) else it
                    }
                    copy(
                        quickEdit = quickEdit?.copy(isFavourite = nextFav),
                        trackerEntriesByProvider = trackerEntriesByProvider + (provider.id to updatedList),
                    ).recomputeLibraryState()
                }
            }
        }
    }

    private fun saveQuickEdit() {
        val qe = uiState.value.quickEdit ?: return
        val provider = registry.get(qe.entry.providerId) ?: return
        val decision = provider.validateStatusAndProgress(
            bounds = qe.entry.releaseBounds,
            status = qe.status,
            progress = qe.progress,
        )
        if (decision is GuardDecision.Blocked) {
            updateState { copy(quickEdit = qe.copy(errorMessage = decision.reason)) }
            return
        }

        updateState { copy(quickEdit = qe.copy(isSaving = true, errorMessage = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val clearScore = qe.score100 == 0 && qe.entry.score100 > 0
            val ok = provider.updateRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = qe.entry.remoteMediaId,
                update = TrackerStatusUpdate(
                    status = qe.status,
                    progress = qe.progress,
                    score100 = qe.score100.takeIf { it > 0 },
                    clearScore = clearScore,
                ),
                bounds = qe.entry.releaseBounds,
            )
            markSelfAuditPending(provider.id)
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = provider.id,
                showName = qe.entry.title,
                remoteMediaId = qe.entry.remoteMediaId,
                episodeNum = qe.progress.toLong(),
                effectiveRemoteEpisode = qe.progress.toLong(),
                oldProgress = qe.entry.progress.toLong(),
                newProgress = if (ok) qe.progress.toLong() else qe.entry.progress.toLong(),
                statusApplied = qe.status.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Manual ${provider.descriptor.displayName} Library edit" else "${provider.descriptor.displayName} rejected update",
                timestamp = System.currentTimeMillis(),
            )
            if (ok) {
                updateState {
                    copy(
                        quickEdit = null,
                        trackerLoadedAllByProvider = trackerLoadedAllByProvider - provider.id,
                    )
                }
                sendEffect(LibraryUiEffect.ShowToast("Updated '${qe.entry.title}' on ${provider.descriptor.displayName}"))
                refreshTrackerSection(providerId = provider.id, forceReset = true)
            } else {
                updateState {
                    copy(quickEdit = qe.copy(isSaving = false, errorMessage = "Failed to save changes to ${provider.descriptor.displayName}."))
                }
            }
        }
    }

    private fun confirmRemoveTrackerEntry() {
        val target = uiState.value.pendingRemoveTrackerEntry ?: return
        val provider = registry.get(target.providerId) ?: return
        updateState { copy(pendingRemoveTrackerEntry = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider.removeEntry(trackingRepository, target.remoteMediaId)
            markSelfAuditPending(provider.id)
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = provider.id,
                showName = target.title,
                remoteMediaId = target.remoteMediaId,
                episodeNum = 0L,
                effectiveRemoteEpisode = 0L,
                oldProgress = target.progress.toLong(),
                newProgress = 0L,
                statusApplied = "NONE",
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Removed from ${provider.descriptor.displayName} Library" else "Failed to remove entry",
                timestamp = System.currentTimeMillis(),
            )
            if (ok) {
                updateState {
                    val remaining = trackerEntriesByProvider[provider.id].orEmpty().filterNot { it.uniqueKey == target.uniqueKey }
                    val adjustedCounts = trackerStatusCountsByProvider[provider.id]?.let { oldMap ->
                        oldMap.toMutableMap().apply {
                            val prev = this[target.status] ?: 0
                            if (prev > 0) this[target.status] = prev - 1
                        }
                    }
                    val adjustedTotal = trackerTotalCountByProvider[provider.id]?.let { t -> (t - 1).coerceAtLeast(0) }
                    copy(
                        quickEdit = if (quickEdit?.entry?.uniqueKey == target.uniqueKey) null else quickEdit,
                        trackerEntriesByProvider = trackerEntriesByProvider + (provider.id to remaining),
                        trackerStatusCountsByProvider = if (adjustedCounts != null) {
                            trackerStatusCountsByProvider + (provider.id to adjustedCounts)
                        } else {
                            trackerStatusCountsByProvider
                        },
                        trackerTotalCountByProvider = if (adjustedTotal != null) {
                            trackerTotalCountByProvider + (provider.id to adjustedTotal)
                        } else {
                            trackerTotalCountByProvider
                        },
                    ).recomputeLibraryState()
                }
                sendEffect(LibraryUiEffect.ShowToast("Removed '${target.title}' from ${provider.descriptor.displayName}"))
                refreshTrackerSection(providerId = provider.id, forceReset = true)
            } else {
                sendEffect(LibraryUiEffect.ShowToast("Failed to remove '${target.title}'", isError = true))
                refreshTrackerSection(providerId = provider.id, forceReset = true)
            }
        }
    }

    private fun changeWatchType(bookmarkId: String, newType: DesktopWatchType) {
        viewModelScope.launch(Dispatchers.IO) {
            setItemCategory.await(bookmarkId, newType.id)
        }
    }

    private fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            removeBookmark.await(bookmarkId)
        }
    }

    private fun searchGlobal() {
        dismissStreamPicker()
        sendEffect(LibraryUiEffect.Navigate(Config.Search))
    }

    override fun dispose() {
        streamLookupJob?.cancel()
        trackerFetchJob?.cancel()
        trackerLoadMoreJob?.cancel()
        bindingRefreshJob?.cancel()
        streamLookupJob = null
        trackerFetchJob = null
        trackerLoadMoreJob = null
        bindingRefreshJob = null
        super.dispose()
    }
}

internal fun computeLocalBookmarkProgress(allHistory: List<WatchHistory>): Map<String, LocalBookmarkProgress> {
    if (allHistory.isEmpty()) return emptyMap()
    val grouped = allHistory.filter {
        !(it.episodeId?.startsWith("unreleased_") == true) &&
            !(it.episodeId?.startsWith("synthetic_") == true) &&
            (it.position > 0L || it.duration > 0L)
    }.groupBy { it.parentId }

    val result = LinkedHashMap<String, LocalBookmarkProgress>(grouped.size)
    for ((parentId, entries) in grouped) {
        val latest = entries.maxByOrNull { it.updateTime } ?: continue
        val completed = latest.duration > 0L && PlayerLinkHandler.isCompleted(latest.position, latest.duration)
        val fraction = if (latest.duration > 0L) {
            if (completed) 1f else (latest.position.toFloat() / latest.duration.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        val label = when {
            latest.season != null && latest.episode != null -> "S${latest.season} E${latest.episode}"
            latest.episode != null -> "Ep ${latest.episode}"
            completed -> "Watched"
            fraction != null -> "${(fraction * 100f).toInt()}%"
            else -> "In Progress"
        }
        result[parentId] = LocalBookmarkProgress(
            label = label,
            progressFraction = fraction,
            isCompleted = completed,
            lastWatchedAtMs = normalizeLibraryEpochMillis(latest.updateTime),
        )
    }
    return result
}

internal fun LibraryUiState.recomputeLibraryState(): LibraryUiState {
    val bookmarksById = bookmarks.associateBy { it.id }
    val descriptorsById = connectedTrackers.associateBy { it.id }
    val entryByKey = LinkedHashMap<String, UnifiedLibraryEntry>()
    for ((_, list) in trackerEntriesByProvider) {
        for (entry in list) {
            entryByKey[entry.uniqueKey] = entry
        }
    }

    val nextLinkedBookmarkByTrackerKey = LinkedHashMap<String, DesktopBookmark>()
    val nextLinkedTrackersByBookmarkId = LinkedHashMap<String, MutableList<LocalTrackerLinkInfo>>()
    for (record in verifiedBindings) {
        val bm = bookmarksById[record.showKey] ?: continue
        val trackerKey = "${record.trackerId}:${record.remoteMediaId}"
        nextLinkedBookmarkByTrackerKey[trackerKey] = bm
        val desc = descriptorsById[record.trackerId] ?: continue
        val remoteEntry = entryByKey[trackerKey]
        val list = nextLinkedTrackersByBookmarkId.getOrPut(bm.id) { mutableListOf() }
        if (list.none { it.providerId == record.trackerId }) {
            list += LocalTrackerLinkInfo(
                providerId = record.trackerId,
                descriptor = desc,
                remoteMediaId = record.remoteMediaId,
                progress = remoteEntry?.progress,
                totalEpisodes = remoteEntry?.totalEpisodes,
            )
        }
    }

    // 1. Strict Local Bookmarks pipeline
    val providerScopedBookmarks = selectedProvider?.let { prov ->
        bookmarks.filter { it.apiName == prov }
    } ?: bookmarks

    val nextLocalTabCounts = LibraryWatchTab.entries.associateWith { tab ->
        val target = tab.filterStatus
        if (target == null) {
            providerScopedBookmarks.size
        } else {
            providerScopedBookmarks.count { it.watchType.toUnifiedWatchStatus() == target }
        }
    }

    var localFiltered = selectedTab.filterStatus?.let { target ->
        providerScopedBookmarks.filter { it.watchType.toUnifiedWatchStatus() == target }
    } ?: providerScopedBookmarks

    val q = searchQuery.trim()
    if (q.isNotEmpty()) {
        localFiltered = localFiltered.filter { it.name.contains(q, ignoreCase = true) }
    }

    fun bookmarkScore(bmId: String): Int =
        nextLinkedTrackersByBookmarkId[bmId]
            ?.mapNotNull { info -> entryByKey["${info.providerId}:${info.remoteMediaId}"]?.score100 }
            ?.maxOrNull() ?: 0

    fun bookmarkYear(bmId: String): Int =
        nextLinkedTrackersByBookmarkId[bmId]
            ?.mapNotNull { info -> entryByKey["${info.providerId}:${info.remoteMediaId}"]?.seasonYear }
            ?.firstOrNull() ?: 0

    fun bookmarkLatestMs(bm: DesktopBookmark): Long = maxOf(
        normalizeLibraryEpochMillis(bm.dateAdded),
        localProgressByBookmarkId[bm.id]?.lastWatchedAtMs ?: 0L,
    )

    localFiltered = when (sortOption) {
        SortOption.RECENTLY_UPDATED -> localFiltered.sortedByDescending { bookmarkLatestMs(it) }
        SortOption.DATE_ADDED_ASC -> localFiltered.sortedBy { normalizeLibraryEpochMillis(it.dateAdded) }
        SortOption.ALPHA_ASC -> localFiltered.sortedBy { it.name.lowercase(Locale.ROOT) }
        SortOption.ALPHA_DESC -> localFiltered.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        SortOption.SCORE_DESC -> localFiltered.sortedWith(
            compareByDescending<DesktopBookmark> { bookmarkScore(it.id) }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )
        SortOption.YEAR_DESC -> localFiltered.sortedWith(
            compareByDescending<DesktopBookmark> { bookmarkYear(it.id) }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    // 2. Strict Per-Tracker Cloud List pipeline
    val activeProviderId = (selectedSection as? LibrarySection.Tracker)?.providerId
    val providerEntries = activeProviderId?.let { trackerEntriesByProvider[it].orEmpty() }.orEmpty()
    val serverStatusCounts = activeProviderId?.let { trackerStatusCountsByProvider[it] }
    val serverTotalCount = activeProviderId?.let { trackerTotalCountByProvider[it] }

    val nextTrackerTabCounts = if (serverStatusCounts != null) {
        LibraryWatchTab.entries.associateWith { tab ->
            val target = tab.filterStatus
            if (target == null) {
                serverTotalCount ?: serverStatusCounts.values.sum()
            } else {
                serverStatusCounts[target] ?: 0
            }
        }
    } else {
        LibraryWatchTab.entries.associateWith { tab ->
            val target = tab.filterStatus
            if (target == null) {
                providerEntries.size
            } else {
                providerEntries.count { it.status == target }
            }
        }
    }

    var trackerFiltered = selectedTab.filterStatus?.let { target ->
        providerEntries.filter { it.status == target }
    } ?: providerEntries

    if (q.isNotEmpty()) {
        trackerFiltered = trackerFiltered.filter { it.title.contains(q, ignoreCase = true) }
    }

    trackerFiltered = when (sortOption) {
        SortOption.RECENTLY_UPDATED -> trackerFiltered.sortedByDescending { normalizeLibraryEpochMillis(it.updatedAt) }
        SortOption.DATE_ADDED_ASC -> trackerFiltered.sortedBy { normalizeLibraryEpochMillis(it.updatedAt) }
        SortOption.ALPHA_ASC -> trackerFiltered.sortedBy { it.title.lowercase(Locale.ROOT) }
        SortOption.ALPHA_DESC -> trackerFiltered.sortedByDescending { it.title.lowercase(Locale.ROOT) }
        SortOption.SCORE_DESC -> trackerFiltered.sortedWith(
            compareByDescending<UnifiedLibraryEntry> { it.score100 }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        SortOption.YEAR_DESC -> trackerFiltered.sortedWith(
            compareByDescending<UnifiedLibraryEntry> { it.seasonYear ?: 0 }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    return copy(
        filteredBookmarks = localFiltered,
        localTabCounts = nextLocalTabCounts,
        filteredTrackerEntries = trackerFiltered,
        trackerTabCounts = nextTrackerTabCounts,
        linkedBookmarkByTrackerKey = nextLinkedBookmarkByTrackerKey,
        linkedTrackersByBookmarkId = nextLinkedTrackersByBookmarkId.mapValues { it.value.toList() },
    )
}
