package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.MalAnimeNode
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import kotlin.coroutines.cancellation.CancellationException

class MalTrackerProvider(
    private val apiProvider: () -> MalApi = { AccountManager.malApi },
) : TrackerProvider {

    override val descriptor: TrackerDescriptor = TrackerDescriptor(
        id = TrackerIds.MAL,
        displayName = "MyAnimeList",
        shortTag = "MAL",
        accentColorHex = 0xFF2E51A2L,
        syncedColorHex = 0xFF60A5FAL,
        supportsFavorites = false,
        supportsEpisodeOffset = true,
        supportsPerEpisodeSync = true,
        supportsLibraryRemoval = true,
        supportsTvAndMovies = false,
    )

    override fun isEligibleForMedia(
        tvType: TvType,
        hasAnimeSeed: Boolean,
        hasExistingBinding: Boolean,
    ): Boolean {
        val isAnimeType = tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        return isAnimeType || hasAnimeSeed || hasExistingBinding
    }

    override fun webUrl(remoteMediaId: String): String =
        "https://myanimelist.net/anime/$remoteMediaId"

    override suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
    ): MatchResolution = MalMatchResolver.resolveMatch(request = request, repository = repository)

    override suspend fun searchCandidates(
        query: String,
        year: Int?,
        tvType: TvType,
    ): List<TrackerMediaCandidate> {
        val auth = AccountManager.cachedAccounts[id]?.firstOrNull()
        return MalMatchResolver.searchCandidates(
            query = query,
            year = year,
            tvType = tvType,
            auth = auth,
            malApi = apiProvider(),
        )
    }

    override suspend fun getRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        fallbackTotalEpisodes: Int?,
    ): UnifiedTrackerStatus? {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return null
        val malId = remoteMediaId.toIntOrNull() ?: return null
        val details = runCatching {
            apiProvider().getAnimeDetails(auth, malId) { updated ->
                persistUpdatedAuth(repository, updated)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null

        val bounds = evaluateMalBounds(details, fallbackTotalEpisodes)
        val status = details.myListStatus?.toUnifiedStatus() ?: UnifiedWatchStatus.NONE
        val score100 = (details.myListStatus?.score ?: 0).coerceIn(0, 10) * 10
        return UnifiedTrackerStatus(
            providerId = id,
            remoteMediaId = malId.toString(),
            title = details.englishTitle ?: details.title,
            status = status,
            progress = details.myListStatus?.numEpisodesWatched ?: 0,
            totalEpisodes = details.knownEpisodes ?: fallbackTotalEpisodes,
            score100 = score100,
            isFavourite = false,
            posterUrl = details.posterUrl,
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
        val malId = remoteMediaId.toIntOrNull() ?: return false
        return apiProvider().updateAnimeListStatus(
            auth = auth,
            malId = malId,
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
        val malId = remoteMediaId.toIntOrNull() ?: return false
        return apiProvider().deleteAnimeListStatus(auth, malId) { updated ->
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
            return Result.failure(IllegalStateException("MyAnimeList is not connected"))
        }
        val api = apiProvider()
        val entries = runCatching {
            api.getFullUserAnimeList(auth) { updated ->
                persistUpdatedAuth(repository, updated)
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrElse { err ->
                return Result.failure(
                    IllegalStateException(err.message ?: "Unable to load MyAnimeList library right now."),
                )
            }

        api.requireLibraryRefresh = false
        val seenIds = HashSet<String>()
        val unified = entries.mapNotNull { item ->
            val remoteId = item.id.toString()
            if (!seenIds.add(remoteId)) return@mapNotNull null
            val bounds = evaluateMalBounds(item, fallbackTotalEpisodes = null)
            val listStatus = item.myListStatus
            UnifiedLibraryEntry(
                providerId = id,
                remoteMediaId = remoteId,
                malId = item.id,
                title = item.englishTitle ?: item.title,
                format = item.normalizedFormat,
                mediaStatus = item.normalizedAirStatus,
                seasonYear = item.startSeasonYear,
                posterUrl = item.posterUrl,
                progress = listStatus?.numEpisodesWatched ?: 0,
                totalEpisodes = item.knownEpisodes,
                score100 = (listStatus?.score ?: 0).coerceIn(0, 10) * 10,
                averageScore = item.meanScore100,
                isFavourite = false,
                updatedAt = listStatus?.updatedAtEpochSec ?: 0L,
                status = listStatus?.toUnifiedStatus() ?: UnifiedWatchStatus.NONE,
                releaseBounds = bounds,
            )
        }
        return Result.success(unified)
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
        fun evaluateMalBounds(
            node: MalAnimeNode?,
            fallbackTotalEpisodes: Int? = null,
        ): ReleaseBounds = AniListReleaseGuard.evaluate(
            mediaStatus = node?.normalizedAirStatus,
            format = node?.normalizedFormat,
            totalEpisodes = node?.knownEpisodes ?: fallbackTotalEpisodes,
            nextAiring = null,
        )
    }
}
