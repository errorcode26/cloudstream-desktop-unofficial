package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListTitleHolder
import com.lagradost.cloudstream3.syncproviders.providers.anilist.Entries
import java.util.Locale

class AniListTrackerProvider(
    private val apiProvider: () -> AniListApi = { AccountManager.aniListApi },
) : TrackerProvider {

    override val descriptor: TrackerDescriptor = TrackerDescriptor(
        id = TrackerIds.ANILIST,
        displayName = "AniList",
        shortTag = "AL",
        accentColorHex = 0xFF02A9FFL,
        syncedColorHex = 0xFF38BDF8L,
        supportsFavorites = true,
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

    override fun webUrl(remoteMediaId: String): String = "https://anilist.co/anime/$remoteMediaId"

    override suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
    ): MatchResolution = AniListMatchResolver.resolveMatch(request = request, repository = repository)

    override suspend fun searchCandidates(
        query: String,
        year: Int?,
        tvType: TvType,
    ): List<TrackerMediaCandidate> {
        return AniListMatchResolver.searchCandidates(query = query, year = year, tvType = tvType).map { it.toMediaCandidate() }
    }

    override suspend fun getRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        fallbackTotalEpisodes: Int?,
    ): UnifiedTrackerStatus? {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return null
        val idInt = remoteMediaId.toIntOrNull() ?: return null
        val holder = runCatching {
            apiProvider().getDataAboutId(auth, idInt, allowCache = false)
        }.getOrNull() ?: return null
        return holder.toUnifiedStatus(remoteMediaId, fallbackTotalEpisodes)
    }

    override suspend fun updateRemoteStatus(
        repository: TrackingRepository,
        remoteMediaId: String,
        update: TrackerStatusUpdate,
        bounds: ReleaseBounds,
    ): Boolean {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return false
        val idInt = remoteMediaId.toIntOrNull() ?: return false
        val scoreObj = update.score100?.takeIf { it > 0 }?.let { Score.from100(it) }
        return apiProvider().postDataAboutId(
            auth = auth,
            id = idInt,
            type = update.status.toAniListStatusType(),
            score = scoreObj,
            progress = update.progress,
            clearScore = update.clearScore,
        )
    }

    override suspend fun removeEntry(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return false
        val idInt = remoteMediaId.toIntOrNull() ?: return false
        return apiProvider().postDataAboutId(
            auth = auth,
            id = idInt,
            type = AniListStatusType.None,
            score = null,
            progress = null,
        )
    }

    override suspend fun toggleFavorite(
        repository: TrackingRepository,
        remoteMediaId: String,
    ): Boolean? {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull() ?: return null
        val idInt = remoteMediaId.toIntOrNull() ?: return null
        return runCatching { apiProvider().toggleLike(auth, idInt) }.getOrNull()
    }

    override suspend fun fetchLibrary(repository: TrackingRepository): Result<List<UnifiedLibraryEntry>> {
        val auth = repository.getAccountsForProvider(id)?.firstOrNull()
        if (auth == null || auth.token.accessToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("AniList is not connected"))
        }
        val api = apiProvider()
        val full = runCatching { api.getFullAniListList(auth) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Unable to load AniList library right now."))
        val lists = full.data?.mediaListCollection?.lists
            ?: return Result.failure(IllegalStateException("AniList library response was empty."))

        api.requireLibraryRefresh = false
        val seenIds = HashSet<String>()
        val entries = lists.flatMap { list ->
            val fallbackStatus = parseRawStatus(list.status)
            list.entries.mapNotNull { entry -> mapEntry(entry, fallbackStatus) }
        }.filter { seenIds.add(it.remoteMediaId) }

        return Result.success(entries)
    }

    override fun requiresLibraryRefresh(): Boolean = apiProvider().requireLibraryRefresh

    override fun validateStatusAndProgress(
        bounds: ReleaseBounds,
        status: UnifiedWatchStatus,
        progress: Int?,
    ): GuardDecision = AniListReleaseGuard.validateStatusAndProgress(
        bounds = bounds,
        requestedStatus = status.toAniListStatusType(),
        requestedProgress = progress,
    )

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
    ): UnifiedWatchStatus = AniListReleaseGuard.nextStatusForManualEpisodeSync(
        bounds = bounds,
        currentStatus = currentStatus.toAniListStatusType(),
        effectiveEpisode = effectiveEpisode,
    ).toUnifiedStatus()

    private fun mapEntry(entry: Entries, fallbackStatus: UnifiedWatchStatus): UnifiedLibraryEntry? {
        val media = entry.media ?: return null
        val displayTitle = media.title?.userPreferred
            ?: media.title?.english
            ?: media.title?.romaji
            ?: media.synonyms.orEmpty().firstOrNull()
            ?: return null
        val parsedEntryStatus = parseRawStatus(entry.status).takeIf { it != UnifiedWatchStatus.NONE } ?: fallbackStatus
        val bounds = AniListReleaseGuard.evaluate(
            mediaStatus = media.status,
            format = media.format,
            totalEpisodes = media.episodes,
            nextAiring = media.nextAiringEpisode,
        )
        return UnifiedLibraryEntry(
            providerId = id,
            remoteMediaId = media.id.toString(),
            malId = media.idMal,
            title = displayTitle,
            format = media.format,
            mediaStatus = media.status,
            seasonYear = media.seasonYear,
            posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium,
            progress = entry.progress ?: 0,
            totalEpisodes = media.episodes,
            score100 = entry.score ?: 0,
            averageScore = media.averageScore,
            isFavourite = media.isFavourite == true,
            updatedAt = entry.updatedAt?.toLong() ?: 0L,
            status = parsedEntryStatus,
            releaseBounds = bounds,
        )
    }

    private fun AniListTitleHolder.toUnifiedStatus(
        fallbackId: String,
        fallbackTotalEpisodes: Int?,
    ): UnifiedTrackerStatus {
        val bounds = AniListReleaseGuard.evaluate(holder = this, fallbackTotalEpisodes = fallbackTotalEpisodes)
        val displayTitle = title?.userPreferred ?: title?.english ?: title?.romaji ?: "AniList #$fallbackId"
        return UnifiedTrackerStatus(
            providerId = id?.let { TrackerIds.ANILIST } ?: TrackerIds.ANILIST,
            remoteMediaId = this.id?.toString() ?: fallbackId,
            title = displayTitle,
            status = (type ?: AniListStatusType.None).toUnifiedStatus(),
            progress = progress ?: 0,
            totalEpisodes = episodes ?: fallbackTotalEpisodes,
            score100 = score ?: 0,
            isFavourite = isFavourite == true,
            posterUrl = coverImageUrl,
            releaseBounds = bounds,
        )
    }

    private fun parseRawStatus(raw: String?): UnifiedWatchStatus {
        val idx = AniListApi.aniListStatusString.indexOf(raw?.uppercase(Locale.ROOT))
        return AniListApi.fromIntToAnimeStatus(idx).toUnifiedStatus()
    }

    companion object {
        fun AniListCandidate.toMediaCandidate(): TrackerMediaCandidate = TrackerMediaCandidate(
            providerId = TrackerIds.ANILIST,
            remoteMediaId = anilistId.toString(),
            malId = malId,
            title = title,
            format = format,
            seasonYear = seasonYear,
            episodes = episodes,
            status = status,
            posterUrl = posterUrl,
            matchScore = matchScore,
        )

        fun AniListStatusType.toUnifiedStatus(): UnifiedWatchStatus = when (this) {
            AniListStatusType.Watching -> UnifiedWatchStatus.WATCHING
            AniListStatusType.Completed -> UnifiedWatchStatus.COMPLETED
            AniListStatusType.Planning -> UnifiedWatchStatus.PLAN_TO_WATCH
            AniListStatusType.Paused -> UnifiedWatchStatus.PAUSED
            AniListStatusType.Dropped -> UnifiedWatchStatus.DROPPED
            AniListStatusType.ReWatching -> UnifiedWatchStatus.REWATCHING
            AniListStatusType.None -> UnifiedWatchStatus.NONE
        }

        fun UnifiedWatchStatus.toAniListStatusType(): AniListStatusType = when (this) {
            UnifiedWatchStatus.WATCHING -> AniListStatusType.Watching
            UnifiedWatchStatus.COMPLETED -> AniListStatusType.Completed
            UnifiedWatchStatus.PLAN_TO_WATCH -> AniListStatusType.Planning
            UnifiedWatchStatus.PAUSED -> AniListStatusType.Paused
            UnifiedWatchStatus.DROPPED -> AniListStatusType.Dropped
            UnifiedWatchStatus.REWATCHING -> AniListStatusType.ReWatching
            UnifiedWatchStatus.NONE -> AniListStatusType.None
        }
    }
}
