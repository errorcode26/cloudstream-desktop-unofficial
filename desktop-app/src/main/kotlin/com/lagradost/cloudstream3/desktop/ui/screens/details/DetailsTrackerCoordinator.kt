package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.EpisodeSyncCheck
import com.lagradost.cloudstream3.desktop.tracking.GuardDecision
import com.lagradost.cloudstream3.desktop.tracking.MatchRequest
import com.lagradost.cloudstream3.desktop.tracking.MatchResolution
import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerMediaCandidate
import com.lagradost.cloudstream3.desktop.tracking.TrackerProvider
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.tracking.TrackerStatusUpdate
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.tracking.buildTrackerShowKey
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackerEntryState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

class DetailsTrackerCoordinator(
    private val scope: CoroutineScope,
    private val provider: MainAPI,
    private val fallbackUrl: String,
    private val preloadedName: String?,
    private val trackingRepository: TrackingRepository,
    private val registry: TrackerRegistry = TrackerRegistry,
    private val getState: () -> DetailsUiState,
    private val updateState: (DetailsUiState.() -> DetailsUiState) -> Unit,
    private val sendEffect: (DetailsUiEffect) -> Unit,
) {
    private val refreshLock = Any()
    private var refreshJob: Job? = null
    private var isRefreshing: Boolean = false
    private var hasPendingRefresh: Boolean = false
    private var pendingForceRemoteFetch: Boolean = false
    private var pendingTargetProviderId: String? = null
    private val resolvedInputFingerprints = mutableMapOf<String, String>()
    private val searchJobs = mutableMapOf<String, Job>()

    private fun updateEntry(
        providerId: String,
        transform: DetailsTrackerEntryState.() -> DetailsTrackerEntryState,
    ) {
        updateState {
            val current = trackers.entries[providerId]
                ?: registry.get(providerId)?.let { DetailsTrackerEntryState(descriptor = it.descriptor) }
                ?: return@updateState this
            copy(
                trackers = trackers.copy(
                    entries = trackers.entries + (providerId to current.transform()),
                ),
            )
        }
    }

    fun refreshAll(forceRemoteFetch: Boolean = false, targetProviderId: String? = null) {
        synchronized(refreshLock) {
            if (isRefreshing) {
                pendingTargetProviderId = if (!hasPendingRefresh) {
                    targetProviderId
                } else if (pendingTargetProviderId != targetProviderId) {
                    null
                } else {
                    pendingTargetProviderId
                }
                hasPendingRefresh = true
                pendingForceRemoteFetch = pendingForceRemoteFetch || forceRemoteFetch
                return
            }
            isRefreshing = true
            hasPendingRefresh = false
            pendingForceRemoteFetch = false
            pendingTargetProviderId = null
        }

        refreshJob = scope.launch(Dispatchers.IO) {
            var nextForce = forceRemoteFetch
            var nextTarget = targetProviderId
            try {
                while (true) {
                    executeRefreshPass(forceRemoteFetch = nextForce, targetProviderId = nextTarget)
                    val next = synchronized(refreshLock) {
                        if (hasPendingRefresh) {
                            val pair = pendingForceRemoteFetch to pendingTargetProviderId
                            hasPendingRefresh = false
                            pendingForceRemoteFetch = false
                            pendingTargetProviderId = null
                            pair
                        } else {
                            isRefreshing = false
                            null
                        }
                    } ?: break
                    nextForce = next.first
                    nextTarget = next.second
                }
            } finally {
                synchronized(refreshLock) {
                    isRefreshing = false
                    hasPendingRefresh = false
                    pendingForceRemoteFetch = false
                    pendingTargetProviderId = null
                }
            }
        }
    }

    private suspend fun executeRefreshPass(forceRemoteFetch: Boolean, targetProviderId: String?) {
        val state = getState()
        val resp = state.response
        val effectiveUrl = resp?.url?.takeIf { it.isNotBlank() } ?: fallbackUrl
        val effectiveApiName = resp?.apiName?.takeIf { it.isNotBlank() } ?: provider.name
        val showKey = buildTrackerShowKey(effectiveApiName, effectiveUrl)
        val mappedSeason = (state.selectedSeason ?: 1).coerceAtLeast(1)
        val showTitle = resp?.name?.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty()

        val isAnimeType = resp?.let {
            it is AnimeLoadResponse ||
                it.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
                it.tags?.any { tag -> tag.contains("anime", ignoreCase = true) } == true
        } == true
        val hasAnimeSeed = isAnimeType ||
            state.anilistId != null ||
            state.malId != null ||
            state.canonicalSeed?.anilistId != null ||
            state.canonicalSeed?.malId != null

        val hasMultiSeasons = (state.enrichedSeasonsCount ?: 0) > 1 ||
            state.enrichedSeasonsMetadata.size > 1 ||
            when (resp) {
                is TvSeriesLoadResponse -> resp.episodes.mapNotNull { it.season }.distinct().size > 1
                is AnimeLoadResponse -> resp.episodes.keys.size > 1
                else -> false
            }

        val providersToRefresh = if (targetProviderId != null) {
            listOfNotNull(registry.get(targetProviderId))
        } else {
            registry.all()
        }

        val resolvedEntries = LinkedHashMap<String, DetailsTrackerEntryState>(state.trackers.entries)
        val fetchTargets = mutableListOf<Pair<TrackerProvider, TrackerBinding>>()
        var anyResolvedOrChanged = false

        fun buildInputFingerprint(
            isConnected: Boolean,
            isEligible: Boolean,
            bindPolicy: TrackerBindPolicy?,
            isAutoSync: Boolean,
            binding: TrackerBinding?,
        ): String = listOf(
            showKey,
            mappedSeason.toString(),
            showTitle,
            (state.enrichedYear ?: resp?.year)?.toString().orEmpty(),
            (resp?.type ?: TvType.TvSeries).name,
            hasAnimeSeed.toString(),
            hasMultiSeasons.toString(),
            (state.canonicalSeed?.anilistId ?: state.anilistId)?.toString().orEmpty(),
            (state.canonicalSeed?.malId ?: state.malId)?.toString().orEmpty(),
            state.anilistId?.toString().orEmpty(),
            state.malId?.toString().orEmpty(),
            state.tmdbId?.toString().orEmpty(),
            state.imdbId.orEmpty(),
            isConnected.toString(),
            isEligible.toString(),
            bindPolicy?.name.orEmpty(),
            isAutoSync.toString(),
            binding?.remoteMediaId.orEmpty(),
            binding?.bindMode?.name.orEmpty(),
            binding?.episodeOffset?.toString().orEmpty(),
        ).joinToString("|")

        for (trackerProvider in providersToRefresh) {
            val pId = trackerProvider.id
            val prevEntry = state.trackers.entries[pId]
            val isConnected = trackerProvider.isConnected(trackingRepository)
            val existingBinding = trackingRepository.getBinding(showKey, mappedSeason, pId)
            val isEligible = trackerProvider.isEligibleForMedia(
                tvType = resp?.type ?: TvType.TvSeries,
                hasAnimeSeed = hasAnimeSeed,
                hasExistingBinding = existingBinding != null,
            )
            val bindPolicy = if (isConnected && isEligible) trackingRepository.getBindPolicy(pId) else null
            val isAutoSync = if (isConnected && isEligible) trackingRepository.isAutoSyncEnabled(pId) else false
            val preFingerprint = buildInputFingerprint(
                isConnected = isConnected,
                isEligible = isEligible,
                bindPolicy = bindPolicy,
                isAutoSync = isAutoSync,
                binding = existingBinding,
            )

            if (!forceRemoteFetch && prevEntry != null && resolvedInputFingerprints[pId] == preFingerprint) {
                continue
            }
            anyResolvedOrChanged = true

            if (!isConnected || !isEligible || bindPolicy == null) {
                resolvedInputFingerprints[pId] = preFingerprint
                resolvedEntries[pId] = DetailsTrackerEntryState(
                    descriptor = trackerProvider.descriptor,
                    isConnected = isConnected,
                    isEligible = isEligible,
                    showKey = showKey,
                    mappedSeason = mappedSeason,
                    boundEntry = existingBinding,
                )
                continue
            }

            val resolution = if (showTitle.isNotBlank()) {
                trackerProvider.resolveMatch(
                    request = MatchRequest(
                        profileId = DesktopDataStore.activeProfileId.toLong(),
                        showKey = showKey,
                        showTitle = showTitle,
                        mappedSeason = mappedSeason,
                        year = state.enrichedYear ?: resp?.year,
                        tvType = resp?.type ?: TvType.Anime,
                        canonicalSeed = state.canonicalSeed,
                        resolvedAnilistId = state.anilistId,
                        resolvedMalId = state.malId,
                        resolvedTmdbId = state.tmdbId,
                        resolvedImdbId = state.imdbId,
                        bindPolicy = bindPolicy,
                        hasMultipleSeasons = hasMultiSeasons,
                    ),
                    repository = trackingRepository,
                )
            } else {
                existingBinding?.let { MatchResolution.Bound(it) }
            }

            val bound = (resolution as? MatchResolution.Bound)?.binding
            // Record using the post-resolution binding so resolveMatch's own DB write does not trigger a duplicate pass.
            resolvedInputFingerprints[pId] = buildInputFingerprint(
                isConnected = isConnected,
                isEligible = isEligible,
                bindPolicy = bindPolicy,
                isAutoSync = isAutoSync,
                binding = bound ?: existingBinding,
            )
            val needsConfirmation = resolution as? MatchResolution.NeedsConfirmation
            val initialCandidates = if (needsConfirmation != null && needsConfirmation.mediaCandidates.isNotEmpty()) {
                needsConfirmation.mediaCandidates
            } else {
                needsConfirmation?.candidates?.map {
                    TrackerMediaCandidate(
                        providerId = pId,
                        remoteMediaId = it.anilistId.toString(),
                        malId = it.malId,
                        title = it.title,
                        format = it.format,
                        seasonYear = it.seasonYear,
                        episodes = it.episodes,
                        status = it.status,
                        posterUrl = it.posterUrl,
                        matchScore = it.matchScore,
                    )
                }.orEmpty()
            }

            val needsRemoteFetch = bound != null && (
                forceRemoteFetch ||
                    targetProviderId == pId ||
                    prevEntry?.remoteStatus?.remoteMediaId != bound.remoteMediaId
                )

            val baseEntry = (prevEntry ?: DetailsTrackerEntryState(descriptor = trackerProvider.descriptor)).copy(
                descriptor = trackerProvider.descriptor,
                isConnected = true,
                isEligible = true,
                showKey = showKey,
                mappedSeason = mappedSeason,
                resolution = resolution,
                boundEntry = bound,
                editOffset = bound?.episodeOffset?.toInt() ?: 0,
                searchQuery = prevEntry?.searchQuery?.takeIf { it.isNotBlank() } ?: showTitle,
                searchResults = if (prevEntry?.searchResults.isNullOrEmpty() && initialCandidates.isNotEmpty()) {
                    initialCandidates
                } else {
                    prevEntry?.searchResults.orEmpty()
                },
                isLoadingStatus = needsRemoteFetch,
                remoteStatus = if (bound == null) null else prevEntry?.remoteStatus?.takeIf { it.remoteMediaId == bound.remoteMediaId },
                releaseBounds = if (bound == null) ReleaseBounds() else prevEntry?.releaseBounds ?: ReleaseBounds(),
                editScore = if (bound == null) 0 else prevEntry?.editScore ?: 0,
                isFavourite = if (bound == null) false else prevEntry?.isFavourite ?: false,
                isAutoSyncEnabled = isAutoSync,
                bindPolicy = bindPolicy,
            )
            resolvedEntries[pId] = baseEntry
            if (bound != null && needsRemoteFetch) {
                fetchTargets.add(trackerProvider to bound)
            }
        }

        if (!anyResolvedOrChanged && fetchTargets.isEmpty()) return

        updateState {
            val mergedEntries = LinkedHashMap<String, DetailsTrackerEntryState>(trackers.entries)
            for ((pId, freshEntry) in resolvedEntries) {
                val live = trackers.entries[pId]
                mergedEntries[pId] = if (live != null) {
                    val effectiveResults = when {
                        live.hasSearched -> live.searchResults
                        live.searchResults.isNotEmpty() -> live.searchResults
                        else -> freshEntry.searchResults
                    }
                    freshEntry.copy(
                        searchQuery = live.searchQuery.ifBlank { freshEntry.searchQuery },
                        searchResults = effectiveResults,
                        isSearching = live.isSearching,
                        hasSearched = live.hasSearched || effectiveResults.isNotEmpty(),
                        showSearchDrawer = if (freshEntry.boundEntry == null) {
                            live.showSearchDrawer || freshEntry.showSearchDrawer
                        } else {
                            live.showSearchDrawer
                        },
                    )
                } else {
                    freshEntry
                }
            }
            val activeId = trackers.activeProviderId?.takeIf {
                mergedEntries[it]?.let { e -> e.isConnected && e.isEligible } == true
            } ?: mergedEntries.values.firstOrNull { it.isConnected && it.isEligible }?.descriptor?.id
            copy(
                trackers = trackers.copy(
                    entries = mergedEntries,
                    activeProviderId = activeId,
                ),
            )
        }

        if (fetchTargets.isNotEmpty()) {
            coroutineScope {
                fetchTargets.map { (trackerProvider, bound) ->
                    async {
                        val freshStatus = trackerProvider.getRemoteStatus(
                            repository = trackingRepository,
                            remoteMediaId = bound.remoteMediaId,
                            fallbackTotalEpisodes = bound.expectedTotalEpisodes?.toInt(),
                        )
                        trackerProvider to (bound to freshStatus)
                    }
                }.awaitAll()
            }.forEach { (trackerProvider, pair) ->
                val (bound, freshStatus) = pair
                updateEntry(trackerProvider.id) {
                    val effectiveStatus = freshStatus ?: remoteStatus
                    val bounds = trackerProvider.evaluateBounds(
                        status = effectiveStatus,
                        fallbackTotalEpisodes = bound.expectedTotalEpisodes?.toInt(),
                    )
                    val resolvedWatchStatus = effectiveStatus?.status?.takeIf { it != UnifiedWatchStatus.NONE }
                        ?: UnifiedWatchStatus.WATCHING
                    copy(
                        remoteStatus = effectiveStatus,
                        releaseBounds = bounds,
                        editStatus = resolvedWatchStatus,
                        editProgress = effectiveStatus?.progress ?: 0,
                        editScore = effectiveStatus?.score100 ?: 0,
                        isFavourite = effectiveStatus?.isFavourite == true,
                        isLoadingStatus = false,
                    )
                }
            }
        }
    }

    fun openDrawer(requestedProviderId: String?) {
        val state = getState()
        val available = state.trackers.availableTrackers
        val targetEntry = requestedProviderId?.let { id -> available.firstOrNull { it.descriptor.id == id } }
            ?: state.trackers.activeEntry
            ?: available.firstOrNull()
        val targetId = targetEntry?.descriptor?.id
        val currentTitle = state.response?.name?.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty()

        updateState {
            val nextEntries = if (targetEntry != null) {
                entriesWithSearchPrepared(trackers.entries, targetEntry.descriptor.id, currentTitle)
            } else {
                trackers.entries
            }
            copy(
                trackers = trackers.copy(
                    entries = nextEntries,
                    isDrawerOpen = true,
                    activeProviderId = targetId,
                ),
            )
        }

        if (targetId != null) {
            refreshAll(forceRemoteFetch = true, targetProviderId = targetId)
            val entryAfter = getState().trackers.entries[targetId]
            if (entryAfter != null &&
                entryAfter.boundEntry == null &&
                entryAfter.searchResults.isEmpty() &&
                !entryAfter.isSearching &&
                currentTitle.isNotBlank()
            ) {
                searchCandidates(targetId)
            }
        }
    }

    fun selectTab(providerId: String) {
        val state = getState()
        val entry = state.trackers.entries[providerId] ?: return
        val currentTitle = state.response?.name?.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty()
        updateState {
            copy(
                trackers = trackers.copy(
                    activeProviderId = providerId,
                    entries = trackers.entries + (
                        providerId to entry.copy(
                            showSearchDrawer = entry.boundEntry == null,
                            feedbackMessage = null,
                            searchQuery = entry.searchQuery.ifBlank { currentTitle },
                        )
                    ),
                ),
            )
        }
        if (entry.boundEntry == null && entry.searchResults.isEmpty() && !entry.isSearching && currentTitle.isNotBlank()) {
            searchCandidates(providerId)
        }
    }

    fun toggleSearch(providerId: String) {
        val state = getState()
        val entry = state.trackers.entries[providerId] ?: return
        val currentTitle = state.response?.name?.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty()
        val opening = !entry.showSearchDrawer
        updateEntry(providerId) {
            copy(
                showSearchDrawer = opening,
                searchQuery = searchQuery.ifBlank { currentTitle },
                feedbackMessage = null,
            )
        }
        if (opening && entry.searchResults.isEmpty() && !entry.isSearching && currentTitle.isNotBlank()) {
            searchCandidates(providerId)
        }
    }

    fun searchCandidates(providerId: String) {
        val state = getState()
        val trackerProvider = registry.get(providerId) ?: return
        val entry = state.trackers.entries[providerId] ?: return
        val query = entry.searchQuery.trim()
        if (query.isBlank()) return

        searchJobs[providerId]?.cancel()
        updateEntry(providerId) { copy(isSearching = true, feedbackMessage = null) }
        searchJobs[providerId] = scope.launch(Dispatchers.IO) {
            val resp = state.response
            val results = runCatching {
                trackerProvider.searchCandidates(
                    repository = trackingRepository,
                    query = query,
                    year = state.enrichedYear ?: resp?.year,
                    tvType = resp?.type ?: TvType.Anime,
                )
            }.getOrElse { emptyList() }
            updateEntry(providerId) { copy(isSearching = false, hasSearched = true, searchResults = results) }
        }
    }

    fun pinCandidate(providerId: String, candidate: TrackerMediaCandidate) {
        scope.launch(Dispatchers.IO) {
            val state = getState()
            val entry = state.trackers.entries[providerId] ?: return@launch
            val showKey = entry.showKey ?: buildTrackerShowKey(provider.name, state.response?.url ?: fallbackUrl)
            val season = entry.mappedSeason.coerceAtLeast(1)
            val binding = TrackerBinding(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showKey = showKey,
                mappedSeason = season.toLong(),
                remoteMediaId = candidate.remoteMediaId,
                remoteTitle = candidate.title,
                bindMode = BindMode.USER_PINNED,
                episodeOffset = entry.editOffset.toLong(),
                expectedTotalEpisodes = candidate.episodes?.toLong(),
                updatedAt = System.currentTimeMillis(),
            )
            trackingRepository.saveBinding(binding)
            updateEntry(providerId) {
                copy(
                    boundEntry = binding,
                    resolution = MatchResolution.Bound(binding),
                    showSearchDrawer = false,
                    feedbackMessage = "Pinned '${candidate.title}' for Season $season",
                    feedbackIsError = false,
                )
            }
            refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
        }
    }

    fun pushRemoteUpdate(providerId: String) {
        val state = getState()
        val entry = state.trackers.entries[providerId] ?: return
        val trackerProvider = registry.get(providerId) ?: return
        val binding = entry.boundEntry ?: return

        val guardDecision = trackerProvider.validateStatusAndProgress(
            bounds = entry.releaseBounds,
            status = entry.editStatus,
            progress = entry.editProgress,
        )
        if (guardDecision is GuardDecision.Blocked) {
            updateEntry(providerId) { copy(feedbackMessage = guardDecision.reason, feedbackIsError = true) }
            sendEffect(DetailsUiEffect.ShowToast(guardDecision.reason))
            return
        }

        updateEntry(providerId) { copy(isSaving = true, feedbackMessage = null) }
        scope.launch(Dispatchers.IO) {
            if (entry.editOffset.toLong() != binding.episodeOffset) {
                trackingRepository.saveBinding(
                    binding.copy(
                        episodeOffset = entry.editOffset.toLong(),
                        bindMode = BindMode.USER_PINNED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            val prevScore = entry.remoteStatus?.score100 ?: 0
            val clearScore = entry.editScore == 0 && prevScore > 0
            val ok = trackerProvider.updateRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = binding.remoteMediaId,
                update = TrackerStatusUpdate(
                    status = entry.editStatus,
                    progress = entry.editProgress,
                    score100 = entry.editScore.takeIf { it > 0 },
                    clearScore = clearScore,
                ),
                bounds = entry.releaseBounds,
            )
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showName = binding.remoteTitle.ifBlank { state.response?.name ?: preloadedName.orEmpty() },
                remoteMediaId = binding.remoteMediaId,
                episodeNum = entry.editProgress.toLong(),
                effectiveRemoteEpisode = entry.editProgress.toLong(),
                oldProgress = (entry.remoteStatus?.progress ?: 0).toLong(),
                newProgress = entry.editProgress.toLong(),
                statusApplied = entry.editStatus.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Manual Details Drawer update" else "${trackerProvider.descriptor.displayName} rejected update",
                timestamp = System.currentTimeMillis(),
            )
            updateEntry(providerId) {
                copy(
                    isSaving = false,
                    feedbackMessage = if (ok) {
                        val scoreNote = if (entry.editScore > 0) " · ★ ${String.format(Locale.US, "%.1f", entry.editScore / 10.0)}/10" else ""
                        "Saved to ${trackerProvider.descriptor.displayName}: Ep ${entry.editProgress} (${entry.editStatus.label})$scoreNote"
                    } else {
                        "Failed to update ${trackerProvider.descriptor.displayName} entry."
                    },
                    feedbackIsError = !ok,
                )
            }
            if (ok) refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
        }
    }

    fun toggleFavorite(providerId: String) {
        val entry = getState().trackers.entries[providerId] ?: return
        val trackerProvider = registry.get(providerId) ?: return
        val remoteId = entry.boundEntry?.remoteMediaId ?: return
        if (!trackerProvider.descriptor.supportsFavorites) return
        val nextFav = !entry.isFavourite

        updateEntry(providerId) { copy(isSaving = true, feedbackMessage = null) }
        scope.launch(Dispatchers.IO) {
            val ok = trackerProvider.toggleFavorite(trackingRepository, remoteId) == true
            updateEntry(providerId) {
                copy(
                    isSaving = false,
                    isFavourite = if (ok) nextFav else isFavourite,
                    feedbackMessage = if (ok) {
                        if (nextFav) "Added to ${trackerProvider.descriptor.displayName} favorites" else "Removed from favorites"
                    } else {
                        "Could not update favorite status."
                    },
                    feedbackIsError = !ok,
                )
            }
            if (ok) refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
        }
    }

    fun removeEntry(providerId: String) {
        val state = getState()
        val entry = state.trackers.entries[providerId] ?: return
        val trackerProvider = registry.get(providerId) ?: return
        val binding = entry.boundEntry ?: return

        updateEntry(providerId) { copy(isSaving = true, feedbackMessage = null) }
        scope.launch(Dispatchers.IO) {
            val ok = trackerProvider.removeEntry(trackingRepository, binding.remoteMediaId)
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showName = binding.remoteTitle.ifBlank { state.response?.name ?: preloadedName.orEmpty() },
                remoteMediaId = binding.remoteMediaId,
                episodeNum = 0L,
                effectiveRemoteEpisode = 0L,
                oldProgress = (entry.remoteStatus?.progress ?: 0).toLong(),
                newProgress = 0L,
                statusApplied = UnifiedWatchStatus.NONE.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Removed entry from ${trackerProvider.descriptor.displayName}" else "Failed to remove entry",
                timestamp = System.currentTimeMillis(),
            )
            updateEntry(providerId) {
                copy(
                    isSaving = false,
                    feedbackMessage = if (ok) "Removed entry from ${trackerProvider.descriptor.displayName}." else "Failed to remove entry.",
                    feedbackIsError = !ok,
                )
            }
            if (ok) refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
        }
    }

    fun syncEpisode(providerId: String, ep: Episode) {
        val entry = getState().trackers.entries[providerId] ?: return
        val trackerProvider = registry.get(providerId) ?: return
        val binding = entry.boundEntry
        if (!entry.isConnected || binding == null) {
            sendEffect(DetailsUiEffect.ShowToast("Link ${trackerProvider.descriptor.displayName} first to sync episodes."))
            openDrawer(providerId)
            return
        }

        val localStatus = parseEpisodeReleaseStatus(ep, provider.name)
        val localBlocked = localStatus.isUnreleased || localStatus.isMissingFromProvider

        updateEntry(providerId) { copy(syncingEpisodeNum = ep.episode, feedbackMessage = null) }
        scope.launch(Dispatchers.IO) {
            val freshStatus = trackerProvider.getRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = binding.remoteMediaId,
                fallbackTotalEpisodes = binding.expectedTotalEpisodes?.toInt(),
            )
            val bounds = trackerProvider.evaluateBounds(
                status = freshStatus ?: entry.remoteStatus,
                fallbackTotalEpisodes = binding.expectedTotalEpisodes?.toInt(),
            )
            val check = trackerProvider.canSyncEpisode(
                bounds = bounds,
                rawEpisodeNumber = ep.episode,
                episodeOffset = entry.editOffset,
                localReleaseBlocked = localBlocked,
            )
            if (check is EpisodeSyncCheck.Blocked) {
                val oldProg = (freshStatus?.progress ?: entry.remoteStatus?.progress ?: 0).toLong()
                trackingRepository.recordAudit(
                    profileId = DesktopDataStore.activeProfileId.toLong(),
                    trackerId = providerId,
                    showName = binding.remoteTitle,
                    remoteMediaId = binding.remoteMediaId,
                    episodeNum = (ep.episode ?: 0).toLong(),
                    effectiveRemoteEpisode = 0L,
                    oldProgress = oldProg,
                    newProgress = oldProg,
                    statusApplied = null,
                    state = SyncState.BLOCKED,
                    reason = check.reason,
                    timestamp = System.currentTimeMillis(),
                )
                updateEntry(providerId) {
                    copy(
                        syncingEpisodeNum = null,
                        releaseBounds = bounds,
                        feedbackMessage = check.reason,
                        feedbackIsError = true,
                    )
                }
                sendEffect(DetailsUiEffect.ShowToast(check.reason))
                return@launch
            }

            val effectiveEp = (check as EpisodeSyncCheck.Allowed).effectiveEpisode
            val oldProgress = freshStatus?.progress ?: entry.remoteStatus?.progress ?: 0
            val oldWatchStatus = freshStatus?.status ?: entry.remoteStatus?.status ?: UnifiedWatchStatus.NONE
            if (oldProgress >= effectiveEp) {
                updateEntry(providerId) {
                    copy(syncingEpisodeNum = null, remoteStatus = freshStatus ?: remoteStatus)
                }
                sendEffect(DetailsUiEffect.ShowToast("${trackerProvider.descriptor.displayName} already at Ep $oldProgress."))
                return@launch
            }

            val nextStatus = trackerProvider.nextStatusForManualEpisodeSync(bounds, oldWatchStatus, effectiveEp)
            val ok = trackerProvider.updateRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = binding.remoteMediaId,
                update = TrackerStatusUpdate(status = nextStatus, progress = effectiveEp, score100 = null),
                bounds = bounds,
            )
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showName = binding.remoteTitle,
                remoteMediaId = binding.remoteMediaId,
                episodeNum = (ep.episode ?: 0).toLong(),
                effectiveRemoteEpisode = effectiveEp.toLong(),
                oldProgress = oldProgress.toLong(),
                newProgress = if (ok) effectiveEp.toLong() else oldProgress.toLong(),
                statusApplied = nextStatus.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Manual episode sync" else "${trackerProvider.descriptor.displayName} rejected episode sync",
                timestamp = System.currentTimeMillis(),
            )
            updateEntry(providerId) {
                copy(
                    syncingEpisodeNum = null,
                    releaseBounds = bounds,
                    editProgress = if (ok) effectiveEp else editProgress,
                    editStatus = if (ok) nextStatus else editStatus,
                    feedbackMessage = if (ok) "Synced Ep $effectiveEp (${nextStatus.label})" else "Failed to sync Ep $effectiveEp.",
                    feedbackIsError = !ok,
                )
            }
            if (ok) {
                sendEffect(DetailsUiEffect.ShowToast("Synced Ep $effectiveEp to ${trackerProvider.descriptor.displayName} (${nextStatus.label})"))
                refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
            } else {
                sendEffect(DetailsUiEffect.ShowToast("${trackerProvider.descriptor.displayName} sync failed for Ep $effectiveEp"))
            }
        }
    }

    fun syncReleasedEpisodes(providerId: String, episodes: List<Episode>) {
        val entry = getState().trackers.entries[providerId] ?: return
        val trackerProvider = registry.get(providerId) ?: return
        val binding = entry.boundEntry ?: return

        scope.launch(Dispatchers.IO) {
            updateEntry(providerId) { copy(isSaving = true, feedbackMessage = null) }
            val freshStatus = trackerProvider.getRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = binding.remoteMediaId,
                fallbackTotalEpisodes = binding.expectedTotalEpisodes?.toInt(),
            )
            val bounds = trackerProvider.evaluateBounds(
                status = freshStatus ?: entry.remoteStatus,
                fallbackTotalEpisodes = binding.expectedTotalEpisodes?.toInt(),
            )

            val releasedValid = episodes.mapNotNull { ep ->
                val localStatus = parseEpisodeReleaseStatus(ep, provider.name)
                val check = trackerProvider.canSyncEpisode(
                    bounds = bounds,
                    rawEpisodeNumber = ep.episode,
                    episodeOffset = entry.editOffset,
                    localReleaseBlocked = localStatus.isUnreleased || localStatus.isMissingFromProvider,
                )
                (check as? EpisodeSyncCheck.Allowed)?.effectiveEpisode?.let { effEp -> ep to effEp }
            }

            val maxEpEntry = releasedValid.maxByOrNull { it.second }
            if (maxEpEntry == null) {
                val msg = "No released episodes available to sync yet."
                updateEntry(providerId) { copy(isSaving = false, releaseBounds = bounds, feedbackMessage = msg, feedbackIsError = true) }
                sendEffect(DetailsUiEffect.ShowToast(msg))
                return@launch
            }

            val (highestEp, targetEffEp) = maxEpEntry
            val oldProgress = freshStatus?.progress ?: entry.remoteStatus?.progress ?: 0
            if (oldProgress >= targetEffEp) {
                val msg = "All released episodes up to Ep $oldProgress are already synced."
                updateEntry(providerId) { copy(isSaving = false, releaseBounds = bounds, feedbackMessage = msg, feedbackIsError = false) }
                sendEffect(DetailsUiEffect.ShowToast(msg))
                return@launch
            }

            val oldWatchStatus = freshStatus?.status ?: entry.remoteStatus?.status ?: UnifiedWatchStatus.NONE
            val nextStatus = trackerProvider.nextStatusForManualEpisodeSync(bounds, oldWatchStatus, targetEffEp)
            val ok = trackerProvider.updateRemoteStatus(
                repository = trackingRepository,
                remoteMediaId = binding.remoteMediaId,
                update = TrackerStatusUpdate(status = nextStatus, progress = targetEffEp, score100 = null),
                bounds = bounds,
            )
            trackingRepository.recordAudit(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showName = binding.remoteTitle,
                remoteMediaId = binding.remoteMediaId,
                episodeNum = (highestEp.episode ?: targetEffEp).toLong(),
                effectiveRemoteEpisode = targetEffEp.toLong(),
                oldProgress = oldProgress.toLong(),
                newProgress = if (ok) targetEffEp.toLong() else oldProgress.toLong(),
                statusApplied = nextStatus.name,
                state = if (ok) SyncState.SENT else SyncState.FAILED,
                reason = if (ok) "Manual batch sync of released episodes" else "Rejected batch sync",
                timestamp = System.currentTimeMillis(),
            )
            val skippedCount = (episodes.size - releasedValid.size).coerceAtLeast(0)
            val successMsg = buildString {
                append("Synced up to released Ep $targetEffEp (${nextStatus.label})")
                if (skippedCount > 0) append(" · skipped $skippedCount unreleased")
            }
            updateEntry(providerId) {
                copy(
                    isSaving = false,
                    releaseBounds = bounds,
                    feedbackMessage = if (ok) successMsg else "Batch sync failed.",
                    feedbackIsError = !ok,
                )
            }
            if (ok) {
                sendEffect(DetailsUiEffect.ShowToast(successMsg))
                refreshAll(forceRemoteFetch = true, targetProviderId = providerId)
            }
        }
    }

    fun blockShow(providerId: String) {
        scope.launch(Dispatchers.IO) {
            val state = getState()
            val entry = state.trackers.entries[providerId] ?: return@launch
            val showKey = entry.showKey ?: buildTrackerShowKey(provider.name, state.response?.url ?: fallbackUrl)
            val season = entry.mappedSeason.coerceAtLeast(1)
            val title = state.response?.name?.takeIf { it.isNotBlank() } ?: preloadedName.orEmpty()
            val blocked = TrackerBinding(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = providerId,
                showKey = showKey,
                mappedSeason = season.toLong(),
                remoteMediaId = entry.boundEntry?.remoteMediaId ?: "0",
                remoteTitle = entry.boundEntry?.remoteTitle ?: title,
                bindMode = BindMode.BLOCKED,
                episodeOffset = 0L,
                expectedTotalEpisodes = null,
                updatedAt = System.currentTimeMillis(),
            )
            trackingRepository.saveBinding(blocked)
            updateEntry(providerId) {
                copy(
                    boundEntry = null,
                    resolution = MatchResolution.BlockedByUser,
                    remoteStatus = null,
                    feedbackMessage = "Tracking disabled for Season $season.",
                    feedbackIsError = false,
                )
            }
        }
    }

    fun unbindSeason(providerId: String) {
        scope.launch(Dispatchers.IO) {
            val state = getState()
            val entry = state.trackers.entries[providerId] ?: return@launch
            val showKey = entry.showKey ?: buildTrackerShowKey(provider.name, state.response?.url ?: fallbackUrl)
            val season = entry.mappedSeason.coerceAtLeast(1)
            trackingRepository.deleteBinding(showKey, season, providerId)
            updateEntry(providerId) {
                copy(
                    boundEntry = null,
                    resolution = null,
                    remoteStatus = null,
                    editOffset = 0,
                    feedbackMessage = "Unlinked Season $season.",
                    feedbackIsError = false,
                )
            }
            refreshAll(forceRemoteFetch = false, targetProviderId = providerId)
        }
    }

    fun updateSearchQuery(providerId: String, query: String) {
        updateEntry(providerId) { copy(searchQuery = query, hasSearched = false) }
    }

    fun updateOffset(providerId: String, offset: Int) {
        updateEntry(providerId) { copy(editOffset = offset) }
    }

    fun updateRemoteStatus(providerId: String, status: UnifiedWatchStatus) {
        updateEntry(providerId) {
            val ceiling = releaseBounds.ceilingEpisode
            val clampedProgress = if (ceiling != null && editProgress > ceiling) ceiling else editProgress
            copy(editStatus = status, editProgress = clampedProgress, feedbackMessage = null)
        }
    }

    fun updateRemoteProgress(providerId: String, progress: Int) {
        updateEntry(providerId) {
            val ceiling = releaseBounds.ceilingEpisode
            val clamped = progress.coerceAtLeast(0).let { p -> if (ceiling != null) minOf(p, ceiling) else p }
            copy(editProgress = clamped, feedbackMessage = null)
        }
    }

    fun updateScore(providerId: String, score100: Int) {
        updateEntry(providerId) { copy(editScore = score100.coerceIn(0, 100), feedbackMessage = null) }
    }

    fun setAutoSync(providerId: String, enabled: Boolean) {
        updateEntry(providerId) { copy(isAutoSyncEnabled = enabled) }
        scope.launch(Dispatchers.IO) {
            trackingRepository.setAutoSyncEnabled(enabled, providerId)
        }
    }

    fun setBindPolicy(providerId: String, policy: com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy) {
        updateEntry(providerId) { copy(bindPolicy = policy) }
        scope.launch(Dispatchers.IO) {
            trackingRepository.setBindPolicy(policy, providerId)
        }
    }

    fun dismissDrawer() {
        updateState {
            val cleanedEntries = trackers.entries.mapValues { (_, entry) ->
                entry.copy(showSearchDrawer = false, feedbackMessage = null)
            }
            copy(trackers = trackers.copy(entries = cleanedEntries, isDrawerOpen = false))
        }
    }

    fun isEpisodeReleasedForAllTrackers(ep: Episode, hasCachedLinks: Boolean): Boolean {
        val ceiling = getState().trackers.strictLocalReleaseCeiling ?: return true
        val epNum = ep.episode ?: return true
        return epNum <= 0 || epNum <= ceiling || hasCachedLinks
    }

    private fun entriesWithSearchPrepared(
        entries: Map<String, DetailsTrackerEntryState>,
        targetId: String,
        fallbackTitle: String,
    ): Map<String, DetailsTrackerEntryState> {
        val target = entries[targetId] ?: return entries
        return entries + (
            targetId to target.copy(
                showSearchDrawer = target.boundEntry == null,
                feedbackMessage = null,
                searchQuery = target.searchQuery.ifBlank { fallbackTitle },
            )
        )
    }

    fun dispose() {
        refreshJob?.cancel()
        searchJobs.values.forEach { it.cancel() }
        searchJobs.clear()
    }
}
