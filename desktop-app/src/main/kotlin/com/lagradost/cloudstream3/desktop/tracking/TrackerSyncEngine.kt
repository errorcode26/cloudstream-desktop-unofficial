package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.utils.appScope
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListTitleHolder
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonResponse
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class SyncCompletionRequest(
    val profileId: Long = DesktopDataStore.activeProfileId.toLong(),
    val trackerId: String = TrackerIds.ANILIST,
    val apiName: String,
    val showUrl: String,
    val showTitle: String,
    val mappedSeason: Int = 1,
    val episodeNum: Int? = null,
    val year: Int? = null,
    val tvType: TvType = TvType.Anime,
    val completionVerified: Boolean,
    val canonicalSeed: CanonicalMediaSeed? = null,
    val resolvedAnilistId: Int? = null,
    val resolvedMalId: Int? = null,
    val resolvedTmdbId: Int? = null,
    val resolvedImdbId: String? = null,
    val hasMultipleSeasons: Boolean = false,
)

data class PendingTrackerSync(
    val auditRowId: Long,
    val showKey: String,
    val remoteMediaId: Int,
    val remoteTitle: String,
    val season: Int,
    val episode: Int,
    val oldProgress: Int,
    val totalEpisodes: Int?,
    val targetStatus: AniListStatusType,
    val secondsRemaining: Int,
)

sealed interface SyncExecutionResult {
    data class Synced(val auditRowId: Long, val remoteMediaId: Int, val newProgress: Int, val status: AniListStatusType) : SyncExecutionResult
    data class AlreadySynced(val remoteProgress: Int) : SyncExecutionResult
    data class Blocked(val auditRowId: Long, val reason: String) : SyncExecutionResult
    data class NeedsConfirmation(val reason: String, val candidates: List<AniListCandidate>) : SyncExecutionResult
    data class Cancelled(val auditRowId: Long) : SyncExecutionResult
    data class Failed(val auditRowId: Long, val reason: String) : SyncExecutionResult
    data class Skipped(val reason: String) : SyncExecutionResult
}

interface TrackerRemoteGateway {
    suspend fun getSeason(id: Int): SeasonResponse?
    suspend fun getSeasonByMalId(malId: Int): SeasonResponse?
    suspend fun searchShows(query: String): List<GetSearchMedia>
    suspend fun getDataAboutId(auth: AuthData, id: Int): AniListTitleHolder?
    suspend fun postDataAboutId(
        auth: AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?,
    ): Boolean
}

class DefaultAniListRemoteGateway(
    private val apiProvider: () -> AniListApi = { AccountManager.aniListApi },
) : TrackerRemoteGateway {
    override suspend fun getSeason(id: Int): SeasonResponse? = AniListApi.getSeasonOrNull(id)
    override suspend fun getSeasonByMalId(malId: Int): SeasonResponse? = AniListApi.getSeasonByMalId(malId)
    override suspend fun searchShows(query: String): List<GetSearchMedia> =
        AniListApi.searchShows(query)?.data?.page?.media.orEmpty()

    override suspend fun getDataAboutId(auth: AuthData, id: Int): AniListTitleHolder? =
        apiProvider().getDataAboutId(auth, id, allowCache = false)

    override suspend fun postDataAboutId(
        auth: AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?,
    ): Boolean = apiProvider().postDataAboutId(auth, id, type, score, progress)
}

/**
 * Coordinates safe post-playback synchronization to AniList with hard bounds checking,
 * monotonic progress guarantees, a cancellable grace window, and full SQLite audit logging.
 */
class TrackerSyncEngine(
    private val repository: TrackingRepository,
    private val remoteGateway: TrackerRemoteGateway = DefaultAniListRemoteGateway(),
    private val registry: TrackerRegistry = TrackerRegistry,
    private val scope: CoroutineScope = appScope,
    private val graceDelayMs: Long = DEFAULT_GRACE_DELAY_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onGlobalToast: (text: String, isWarning: Boolean, actionLabel: String?, onAction: (() -> Unit)?) -> Unit = { text, isWarning, actionLabel, onAction ->
        if (isWarning) {
            AppToastManager.showWarning(text)
        } else {
            AppToastManager.showInfoWithAction(text, actionLabel = actionLabel, onAction = onAction)
        }
    },
) {
    companion object {
        private const val TAG = "TrackerSyncEngine"
        const val DEFAULT_GRACE_DELAY_MS = 8_000L
    }

    private val _pendingSync = MutableStateFlow<PendingTrackerSync?>(null)
    val pendingSync: StateFlow<PendingTrackerSync?> = _pendingSync.asStateFlow()

    private val _playerNotices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val playerNotices: SharedFlow<String> = _playerNotices.asSharedFlow()

    private val dedupMutex = Mutex()
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeGraceJobs = ConcurrentHashMap<Long, Job>()
    private val promptedUnconfirmedShows = ConcurrentHashMap.newKeySet<String>()

    fun enqueueEpisodeCompleted(request: SyncCompletionRequest): Job {
        return scope.launch(Dispatchers.IO) {
            onEpisodeCompleted(request)
        }
    }

    /** Dispatches completion checks across every connected, media-eligible tracker concurrently. */
    fun enqueueCompletionForConnectedTrackers(baseRequest: SyncCompletionRequest): Job {
        return scope.launch(Dispatchers.IO) {
            val showKey = buildTrackerShowKey(baseRequest.apiName, baseRequest.showUrl)
            val hasAnimeSeed = baseRequest.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
                baseRequest.canonicalSeed?.anilistId != null ||
                baseRequest.canonicalSeed?.malId != null ||
                baseRequest.resolvedAnilistId != null ||
                baseRequest.resolvedMalId != null
            val mappedSeason = baseRequest.mappedSeason.coerceAtLeast(1)

            val providers = registry.allConnected(repository)
            for (provider in providers) {
                val existing = repository.getBinding(showKey, mappedSeason, provider.id)
                val eligible = provider.isEligibleForMedia(
                    tvType = baseRequest.tvType,
                    hasAnimeSeed = hasAnimeSeed,
                    hasExistingBinding = existing != null,
                )
                if (eligible) {
                    launch {
                        onEpisodeCompleted(baseRequest.copy(trackerId = provider.id))
                    }
                }
            }
        }
    }

    fun cancelPendingSync(auditRowId: Long? = null, showKey: String? = null) {
        val current = _pendingSync.value
        val targetId = auditRowId ?: current?.takeIf { showKey == null || it.showKey == showKey }?.auditRowId ?: return
        val job = activeGraceJobs.remove(targetId)
        if (job != null) {
            job.cancel(CancellationException("Cancelled by user"))
        } else if (current?.auditRowId == targetId) {
            _pendingSync.value = null
        }
    }

    suspend fun onEpisodeCompleted(request: SyncCompletionRequest): SyncExecutionResult {
        if (request.showTitle.isBlank()) {
            return SyncExecutionResult.Skipped("Blank show title")
        }
        val trackerId = request.trackerId
        val provider = if (trackerId == TrackerIds.ANILIST) null else {
            registry.get(trackerId) ?: return SyncExecutionResult.Skipped("Unsupported tracker $trackerId")
        }
        val trackerLabel = provider?.descriptor?.displayName ?: "AniList"
        val showKey = buildTrackerShowKey(request.apiName, request.showUrl)

        if (request.apiName in setOf("Local", "Network")) {
            val pinned = repository.getBinding(showKey, request.mappedSeason.coerceAtLeast(1), trackerId)
            if (pinned?.bindMode != BindMode.USER_PINNED) {
                return SyncExecutionResult.Skipped("Unbound ${request.apiName} source")
            }
        }

        if (!repository.isAutoSyncEnabled(trackerId)) {
            return SyncExecutionResult.Skipped("$trackerLabel auto-sync disabled")
        }

        val auth = repository.getAccountsForProvider(trackerId)?.firstOrNull()
        if (auth == null || auth.token.accessToken.isNullOrBlank()) {
            return SyncExecutionResult.Skipped("$trackerLabel not connected")
        }

        val seasonNum = request.mappedSeason.coerceAtLeast(1)
        val matchRequest = MatchRequest(
            profileId = request.profileId,
            showKey = showKey,
            showTitle = request.showTitle,
            mappedSeason = seasonNum,
            year = request.year,
            tvType = request.tvType,
            canonicalSeed = request.canonicalSeed,
            resolvedAnilistId = request.resolvedAnilistId,
            resolvedMalId = request.resolvedMalId,
            resolvedTmdbId = request.resolvedTmdbId,
            resolvedImdbId = request.resolvedImdbId,
            bindPolicy = repository.getBindPolicy(trackerId),
            hasMultipleSeasons = request.hasMultipleSeasons,
        )
        val match = if (provider != null) {
            provider.resolveMatch(matchRequest, repository)
        } else {
            AniListMatchResolver.resolveMatch(
                request = matchRequest,
                repository = repository,
                fetchSeason = { remoteGateway.getSeason(it) },
                fetchByMalId = { remoteGateway.getSeasonByMalId(it) },
                searchRemote = { remoteGateway.searchShows(it) },
            )
        }

        val binding = when (match) {
            is MatchResolution.Skipped -> return SyncExecutionResult.Skipped(match.reason)
            is MatchResolution.BlockedByUser -> return SyncExecutionResult.Skipped("Blocked by user")
            is MatchResolution.NeedsConfirmation -> {
                if (request.completionVerified && promptedUnconfirmedShows.add("$trackerId:$showKey")) {
                    notifyAll(
                        text = "$trackerLabel: Confirm match for '${request.showTitle}' in Details to track progress",
                        isWarning = false,
                    )
                }
                return SyncExecutionResult.NeedsConfirmation(match.reason, match.candidates)
            }
            is MatchResolution.Bound -> match.binding
        }

        val parsedNumericId = binding.remoteMediaId.substringAfterLast(':').toIntOrNull()
        val remoteId = parsedNumericId ?: 0
        val isInvalidRemoteId = if (provider == null) {
            parsedNumericId == null || parsedNumericId <= 0
        } else {
            binding.remoteMediaId.isBlank()
        }
        if (isInvalidRemoteId) {
            val rowId = repository.recordAudit(
                profileId = request.profileId,
                trackerId = trackerId,
                showName = binding.remoteTitle.ifBlank { request.showTitle },
                remoteMediaId = binding.remoteMediaId,
                episodeNum = (request.episodeNum ?: 0).toLong(),
                effectiveRemoteEpisode = 0L,
                oldProgress = 0L,
                newProgress = 0L,
                statusApplied = null,
                state = SyncState.BLOCKED,
                reason = "Invalid bound $trackerLabel ID '${binding.remoteMediaId}'",
                timestamp = nowMs(),
            )
            return SyncExecutionResult.Blocked(rowId, "Invalid bound $trackerLabel ID")
        }

        val isMovieType = request.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        val rawEp = if (isMovieType && (request.episodeNum == null || request.episodeNum <= 0)) {
            1
        } else {
            request.episodeNum ?: 0
        }
        val effectiveEp = rawEp + binding.episodeOffset.toInt()
        val displayTitle = binding.remoteTitle.ifBlank { request.showTitle }

        if (!request.completionVerified) {
            val rowId = repository.recordAudit(
                profileId = request.profileId,
                trackerId = trackerId,
                showName = displayTitle,
                remoteMediaId = binding.remoteMediaId,
                episodeNum = rawEp.toLong(),
                effectiveRemoteEpisode = effectiveEp.toLong(),
                oldProgress = 0L,
                newProgress = 0L,
                statusApplied = null,
                state = SyncState.BLOCKED,
                reason = "Playback completion guard failed (insufficient coverage or error exit)",
                timestamp = nowMs(),
            )
            return SyncExecutionResult.Blocked(rowId, "Playback completion guard failed")
        }

        if (rawEp <= 0 || effectiveEp <= 0) {
            val rowId = repository.recordAudit(
                profileId = request.profileId,
                trackerId = trackerId,
                showName = displayTitle,
                remoteMediaId = binding.remoteMediaId,
                episodeNum = rawEp.toLong(),
                effectiveRemoteEpisode = effectiveEp.toLong(),
                oldProgress = 0L,
                newProgress = 0L,
                statusApplied = null,
                state = SyncState.BLOCKED,
                reason = "Invalid episode number ($rawEp, offset=${binding.episodeOffset} -> $effectiveEp)",
                timestamp = nowMs(),
            )
            return SyncExecutionResult.Blocked(rowId, "Invalid episode number")
        }

        val dedupKey = "${request.profileId}:$trackerId:${binding.remoteMediaId}:$effectiveEp"
        val acquired = dedupMutex.withLock { inFlightKeys.add(dedupKey) }
        if (!acquired) {
            return SyncExecutionResult.Skipped("Sync already in progress for $dedupKey")
        }

        try {
            val remoteStatus = if (provider != null) {
                provider.getRemoteStatus(repository, binding.remoteMediaId, binding.expectedTotalEpisodes?.toInt())
            } else {
                null
            }
            val remoteEntry = if (provider == null) {
                remoteGateway.getDataAboutId(auth, remoteId)
            } else {
                null
            }
            val oldProgress = remoteStatus?.progress ?: remoteEntry?.progress ?: 0
            val oldStatus = remoteStatus?.status ?: (remoteEntry?.type ?: AniListStatusType.None).let {
                with(AniListTrackerProvider) { it.toUnifiedStatus() }
            }
            val bounds = if (provider != null) {
                provider.evaluateBounds(remoteStatus, binding.expectedTotalEpisodes?.toInt())
            } else {
                AniListReleaseGuard.evaluate(
                    holder = remoteEntry,
                    fallbackTotalEpisodes = binding.expectedTotalEpisodes?.toInt(),
                )
            }

            val epCheck = if (provider != null) {
                provider.canSyncEpisode(bounds, rawEp, binding.episodeOffset.toInt())
            } else {
                AniListReleaseGuard.canSyncEpisode(
                    bounds = bounds,
                    rawEpisodeNumber = rawEp,
                    episodeOffset = binding.episodeOffset.toInt(),
                )
            }
            if (epCheck is EpisodeSyncCheck.Blocked) {
                val rowId = repository.recordAudit(
                    profileId = request.profileId,
                    trackerId = trackerId,
                    showName = displayTitle,
                    remoteMediaId = binding.remoteMediaId,
                    episodeNum = rawEp.toLong(),
                    effectiveRemoteEpisode = effectiveEp.toLong(),
                    oldProgress = oldProgress.toLong(),
                    newProgress = oldProgress.toLong(),
                    statusApplied = null,
                    state = SyncState.BLOCKED,
                    reason = epCheck.reason,
                    timestamp = nowMs(),
                )
                notifyAll("$trackerLabel blocked '$displayTitle': ${epCheck.reason}", isWarning = true)
                return SyncExecutionResult.Blocked(rowId, epCheck.reason)
            }

            if (oldProgress >= effectiveEp) {
                AppLogger.d(TAG, "Skipping sync for '$displayTitle' (${binding.remoteMediaId}): remote progress $oldProgress >= $effectiveEp")
                return SyncExecutionResult.AlreadySynced(oldProgress)
            }

            val knownMaxEpisodes = bounds.totalEpisodes
            val unifiedTargetStatus = if (provider != null) {
                provider.nextStatusForManualEpisodeSync(bounds, oldStatus, effectiveEp)
            } else {
                val aniOld = with(AniListTrackerProvider) { oldStatus.toAniListStatusType() }
                val aniNext = AniListReleaseGuard.nextStatusForManualEpisodeSync(bounds, aniOld, effectiveEp)
                with(AniListTrackerProvider) { aniNext.toUnifiedStatus() }
            }
            val legacyAniStatus = with(AniListTrackerProvider) { unifiedTargetStatus.toAniListStatusType() }

            val auditRowId = repository.recordAudit(
                profileId = request.profileId,
                trackerId = trackerId,
                showName = displayTitle,
                remoteMediaId = binding.remoteMediaId,
                episodeNum = rawEp.toLong(),
                effectiveRemoteEpisode = effectiveEp.toLong(),
                oldProgress = oldProgress.toLong(),
                newProgress = effectiveEp.toLong(),
                statusApplied = legacyAniStatus.name,
                state = SyncState.PENDING,
                reason = if (repository.isConfirmBeforeSync(trackerId)) "Waiting confirmation grace window" else "Committing",
                timestamp = nowMs(),
            )

            if (repository.isConfirmBeforeSync(trackerId) && graceDelayMs > 0L) {
                val currentJob = kotlin.coroutines.coroutineContext[Job]
                if (currentJob != null) {
                    activeGraceJobs[auditRowId] = currentJob
                }
                val epDisplay = if (knownMaxEpisodes != null && knownMaxEpisodes > 0) "$effectiveEp/$knownMaxEpisodes" else "$effectiveEp"
                val totalSeconds = ((graceDelayMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                notifyAll(
                    text = "$trackerLabel: Syncing '$displayTitle' Ep $epDisplay in ${totalSeconds}s",
                    isWarning = false,
                    actionLabel = "Undo",
                    onAction = { cancelPendingSync(auditRowId) },
                )

                try {
                    val stepMs = 1000L
                    var remainingMs = graceDelayMs
                    while (remainingMs > 0L) {
                        _pendingSync.value = PendingTrackerSync(
                            auditRowId = auditRowId,
                            showKey = showKey,
                            remoteMediaId = remoteId,
                            remoteTitle = displayTitle,
                            season = seasonNum,
                            episode = effectiveEp,
                            oldProgress = oldProgress,
                            totalEpisodes = knownMaxEpisodes,
                            targetStatus = legacyAniStatus,
                            secondsRemaining = ((remainingMs + 999L) / 1000L).toInt(),
                        )
                        val chunk = minOf(stepMs, remainingMs)
                        delay(chunk)
                        remainingMs -= chunk
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        repository.updateAuditState(auditRowId, SyncState.CANCELLED, "Cancelled by user during grace window")
                    }
                    notifyAll("$trackerLabel sync cancelled for '$displayTitle' Ep $effectiveEp", isWarning = false)
                    return SyncExecutionResult.Cancelled(auditRowId)
                } finally {
                    activeGraceJobs.remove(auditRowId)
                    if (_pendingSync.value?.auditRowId == auditRowId) {
                        _pendingSync.value = null
                    }
                }
            }

            val ok = try {
                if (provider != null) {
                    provider.updateRemoteStatus(
                        repository = repository,
                        remoteMediaId = binding.remoteMediaId,
                        update = TrackerStatusUpdate(
                            status = unifiedTargetStatus,
                            progress = effectiveEp,
                            score100 = null,
                        ),
                        bounds = bounds,
                    )
                } else {
                    remoteGateway.postDataAboutId(
                        auth = auth,
                        id = remoteId,
                        type = legacyAniStatus,
                        score = null,
                        progress = effectiveEp,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.updateAuditState(auditRowId, SyncState.FAILED, e.message ?: "Network exception")
                notifyAll("$trackerLabel sync failed for '$displayTitle' Ep $effectiveEp", isWarning = true)
                return SyncExecutionResult.Failed(auditRowId, e.message ?: "Network exception")
            }

            return if (ok) {
                repository.updateAuditState(
                    rowId = auditRowId,
                    state = SyncState.SENT,
                    reason = "Synced Ep $effectiveEp (${legacyAniStatus.name})",
                )
                notifyAll("Synced '$displayTitle' Ep $effectiveEp to $trackerLabel (${legacyAniStatus.name})", isWarning = false)
                SyncExecutionResult.Synced(auditRowId, remoteId, effectiveEp, legacyAniStatus)
            } else {
                repository.updateAuditState(
                    rowId = auditRowId,
                    state = SyncState.FAILED,
                    reason = "$trackerLabel API rejected mutation",
                )
                notifyAll("$trackerLabel sync failed for '$displayTitle' Ep $effectiveEp", isWarning = true)
                SyncExecutionResult.Failed(auditRowId, "Remote API rejected update")
            }
        } finally {
            dedupMutex.withLock { inFlightKeys.remove(dedupKey) }
        }
    }

    private fun notifyAll(
        text: String,
        isWarning: Boolean,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        _playerNotices.tryEmit(text)
        onGlobalToast(text, isWarning, actionLabel, onAction)
    }
}
