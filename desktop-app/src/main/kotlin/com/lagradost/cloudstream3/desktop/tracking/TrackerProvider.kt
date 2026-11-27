package com.lagradost.cloudstream3.desktop.tracking

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.ui.SyncWatchType

@Immutable
enum class UnifiedWatchStatus(val label: String) {
    WATCHING("Watching"),
    COMPLETED("Completed"),
    PLAN_TO_WATCH("Plan to Watch"),
    PAUSED("Paused"),
    DROPPED("Dropped"),
    REWATCHING("Rewatching"),
    NONE("None");

    companion object {
        val editableStatuses: List<UnifiedWatchStatus> = listOf(
            WATCHING,
            COMPLETED,
            PLAN_TO_WATCH,
            PAUSED,
            DROPPED,
            REWATCHING,
        )

        fun supportedStatusesFor(providerId: String, isMovie: Boolean): List<UnifiedWatchStatus> =
            editableStatuses.filter { status ->
                when {
                    providerId == TrackerIds.SIMKL && isMovie ->
                        status == PLAN_TO_WATCH || status == COMPLETED || status == DROPPED
                    providerId == TrackerIds.SIMKL -> status != REWATCHING
                    else -> true
                }
            }

        fun defaultUnaddedStatusFor(providerId: String, isMovie: Boolean): UnifiedWatchStatus =
            if (isMovie) PLAN_TO_WATCH else WATCHING
    }
}

fun adjustedProgressForStatus(
    currentProgress: Int,
    targetStatus: UnifiedWatchStatus,
    isMovie: Boolean,
    ceiling: Int?,
): Int {
    val base = when {
        targetStatus == UnifiedWatchStatus.PLAN_TO_WATCH -> 0
        isMovie && targetStatus == UnifiedWatchStatus.COMPLETED -> 1
        isMovie && currentProgress > 0 && targetStatus != UnifiedWatchStatus.COMPLETED -> 0
        else -> currentProgress.coerceAtLeast(0)
    }
    return if (ceiling != null && base > ceiling) ceiling else base
}

fun SyncWatchType.toUnifiedWatchStatus(): UnifiedWatchStatus = when (this) {
    SyncWatchType.WATCHING -> UnifiedWatchStatus.WATCHING
    SyncWatchType.COMPLETED -> UnifiedWatchStatus.COMPLETED
    SyncWatchType.ON_HOLD -> UnifiedWatchStatus.PAUSED
    SyncWatchType.DROPPED -> UnifiedWatchStatus.DROPPED
    SyncWatchType.PLAN_TO_WATCH -> UnifiedWatchStatus.PLAN_TO_WATCH
    SyncWatchType.NONE -> UnifiedWatchStatus.NONE
}

fun UnifiedWatchStatus.toSyncWatchType(): SyncWatchType = when (this) {
    UnifiedWatchStatus.WATCHING, UnifiedWatchStatus.REWATCHING -> SyncWatchType.WATCHING
    UnifiedWatchStatus.COMPLETED -> SyncWatchType.COMPLETED
    UnifiedWatchStatus.PAUSED -> SyncWatchType.ON_HOLD
    UnifiedWatchStatus.DROPPED -> SyncWatchType.DROPPED
    UnifiedWatchStatus.PLAN_TO_WATCH -> SyncWatchType.PLAN_TO_WATCH
    UnifiedWatchStatus.NONE -> SyncWatchType.NONE
}

@Immutable
data class TrackerDescriptor(
    val id: String,
    val displayName: String,
    val shortTag: String,
    val accentColorHex: Long,
    val syncedColorHex: Long,
    val supportsFavorites: Boolean = false,
    val supportsEpisodeOffset: Boolean = true,
    val supportsPerEpisodeSync: Boolean = true,
    val supportsLibraryRemoval: Boolean = true,
    val supportsTvAndMovies: Boolean = false,
)

@Immutable
data class TrackerMediaCandidate(
    val providerId: String,
    val remoteMediaId: String,
    val malId: Int? = null,
    val title: String,
    val format: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val posterUrl: String? = null,
    val matchScore: Double? = null,
)

@Immutable
data class UnifiedTrackerStatus(
    val providerId: String,
    val remoteMediaId: String,
    val title: String,
    val status: UnifiedWatchStatus,
    val progress: Int,
    val totalEpisodes: Int?,
    val score100: Int,
    val isFavourite: Boolean,
    val posterUrl: String?,
    val releaseBounds: ReleaseBounds,
)

@Immutable
data class TrackerStatusUpdate(
    val status: UnifiedWatchStatus,
    val progress: Int?,
    val score100: Int?,
    val clearScore: Boolean = false,
)

@Immutable
data class UnifiedLibraryEntry(
    val providerId: String,
    val remoteMediaId: String,
    val malId: Int?,
    val title: String,
    val format: String?,
    val mediaStatus: String?,
    val seasonYear: Int?,
    val posterUrl: String?,
    val progress: Int,
    val totalEpisodes: Int?,
    val score100: Int,
    val averageScore: Int?,
    val isFavourite: Boolean,
    val updatedAt: Long,
    val status: UnifiedWatchStatus,
    val releaseBounds: ReleaseBounds,
) {
    val isMovie: Boolean
        get() = format.equals("MOVIE", ignoreCase = true)

    val uniqueKey: String
        get() = "$providerId:$remoteMediaId"
}

@Immutable
data class TrackerLibraryPage(
    val entries: List<UnifiedLibraryEntry>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val statusCounts: Map<UnifiedWatchStatus, Int>? = null,
    val totalCount: Int? = null,
    val supportsServerTabFilter: Boolean = false,
)

/**
 * Provider-agnostic interface implemented by each remote tracking integration
 * (AniList today, MyAnimeList / SIMKL / Trakt incrementally).
 */
interface TrackerProvider {
    val descriptor: TrackerDescriptor
    val id: String
        get() = descriptor.id

    fun isConnected(accounts: Map<String, Array<AuthData>>): Boolean =
        accounts[id]?.firstOrNull()?.token?.accessToken?.isNotBlank() == true

    fun isConnected(repository: TrackingRepository): Boolean =
        repository.getAccountsForProvider(id)?.firstOrNull()?.token?.accessToken?.isNotBlank() == true

    fun getUser(repository: TrackingRepository): AuthUser? =
        repository.getAccountsForProvider(id)?.firstOrNull()?.user

    fun isEligibleForMedia(
        tvType: TvType,
        hasAnimeSeed: Boolean,
        hasExistingBinding: Boolean,
    ): Boolean

    fun webUrl(remoteMediaId: String): String

    suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
    ): MatchResolution

    suspend fun searchCandidates(
        query: String,
        year: Int? = null,
        tvType: TvType = TvType.Anime,
    ): List<TrackerMediaCandidate>

    suspend fun searchCandidates(
        repository: TrackingRepository,
        query: String,
        year: Int? = null,
        tvType: TvType = TvType.Anime,
    ): List<TrackerMediaCandidate> = searchCandidates(query = query, year = year, tvType = tvType)

    suspend fun getRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        fallbackTotalEpisodes: Int? = null,
    ): UnifiedTrackerStatus?

    suspend fun updateRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        update: TrackerStatusUpdate,
        bounds: ReleaseBounds,
    ): Boolean

    suspend fun removeEntry(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean

    suspend fun toggleFavorite(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean?

    suspend fun fetchLibrary(repository: TrackingRepository): Result<List<UnifiedLibraryEntry>>

    suspend fun fetchLibraryPage(
        repository: TrackingRepository,
        statusFilter: UnifiedWatchStatus? = null,
        sortKey: String? = null,
        cursor: String? = null,
        pageSize: Int = DEFAULT_LIBRARY_PAGE_SIZE,
    ): Result<TrackerLibraryPage> = fetchLibrary(repository).map { allEntries ->
        val counts = HashMap<UnifiedWatchStatus, Int>()
        for (entry in allEntries) {
            counts[entry.status] = (counts[entry.status] ?: 0) + 1
        }
        val filtered = if (statusFilter == null) allEntries else allEntries.filter { it.status == statusFilter }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = pageSize.coerceAtLeast(1)
        val slice = if (offset >= filtered.size) emptyList() else filtered.subList(offset, minOf(offset + limit, filtered.size))
        val nextOffset = offset + slice.size
        TrackerLibraryPage(
            entries = slice,
            nextCursor = nextOffset.takeIf { it < filtered.size }?.toString(),
            hasMore = nextOffset < filtered.size,
            statusCounts = counts,
            totalCount = allEntries.size,
            supportsServerTabFilter = false,
        )
    }

    fun requiresLibraryRefresh(): Boolean = false

    companion object {
        const val DEFAULT_LIBRARY_PAGE_SIZE = 50
    }

    fun validateStatusAndProgress(
        bounds: ReleaseBounds,
        status: UnifiedWatchStatus,
        progress: Int?,
    ): GuardDecision

    fun canSyncEpisode(
        bounds: ReleaseBounds,
        rawEpisodeNumber: Int?,
        episodeOffset: Int = 0,
        localReleaseBlocked: Boolean = false,
    ): EpisodeSyncCheck

    fun nextStatusForManualEpisodeSync(
        bounds: ReleaseBounds,
        currentStatus: UnifiedWatchStatus,
        effectiveEpisode: Int,
    ): UnifiedWatchStatus

    fun evaluateBounds(
        status: UnifiedTrackerStatus?,
        fallbackTotalEpisodes: Int? = null,
    ): ReleaseBounds = status?.releaseBounds ?: ReleaseBounds(totalEpisodes = fallbackTotalEpisodes)
}
