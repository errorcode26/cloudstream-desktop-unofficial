package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.desktop.metadata.providers.AniListMetadataProvider
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MalAnimeNode
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import com.lagradost.common.logging.AppLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Resolves a local show + season to a verified MyAnimeList anime entry using
 * persisted user pins, canonical ID seeds (`malId` or `anilistId -> idMal`),
 * sequel graph hops, and strict title confirmation.
 */
object MalMatchResolver {
    private const val TAG = "MalMatchResolver"
    private const val MIN_STRICT_SIMILARITY = 0.90
    private const val MIN_AMBIGUITY_MARGIN = 0.08
    private const val MAX_GRAPH_HOPS = 12
    private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")

    suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
        fetchDetails: suspend (AuthData?, Int) -> MalAnimeNode? = { auth, id ->
            AccountManager.malApi.getAnimeDetails(auth, id) { updated ->
                repository.updateAccounts(TrackerIds.MAL, arrayOf(updated))
            }
        },
        fetchMalIdFromAniList: suspend (Int) -> Int? = { anilistId ->
            AniListApi.getSeasonOrNull(anilistId)?.data?.media?.idMal
        },
        searchRemote: suspend (AuthData?, String) -> List<MalAnimeNode> = { auth, q ->
            AccountManager.malApi.searchAnime(auth, q) { updated ->
                repository.updateAccounts(TrackerIds.MAL, arrayOf(updated))
            }
        },
    ): MatchResolution {
        val seasonNum = request.mappedSeason.coerceAtLeast(1)
        val auth = repository.getAccountsForProvider(TrackerIds.MAL)?.firstOrNull()

        val existing = repository.getBinding(request.showKey, seasonNum, TrackerIds.MAL)
        if (existing != null) {
            return when (existing.bindMode) {
                BindMode.BLOCKED -> MatchResolution.BlockedByUser
                BindMode.USER_PINNED -> MatchResolution.Bound(existing)
                BindMode.AUTO_VERIFIED -> {
                    if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
                        val candidate = TrackerMediaCandidate(
                            providerId = TrackerIds.MAL,
                            remoteMediaId = existing.remoteMediaId,
                            malId = existing.remoteMediaId.toIntOrNull(),
                            title = existing.remoteTitle,
                            episodes = existing.expectedTotalEpisodes?.toInt(),
                        )
                        MatchResolution.NeedsConfirmation(
                            reason = "Manual-pin mode is active; confirm MyAnimeList binding before syncing.",
                            mediaCandidates = listOf(candidate),
                        )
                    } else {
                        MatchResolution.Bound(existing)
                    }
                }
            }
        }

        if (request.tvType in setOf(TvType.Live, TvType.Audio, TvType.AudioBook, TvType.Podcast)) {
            return MatchResolution.Skipped("Media type ${request.tvType} is not trackable")
        }
        val hasAnimeIdSeed = request.canonicalSeed?.malId != null ||
            request.canonicalSeed?.anilistId != null ||
            request.resolvedMalId != null ||
            request.resolvedAnilistId != null
        val isAnimeType = request.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        if (!isAnimeType && !hasAnimeIdSeed) {
            return MatchResolution.Skipped("Non-anime entry without AniList/MAL identity")
        }

        if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
            val candidates = findStrictCandidates(request, auth, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = "Manual control active — select a MyAnimeList entry to link.",
                mediaCandidates = candidates,
            )
        }

        val isVerifiedOrigin = request.canonicalSeed?.origin in setOf(
            MediaSeedOrigin.EXPLORE,
            MediaSeedOrigin.DIRECT_SYNC_DATA,
        ) && hasAnimeIdSeed
        val isStrictAuto = request.bindPolicy == TrackerBindPolicy.STRICT_AUTO_AND_PINNED

        if (!isVerifiedOrigin && !hasAnimeIdSeed && !isStrictAuto) {
            val candidates = findStrictCandidates(request, auth, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = if (candidates.isNotEmpty()) {
                    "Match found on MyAnimeList — confirm before tracking."
                } else {
                    "No verified MyAnimeList ID on this entry; link it manually to track."
                },
                mediaCandidates = candidates,
            )
        }

        val directMalId = request.canonicalSeed?.malId
            ?: request.resolvedMalId
            ?: (request.canonicalSeed?.anilistId ?: request.resolvedAnilistId)?.let { aniId ->
                runCatching { fetchMalIdFromAniList(aniId) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }

        if (directMalId != null && directMalId > 0) {
            val baseNode = runCatching { fetchDetails(auth, directMalId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            val (cleanTitle, parsedYear) = TitleUtils.cleanProviderTitle(
                request.canonicalSeed?.expectedTitle?.takeIf { it.isNotBlank() } ?: request.showTitle,
            )
            val titleOk = baseNode != null && (
                isVerifiedOrigin || (
                    cleanTitle.isNotBlank() &&
                        AniListMetadataProvider.scoreStrictCandidate(
                            cleanQuery = cleanTitle,
                            candidateTitles = baseNode.allTitles(),
                            canonicalYear = request.canonicalSeed?.expectedYear ?: request.year ?: parsedYear,
                            candidateYear = baseNode.startSeasonYear,
                            candidateFormat = baseNode.normalizedFormat,
                            requestedType = request.tvType,
                        ) != null
                    )
                )
            if (baseNode != null && titleOk) {
                val resolved = if (seasonNum > 1) {
                    traverseMalSequel(baseNode, seasonNum, request.showTitle, auth, fetchDetails)
                } else {
                    baseNode
                }
                if (resolved != null) {
                    val binding = TrackerBinding(
                        profileId = request.profileId,
                        trackerId = TrackerIds.MAL,
                        showKey = request.showKey,
                        mappedSeason = seasonNum.toLong(),
                        remoteMediaId = resolved.id.toString(),
                        remoteTitle = resolved.title,
                        bindMode = BindMode.AUTO_VERIFIED,
                        episodeOffset = 0L,
                        expectedTotalEpisodes = resolved.knownEpisodes?.toLong(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    repository.saveBinding(binding)
                    return MatchResolution.Bound(binding)
                }
            }
        }

        val fallback = findStrictCandidates(request, auth, searchRemote)
        val top = fallback.firstOrNull()
        val second = fallback.getOrNull(1)
        val topScore = top?.matchScore ?: 0.0
        val secondScore = second?.matchScore ?: 0.0
        val isAutoBindScoreEligible = (isVerifiedOrigin || isStrictAuto) &&
            topScore >= MIN_STRICT_SIMILARITY &&
            (second == null || (topScore - secondScore) >= MIN_AMBIGUITY_MARGIN)

        if (top != null && isAutoBindScoreEligible) {
            if (seasonNum == 1 && !request.hasMultipleSeasons) {
                val binding = TrackerBinding(
                    profileId = request.profileId,
                    trackerId = TrackerIds.MAL,
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
            } else if (seasonNum > 1) {
                val topId = top.remoteMediaId.toIntOrNull()
                val baseNode = topId?.let {
                    runCatching { fetchDetails(auth, it) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }
                val resolved = baseNode?.let { traverseMalSequel(it, seasonNum, request.showTitle, auth, fetchDetails) }
                if (resolved != null) {
                    val binding = TrackerBinding(
                        profileId = request.profileId,
                        trackerId = TrackerIds.MAL,
                        showKey = request.showKey,
                        mappedSeason = seasonNum.toLong(),
                        remoteMediaId = resolved.id.toString(),
                        remoteTitle = resolved.title,
                        bindMode = BindMode.AUTO_VERIFIED,
                        episodeOffset = 0L,
                        expectedTotalEpisodes = resolved.knownEpisodes?.toLong(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    repository.saveBinding(binding)
                    return MatchResolution.Bound(binding)
                }
            }
        }

        AppLogger.w(TAG, "Unable to auto-resolve MAL entry for '${request.showTitle}' S$seasonNum")
        return MatchResolution.NeedsConfirmation(
            reason = if (fallback.isNotEmpty()) {
                "Match found on MyAnimeList — confirm '${request.showTitle}' (Season $seasonNum) before syncing."
            } else {
                "Select the exact MyAnimeList entry for '${request.showTitle}' (Season $seasonNum)."
            },
            mediaCandidates = fallback,
        )
    }

    private suspend fun traverseMalSequel(
        startNode: MalAnimeNode,
        targetSeason: Int,
        showTitle: String,
        auth: AuthData?,
        fetchDetails: suspend (AuthData?, Int) -> MalAnimeNode?,
    ): MalAnimeNode? {
        var current = startNode
        val visited = HashSet<Int>().apply { add(startNode.id) }
        var step = 1
        var hops = 0
        while (step < targetSeason && hops < MAX_GRAPH_HOPS) {
            hops++
            val sequelEdges = current.relatedAnime.filter {
                it.relationType.equals("sequel", ignoreCase = true)
            }
            if (sequelEdges.isEmpty()) return null

            val detailedSequels = sequelEdges.mapNotNull { edge ->
                if (!visited.add(edge.id)) return@mapNotNull null
                runCatching { fetchDetails(auth, edge.id) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }.filter { it.normalizedFormat in SERIES_FORMATS || it.normalizedFormat == "SPECIAL" }

            if (detailedSequels.isEmpty()) return null

            val (cleanShow, _) = TitleUtils.cleanProviderTitle(showTitle)
            val chosen = detailedSequels.firstOrNull { cand ->
                cand.normalizedFormat in SERIES_FORMATS
            } ?: detailedSequels.firstOrNull { cand ->
                cand.allTitles().any { t -> cleanShow.isNotBlank() && t.contains(cleanShow, ignoreCase = true) }
            } ?: detailedSequels.firstOrNull()
                ?: return null

            current = chosen
            if (chosen.normalizedFormat in SERIES_FORMATS) {
                step++
            }
        }
        return if (step == targetSeason) current else null
    }

    suspend fun findStrictCandidates(
        request: MatchRequest,
        auth: AuthData? = null,
        searchRemote: suspend (AuthData?, String) -> List<MalAnimeNode>,
    ): List<TrackerMediaCandidate> {
        val rawTitle = request.canonicalSeed?.expectedTitle?.takeIf { it.isNotBlank() } ?: request.showTitle
        val (cleanQuery, parsedYear) = TitleUtils.cleanProviderTitle(rawTitle)
        if (cleanQuery.length < 3) return emptyList()

        val canonicalYear = request.canonicalSeed?.expectedYear ?: request.year ?: parsedYear
        val results = runCatching { searchRemote(auth, cleanQuery) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())

        return results.mapNotNull { node ->
            val titles = node.allTitles()
            val strict = AniListMetadataProvider.scoreStrictCandidate(
                cleanQuery = cleanQuery,
                candidateTitles = titles,
                canonicalYear = canonicalYear,
                candidateYear = node.startSeasonYear,
                candidateFormat = node.normalizedFormat,
                requestedType = request.tvType,
            ) ?: return@mapNotNull null

            val yearBonus = when {
                canonicalYear == null || node.startSeasonYear == null -> 0.0
                node.startSeasonYear == canonicalYear -> 0.05
                abs(node.startSeasonYear - canonicalYear) <= 1 -> 0.02
                else -> -0.05
            }
            val finalScore = (strict + yearBonus).coerceIn(0.0, 1.0)
            TrackerMediaCandidate(
                providerId = TrackerIds.MAL,
                remoteMediaId = node.id.toString(),
                malId = node.id,
                title = node.englishTitle ?: node.title,
                format = node.normalizedFormat,
                seasonYear = node.startSeasonYear,
                episodes = node.knownEpisodes,
                status = node.normalizedAirStatus,
                posterUrl = node.posterUrl,
                matchScore = finalScore,
            )
        }.sortedByDescending { it.matchScore ?: 0.0 }
    }

    suspend fun searchCandidates(
        query: String,
        year: Int? = null,
        tvType: TvType = TvType.Anime,
        auth: AuthData? = null,
        malApi: MalApi = AccountManager.malApi,
    ): List<TrackerMediaCandidate> {
        val (cleanedQuery, parsedYear) = TitleUtils.cleanProviderTitle(query)
        val cleanQuery = cleanedQuery.ifBlank { query.trim() }
        if (cleanQuery.length < 3) return emptyList()
        val canonicalYear = year ?: parsedYear

        val results = runCatching { malApi.searchAnime(auth, cleanQuery) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())

        return results.map { node ->
            val strictScore = AniListMetadataProvider.scoreStrictCandidate(
                cleanQuery = cleanQuery,
                candidateTitles = node.allTitles(),
                canonicalYear = canonicalYear,
                candidateYear = node.startSeasonYear,
                candidateFormat = node.normalizedFormat,
                requestedType = tvType,
            )
            TrackerMediaCandidate(
                providerId = TrackerIds.MAL,
                remoteMediaId = node.id.toString(),
                malId = node.id,
                title = node.englishTitle ?: node.title,
                format = node.normalizedFormat,
                seasonYear = node.startSeasonYear,
                episodes = node.knownEpisodes,
                status = node.normalizedAirStatus,
                posterUrl = node.posterUrl,
                matchScore = strictScore,
            )
        }.sortedByDescending { it.matchScore ?: 0.0 }
    }
}
