package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.bookmarks.repository.BookmarksRepository
import com.lagradost.cloudstream3.desktop.domain.history.interactor.GetWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class DetailsViewModel(
    val provider: MainAPI,
    val url: String,
    val preloadedName: String? = null,
    val preloadedPoster: String? = null,
    val preloadedBg: String? = null,
    val initialSeason: Int? = null,
    val targetEpisodeId: String? = null,
    val canonicalSeed: com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed? = null,
    cachedResponse: LoadResponse? = DetailsCache.get(url),
    cachedUiState: DetailsUiState? = EnrichedDetailsCache.get(url),
    private val getWatchHistory: GetWatchHistory = AppContainerHolder.container.getWatchHistory,
    private val upsertWatchHistory: UpsertWatchHistory = AppContainerHolder.container.upsertWatchHistory,
    private val removeWatchHistory: RemoveWatchHistory = AppContainerHolder.container.removeWatchHistory,
    private val getBookmarks: GetBookmarks = AppContainerHolder.container.getBookmarks,
    private val bookmarksRepository: BookmarksRepository = AppContainerHolder.container.bookmarksRepository,
    private val trackingRepository: TrackingRepository = AppContainerHolder.container.trackingRepository,
    private val trackerSyncEngine: com.lagradost.cloudstream3.desktop.tracking.TrackerSyncEngine? = runCatching {
        AppContainerHolder.container.trackerSyncEngine
    }.getOrNull(),
) : BaseMviViewModel<DetailsUiState, DetailsUiEvent, DetailsUiEffect>(
    initialState = cachedUiState?.copy(
        fetchFailed = false,
        error = null,
        selectedSeason = initialSeason ?: cachedUiState.selectedSeason,
        hasUserSelectedSeason = initialSeason != null,
        canonicalSeed = canonicalSeed ?: cachedUiState.canonicalSeed,
        tmdbId = canonicalSeed?.tmdbId ?: cachedUiState.tmdbId,
        imdbId = canonicalSeed?.imdbId ?: cachedUiState.imdbId,
        anilistId = canonicalSeed?.anilistId ?: cachedUiState.anilistId,
        malId = canonicalSeed?.malId ?: cachedUiState.malId,
    ) ?: DetailsUiState(
        preloadedName = preloadedName,
        response = cachedResponse,
        tmdbId = canonicalSeed?.tmdbId,
        imdbId = canonicalSeed?.imdbId,
        anilistId = canonicalSeed?.anilistId,
        malId = canonicalSeed?.malId,
        canonicalSeed = canonicalSeed,
        selectedSeason = initialSeason,
        hasUserSelectedSeason = initialSeason != null,
        enrichedLogoUrl = cachedResponse?.logoUrl,
        enrichedBackdropUrl = cachedResponse?.backgroundPosterUrl,
        isLoading = cachedResponse == null,
        fakeData = if (cachedResponse == null) {
            @Suppress("DEPRECATION_ERROR", "DEPRECATION")
            MovieLoadResponse(
                name = preloadedName ?: "",
                url = url,
                apiName = provider.name,
                type = TvType.Movie,
                dataUrl = url,
                posterUrl = preloadedPoster,
            ).apply {
                this.backgroundPosterUrl = preloadedBg
            }
        } else {
            null
        },
    ),
) {

    private val trackerCoordinator = DetailsTrackerCoordinator(
        scope = viewModelScope,
        provider = provider,
        fallbackUrl = url,
        preloadedName = preloadedName,
        trackingRepository = trackingRepository,
        getState = { uiState.value },
        updateState = ::updateState,
        sendEffect = ::sendEffect,
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val autoPlay = DesktopDataStore.getKey<Boolean>(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_AUTO_PLAY) ?: true
            val isStacked = DesktopDataStore.getKey<Boolean>(PreferenceKeys.PREF_EPISODES_STACKED_VIEW) ?: false
            val viewMode = DesktopDataStore.getKey<Int>(PreferenceKeys.PREF_EPISODES_VIEW_MODE) ?: if (isStacked) 1 else 0
            val isPinned = DesktopDataStore.getKey<Boolean>(PreferenceKeys.DETAILS_RIGHT_COLUMN_PINNED) ?: false
            updateState {
                copy(
                    autoPlayEnabled = autoPlay,
                    isEpisodesStackedView = isStacked,
                    episodeViewMode = viewMode,
                    isRightColumnPinned = isPinned,
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            getWatchHistory.subscribeAll().collect {
                val currentDataUrl = uiState.value.response?.url ?: url
                val currentParentId = DesktopDataStore.watchHistoryId(provider.name, currentDataUrl)
                val fallbackParentId = DesktopDataStore.watchHistoryId(provider.name, url)

                val historyMap = (
                    getWatchHistory.awaitByParent(currentParentId) +
                        getWatchHistory.awaitByParent(fallbackParentId)
                    )
                    .distinctBy { it.episodeId }
                    .associateBy { it.episodeId ?: "" }

                if (uiState.value.watchHistory == historyMap) return@collect

                val latestHistory = historyMap.values.maxByOrNull { it.updateTime }
                val resp = uiState.value.response
                val autoTarget = if (resp != null) {
                    DetailsWatchCoordinator.determineAutoPlayTarget(provider, resp, historyMap)
                } else {
                    null
                }
                val targetSeason = autoTarget?.season ?: latestHistory?.season

                updateState {
                    val resolvedSeason = if (!hasUserSelectedSeason && targetSeason != null && targetSeason > 0) {
                        targetSeason
                    } else {
                        selectedSeason ?: targetSeason
                    }
                    copy(
                        watchHistory = historyMap,
                        selectedSeason = resolvedSeason,
                    )
                }
                val effectiveSeason = uiState.value.selectedSeason
                if (effectiveSeason != null && effectiveSeason > 0) {
                    loadSeasonCredits(effectiveSeason)
                }
            }
        }
        viewModelScope.launch {
            getBookmarks.subscribeAll().collect { bookmarks ->
                updateState { copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            trackingRepository.accounts.drop(1).collect { trackerCoordinator.refreshAll() }
        }
        viewModelScope.launch {
            trackingRepository.bindingsVersion.drop(1).collect { trackerCoordinator.refreshAll() }
        }
        viewModelScope.launch {
            trackingRepository.settingsVersion.drop(1).collect { trackerCoordinator.refreshAll() }
        }
        viewModelScope.launch {
            trackingRepository.auditVersion.drop(1).collect { version ->
                if (version > 0L) {
                    trackerCoordinator.refreshAll(forceRemoteFetch = true)
                }
            }
        }
    }

    override fun handleEvent(event: DetailsUiEvent) {
        when (event) {
            is DetailsUiEvent.OnLoad -> load()
            is DetailsUiEvent.OnRetry -> retry()
            is DetailsUiEvent.OnOpenLinksPanel -> openLinksPanel(event.data)
            is DetailsUiEvent.OnCloseLinksPanel -> closeLinksPanel()
            is DetailsUiEvent.OnRequestAutoPlay -> handleAutoPlay()
            is DetailsUiEvent.OnMarkAutoPlayHandled -> updateState { copy(hasAutoPlayed = true) }
            is DetailsUiEvent.OnPlayEpisode -> handlePlayEpisode(event.ep)
            is DetailsUiEvent.OnDownloadEpisode -> handleDownloadEpisode(event.ep)
            is DetailsUiEvent.OnToggleEpisodeWatched -> handleToggleEpisodeWatched(event.ep, event.isWatched)
            is DetailsUiEvent.OnRemoveEpisodeWatched -> handleRemoveEpisodeWatched(event.ep)
            is DetailsUiEvent.OnToggleSeasonWatched -> handleToggleSeasonWatched(event.episodes, event.isWatched)
            is DetailsUiEvent.OnToggleEpisodesStackedView -> handleToggleEpisodesStackedView(event.isStacked)
            is DetailsUiEvent.OnSetEpisodeViewMode -> handleSetEpisodeViewMode(event.viewMode)
            is DetailsUiEvent.OnRefresh -> refresh()
            is DetailsUiEvent.OnAddBookmark -> {
                viewModelScope.launch(Dispatchers.IO) {
                    bookmarksRepository.addBookmark(event.bookmark)
                }
            }
            is DetailsUiEvent.OnRemoveBookmark -> {
                viewModelScope.launch(Dispatchers.IO) {
                    bookmarksRepository.removeBookmark(event.id)
                }
            }
            is DetailsUiEvent.OnSelectSeason -> selectSeason(event.season)
            is DetailsUiEvent.OnShowPlaybackError -> updateState { copy(playbackError = event.message) }
            is DetailsUiEvent.OnDismissPlaybackError -> updateState { copy(playbackError = null) }
            is DetailsUiEvent.OnSelectTrailer -> updateState { copy(activeTrailer = event.trailer) }
            is DetailsUiEvent.OnSetPendingExternalUrl -> updateState { copy(pendingExternalUrl = event.url) }
            is DetailsUiEvent.OnTogglePinRightColumn -> handleTogglePinRightColumn()
            is DetailsUiEvent.OnOpenTrackerDrawer -> trackerCoordinator.openDrawer(event.providerId)
            is DetailsUiEvent.OnDismissTrackerDrawer -> trackerCoordinator.dismissDrawer()
            is DetailsUiEvent.OnSelectTrackerTab -> trackerCoordinator.selectTab(event.providerId)
            is DetailsUiEvent.OnRefreshTrackerState -> trackerCoordinator.refreshAll(forceRemoteFetch = true, targetProviderId = event.providerId)
            is DetailsUiEvent.OnTrackerSearchQueryChange -> trackerCoordinator.updateSearchQuery(event.providerId, event.query)
            is DetailsUiEvent.OnSearchTrackerCandidates -> trackerCoordinator.searchCandidates(event.providerId)
            is DetailsUiEvent.OnPinTrackerCandidate -> trackerCoordinator.pinCandidate(event.providerId, event.candidate)
            is DetailsUiEvent.OnSetTrackerOffset -> trackerCoordinator.updateOffset(event.providerId, event.offset)
            is DetailsUiEvent.OnSetTrackerRemoteStatus -> trackerCoordinator.updateRemoteStatus(event.providerId, event.status)
            is DetailsUiEvent.OnSetTrackerRemoteProgress -> trackerCoordinator.updateRemoteProgress(event.providerId, event.progress)
            is DetailsUiEvent.OnSetTrackerScore -> trackerCoordinator.updateScore(event.providerId, event.score100)
            is DetailsUiEvent.OnToggleTrackerFavorite -> trackerCoordinator.toggleFavorite(event.providerId)
            is DetailsUiEvent.OnToggleTrackerSearch -> trackerCoordinator.toggleSearch(event.providerId)
            is DetailsUiEvent.OnPushTrackerRemoteUpdate -> trackerCoordinator.pushRemoteUpdate(event.providerId)
            is DetailsUiEvent.OnRemoveFromTracker -> trackerCoordinator.removeEntry(event.providerId)
            is DetailsUiEvent.OnSyncEpisodeToTracker -> trackerCoordinator.syncEpisode(event.providerId, event.ep)
            is DetailsUiEvent.OnSyncReleasedEpisodesToTracker -> trackerCoordinator.syncReleasedEpisodes(event.providerId, event.episodes)
            is DetailsUiEvent.OnBlockTrackerShow -> trackerCoordinator.blockShow(event.providerId)
            is DetailsUiEvent.OnUnbindTrackerSeason -> trackerCoordinator.unbindSeason(event.providerId)
            is DetailsUiEvent.OnSetTrackerAutoSync -> trackerCoordinator.setAutoSync(event.providerId, event.enabled)
            is DetailsUiEvent.OnSetTrackerBindPolicy -> trackerCoordinator.setBindPolicy(event.providerId, event.policy)
        }
    }

    private fun load() {
        if (uiState.value.isInitialized) return
        updateState { copy(isInitialized = true) }
        loadDetails()
    }

    private fun loadDetails() {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(fetchFailed = false, isLoading = true, error = null) }

            if (uiState.value.response == null && uiState.value.fakeData == null) {
                val fake = provider.newMovieLoadResponse(
                    name = preloadedName ?: "",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = url,
                ) {
                    this.posterUrl = preloadedPoster
                    this.backgroundPosterUrl = preloadedBg
                }
                updateState { copy(fakeData = fake) }
            }

            GetEnrichedDetailsUseCase(
                provider = provider,
                url = url,
                preloadedName = preloadedName,
                preloadedPoster = preloadedPoster,
                preloadedBg = preloadedBg,
                canonicalSeed = uiState.value.canonicalSeed ?: canonicalSeed,
            ).collect { update ->
                when (update) {
                    is EnrichmentUpdate.RawData -> {
                        val rawTmdbId = update.response.syncData["tmdb"]?.toIntOrNull()
                        val isSeries = update.response is TvSeriesLoadResponse || update.response is AnimeLoadResponse
                        val availableSeasons = when (val resp = update.response) {
                            is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().sorted()
                            is AnimeLoadResponse -> resp.episodes.values.flatten().mapNotNull { it.season }.distinct().sorted()
                            else -> emptyList()
                        }.filter { it > 0 }
                        val firstAvailableSeason = availableSeasons.firstOrNull() ?: 1

                        val autoTarget = DetailsWatchCoordinator.determineAutoPlayTarget(provider, update.response, uiState.value.watchHistory)
                        val latestHistorySeason = autoTarget?.season ?: uiState.value.watchHistory.values.maxByOrNull { it.updateTime }?.season

                        val resolvedSeason = if (isSeries) {
                            if (uiState.value.hasUserSelectedSeason && uiState.value.selectedSeason != null && uiState.value.selectedSeason in availableSeasons) {
                                uiState.value.selectedSeason
                            } else {
                                initialSeason?.takeIf { it in availableSeasons }
                                    ?: latestHistorySeason?.takeIf { it in availableSeasons }
                                    ?: firstAvailableSeason
                            }
                        } else {
                            null
                        }

                        updateState {
                            copy(
                                response = update.response,
                                isLoading = false,
                                fakeData = null,
                                enrichedLogoUrl = update.response.logoUrl,
                                enrichedBackdropUrl = update.response.backgroundPosterUrl,
                                isEnriching = true,
                                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.InProgress,
                                tmdbId = rawTmdbId ?: tmdbId,
                                selectedSeason = resolvedSeason,
                            )
                        }
                        val targetSeason = resolvedSeason ?: uiState.value.selectedSeason
                        val currentTmdb = rawTmdbId ?: uiState.value.tmdbId
                        if (targetSeason != null && targetSeason > 0 && currentTmdb != null) {
                            loadSeasonCredits(targetSeason)
                        }
                        trackerCoordinator.refreshAll()
                    }
                    is EnrichmentUpdate.LogoLoaded -> {
                        updateState { copy(enrichedLogoUrl = update.url) }
                        cacheEnrichedState(uiState.value)
                    }
                    is EnrichmentUpdate.BackdropLoaded -> {
                        updateState { copy(enrichedBackdropUrl = update.url) }
                        cacheEnrichedState(uiState.value)
                    }
                    is EnrichmentUpdate.ScreenshotsLoaded -> {
                        updateState { copy(screenshots = update.urls) }
                    }
                    is EnrichmentUpdate.ExtractedColor -> {
                        // Ignored, color extraction removed
                    }
                    is EnrichmentUpdate.ActorsLoaded -> {
                        val currentActors = uiState.value.enrichedActors
                        val currentHasDualCast = currentActors?.any { it.voiceActor != null } == true
                        val updateHasDualCast = update.actors.any { it.voiceActor != null }
                        val resolvedActors = if (currentHasDualCast && !updateHasDualCast) {
                            currentActors
                        } else {
                            update.actors
                        }
                        updateState { copy(enrichedActors = resolvedActors) }
                        cacheEnrichedState(uiState.value)
                    }
                    is EnrichmentUpdate.TrailersLoaded -> {
                        updateState { copy(enrichedTrailers = update.trailers, enrichedTrailerUrl = update.trailers.firstOrNull()?.url) }
                    }
                    is EnrichmentUpdate.ReviewsLoaded -> {
                        updateState { copy(enrichedReviews = update.reviews) }
                    }
                    is EnrichmentUpdate.EpisodeThumbnailsEnriched -> {
                        updateState { copy(episodeThumbnailVersion = episodeThumbnailVersion + 1) }
                    }
                    is EnrichmentUpdate.RatingsLoaded -> {
                        updateState {
                            copy(
                                enrichedImdbRating = update.imdb ?: enrichedImdbRating,
                                enrichedTmdbRating = update.tmdb ?: enrichedTmdbRating,
                                enrichedAniListRating = update.anilist ?: enrichedAniListRating,
                            )
                        }
                    }
                    is EnrichmentUpdate.IdentityResolved -> {
                        updateState {
                            copy(
                                tmdbId = update.tmdbId ?: tmdbId,
                                imdbId = update.imdbId ?: imdbId,
                                anilistId = update.anilistId ?: anilistId,
                                malId = update.malId ?: malId,
                            )
                        }
                        cacheEnrichedState(uiState.value)
                        trackerCoordinator.refreshAll()
                    }
                    is EnrichmentUpdate.MetadataLoaded -> {
                        val currentProd = uiState.value.enrichedProductionCompanies
                        val mergedProdCompanies = if (update.productionCompanies != null) {
                            val current = currentProd.toMutableList()
                            update.productionCompanies.forEach { newComp ->
                                val existingIdx = current.indexOfFirst { it.name.trim().equals(newComp.name.trim(), ignoreCase = true) }
                                if (existingIdx >= 0) {
                                    val existing = current[existingIdx]
                                    if (existing.logoUrl.isNullOrBlank() && !newComp.logoUrl.isNullOrBlank()) {
                                        current[existingIdx] = newComp
                                    }
                                } else {
                                    current.add(newComp)
                                }
                            }
                            current
                        } else {
                            currentProd
                        }

                        val currentNet = uiState.value.enrichedNetworksList
                        val mergedNetCompanies = if (update.networkCompanies != null) {
                            val current = currentNet.toMutableList()
                            update.networkCompanies.forEach { newComp ->
                                val existingIdx = current.indexOfFirst { it.name.trim().equals(newComp.name.trim(), ignoreCase = true) }
                                if (existingIdx >= 0) {
                                    val existing = current[existingIdx]
                                    if (existing.logoUrl.isNullOrBlank() && !newComp.logoUrl.isNullOrBlank()) {
                                        current[existingIdx] = newComp
                                    }
                                } else {
                                    current.add(newComp)
                                }
                            }
                            current
                        } else {
                            currentNet
                        }

                        updateState {
                            copy(
                                enrichedTagline = update.tagline ?: enrichedTagline,
                                enrichedStatus = update.status ?: enrichedStatus,
                                enrichedStudios = if (update.studios.isNotEmpty()) update.studios else enrichedStudios,
                                enrichedProductionCompanies = mergedProdCompanies,
                                enrichedNetworksList = mergedNetCompanies,
                                enrichedCollectionName = update.collName ?: enrichedCollectionName,
                                enrichedCollectionBackdrop = update.collBg ?: enrichedCollectionBackdrop,
                                enrichedSeasonsCount = update.seasons ?: enrichedSeasonsCount,
                                enrichedEpisodesCount = update.episodes ?: enrichedEpisodesCount,
                                enrichedSeasonsMetadata = if (!update.seasonsMetadata.isNullOrEmpty()) update.seasonsMetadata else enrichedSeasonsMetadata,
                                enrichedOriginalLanguage = update.lang ?: enrichedOriginalLanguage,
                                enrichedReleaseDate = update.relDate ?: enrichedReleaseDate,
                                enrichedCountry = update.country ?: enrichedCountry,
                                enrichedCollectionItems = if (update.collItems.isNotEmpty()) update.collItems else enrichedCollectionItems,
                                enrichedBudget = update.budget ?: enrichedBudget,
                                enrichedRevenue = update.revenue ?: enrichedRevenue,
                                enrichedNetworks = if (!update.networks.isNullOrEmpty()) update.networks else enrichedNetworks,
                                enrichedYear = update.year ?: enrichedYear,
                                enrichedDuration = update.duration ?: enrichedDuration,
                                enrichedTags = update.tags ?: enrichedTags,
                                enrichedActors = if (enrichedActors?.any { it.voiceActor != null } == true && update.actors?.none { it.voiceActor != null } == true) {
                                    enrichedActors
                                } else {
                                    update.actors ?: enrichedActors
                                },
                            )
                        }
                        cacheEnrichedState(uiState.value)
                    }
                    is EnrichmentUpdate.FullyEnriched -> {
                        val resolvedTmdbId = uiState.value.tmdbId ?: uiState.value.response?.syncData?.get("tmdb")?.toIntOrNull()
                        updateState {
                            copy(
                                isEnriching = false,
                                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.Complete,
                                tmdbId = resolvedTmdbId ?: tmdbId,
                            )
                        }
                        cacheEnrichedState(uiState.value)
                        val isSeries = uiState.value.response?.let { it is TvSeriesLoadResponse || it is AnimeLoadResponse } ?: false
                        val currentAvailableSeasons = when (val resp = uiState.value.response) {
                            is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().sorted()
                            is AnimeLoadResponse -> resp.episodes.values.flatten().mapNotNull { it.season }.distinct().sorted()
                            else -> emptyList()
                        }.filter { it > 0 }
                        val currentSeason = if (isSeries) {
                            if (uiState.value.hasUserSelectedSeason && uiState.value.selectedSeason in currentAvailableSeasons) {
                                uiState.value.selectedSeason
                            } else {
                                val autoTarget = uiState.value.response?.let { DetailsWatchCoordinator.determineAutoPlayTarget(provider, it, uiState.value.watchHistory) }
                                val latestSeason = autoTarget?.season ?: uiState.value.watchHistory.values.maxByOrNull { it.updateTime }?.season
                                latestSeason?.takeIf { it in currentAvailableSeasons }
                                    ?: uiState.value.selectedSeason?.takeIf { it in currentAvailableSeasons }
                                    ?: currentAvailableSeasons.firstOrNull()
                                    ?: 1
                            }
                        } else {
                            null
                        }
                        if (currentSeason != null && currentSeason > 0) {
                            if (uiState.value.selectedSeason != currentSeason) {
                                updateState { copy(selectedSeason = currentSeason) }
                            }
                            loadSeasonCredits(currentSeason)
                        }
                        trackerCoordinator.refreshAll()
                    }
                    is EnrichmentUpdate.Error -> {
                        AppLogger.e("DetailsViewModel", "Error loading details: ${update.message}")
                        updateState {
                            copy(
                                fetchFailed = true,
                                isLoading = false,
                                error = update.message,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun selectSeason(season: Int?) {
        updateState { copy(selectedSeason = season, hasUserSelectedSeason = true) }
        if (season != null && season > 0) {
            loadSeasonCredits(season)
        }
        trackerCoordinator.refreshAll()
    }

    private fun loadSeasonCredits(season: Int) {
        val state = uiState.value
        if (season <= 0) return
        if (state.seasonCredits.containsKey(season)) return
        val tmdbId = state.tmdbId ?: state.response?.syncData?.get("tmdb")?.toIntOrNull() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val credits = TmdbEnrichmentService.fetchSeasonCredits(tmdbId, season)
            if (credits.isNotEmpty()) {
                updateState {
                    copy(
                        seasonCredits = seasonCredits + (season to credits),
                    )
                }
            }
        }
    }

    private fun handleAutoPlay() {
        if (uiState.value.hasAutoPlayed) return
        updateState { copy(hasAutoPlayed = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val resp = uiState.value.response ?: return@launch
            val targetEp = DetailsWatchCoordinator.determineAutoPlayTarget(
                provider = provider,
                resp = resp,
                watchHistory = uiState.value.watchHistory,
                targetEpisodeId = targetEpisodeId,
            )
            if (targetEp != null) {
                val tSeason = targetEp.season
                if (tSeason != null && tSeason > 0) {
                    updateState { copy(selectedSeason = tSeason) }
                }
                val patchedData = DetailsWatchCoordinator.patchEpisodeData(targetEp, resp)
                val history = DetailsWatchCoordinator.buildWatchHistory(provider.name, targetEp, resp)
                handlePlayRequest(Triple(provider, patchedData, history))
            }
        }
    }

    private fun handlePlayEpisode(ep: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = uiState.value.response ?: return@launch
            val epSeason = ep.season
            if (epSeason != null && epSeason > 0) {
                updateState { copy(selectedSeason = epSeason, hasUserSelectedSeason = true) }
            }
            val patchedData = DetailsWatchCoordinator.patchEpisodeData(ep, data)
            val history = DetailsWatchCoordinator.buildWatchHistory(provider.name, ep, data)
            handlePlayRequest(Triple(provider, patchedData, history))
        }
    }

    private fun handleDownloadEpisode(ep: Episode) {
        val data = uiState.value.response ?: return
        val patchedData = DetailsWatchCoordinator.patchEpisodeData(ep, data)
        val history = DetailsWatchCoordinator.buildWatchHistory(provider.name, ep, data)
        openLinksPanel(Triple(provider, patchedData, history))
    }

    private fun handleRemoveEpisodeWatched(ep: com.lagradost.cloudstream3.Episode) {
        val data = uiState.value.response ?: return

        val matchingHistories = uiState.value.watchHistory.values.filter { ep.matchesHistory(it) || it.episodeId == ep.data }
        val candidateIds = (matchingHistories.mapNotNull { it.episodeId } + listOf(ep.data, DetailsWatchCoordinator.patchEpisodeData(ep, data))).distinct()

        // Optimistic in-memory update for instant UI feedback
        val updatedMap = uiState.value.watchHistory.toMutableMap()
        updatedMap.entries.removeAll { it.key == ep.data || ep.matchesHistory(it.value) }
        updateState { copy(watchHistory = updatedMap) }

        viewModelScope.launch(Dispatchers.IO) {
            DetailsWatchCoordinator.removeEpisodeWatched(
                providerName = provider.name,
                currentDataUrl = data.url,
                fallbackUrl = url,
                epData = ep.data,
                removeWatchHistory = removeWatchHistory,
                season = ep.season,
                episode = ep.episode,
                extraEpisodeIds = candidateIds,
            )
        }
    }

    private fun handleToggleEpisodeWatched(ep: Episode, isWatched: Boolean) {
        val data = uiState.value.response ?: return
        if (isWatched) {
            val localStatus = parseEpisodeReleaseStatus(ep, provider.name)
            val hasCachedLinks = com.lagradost.cloudstream3.desktop.ui.screens.player.LinkCache.get(ep.data) != null
            val allowedByTrackers = trackerCoordinator.isEpisodeReleasedForAllTrackers(ep, hasCachedLinks)
            if ((!hasCachedLinks && (localStatus.isUnreleased || localStatus.isMissingFromProvider)) || !allowedByTrackers) {
                sendEffect(DetailsUiEffect.ShowToast("Cannot mark unreleased episode as watched yet."))
                return
            }
        }

        // Optimistic in-memory update for instant UI feedback
        val updatedMap = uiState.value.watchHistory.toMutableMap()
        if (isWatched) {
            val currentParentId = DesktopDataStore.watchHistoryId(provider.name, data.url)
            val saved = updatedMap[ep.data] ?: updatedMap.values.find { ep.matchesHistory(it) }
            val dur = if (saved != null && saved.duration > 0L) saved.duration else 60L
            val isMovie = data is MovieLoadResponse
            val newHist = WatchHistory(
                parentId = currentParentId,
                showName = data.name,
                showUrl = data.url,
                apiName = provider.name,
                posterUrl = data.posterUrl,
                episodeThumbnailUrl = ep.posterUrl,
                screenshotUrl = saved?.screenshotUrl,
                episode = if (isMovie) null else ep.episode,
                season = if (isMovie) null else ep.season,
                episodeId = ep.data,
                position = dur,
                duration = dur,
                updateTime = System.currentTimeMillis(),
                episodeName = if (isMovie) null else ep.name,
                episodeDescription = ep.description ?: data.plot,
            )
            updatedMap[ep.data] = newHist
            updateState { copy(watchHistory = updatedMap) }

            viewModelScope.launch(Dispatchers.IO) {
                DetailsWatchCoordinator.toggleEpisodeWatched(
                    providerName = provider.name,
                    data = data,
                    fallbackUrl = url,
                    ep = ep,
                    isWatched = true,
                )
            }
            enqueueAutoTrackersForEpisode(data, ep)
        } else {
            val matchingHistories = uiState.value.watchHistory.values.filter { ep.matchesHistory(it) || it.episodeId == ep.data }
            val candidateIds = (matchingHistories.mapNotNull { it.episodeId } + listOf(ep.data, DetailsWatchCoordinator.patchEpisodeData(ep, data))).distinct()
            updatedMap.entries.removeAll { it.key == ep.data || ep.matchesHistory(it.value) }
            updateState { copy(watchHistory = updatedMap) }

            viewModelScope.launch(Dispatchers.IO) {
                DetailsWatchCoordinator.toggleEpisodeWatched(
                    providerName = provider.name,
                    data = data,
                    fallbackUrl = url,
                    ep = ep,
                    isWatched = false,
                    extraEpisodeIds = candidateIds,
                )
            }
        }
    }

    private fun handleToggleSeasonWatched(episodes: List<Episode>, isWatched: Boolean) {
        val data = uiState.value.response ?: return
        val ceiling = uiState.value.trackers.strictLocalReleaseCeiling
        val targetEpisodes = if (isWatched) {
            val released = EpisodeSectionState.filterReleasedEpisodes(
                episodes = episodes,
                providerName = provider.name,
                aniListCeilingEpisode = ceiling,
                episodeOffset = 0,
            )
            if (released.isEmpty()) {
                sendEffect(DetailsUiEffect.ShowToast("No released episodes in this season to mark watched yet."))
                return
            }
            if (released.size < episodes.size) {
                sendEffect(
                    DetailsUiEffect.ShowToast(
                        "Marked ${released.size} released episode${if (released.size == 1) "" else "s"} watched (skipped ${episodes.size - released.size} unreleased).",
                    ),
                )
            }
            released
        } else {
            episodes
        }

        // Optimistic in-memory update for instant UI feedback
        val updatedMap = uiState.value.watchHistory.toMutableMap()
        if (isWatched) {
            val currentParentId = DesktopDataStore.watchHistoryId(provider.name, data.url)
            val isMovie = data is MovieLoadResponse
            targetEpisodes.forEach { ep ->
                val saved = updatedMap[ep.data] ?: updatedMap.values.find { ep.matchesHistory(it) }
                val dur = if (saved != null && saved.duration > 0L) saved.duration else 60L
                updatedMap[ep.data] = WatchHistory(
                    parentId = currentParentId,
                    showName = data.name,
                    showUrl = data.url,
                    apiName = provider.name,
                    posterUrl = data.posterUrl,
                    episodeThumbnailUrl = ep.posterUrl,
                    screenshotUrl = saved?.screenshotUrl,
                    episode = if (isMovie) null else ep.episode,
                    season = if (isMovie) null else ep.season,
                    episodeId = ep.data,
                    position = dur,
                    duration = dur,
                    updateTime = System.currentTimeMillis(),
                    episodeName = if (isMovie) null else ep.name,
                    episodeDescription = ep.description ?: data.plot,
                )
            }
        } else {
            targetEpisodes.forEach { ep ->
                updatedMap.entries.removeAll { it.key == ep.data || ep.matchesHistory(it.value) }
            }
        }
        updateState { copy(watchHistory = updatedMap) }

        viewModelScope.launch(Dispatchers.IO) {
            val newBackup = DetailsWatchCoordinator.toggleSeasonWatched(
                providerName = provider.name,
                data = data,
                fallbackUrl = url,
                episodes = targetEpisodes,
                isWatched = isWatched,
                currentWatchHistory = uiState.value.watchHistory,
                backupSeasonHistory = uiState.value.backupSeasonHistory,
            )
            updateState { copy(backupSeasonHistory = newBackup) }
        }
        if (isWatched) {
            val highestEp = targetEpisodes.maxByOrNull { it.episode ?: 0 } ?: targetEpisodes.lastOrNull()
            if (highestEp != null) {
                enqueueAutoTrackersForEpisode(data, highestEp)
            }
        }
    }

    private fun enqueueAutoTrackersForEpisode(
        data: LoadResponse,
        ep: Episode,
    ) {
        val engine = trackerSyncEngine ?: return
        val stateSnapshot = uiState.value
        val isMovieType = data is MovieLoadResponse || data.type in setOf(TvType.Movie, TvType.AnimeMovie)
        val epNum = ep.episode ?: if (isMovieType) 1 else return
        if (epNum <= 0) return

        val seasonNum = (ep.season ?: stateSnapshot.selectedSeason ?: 1).coerceAtLeast(1)
        val hasMultiSeasons = (stateSnapshot.enrichedSeasonsCount ?: 0) > 1 ||
            stateSnapshot.enrichedSeasonsMetadata.size > 1 ||
            when (data) {
                is TvSeriesLoadResponse -> data.episodes.mapNotNull { it.season }.distinct().size > 1
                is AnimeLoadResponse -> data.episodes.keys.size > 1
                else -> false
            }
        val effectiveUrl = data.url.takeIf { it.isNotBlank() } ?: url
        val effectiveApiName = data.apiName.takeIf { it.isNotBlank() } ?: provider.name

        engine.enqueueCompletionForConnectedTrackers(
            com.lagradost.cloudstream3.desktop.tracking.SyncCompletionRequest(
                apiName = effectiveApiName,
                showUrl = effectiveUrl,
                showTitle = data.name.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty(),
                mappedSeason = seasonNum,
                episodeNum = epNum,
                year = stateSnapshot.enrichedYear ?: data.year,
                tvType = data.type,
                completionVerified = true,
                canonicalSeed = stateSnapshot.canonicalSeed,
                resolvedAnilistId = stateSnapshot.anilistId,
                resolvedMalId = stateSnapshot.malId,
                resolvedTmdbId = stateSnapshot.tmdbId,
                resolvedImdbId = stateSnapshot.imdbId,
                hasMultipleSeasons = hasMultiSeasons,
            ),
        )
    }

    private fun handleToggleEpisodesStackedView(isStacked: Boolean) {
        val viewMode = if (isStacked) 1 else 0
        updateState { copy(isEpisodesStackedView = isStacked, episodeViewMode = viewMode) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_STACKED_VIEW, isStacked)
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_VIEW_MODE, viewMode)
        }
    }

    private fun handleSetEpisodeViewMode(viewMode: Int) {
        val isStacked = viewMode != 0
        updateState { copy(episodeViewMode = viewMode, isEpisodesStackedView = isStacked) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_VIEW_MODE, viewMode)
            DesktopDataStore.setKey(PreferenceKeys.PREF_EPISODES_STACKED_VIEW, isStacked)
        }
    }

    private fun handlePlayRequest(data: Triple<MainAPI, String, WatchHistory>, forceAutoPlay: Boolean? = null) {
        val isPureTorrent = com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.isPureTorrentProvider(data.first)
        val isP2pOn = com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.isP2pEnabled
        val shouldAutoPlay = (forceAutoPlay ?: uiState.value.autoPlayEnabled) && (!isPureTorrent || isP2pOn)
        if (shouldAutoPlay) {
            val linkHistory = data.third
            val epTitle = buildString {
                append(linkHistory.showName)
                if (linkHistory.season != null && linkHistory.episode != null) {
                    append(" - S${linkHistory.season}E${linkHistory.episode}")
                } else if (linkHistory.episode != null) {
                    append(" - E${linkHistory.episode}")
                }
            }
            val response = uiState.value.response
            val isLive = response?.type == TvType.Live
            val resumeMs = if (isLive) 0L else com.lagradost.player.impl.PlayerLinkHandler.resumeStartSeconds(linkHistory.position, linkHistory.duration) * 1000L

            val currentSeason = linkHistory.season ?: uiState.value.selectedSeason
            val seasonCast = if (currentSeason != null && currentSeason > 0) {
                uiState.value.seasonCredits[currentSeason]
            } else {
                null
            }
            val hasAnimeDualCast = uiState.value.enrichedActors?.any { it.voiceActor != null } == true
            val effectiveActors = if (hasAnimeDualCast) uiState.value.enrichedActors else (seasonCast ?: uiState.value.enrichedActors ?: response?.actors)

            val isMovie = response is MovieLoadResponse
            val candidateKeys = buildList {
                data.second.takeIf { it.isNotBlank() }?.let { add(it) }
                linkHistory.episodeId?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (isMovie || isEmpty()) {
                    linkHistory.showUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                    linkHistory.parentId.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.distinct()
            val cached = candidateKeys.firstNotNullOfOrNull { com.lagradost.cloudstream3.desktop.ui.screens.player.LinkCache.get(it) }
            val linksToPass = cached?.links ?: emptyList()
            val subsToPass = cached?.subtitles ?: emptyList()

            val stateSnapshot = uiState.value
            val hasMultipleSeasons = (stateSnapshot.enrichedSeasonsCount ?: 0) > 1 ||
                stateSnapshot.enrichedSeasonsMetadata.size > 1 ||
                when (response) {
                    is TvSeriesLoadResponse -> response.episodes.mapNotNull { it.season }.distinct().size > 1
                    is AnimeLoadResponse -> response.episodes.keys.size > 1
                    else -> false
                }

            sendEffect(
                DetailsUiEffect.NavigateToPlayer(
                    com.lagradost.cloudstream3.desktop.ui.VideoLaunchData(
                        links = linksToPass,
                        initialIndex = 0,
                        title = epTitle,
                        subtitles = subsToPass,
                        startPositionMs = resumeMs,
                        history = linkHistory,
                        loadResponse = response,
                        enrichedLogoUrl = stateSnapshot.enrichedLogoUrl,
                        enrichedBackdropUrl = stateSnapshot.enrichedBackdropUrl,
                        enrichedActors = effectiveActors,
                        canonicalSeed = stateSnapshot.canonicalSeed,
                        resolvedAnilistId = stateSnapshot.anilistId,
                        resolvedMalId = stateSnapshot.malId,
                        resolvedYear = stateSnapshot.enrichedYear ?: response?.year,
                        hasMultipleSeasons = hasMultipleSeasons,
                    ),
                ),
            )
        } else {
            handleEvent(DetailsUiEvent.OnOpenLinksPanel(data))
        }
    }

    private fun retry() {
        updateState { copy(fetchFailed = false, isLoading = true) }
        DetailsCache.remove(url)
        loadDetails()
    }

    private fun refresh() {
        DetailsCache.remove(url)
        uiState.value.response?.url?.let { DetailsCache.remove(it) }
        EnrichedDetailsCache.remove(url)
        uiState.value.response?.url?.let { EnrichedDetailsCache.remove(it) }
        val titleToEvict = uiState.value.response?.name ?: uiState.value.preloadedName
        com.lagradost.cloudstream3.desktop.metadata.MetadataPipeline.clearCache(titleToEvict)
        // Redundant toast removed in favor of native UI loading state
        updateState {
            copy(
                isInitialized = true,
                isLoading = true,
                fetchFailed = false,
                error = null,
                response = null,
                fakeData = null,
                isEnriching = false,
                enrichmentPhase = com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase.Idle,
                enrichedLogoUrl = null,
                enrichedBackdropUrl = null,
                enrichedTagline = null,
                enrichedStatus = null,
                enrichedStudios = emptyList(),
                enrichedProductionCompanies = emptyList(),
                enrichedNetworksList = emptyList(),
                enrichedCollectionName = null,
                enrichedCollectionBackdrop = null,
                enrichedSeasonsCount = null,
                enrichedEpisodesCount = null,
                enrichedSeasonsMetadata = emptyList(),
                enrichedOriginalLanguage = null,
                enrichedReleaseDate = null,
                enrichedCountry = null,
                enrichedCollectionItems = emptyList(),
                enrichedBudget = null,
                enrichedRevenue = null,
                enrichedNetworks = emptyList(),
                enrichedYear = null,
                enrichedDuration = null,
                enrichedTags = null,
                enrichedActors = null,
                enrichedImdbRating = null,
                enrichedTmdbRating = null,
                enrichedAniListRating = null,
                enrichedReviews = emptyList(),
                enrichedTrailers = emptyList(),
                enrichedTrailerUrl = null,
                screenshots = null,
                tmdbId = canonicalSeed?.tmdbId,
                imdbId = canonicalSeed?.imdbId,
                anilistId = canonicalSeed?.anilistId,
                malId = canonicalSeed?.malId,
                seasonCredits = emptyMap(),
                episodeThumbnailVersion = 0,
            )
        }
        loadDetails()
    }

    private fun openLinksPanel(data: Triple<MainAPI, String, WatchHistory>) {
        val currentSeason = data.third.season ?: uiState.value.selectedSeason
        val seasonCast = if (currentSeason != null && currentSeason > 0) {
            uiState.value.seasonCredits[currentSeason]
        } else {
            null
        }
        val hasAnimeDualCast = uiState.value.enrichedActors?.any { it.voiceActor != null } == true
        val effectiveActors = if (hasAnimeDualCast) uiState.value.enrichedActors else (seasonCast ?: uiState.value.enrichedActors ?: uiState.value.response?.actors)

        val snapshot = uiState.value
        val hasMultipleSeasons = (snapshot.enrichedSeasonsCount ?: 0) > 1 ||
            snapshot.enrichedSeasonsMetadata.size > 1 ||
            when (val resp = snapshot.response) {
                is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().size > 1
                is AnimeLoadResponse -> resp.episodes.keys.size > 1
                else -> false
            }

        sendEffect(
            DetailsUiEffect.OpenLinks(
                Config.Links(
                    providerName = data.first.name,
                    dataUrl = data.second,
                    history = data.third,
                    loadResponse = snapshot.response,
                    enrichedActors = effectiveActors,
                    enrichedLogoUrl = snapshot.enrichedLogoUrl,
                    enrichedBackdropUrl = snapshot.enrichedBackdropUrl,
                    canonicalSeed = snapshot.canonicalSeed,
                    resolvedAnilistId = snapshot.anilistId,
                    resolvedMalId = snapshot.malId,
                    resolvedYear = snapshot.enrichedYear ?: snapshot.response?.year,
                    hasMultipleSeasons = hasMultipleSeasons,
                ),
            ),
        )
    }

    private fun handleTogglePinRightColumn() {
        val nextPinned = !uiState.value.isRightColumnPinned
        updateState { copy(isRightColumnPinned = nextPinned) }
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(PreferenceKeys.DETAILS_RIGHT_COLUMN_PINNED, nextPinned)
        }
    }

    private fun closeLinksPanel() {
        updateState { copy(isPanelOpen = false, activeLinkData = null) }
    }

    private fun cacheEnrichedState(state: DetailsUiState) {
        EnrichedDetailsCache.put(url, state)
        state.response?.url?.let { rUrl -> if (rUrl != url) EnrichedDetailsCache.put(rUrl, state) }
    }

    override fun dispose() {
        trackerCoordinator.dispose()
        super.dispose()
    }
}
