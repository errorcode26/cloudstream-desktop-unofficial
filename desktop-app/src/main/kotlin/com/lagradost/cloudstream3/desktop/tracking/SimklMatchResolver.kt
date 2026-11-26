package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.desktop.metadata.providers.AniListMetadataProvider
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaItem
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaKind
import com.lagradost.common.logging.AppLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Resolves a local anime, movie, or TV series to a typed SIMKL media entry
 * (`anime:<id>`, `show:<id>`, `movie:<id>`) using verified external IDs
 * (`tmdb`, `imdb`, `mal`, `anilist`) and strict type-aware candidate scoring.
 */
object SimklMatchResolver {
    private const val TAG = "SimklMatchResolver"
    private const val MIN_STRICT_SIMILARITY = 0.90
    private const val MIN_AMBIGUITY_MARGIN = 0.08

    private val NON_TRACKABLE_TYPES = setOf(
        TvType.Live,
        TvType.Audio,
        TvType.AudioBook,
        TvType.Podcast,
    )

    suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
        lookupByIds: suspend (tmdbId: Int?, imdbId: String?, malId: Int?, anilistId: Int?) -> List<SimklMediaItem> = { tmdb, imdb, mal, ani ->
            AccountManager.simklApi.searchByExternalIds(tmdbId = tmdb, imdbId = imdb, malId = mal, anilistId = ani)
        },
        fetchDetails: suspend (SimklMediaItem) -> SimklMediaItem? = { hit ->
            AccountManager.simklApi.getMediaDetails(hit.ref)
        },
        searchRemote: suspend (String, List<SimklMediaKind>) -> List<SimklMediaItem> = { q, kinds ->
            val activeToken = repository.getAccountsForProvider(TrackerIds.SIMKL)?.firstOrNull()?.token?.accessToken
            AccountManager.simklApi.searchMedia(query = q, kinds = kinds, accessToken = activeToken)
        },
    ): MatchResolution {
        val seasonNum = request.mappedSeason.coerceAtLeast(1)

        val existing = repository.getBinding(request.showKey, seasonNum, TrackerIds.SIMKL)
        if (existing != null) {
            return when (existing.bindMode) {
                BindMode.BLOCKED -> MatchResolution.BlockedByUser
                BindMode.USER_PINNED -> MatchResolution.Bound(existing)
                BindMode.AUTO_VERIFIED -> {
                    if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
                        val cand = TrackerMediaCandidate(
                            providerId = TrackerIds.SIMKL,
                            remoteMediaId = existing.remoteMediaId,
                            title = existing.remoteTitle,
                            episodes = existing.expectedTotalEpisodes?.toInt(),
                        )
                        MatchResolution.NeedsConfirmation(
                            reason = "Manual-pin mode is active; confirm SIMKL binding before syncing.",
                            mediaCandidates = listOf(cand),
                        )
                    } else {
                        MatchResolution.Bound(existing)
                    }
                }
            }
        }

        if (request.tvType in NON_TRACKABLE_TYPES) {
            return MatchResolution.Skipped("Media type ${request.tvType} is not trackable")
        }

        if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
            val candidates = findStrictCandidates(request, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = "Manual control active — select a SIMKL entry to link.",
                mediaCandidates = candidates,
            )
        }

        val tmdbId = request.resolvedTmdbId ?: request.canonicalSeed?.tmdbId
        val imdbId = request.resolvedImdbId ?: request.canonicalSeed?.imdbId
        val malId = request.resolvedMalId ?: request.canonicalSeed?.malId
        val anilistId = request.resolvedAnilistId ?: request.canonicalSeed?.anilistId
        val hasAnyExternalId = tmdbId != null || !imdbId.isNullOrBlank() || malId != null || anilistId != null

        val hasVerifiedSeed = request.canonicalSeed?.origin in setOf(
            MediaSeedOrigin.EXPLORE,
            MediaSeedOrigin.DIRECT_SYNC_DATA,
        ) && (
            request.canonicalSeed?.anilistId != null ||
                request.canonicalSeed?.malId != null ||
                request.canonicalSeed?.tmdbId != null ||
                !request.canonicalSeed?.imdbId.isNullOrBlank()
            )
        val hasVerifiedExternalIds = !request.resolvedImdbId.isNullOrBlank() ||
            request.resolvedTmdbId != null ||
            request.resolvedMalId != null ||
            request.resolvedAnilistId != null
        val isStrictAuto = request.bindPolicy == TrackerBindPolicy.STRICT_AUTO_AND_PINNED

        if (!hasVerifiedSeed && !hasAnyExternalId && !isStrictAuto) {
            val candidates = findStrictCandidates(request, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = if (candidates.isNotEmpty()) {
                    "Match found on SIMKL — confirm before tracking."
                } else {
                    "No verified external ID on this entry; link it manually to track on SIMKL."
                },
                mediaCandidates = candidates,
            )
        }

        // Numeric TMDB IDs collide across Movies and TV Series, so always prioritize kind-compatible IDs first.
        var idFallbackCandidates: List<TrackerMediaCandidate> = emptyList()
        if (hasAnyExternalId) {
            val expectedKinds = expectedKindsForType(request.tvType)
            val hits = runCatching {
                when {
                    malId != null || anilistId != null -> lookupByIds(null, null, malId, anilistId)
                    !imdbId.isNullOrBlank() -> lookupByIds(null, imdbId, null, null)
                    else -> lookupByIds(tmdbId, null, null, null)
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())

            val secondaryHits = if (hits.isEmpty() && tmdbId != null && (malId != null || anilistId != null || !imdbId.isNullOrBlank())) {
                runCatching { lookupByIds(tmdbId, imdbId, null, null) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val allHits = (hits + secondaryHits).distinctBy { it.ref.encode() }
            val filtered = allHits.filter { it.ref.kind in expectedKinds }
            val rawShowTitle = request.canonicalSeed?.expectedTitle?.takeIf { it.isNotBlank() } ?: request.showTitle
            val (cleanTitle, parsedTitleYear) = TitleUtils.cleanProviderTitle(rawShowTitle)
            val canonicalYear = request.canonicalSeed?.expectedYear ?: request.year ?: parsedTitleYear
            idFallbackCandidates = filtered.map { item ->
                val titleScore = if (cleanTitle.isNotBlank()) scoreSimklTitle(cleanTitle, item, canonicalYear, request.tvType) else null
                TrackerMediaCandidate(
                    providerId = TrackerIds.SIMKL,
                    remoteMediaId = item.ref.encode(),
                    malId = item.externalIds?.mal,
                    title = item.title,
                    format = item.format,
                    seasonYear = item.year,
                    episodes = item.totalEpisodesCount,
                    status = item.normalizedAirStatus,
                    posterUrl = item.posterUrl,
                    matchScore = titleScore,
                )
            }
            val chosen = if (filtered.size == 1) {
                filtered.first()
            } else if (filtered.isNotEmpty() && seasonNum == 1) {
                filtered.firstOrNull { it.year == null || request.year == null || abs(it.year - request.year) <= 1 }
                    ?: filtered.firstOrNull()
            } else {
                null
            }

            // Do not auto-bind anime sequel seasons via a Season-1 external ID without explicit verification.
            val safeForMultiSeason = seasonNum == 1 || chosen?.ref?.kind == SimklMediaKind.SHOW
            val titleMatchesVerifiedId = chosen != null &&
                cleanTitle.isNotBlank() &&
                scoreSimklTitle(cleanTitle, chosen, canonicalYear, request.tvType) != null

            if (chosen != null && safeForMultiSeason && (hasVerifiedSeed || (hasVerifiedExternalIds && titleMatchesVerifiedId))) {
                val enriched = runCatching { fetchDetails(chosen) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: chosen
                val binding = TrackerBinding(
                    profileId = request.profileId,
                    trackerId = TrackerIds.SIMKL,
                    showKey = request.showKey,
                    mappedSeason = seasonNum.toLong(),
                    remoteMediaId = enriched.ref.encode(),
                    remoteTitle = enriched.title,
                    bindMode = BindMode.AUTO_VERIFIED,
                    episodeOffset = 0L,
                    expectedTotalEpisodes = enriched.totalEpisodesCount?.toLong(),
                    updatedAt = System.currentTimeMillis(),
                )
                repository.saveBinding(binding)
                return MatchResolution.Bound(binding)
            }
        }

        val candidates = (idFallbackCandidates + findStrictCandidates(request, searchRemote))
            .distinctBy { it.remoteMediaId }
            .sortedByDescending { it.matchScore ?: 0.0 }
        val top = candidates.firstOrNull()
        val second = candidates.getOrNull(1)
        val topScore = top?.matchScore ?: 0.0
        val secondScore = second?.matchScore ?: 0.0

        val allowStrictSeason = (seasonNum == 1 && !request.hasMultipleSeasons) ||
            (top?.remoteMediaId?.startsWith("show:") == true)
        if ((hasVerifiedSeed || isStrictAuto) &&
            top != null &&
            allowStrictSeason &&
            topScore >= MIN_STRICT_SIMILARITY &&
            (second == null || (topScore - secondScore) >= MIN_AMBIGUITY_MARGIN)
        ) {
            val binding = TrackerBinding(
                profileId = request.profileId,
                trackerId = TrackerIds.SIMKL,
                showKey = request.showKey,
                mappedSeason = seasonNum.toLong(),
                remoteMediaId = top.remoteMediaId,
                remoteTitle = top.title,
                bindMode = BindMode.AUTO_VERIFIED,
                episodeOffset = 0L,
                expectedTotalEpisodes = top.episodes?.toLong(),
                updatedAt = System.currentTimeMillis(),
            )
            repository.saveBinding(binding)
            return MatchResolution.Bound(binding)
        }

        AppLogger.d(TAG, "SIMKL confirmation needed for '${request.showTitle}' S$seasonNum")
        return MatchResolution.NeedsConfirmation(
            reason = if (candidates.isNotEmpty()) {
                "Match found on SIMKL — select the entry for '${request.showTitle}'."
            } else {
                "Select the exact SIMKL entry for '${request.showTitle}'."
            },
            mediaCandidates = candidates,
        )
    }

    suspend fun findStrictCandidates(
        request: MatchRequest,
        searchRemote: suspend (String, List<SimklMediaKind>) -> List<SimklMediaItem>,
    ): List<TrackerMediaCandidate> {
        val rawTitle = request.canonicalSeed?.expectedTitle?.takeIf { it.isNotBlank() } ?: request.showTitle
        val (cleanQuery, parsedYear) = TitleUtils.cleanProviderTitle(rawTitle)
        if (cleanQuery.isBlank()) return emptyList()

        val canonicalYear = request.canonicalSeed?.expectedYear ?: request.year ?: parsedYear
        val kinds = expectedKindsForType(request.tvType)
        val results = runCatching { searchRemote(cleanQuery, kinds) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())

        return results.mapNotNull { item ->
            val strict = scoreSimklTitle(cleanQuery, item, canonicalYear, request.tvType) ?: return@mapNotNull null
            TrackerMediaCandidate(
                providerId = TrackerIds.SIMKL,
                remoteMediaId = item.ref.encode(),
                malId = item.externalIds?.mal,
                title = item.title,
                format = item.format,
                seasonYear = item.year,
                episodes = item.totalEpisodesCount,
                status = item.normalizedAirStatus,
                posterUrl = item.posterUrl,
                matchScore = strict,
            )
        }.sortedByDescending { it.matchScore ?: 0.0 }
    }

    suspend fun searchCandidates(
        query: String,
        year: Int? = null,
        tvType: TvType = TvType.Anime,
        accessToken: String? = null,
        simklApi: SimklApi = AccountManager.simklApi,
    ): List<TrackerMediaCandidate> {
        val (cleanedQuery, parsedYear) = TitleUtils.cleanProviderTitle(query)
        val cleanQuery = cleanedQuery.ifBlank { query.trim() }
        if (cleanQuery.isBlank()) return emptyList()
        val effectiveYear = year ?: parsedYear
        val kinds = expectedKindsForType(tvType)
        val results = runCatching { simklApi.searchMedia(query = cleanQuery, kinds = kinds, accessToken = accessToken) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())

        return results.map { item ->
            val score = scoreSimklTitle(cleanQuery, item, effectiveYear, tvType)
            TrackerMediaCandidate(
                providerId = TrackerIds.SIMKL,
                remoteMediaId = item.ref.encode(),
                malId = item.externalIds?.mal,
                title = item.title,
                format = item.format,
                seasonYear = item.year,
                episodes = item.totalEpisodesCount,
                status = item.normalizedAirStatus,
                posterUrl = item.posterUrl,
                matchScore = score,
            )
        }.sortedByDescending { it.matchScore ?: 0.0 }
    }

    private fun scoreSimklTitle(
        cleanQuery: String,
        item: SimklMediaItem,
        canonicalYear: Int?,
        requestedType: TvType,
    ): Double? {
        val compatType = when {
            requestedType == TvType.Movie -> TvType.AnimeMovie
            item.ref.kind == SimklMediaKind.MOVIE -> TvType.AnimeMovie
            else -> requestedType
        }
        val base = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = cleanQuery,
            candidateTitles = listOf(item.title),
            canonicalYear = canonicalYear,
            candidateYear = item.year,
            candidateFormat = item.format,
            requestedType = compatType,
        ) ?: return null
        val bonus = when {
            canonicalYear == null || item.year == null -> 0.0
            item.year == canonicalYear -> 0.05
            abs(item.year - canonicalYear) <= 1 -> 0.02
            else -> -0.05
        }
        return (base + bonus).coerceIn(0.0, 1.0)
    }

    fun expectedKindsForType(tvType: TvType): List<SimklMediaKind> = when (tvType) {
        TvType.Anime, TvType.OVA -> listOf(SimklMediaKind.ANIME, SimklMediaKind.SHOW)
        TvType.AnimeMovie -> listOf(SimklMediaKind.MOVIE, SimklMediaKind.ANIME)
        TvType.Movie -> listOf(SimklMediaKind.MOVIE)
        else -> listOf(SimklMediaKind.SHOW, SimklMediaKind.ANIME)
    }
}
