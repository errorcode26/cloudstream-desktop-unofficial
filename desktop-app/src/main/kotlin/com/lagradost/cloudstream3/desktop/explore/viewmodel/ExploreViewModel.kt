package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetWatchHistory
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCatalogClient
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCatalogDiscoverer
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCollectionsHub
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ExploreSearchScope
import com.lagradost.cloudstream3.desktop.explore.models.ExploreShelf
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.explore.models.StreamingPlatform
import com.lagradost.cloudstream3.desktop.explore.search.ExploreSearchEngine
import com.lagradost.cloudstream3.desktop.explore.search.ExploreSearchResults
import com.lagradost.cloudstream3.desktop.explore.settings.ExploreCatalogSettingsManager
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.cloudstream3.desktop.stremio.ManagedStremioAddon
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.LinkedHashMap

class ExploreViewModel(
    private val getWatchHistory: GetWatchHistory = AppContainerHolder.container.getWatchHistory,
) : BaseMviViewModel<ExploreUiState, ExploreUiEvent, ExploreUiEffect>(ExploreUiState()) {
    private val TAG = "ExploreViewModel"

    private var refreshJob: Job? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadShelvesJob: Job? = null
    private var loadPlatformShelvesJob: Job? = null
    private var providerSearchJob: Job? = null
    private var liveSearchJob: Job? = null
    private var collectionLoadJob: Job? = null

    private val searchSemaphore = Semaphore(8)
    private val shelfFetchSemaphore = Semaphore(8)

    companion object {
        private const val MAX_CACHE_ENTRIES = 120
    }

    private val nativeCatalogs: List<ManifestCatalogDescriptor> by lazy {
        ExploreNativeCatalogs.buildNativeCatalogs()
    }

    private val catalogItemsCache: MutableMap<String, List<ExploreItem>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<ExploreItem>>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExploreItem>>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        },
    )

    init {
        viewModelScope.launch {
            StremioAddonManager.addons.collect {
                refreshCatalogs()
            }
        }

        viewModelScope.launch {
            getWatchHistory.subscribeAll().collect { histories ->
                val map = mutableMapOf<String, WatchHistory>()
                histories.sortedBy { it.updateTime }.forEach { hist ->
                    map[hist.parentId] = hist
                    val cleanId = hist.showUrl.substringAfterLast("/").takeIf { it.isNotBlank() }
                    if (cleanId != null) {
                        map[cleanId] = hist
                    }
                    val epId = hist.episodeId
                    if (!epId.isNullOrBlank()) {
                        val epClean = epId.substringBefore(":")
                        map[epClean] = hist
                    }
                }
                updateState { copy(watchHistoryMap = map) }
            }
        }

        viewModelScope.launch {
            ExploreCatalogSettingsManager.preferences.collect {
                val state = uiState.value
                if (state.isShelvesMode && state.drilledCatalog == null && state.drilledPlatform == null && state.searchQuery.isBlank() && state.allCatalogs.isNotEmpty()) {
                    val forType = state.allCatalogs.filter {
                        ExploreFilterEngine.matchesType(it, state.selectedType) &&
                            (state.selectedAddon == "All Sources" || it.addonName.equals(state.selectedAddon, ignoreCase = true))
                    }
                    val orderedAndFiltered = ExploreCatalogSettingsManager.filterAndSort(forType)
                    loadShelves(state.selectedType, orderedAndFiltered)
                }
            }
        }

        viewModelScope.launch {
            MetadataConfig.animeTitleLanguage.drop(1).collect {
                catalogItemsCache.clear()
                val state = uiState.value
                when {
                    state.drilledPlatform != null -> loadPlatformShelves(state.drilledPlatform)
                    state.isShelvesMode && state.drilledCatalog == null && state.filteredCatalogs.isNotEmpty() -> {
                        updateState { copy(heroItems = emptyList()) }
                        val orderedAndFiltered = ExploreCatalogSettingsManager.filterAndSort(state.filteredCatalogs)
                        loadShelves(state.selectedType, orderedAndFiltered)
                    }
                    state.selectedCatalog != null -> loadCurrentCatalog(skip = 0)
                }
                if (state.searchQuery.isNotBlank()) {
                    updateSearchQuery(state.searchQuery)
                }
            }
        }
    }

    override fun handleEvent(event: ExploreUiEvent) {
        when (event) {
            is ExploreUiEvent.SelectType -> selectType(event.type)
            is ExploreUiEvent.SelectAddon -> selectAddon(event.addonName)
            is ExploreUiEvent.SelectCatalog -> selectCatalog(event.catalog)
            is ExploreUiEvent.SelectGenre -> selectGenre(event.genre)
            is ExploreUiEvent.SelectYear -> selectYear(event.year)
            is ExploreUiEvent.SelectSearchScope -> selectSearchScope(event.scope)
            is ExploreUiEvent.UpdateSearchQuery -> updateSearchQuery(event.query)
            is ExploreUiEvent.ClearSearchQuery -> clearSearchQuery()
            is ExploreUiEvent.OpenProviderPicker -> openProviderPicker(event.item)
            is ExploreUiEvent.CloseProviderPicker -> closeProviderPicker()
            is ExploreUiEvent.SelectProviderMatch -> selectProviderMatch(event.match)
            is ExploreUiEvent.LoadMore -> loadMore()
            is ExploreUiEvent.RefreshCatalogs -> refreshCatalogs(clearCache = true)
            is ExploreUiEvent.ToggleViewMode -> toggleViewMode(event.isShelves)
            is ExploreUiEvent.DrillIntoCatalog -> drillIntoCatalog(event.catalog)
            is ExploreUiEvent.DrillIntoPlatform -> drillIntoPlatform(event.platform)
            is ExploreUiEvent.ReturnToShelves -> returnToShelves()
            is ExploreUiEvent.DrillIntoSearchCategory -> updateState { copy(drilledSearchCategory = event.category) }
            is ExploreUiEvent.ReturnFromSearchCategory -> updateState { copy(drilledSearchCategory = null) }
            is ExploreUiEvent.OpenCollection -> openCollection(event.collectionId)
            is ExploreUiEvent.CloseCollection -> updateState { copy(selectedCollection = null, isCollectionLoading = false) }
            is ExploreUiEvent.OpenCustomizeDialog -> updateState { copy(isCustomizeDialogOpen = true) }
            is ExploreUiEvent.CloseCustomizeDialog -> updateState { copy(isCustomizeDialogOpen = false) }
        }
    }

    private fun selectProviderMatch(match: ProviderMatch) {
        val seed = uiState.value.selectedItemForMatch?.toCanonicalSeed()
        closeProviderPicker()
        sendEffect(
            ExploreUiEffect.OpenDetails(
                providerName = match.providerName,
                url = match.searchResponse.url,
                title = match.displayTitle,
                canonicalSeed = seed,
            ),
        )
    }

    private fun refreshCatalogs(clearCache: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (clearCache) {
                ExploreCatalogDiscoverer.clearCache()
                ExploreCatalogClient.clearCache()
                catalogItemsCache.clear()
            }
            val enabledAddons: List<ManagedStremioAddon> = StremioAddonManager.addons.value.filter { it.enabled && it.providesCatalogs }

            val initialDiscovered = mutableListOf<ManifestCatalogDescriptor>()
            for (addon in enabledAddons) {
                val cached = ExploreCatalogDiscoverer.getCachedCatalogs(addon)
                if (!cached.isNullOrEmpty()) {
                    initialDiscovered.addAll(cached)
                }
            }
            initialDiscovered.addAll(nativeCatalogs)

            if (initialDiscovered.isNotEmpty()) {
                applyDiscoveredCatalogs(initialDiscovered)
            }

            val catalogDeferreds = enabledAddons.map { addon ->
                async { ExploreCatalogDiscoverer.getCatalogsForAddon(addon) }
            }
            val freshAddonCatalogs = catalogDeferreds.awaitAll().flatten()

            val finalDiscovered = mutableListOf<ManifestCatalogDescriptor>()
            finalDiscovered.addAll(freshAddonCatalogs)
            finalDiscovered.addAll(nativeCatalogs)

            if (finalDiscovered != initialDiscovered || initialDiscovered.isEmpty()) {
                applyDiscoveredCatalogs(finalDiscovered)
            }
        }
    }

    private fun applyDiscoveredCatalogs(discovered: List<ManifestCatalogDescriptor>) {
        ExploreCatalogSettingsManager.syncWithDiscovered(discovered)
        val types = listOf("addons", "platforms", "collections", "anime", "movie", "series")

        val currentType = uiState.value.selectedType
        val targetType = if (types.contains(currentType)) currentType else "addons"

        val addons = listOf("All Sources") + discovered.filter { !it.isBuiltIn }.map { it.addonName }.filter { it.isNotBlank() }.distinct().sorted()
        val currentAddon = uiState.value.selectedAddon
        val targetAddon = if (addons.contains(currentAddon)) currentAddon else "All Sources"

        val forType = discovered.filter {
            ExploreFilterEngine.matchesType(it, targetType) &&
                (targetAddon == "All Sources" || it.addonName.equals(targetAddon, ignoreCase = true))
        }
        val nextCat = forType.firstOrNull()

        if (discovered.isEmpty()) {
            updateState {
                copy(
                    isInitializing = false,
                    allCatalogs = emptyList(),
                    availableTypes = emptyList(),
                    availableAddons = emptyList(),
                    selectedAddon = "All Sources",
                    filteredCatalogs = emptyList(),
                    selectedCatalog = null,
                    rawItems = emptyList(),
                    displayItems = emptyList(),
                    isLoading = false,
                    allShelves = emptyList(),
                    shelves = emptyList(),
                    heroItems = emptyList(),
                    isShelvesLoading = false,
                )
            }
        } else {
            updateState {
                copy(
                    isInitializing = false,
                    allCatalogs = discovered,
                    availableTypes = types,
                    availableAddons = addons,
                    selectedAddon = targetAddon,
                    selectedType = targetType,
                    filteredCatalogs = forType,
                    selectedCatalog = nextCat,
                    selectedGenre = "All",
                    selectedYear = "All Years",
                    searchQuery = "",
                )
            }
            if (uiState.value.isShelvesMode && uiState.value.drilledCatalog == null) {
                val shelvesCatalogs = ExploreCatalogSettingsManager.filterAndSort(forType)
                loadShelves(targetType, shelvesCatalogs)
            } else {
                loadCurrentCatalog()
            }
        }
    }

    private fun selectType(type: String) {
        if (uiState.value.selectedType == type) return

        val forType = uiState.value.allCatalogs.filter {
            ExploreFilterEngine.matchesType(it, type) &&
                (uiState.value.selectedAddon == "All Sources" || it.addonName.equals(uiState.value.selectedAddon, ignoreCase = true))
        }
        val nextCat = forType.firstOrNull()

        updateState {
            copy(
                selectedType = type,
                filteredCatalogs = forType,
                selectedCatalog = nextCat,
                selectedGenre = "All",
                selectedYear = "All Years",
                searchQuery = "",
                drilledCatalog = null,
                drilledPlatform = null,
                platformShelves = emptyList(),
                selectedCollection = null,
                isCollectionLoading = false,
            )
        }

        if (uiState.value.isShelvesMode) {
            val allLoaded = uiState.value.allShelves.filter { ExploreFilterEngine.matchesType(it.catalog, type) }
            if (allLoaded.isNotEmpty()) {
                val heroes = allLoaded.firstOrNull { it.items.isNotEmpty() }?.items?.take(5) ?: emptyList()
                updateState {
                    copy(
                        shelves = allLoaded,
                        heroItems = if (heroes.isNotEmpty()) heroes else heroItems,
                    )
                }
            } else {
                val shelvesCatalogs = ExploreCatalogSettingsManager.filterAndSort(forType)
                loadShelves(type, shelvesCatalogs)
            }
        } else {
            loadCurrentCatalog()
        }
    }

    private fun selectAddon(addonName: String) {
        if (uiState.value.selectedAddon == addonName) return

        val forType = uiState.value.allCatalogs.filter {
            ExploreFilterEngine.matchesType(it, uiState.value.selectedType) &&
                (addonName == "All Sources" || it.addonName.equals(addonName, ignoreCase = true))
        }
        val nextCat = forType.firstOrNull()

        updateState {
            copy(
                selectedAddon = addonName,
                filteredCatalogs = forType,
                selectedCatalog = nextCat,
                selectedGenre = "All",
                selectedYear = "All Years",
                searchQuery = "",
                drilledCatalog = null,
                drilledPlatform = null,
                platformShelves = emptyList(),
                heroItems = emptyList(),
            )
        }

        if (uiState.value.isShelvesMode) {
            val shelvesCatalogs = ExploreCatalogSettingsManager.filterAndSort(forType)
            loadShelves(uiState.value.selectedType, shelvesCatalogs)
        } else {
            loadCurrentCatalog()
        }
    }

    private fun selectCatalog(catalog: ManifestCatalogDescriptor) {
        if (uiState.value.selectedCatalog?.id == catalog.id && uiState.value.selectedCatalog?.addonBaseUrl == catalog.addonBaseUrl) return

        updateState {
            copy(
                selectedCatalog = catalog,
                selectedGenre = "All",
                selectedYear = "All Years",
                searchQuery = "",
            )
        }

        loadCurrentCatalog()
    }

    private fun selectGenre(genre: String) {
        if (uiState.value.selectedGenre == genre) return

        updateState {
            copy(
                selectedGenre = genre,
                displayItems = ExploreFilterEngine.applyFilters(rawItems, searchQuery, selectedYear, genre),
            )
        }
        loadCurrentCatalog()
    }

    private fun selectYear(year: String) {
        if (uiState.value.selectedYear == year) return
        updateState {
            copy(
                selectedYear = year,
                displayItems = ExploreFilterEngine.applyFilters(rawItems, searchQuery, year, selectedGenre),
            )
        }
    }

    private fun selectSearchScope(scope: ExploreSearchScope) {
        if (uiState.value.searchScope == scope) return
        updateState { copy(searchScope = scope) }
        val currentQuery = uiState.value.searchQuery
        if (currentQuery.isNotBlank()) {
            updateSearchQuery(currentQuery)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun updateSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearchQuery()
            return
        }

        updateState {
            copy(
                searchQuery = query,
                displayItems = ExploreFilterEngine.applyFilters(rawItems, query, selectedYear, selectedGenre),
                isLiveSearching = true,
            )
        }

        liveSearchJob?.cancel()
        liveSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250L)
            try {
                when (uiState.value.searchScope) {
                    ExploreSearchScope.ADDONS -> {
                        val enabledAddons = StremioAddonManager.addons.value.filter { it.enabled }
                        ExploreSearchEngine.searchFlow(
                            query = trimmed,
                            enabledAddons = enabledAddons,
                            limitPerCategory = 24,
                        ).collect { results ->
                            updateState {
                                copy(
                                    searchResults = results,
                                    isLiveSearching = false,
                                )
                            }
                        }
                    }
                    ExploreSearchScope.PLUGINS -> {
                        val pluginResults = ExploreProviderMatcher.searchPlugins(trimmed, searchSemaphore)
                        val top = pluginResults.firstOrNull()?.items?.firstOrNull()
                        val movies = pluginResults.flatMap { it.items }.filter { it.type == "movie" }
                        val series = pluginResults.flatMap { it.items }.filter { it.type == "series" }
                        val anime = pluginResults.flatMap { it.items }.filter { it.type == "anime" }
                        val results = ExploreSearchResults(
                            query = trimmed,
                            topMatch = top,
                            movies = movies,
                            series = series,
                            anime = anime,
                            addonGroups = pluginResults,
                        )
                        updateState {
                            copy(
                                searchResults = results,
                                isLiveSearching = false,
                            )
                        }
                    }
                    ExploreSearchScope.ALL -> {
                        val enabledAddons = StremioAddonManager.addons.value.filter { it.enabled }
                        var currentResults = ExploreSearchResults(query = trimmed)
                        val pluginDeferred = async { ExploreProviderMatcher.searchPlugins(trimmed, searchSemaphore) }

                        val addonJob = launch {
                            ExploreSearchEngine.searchFlow(
                                query = trimmed,
                                enabledAddons = enabledAddons,
                                limitPerCategory = 24,
                            ).collect { addonRes ->
                                currentResults = addonRes
                                val pluginGroups = if (pluginDeferred.isCompleted) pluginDeferred.getCompleted() else emptyList()
                                updateState {
                                    copy(
                                        searchResults = currentResults.copy(
                                            addonGroups = currentResults.addonGroups + pluginGroups,
                                        ),
                                        isLiveSearching = !pluginDeferred.isCompleted,
                                    )
                                }
                            }
                        }

                        val pluginGroups = pluginDeferred.await()
                        addonJob.join()
                        updateState {
                            copy(
                                searchResults = currentResults.copy(
                                    addonGroups = currentResults.addonGroups + pluginGroups,
                                ),
                                isLiveSearching = false,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Explore search failed: ${e.message}")
                updateState { copy(isLiveSearching = false) }
            }
        }
    }

    private fun clearSearchQuery() {
        liveSearchJob?.cancel()
        updateState {
            copy(
                searchQuery = "",
                displayItems = ExploreFilterEngine.applyFilters(rawItems, "", selectedYear, selectedGenre),
                searchResults = null,
                isLiveSearching = false,
                drilledSearchCategory = null,
            )
        }
    }

    internal fun applyFilters(items: List<ExploreItem>, query: String, year: String, genre: String = "All"): List<ExploreItem> {
        return ExploreFilterEngine.applyFilters(items, query, year, genre)
    }

    private fun loadCurrentCatalog(skip: Int = 0) {
        val cat = uiState.value.selectedCatalog ?: return
        val genreArg = if (uiState.value.selectedGenre.equals("All", ignoreCase = true)) cat.genre else uiState.value.selectedGenre
        val cacheKey = "${cat.addonBaseUrl}_${cat.type}_${cat.id}_${cat.queryParams}_${genreArg ?: "all"}_$skip"

        if (skip == 0) {
            val cached = catalogItemsCache[cacheKey]
            if (cached != null && cached.isNotEmpty()) {
                val filtered = ExploreFilterEngine.applyFilters(cached, uiState.value.searchQuery, uiState.value.selectedYear, uiState.value.selectedGenre)
                updateState {
                    copy(
                        isInitializing = false,
                        rawItems = cached,
                        displayItems = filtered,
                        isLoading = false,
                        canLoadMore = cached.size >= 20,
                    )
                }
                return
            }
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isLoading = true, canLoadMore = true) }
            try {
                val fetched = ExploreCatalogItemFetcher.fetchItemsForCatalog(
                    cat = cat,
                    genreArg = genreArg,
                    skip = skip,
                )

                val distinctFetched = fetched.distinctBy { it.id }
                if (distinctFetched.isNotEmpty()) {
                    catalogItemsCache[cacheKey] = distinctFetched
                }

                val newRaw = if (skip == 0) distinctFetched else (uiState.value.rawItems + distinctFetched).distinctBy { it.id }
                val filtered = ExploreFilterEngine.applyFilters(newRaw, uiState.value.searchQuery, uiState.value.selectedYear, uiState.value.selectedGenre)

                updateState {
                    copy(
                        isInitializing = false,
                        rawItems = newRaw,
                        displayItems = filtered,
                        isLoading = false,
                        canLoadMore = distinctFetched.size >= 20,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading catalog ${cat.name}: ${e.message}")
                updateState { copy(isLoading = false, isInitializing = false) }
            }
        }
    }

    private fun loadMore() {
        val state = uiState.value
        if (state.isLoading || state.isLoadingMore || !state.canLoadMore || state.selectedCatalog == null) return
        if (state.rawItems.isEmpty()) return

        val cat = state.selectedCatalog
        val genreArg = if (state.selectedGenre.equals("All", ignoreCase = true)) cat.genre else state.selectedGenre
        val skip = state.rawItems.size

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isLoadingMore = true) }
            try {
                val fetched = ExploreCatalogItemFetcher.fetchItemsForCatalog(
                    cat = cat,
                    genreArg = genreArg,
                    skip = skip,
                )

                if (fetched.isEmpty()) {
                    updateState { copy(isLoadingMore = false, canLoadMore = false) }
                } else {
                    val distinctFetched = fetched.distinctBy { it.id }
                    val newRaw = (uiState.value.rawItems + distinctFetched).distinctBy { it.id }
                    val filtered = ExploreFilterEngine.applyFilters(newRaw, uiState.value.searchQuery, uiState.value.selectedYear, uiState.value.selectedGenre)
                    updateState {
                        copy(
                            rawItems = newRaw,
                            displayItems = filtered,
                            isLoadingMore = false,
                            canLoadMore = distinctFetched.size >= 20,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading more items for ${cat.name}: ${e.message}")
                updateState { copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadShelves(type: String, catalogs: List<ManifestCatalogDescriptor>) {
        loadShelvesJob?.cancel()
        if (catalogs.isEmpty()) {
            updateState {
                copy(
                    isInitializing = false,
                    isShelvesLoading = false,
                    shelves = emptyList(),
                    heroItems = emptyList(),
                )
            }
            return
        }

        val initialShelves = catalogs.map { cat ->
            val cacheKey = "${cat.addonBaseUrl}_${cat.type}_${cat.id}_${cat.queryParams}_${cat.genre ?: "all"}_0"
            val cached = catalogItemsCache[cacheKey]
            ExploreShelf(
                catalog = cat,
                items = cached ?: emptyList(),
                isLoading = cached == null,
            )
        }

        val cachedHeroes = initialShelves.firstOrNull { it.items.isNotEmpty() }?.items?.take(5) ?: emptyList()
        val activeType = uiState.value.selectedType
        val filteredInitial = if (activeType.equals("all", ignoreCase = true)) {
            initialShelves
        } else {
            initialShelves.filter { ExploreFilterEngine.matchesType(it.catalog, activeType) }
        }

        updateState {
            copy(
                isInitializing = false,
                isShelvesLoading = initialShelves.any { it.isLoading },
                allShelves = initialShelves,
                shelves = filteredInitial,
                heroItems = cachedHeroes,
            )
        }

        loadShelvesJob = viewModelScope.launch(Dispatchers.IO) {
            val updatedShelves = initialShelves.toMutableList()

            fun emitShelvesState() {
                val currentAll = synchronized(updatedShelves) { updatedShelves.toList() }
                val currentType = uiState.value.selectedType
                val currentFiltered = if (currentType.equals("all", ignoreCase = true)) {
                    currentAll
                } else {
                    currentAll.filter { ExploreFilterEngine.matchesType(it.catalog, currentType) }
                }
                val heroes = if (uiState.value.heroItems.isEmpty()) {
                    currentAll.firstOrNull { it.items.isNotEmpty() }?.items?.take(5) ?: emptyList()
                } else {
                    uiState.value.heroItems
                }

                updateState {
                    copy(
                        allShelves = currentAll,
                        shelves = currentFiltered,
                        heroItems = heroes,
                        isShelvesLoading = currentAll.any { it.isLoading },
                    )
                }
            }

            val jobs = catalogs.mapIndexed { index, cat ->
                launch {
                    val cacheKey = "${cat.addonBaseUrl}_${cat.type}_${cat.id}_${cat.queryParams}_${cat.genre ?: "all"}_0"
                    val cached = catalogItemsCache[cacheKey]
                    if (cached != null && cached.isNotEmpty()) {
                        synchronized(updatedShelves) {
                            updatedShelves[index] = ExploreShelf(
                                catalog = cat,
                                items = cached,
                                isLoading = false,
                            )
                        }
                        emitShelvesState()
                        return@launch
                    }

                    shelfFetchSemaphore.withPermit {
                        try {
                            val items = ExploreCatalogItemFetcher.fetchItemsForCatalog(
                                cat = cat,
                                genreArg = cat.genre,
                                skip = 0,
                            ).distinctBy { it.id }

                            if (items.isNotEmpty()) {
                                catalogItemsCache[cacheKey] = items
                            }

                            synchronized(updatedShelves) {
                                updatedShelves[index] = ExploreShelf(
                                    catalog = cat,
                                    items = items,
                                    isLoading = false,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed loading shelf for ${cat.name}: ${e.message}")
                            synchronized(updatedShelves) {
                                updatedShelves[index] = ExploreShelf(
                                    catalog = cat,
                                    items = emptyList(),
                                    isLoading = false,
                                    error = e.message,
                                )
                            }
                        }
                        emitShelvesState()
                    }
                }
            }
            jobs.joinAll()
            emitShelvesState()
        }
    }

    private fun drillIntoCatalog(catalog: ManifestCatalogDescriptor) {
        updateState {
            copy(
                isShelvesMode = false,
                drilledPlatform = null,
                drilledCatalog = catalog,
                selectedCatalog = catalog,
                selectedGenre = catalog.genre ?: "All",
                selectedYear = "All Years",
                searchQuery = "",
            )
        }
        loadCurrentCatalog(skip = 0)
    }

    private fun drillIntoPlatform(platform: StreamingPlatform) {
        val platformCatalog = ManifestCatalogDescriptor(
            addonName = platform.displayName,
            addonBaseUrl = "tmdb://platform/${platform.id}",
            type = if (platform == StreamingPlatform.CRUNCHYROLL) "anime" else uiState.value.selectedType,
            id = platform.id,
            name = "${platform.displayName} Hub",
            genres = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller"),
            supportsSearch = false,
        )
        updateState {
            copy(
                drilledPlatform = platform,
                drilledCatalog = platformCatalog,
                selectedCatalog = platformCatalog,
                selectedGenre = "All",
                selectedYear = "All Years",
                searchQuery = "",
            )
        }
        loadPlatformShelves(platform)
    }

    private fun loadPlatformShelves(platform: StreamingPlatform) {
        loadPlatformShelvesJob?.cancel()
        val descriptors = ExploreCatalogItemFetcher.getPlatformDescriptors(platform)

        val initialShelves = descriptors.map { cat ->
            val cacheKey = "${cat.addonBaseUrl}_${cat.type}_${cat.id}_all_0"
            val cached = catalogItemsCache[cacheKey]
            ExploreShelf(
                catalog = cat,
                items = cached ?: emptyList(),
                isLoading = cached == null,
            )
        }

        updateState {
            copy(
                isPlatformShelvesLoading = initialShelves.any { it.isLoading },
                platformShelves = initialShelves,
            )
        }

        loadPlatformShelvesJob = viewModelScope.launch(Dispatchers.IO) {
            val updatedShelves = initialShelves.toMutableList()

            val jobs = descriptors.mapIndexed { index, cat ->
                launch {
                    val cacheKey = "${cat.addonBaseUrl}_${cat.type}_${cat.id}_all_0"
                    val cached = catalogItemsCache[cacheKey]
                    if (cached != null && cached.isNotEmpty()) {
                        return@launch
                    }

                    shelfFetchSemaphore.withPermit {
                        try {
                            val items = ExploreCatalogItemFetcher.fetchItemsForCatalog(
                                cat = cat,
                                genreArg = null,
                                skip = 0,
                            ).distinctBy { it.id }

                            if (items.isNotEmpty()) {
                                catalogItemsCache[cacheKey] = items
                            }

                            synchronized(updatedShelves) {
                                updatedShelves[index] = ExploreShelf(
                                    catalog = cat,
                                    items = items,
                                    isLoading = false,
                                )
                            }
                            updateState {
                                val currentShelves = updatedShelves.toList()
                                copy(
                                    platformShelves = currentShelves,
                                    isPlatformShelvesLoading = currentShelves.any { it.isLoading },
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed loading platform shelf for ${cat.name}: ${e.message}")
                            synchronized(updatedShelves) {
                                updatedShelves[index] = ExploreShelf(
                                    catalog = cat,
                                    items = emptyList(),
                                    isLoading = false,
                                    error = e.message,
                                )
                            }
                            updateState {
                                val currentShelves = updatedShelves.toList()
                                copy(
                                    platformShelves = currentShelves,
                                    isPlatformShelvesLoading = currentShelves.any { it.isLoading },
                                )
                            }
                        }
                    }
                }
            }

            jobs.joinAll()
            updateState {
                copy(
                    platformShelves = updatedShelves.toList(),
                    isPlatformShelvesLoading = false,
                )
            }
        }
    }

    private fun openCollection(collectionId: Int) {
        collectionLoadJob?.cancel()
        collectionLoadJob = viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isCollectionLoading = true) }
            val detail = ExploreCollectionsHub.fetchCollectionDetails(collectionId)
            updateState {
                copy(
                    selectedCollection = detail,
                    isCollectionLoading = false,
                )
            }
        }
    }

    private fun returnToShelves() {
        if (uiState.value.selectedCollection != null) {
            updateState {
                copy(
                    selectedCollection = null,
                    isCollectionLoading = false,
                )
            }
            return
        }
        val currentDrilledCat = uiState.value.drilledCatalog
        if (uiState.value.drilledPlatform != null) {
            updateState {
                copy(
                    drilledPlatform = null,
                    drilledCatalog = null,
                    platformShelves = emptyList(),
                    isPlatformShelvesLoading = false,
                    isShelvesMode = true,
                    searchQuery = "",
                )
            }
            if (uiState.value.shelves.isEmpty()) {
                loadShelves(uiState.value.selectedType, uiState.value.filteredCatalogs)
            }
        } else if (currentDrilledCat?.addonBaseUrl?.startsWith("tmdb://platform/") == true) {
            val platformId = currentDrilledCat.addonBaseUrl.removePrefix("tmdb://platform/").substringBefore("?")
            val platform = StreamingPlatform.fromIdOrNull(platformId)
            if (platform != null) {
                drillIntoPlatform(platform)
            } else {
                updateState {
                    copy(
                        isShelvesMode = true,
                        drilledCatalog = null,
                        drilledPlatform = null,
                        searchQuery = "",
                    )
                }
                if (uiState.value.shelves.isEmpty()) {
                    loadShelves(uiState.value.selectedType, uiState.value.filteredCatalogs)
                }
            }
        } else {
            updateState {
                copy(
                    isShelvesMode = true,
                    drilledCatalog = null,
                    drilledPlatform = null,
                    searchQuery = "",
                )
            }
            if (uiState.value.shelves.isEmpty()) {
                loadShelves(uiState.value.selectedType, uiState.value.filteredCatalogs)
            }
        }
    }

    private fun toggleViewMode(isShelves: Boolean) {
        if (uiState.value.isShelvesMode == isShelves) return
        updateState { copy(isShelvesMode = isShelves) }
        if (isShelves) {
            if (uiState.value.shelves.isEmpty()) {
                loadShelves(uiState.value.selectedType, uiState.value.filteredCatalogs)
            }
        } else {
            if (uiState.value.rawItems.isEmpty()) {
                loadCurrentCatalog(skip = 0)
            }
        }
    }

    internal fun isTitleRelevant(targetTitle: String, candidateTitle: String): Boolean {
        return ExploreFilterEngine.isTitleRelevant(targetTitle, candidateTitle)
    }

    private fun openProviderPicker(item: ExploreItem) {
        updateState {
            copy(
                selectedItemForMatch = item,
                providerMatches = emptyList(),
                isSearchingProviders = true,
            )
        }

        providerSearchJob?.cancel()
        providerSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ExploreProviderMatcher.resolveProviderMatches(
                    item = item,
                    searchSemaphore = searchSemaphore,
                    onEnrichmentUpdated = { enriched ->
                        updateState { copy(selectedItemForMatch = enriched) }
                    },
                    onMatchesUpdated = { matches ->
                        updateState { copy(providerMatches = matches) }
                    },
                )
                updateState { copy(isSearchingProviders = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Provider resolution failed: ${e.message}")
                updateState { copy(isSearchingProviders = false) }
            }
        }
    }

    private fun closeProviderPicker() {
        providerSearchJob?.cancel()
        updateState {
            copy(
                selectedItemForMatch = null,
                providerMatches = emptyList(),
                isSearchingProviders = false,
            )
        }
    }

    fun formatTypeTitle(type: String): String {
        return ExploreFilterEngine.formatTypeTitle(type)
    }

    override fun dispose() {
        refreshJob?.cancel()
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadShelvesJob?.cancel()
        loadPlatformShelvesJob?.cancel()
        providerSearchJob?.cancel()
        liveSearchJob?.cancel()
        collectionLoadJob?.cancel()
        catalogItemsCache.clear()
        super.dispose()
    }
}
