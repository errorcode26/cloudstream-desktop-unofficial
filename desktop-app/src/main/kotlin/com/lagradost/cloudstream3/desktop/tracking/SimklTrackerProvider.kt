package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaKind
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaRef
import kotlin.coroutines.cancellation.CancellationException

class SimklTrackerProvider(
    private val apiProvider: () -> SimklApi = { AccountManager.simklApi },
) : TrackerProvider {

    override val descriptor: TrackerDescriptor = TrackerDescriptor(
        id = TrackerIds.SIMKL,
        displayName = "SIMKL",
        shortTag = "SK",
        accentColorHex = 0xFF00B7FFL,
        syncedColorHex = 0xFF22D3EEL,
        supportsFavorites = false,
        supportsEpisodeOffset = true,
        supportsPerEpisodeSync = true,
        supportsLibraryRemoval = true,
        supportsTvAndMovies = true,
    )

    override fun isEligibleForMedia(
        tvType: TvType,
        hasAnimeSeed: Boolean,
        hasExistingBinding: Boolean,
    ): Boolean {
        val nonTrackable = tvType in setOf(TvType.Live, TvType.Audio, TvType.AudioBook, TvType.Podcast)
        if (nonTrackable && !hasExistingBinding) return false
        return tvType in setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA,
            TvType.TvSeries,
            TvType.Movie,
            TvType.Cartoon,
            TvType.AsianDrama,
            TvType.Documentary,
        ) || hasAnimeSeed || hasExistingBinding
    }

    override fun webUrl(remoteMediaId: String): String {
        val ref = SimklMediaRef.parse(remoteMediaId)
            ?: return "https://simkl.com/anime/${remoteMediaId.trim()}"
        return "https://simkl.com/${ref.kind.webSegment}/${ref.simklId}"
    }

    override suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
    ): MatchResolution = SimklMatchResolver.resolveMatch(request = request, repository = repository)

    override suspend fun searchCandidates(
        query: String,
        year: Int?,
        tvType: TvType,
    ): List<TrackerMediaCandidate> = SimklMatchResolver.searchCandidates(
        query = query,
        year = year,
        tvType = tvType,
        simklApi = apiProvider(),
    )

    override suspend fun searchCandidates(
        repository: TrackingRepository,
        query: String,
        year: Int?,
        tvType: TvType,
    ): List<TrackerMediaCandidate> {
        val activeToken = repository.getAccountsForProvider(id)?.firstOrNull()?.token?.accessToken
        return SimklMatchResolver.searchCandidates(
            query = query,
            year = year,
            tvType = tvType,
            accessToken = activeToken,
            simklApi = apiProvider(),
        )
    }

    override suspend fun getRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        fallbackTotalEpisodes: Int?,
    ): UnifiedTrackerStatus? {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return null
        val ref = SimklMediaRef.parse(remoteMediaId) ?: return null
        val (item, slots) = runCatching {
            apiProvider().getRemoteEntryStatus(auth, ref) { updated ->
                persistUpdatedAuth(repository, updated)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null

        val bounds = item.computeReleaseBounds(slots)
        return UnifiedTrackerStatus(
            providerId = id,
            remoteMediaId = item.ref.encode(),
            title = item.title,
            status = item.watchStatus,
            progress = item.watchedEpisodesCount,
            totalEpisodes = item.totalEpisodesCount ?: fallbackTotalEpisodes,
            score100 = item.userScore100,
            isFavourite = false,
            posterUrl = item.posterUrl,
            releaseBounds = bounds,
        )
    }

    override suspend fun updateRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        update: TrackerStatusUpdate,
        bounds: ReleaseBounds,
    ): Boolean {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return false
        val ref = SimklMediaRef.parse(remoteMediaId) ?: return false
        return apiProvider().updateRemoteEntry(
            auth = auth,
            ref = ref,
            status = update.status,
            progress = update.progress,
            score100 = update.score100,
            clearScore = update.clearScore,
            persistAuth = { updated -> persistUpdatedAuth(repository, updated) },
        )
    }

    override suspend fun removeEntry(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return false
        val ref = SimklMediaRef.parse(remoteMediaId) ?: return false
        return apiProvider().removeRemoteEntry(auth, ref) { updated ->
            persistUpdatedAuth(repository, updated)
        }
    }

    override suspend fun toggleFavorite(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean? = null

    override suspend fun fetchLibrary(repository: TrackingRepository): Result<List<UnifiedLibraryEntry>> {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull()
        if (auth == null || auth.token.accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("SIMKL is not connected"))
        }
        val api = apiProvider()
        val items = runCatching {
            api.getFullLibrary(auth, forceRefresh = false) { updated ->
                persistUpdatedAuth(repository, updated)
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrElse { err ->
                return Result.failure(
                    IllegalStateException(err.message ?: "Unable to load SIMKL library right now."),
                )
            }

        val entries = items.map { item ->
            val bounds = item.computeReleaseBounds()
            UnifiedLibraryEntry(
                providerId = id,
                remoteMediaId = item.ref.encode(),
                malId = item.externalIds?.mal,
                title = item.title,
                format = item.format,
                mediaStatus = item.normalizedAirStatus,
                seasonYear = item.year,
                posterUrl = item.posterUrl,
                progress = item.watchedEpisodesCount,
                totalEpisodes = item.totalEpisodesCount,
                score100 = item.userScore100,
                averageScore = item.rating100,
                isFavourite = false,
                updatedAt = item.lastWatchedEpochSec,
                status = item.watchStatus,
                releaseBounds = bounds,
            )
        }
        return Result.success(entries)
    }

    override fun requiresLibraryRefresh(): Boolean = apiProvider().requireLibraryRefresh

    override fun validateStatusAndProgress(
        bounds: ReleaseBounds,
        status: UnifiedWatchStatus,
        progress: Int?,
    ): GuardDecision = with(AniListTrackerProvider) {
        AniListReleaseGuard.validateStatusAndProgress(
            bounds = bounds,
            requestedStatus = status.toAniListStatusType(),
            requestedProgress = progress,
        )
    }

    override fun canSyncEpisode(
        bounds: ReleaseBounds,
        rawEpisodeNumber: Int?,
        episodeOffset: Int,
        localReleaseBlocked: Boolean,
    ): EpisodeSyncCheck = AniListReleaseGuard.canSyncEpisode(
        bounds = bounds,
        rawEpisodeNumber = rawEpisodeNumber,
        episodeOffset = episodeOffset,
        localReleaseBlocked = localReleaseBlocked,
    )

    override fun nextStatusForManualEpisodeSync(
        bounds: ReleaseBounds,
        currentStatus: UnifiedWatchStatus,
        effectiveEpisode: Int,
    ): UnifiedWatchStatus = with(AniListTrackerProvider) {
        AniListReleaseGuard.nextStatusForManualEpisodeSync(
            bounds = bounds,
            currentStatus = currentStatus.toAniListStatusType(),
            effectiveEpisode = effectiveEpisode,
        ).toUnifiedStatus()
    }

    private fun persistUpdatedAuth(repository: TrackingRepository, updated: AuthData) {
        repository.updateAccounts(id, arrayOf(updated))
    }

    companion object {
        fun simklFallbackKind(tvType: TvType): SimklMediaKind = SimklMediaKind.defaultForTvType(tvType)
    }
}
