package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.desktop.metadata.providers.AniListMetadataProvider
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonResponse
import com.lagradost.common.logging.AppLogger
import kotlin.math.abs

/**
 * Resolves a local show + season to a verified AniList media entry with strict
 * origin checks and cour/sequel graph traversal so mismatched provider entries
 * never silently corrupt user watchlists.
 */
object AniListMatchResolver {
    private const val TAG = "AniListMatchResolver"
    private const val MIN_STRICT_SIMILARITY = 0.90
    private const val MIN_AMBIGUITY_MARGIN = 0.08
    private const val MAX_GRAPH_HOPS = 12
    private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")

    sealed interface SeasonTraversalResult {
        data class Resolved(val media: SeasonMedia) : SeasonTraversalResult
        data class AmbiguousOrMissing(
            val reason: String,
            val fallbackCandidates: List<AniListCandidate>,
        ) : SeasonTraversalResult
    }

    suspend fun resolveMatch(
        request: MatchRequest,
        repository: TrackingRepository,
        fetchSeason: suspend (Int) -> SeasonResponse? = { AniListApi.getSeasonOrNull(it) },
        fetchByMalId: suspend (Int) -> SeasonResponse? = { AniListApi.getSeasonByMalId(it) },
        searchRemote: suspend (String) -> List<GetSearchMedia> = { AniListApi.searchShows(it)?.data?.page?.media.orEmpty() },
    ): MatchResolution {
        val seasonNum = request.mappedSeason.coerceAtLeast(1)

        // 1. Persisted binding takes absolute precedence (blocked/pinned always honored).
        val existing = repository.getBinding(request.showKey, seasonNum, TrackerIds.ANILIST)
        if (existing != null) {
            return when (existing.bindMode) {
                BindMode.BLOCKED -> MatchResolution.BlockedByUser
                BindMode.USER_PINNED -> MatchResolution.Bound(existing)
                BindMode.AUTO_VERIFIED -> {
                    if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
                        MatchResolution.NeedsConfirmation(
                            reason = "Manual-pin mode is active; confirm binding before syncing.",
                            candidates = listOf(
                                AniListCandidate(
                                    anilistId = existing.remoteMediaId.toIntOrNull() ?: 0,
                                    title = existing.remoteTitle,
                                    episodes = existing.expectedTotalEpisodes?.toInt(),
                                ),
                            ).filter { it.anilistId > 0 },
                        )
                    } else {
                        MatchResolution.Bound(existing)
                    }
                }
            }
        }

        // 2. Non-anime gate: skip live/audio/non-anime entries lacking anime ID seeds.
        if (request.tvType in setOf(TvType.Live, TvType.Audio, TvType.AudioBook, TvType.Podcast)) {
            return MatchResolution.Skipped("Media type ${request.tvType} is not trackable")
        }
        val hasAnimeIdSeed = request.canonicalSeed?.anilistId != null ||
            request.canonicalSeed?.malId != null ||
            request.resolvedAnilistId != null ||
            request.resolvedMalId != null
        val isAnimeType = request.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        if (!isAnimeType && !hasAnimeIdSeed) {
            return MatchResolution.Skipped("Non-anime entry without AniList/MAL identity")
        }

        // 3. Manual-pin-only policy never creates automatic bindings, but preloads ranked choices.
        if (request.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY) {
            val candidates = findStrictCandidates(request, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = "Manual control active — select an AniList entry to track.",
                candidates = candidates,
            )
        }

        val isVerifiedOrigin = request.canonicalSeed?.origin in setOf(
            MediaSeedOrigin.EXPLORE,
            MediaSeedOrigin.DIRECT_SYNC_DATA,
        ) && (request.canonicalSeed?.anilistId != null || request.canonicalSeed?.malId != null)
        val hasResolvedAnimeId = request.resolvedAnilistId != null || request.resolvedMalId != null
        val isStrictAuto = request.bindPolicy == TrackerBindPolicy.STRICT_AUTO_AND_PINNED

        // 4. Verified IDs Only requires a seed/enriched ID; Strict Auto can search unambiguously.
        if (!isVerifiedOrigin && !hasResolvedAnimeId && !isStrictAuto) {
            val candidates = findStrictCandidates(request, searchRemote)
            return MatchResolution.NeedsConfirmation(
                reason = if (candidates.isNotEmpty()) {
                    "Match found on AniList — confirm before tracking."
                } else {
                    "No verified AniList ID on this entry; link it manually to track."
                },
                candidates = candidates,
            )
        }

        // 5. Resolve base AniList media from verified ID, enriched ID, or unambiguous strict search.
        val baseMedia = resolveBaseMedia(
            request = request,
            isVerifiedOrigin = isVerifiedOrigin,
            allowStrictSearch = isStrictAuto,
            fetchSeason = fetchSeason,
            fetchByMalId = fetchByMalId,
            searchRemote = searchRemote,
        )

        if (baseMedia == null) {
            val candidates = if (request.tvType == TvType.Anime || request.tvType == TvType.AnimeMovie || request.tvType == TvType.OVA || hasAnimeIdSeed) {
                findStrictCandidates(request, searchRemote)
            } else {
                emptyList()
            }
            return MatchResolution.NeedsConfirmation(
                reason = if (candidates.isNotEmpty()) {
                    "Match found on AniList — select the right season/entry to track."
                } else {
                    "Could not find an unambiguous AniList match."
                },
                candidates = candidates,
            )
        }

        // 6. Traverse cour/sequel graph when mapping provider season > 1 (or multi-season S1).
        val targetMedia = when (
            val traversal = resolveSeasonTarget(
                seedMedia = baseMedia,
                targetSeason = seasonNum,
                hasMultipleSeasons = request.hasMultipleSeasons,
                requestedType = request.tvType,
                fetchSeason = fetchSeason,
            )
        ) {
            is SeasonTraversalResult.Resolved -> traversal.media
            is SeasonTraversalResult.AmbiguousOrMissing -> {
                return MatchResolution.NeedsConfirmation(
                    reason = traversal.reason,
                    candidates = traversal.fallbackCandidates,
                )
            }
        }

        val targetId = targetMedia.id
            ?: return MatchResolution.NeedsConfirmation("Resolved AniList entry had no ID.")
        val resolvedTitle = targetMedia.title?.english
            ?: targetMedia.title?.romaji
            ?: targetMedia.title?.userPreferred
            ?: request.showTitle

        val binding = TrackerBinding(
            profileId = request.profileId,
            trackerId = TrackerIds.ANILIST,
            showKey = request.showKey,
            mappedSeason = seasonNum.toLong(),
            remoteMediaId = targetId.toString(),
            remoteTitle = resolvedTitle,
            bindMode = BindMode.AUTO_VERIFIED,
            episodeOffset = 0L,
            expectedTotalEpisodes = targetMedia.episodes?.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
        repository.saveBinding(binding)
        AppLogger.i(TAG, "Bound '${request.showTitle}' S$seasonNum -> AniList #$targetId ('$resolvedTitle')")
        return MatchResolution.Bound(binding)
    }

    /**
     * Pure cour/sequel graph traversal isolated for deterministic unit testing.
     */
    internal suspend fun resolveSeasonTarget(
        seedMedia: SeasonMedia,
        targetSeason: Int,
        hasMultipleSeasons: Boolean,
        requestedType: TvType,
        fetchSeason: suspend (Int) -> SeasonResponse?,
    ): SeasonTraversalResult {
        val seedId = seedMedia.id
            ?: return SeasonTraversalResult.AmbiguousOrMissing("Missing AniList media ID", emptyList())

        // Single-season entries (including standalone Season 2 pages where provider season == 1) stay pinned to the seed.
        if (targetSeason <= 1 && !hasMultipleSeasons) {
            return SeasonTraversalResult.Resolved(seedMedia)
        }

        if (requestedType == TvType.AnimeMovie) {
            return if (targetSeason <= 1) {
                SeasonTraversalResult.Resolved(seedMedia)
            } else {
                SeasonTraversalResult.AmbiguousOrMissing(
                    "Movie entry cannot auto-map to season $targetSeason",
                    listOf(seedMedia.toCandidate()),
                )
            }
        }

        // Determine where the seed sits on the prequel chain (1 = root season, 2 = sequel, etc.).
        val visitedPrequels = mutableSetOf(seedId)
        var cursor = seedMedia
        var prequelSteps = 0
        val prequelChain = mutableListOf(seedMedia)

        while (prequelSteps < MAX_GRAPH_HOPS) {
            val prequelNodes = cursor.relations?.edges
                ?.filter { it.relationType == "PREQUEL" && it.node?.format?.uppercase() in SERIES_FORMATS }
                ?.mapNotNull { it.node }
                .orEmpty()
            if (prequelNodes.size != 1) break
            val prevNode = prequelNodes.first()
            if (!visitedPrequels.add(prevNode.id)) break
            val fetched = fetchSeason(prevNode.id)?.data?.media ?: break
            prequelChain.add(fetched)
            cursor = fetched
            prequelSteps++
        }

        val seedPosition = prequelSteps + 1
        if (targetSeason == seedPosition) {
            return SeasonTraversalResult.Resolved(seedMedia)
        }

        if (targetSeason < seedPosition) {
            // Walk back toward root only for explicitly multi-season shows (e.g. Explore pointed to S2, watching S1).
            val backIndex = (seedPosition - targetSeason)
            val resolved = prequelChain.getOrNull(backIndex)
            return if (resolved != null) {
                SeasonTraversalResult.Resolved(resolved)
            } else {
                SeasonTraversalResult.AmbiguousOrMissing(
                    "Could not resolve Season $targetSeason prequel from '${seedMedia.displayTitle()}'",
                    prequelChain.map { it.toCandidate() },
                )
            }
        }

        // Walk forward along unambiguous TV/ONA/TV_SHORT SEQUEL edges.
        var current = seedMedia
        val visitedSequels = mutableSetOf(seedId)
        var hopsRemaining = targetSeason - seedPosition

        while (hopsRemaining > 0) {
            val allSequels = current.relations?.edges
                ?.filter { it.relationType == "SEQUEL" && it.node != null }
                ?.mapNotNull { it.node }
                .orEmpty()

            val seriesSequels = allSequels.filter { it.format?.uppercase() in SERIES_FORMATS }
            if (seriesSequels.isEmpty()) {
                val candidates = (listOf(current.toCandidate()) + allSequels.map {
                    AniListCandidate(
                        anilistId = it.id,
                        malId = it.idMal,
                        title = it.title?.userPreferred ?: it.title?.romaji ?: it.title?.english ?: "AniList #${it.id}",
                        format = it.format,
                        seasonYear = it.seasonYear,
                        episodes = it.episodes,
                    )
                }).distinctBy { it.anilistId }
                return SeasonTraversalResult.AmbiguousOrMissing(
                    reason = "No TV/ONA sequel edge on AniList for Season $targetSeason (possible split-cour merged entry).",
                    fallbackCandidates = candidates,
                )
            }

            if (seriesSequels.size > 1) {
                val candidates = seriesSequels.map {
                    AniListCandidate(
                        anilistId = it.id,
                        malId = it.idMal,
                        title = it.title?.userPreferred ?: it.title?.romaji ?: it.title?.english ?: "AniList #${it.id}",
                        format = it.format,
                        seasonYear = it.seasonYear,
                        episodes = it.episodes,
                    )
                }
                return SeasonTraversalResult.AmbiguousOrMissing(
                    reason = "Multiple TV sequels found from '${current.displayTitle()}'; user selection required.",
                    fallbackCandidates = candidates,
                )
            }

            val nextNode = seriesSequels.first()
            if (!visitedSequels.add(nextNode.id)) {
                return SeasonTraversalResult.AmbiguousOrMissing(
                    reason = "Sequel cycle detected while resolving Season $targetSeason.",
                    fallbackCandidates = listOf(current.toCandidate()),
                )
            }

            val nextMedia = fetchSeason(nextNode.id)?.data?.media
                ?: return SeasonTraversalResult.AmbiguousOrMissing(
                    reason = "Failed to fetch AniList sequel #${nextNode.id}.",
                    fallbackCandidates = listOf(current.toCandidate()),
                )
            current = nextMedia
            hopsRemaining--
        }

        return SeasonTraversalResult.Resolved(current)
    }

    private suspend fun resolveBaseMedia(
        request: MatchRequest,
        isVerifiedOrigin: Boolean,
        allowStrictSearch: Boolean,
        fetchSeason: suspend (Int) -> SeasonResponse?,
        fetchByMalId: suspend (Int) -> SeasonResponse?,
        searchRemote: suspend (String) -> List<GetSearchMedia>,
    ): SeasonMedia? {
        if (isVerifiedOrigin) {
            request.canonicalSeed?.anilistId?.let { id ->
                fetchSeason(id)?.data?.media?.let { return it }
            }
            request.canonicalSeed?.malId?.let { malId ->
                fetchByMalId(malId)?.data?.media?.let { return it }
            }
        }

        // Enriched IDs from MetadataPipeline must pass title/year/format sanity check before auto-binding.
        val candidateAniId = request.resolvedAnilistId ?: request.canonicalSeed?.anilistId
        if (candidateAniId != null && candidateAniId > 0) {
            val fetched = fetchSeason(candidateAniId)?.data?.media
            if (fetched != null && verifySeasonMediaMatches(request, fetched)) {
                return fetched
            }
        }
        val candidateMalId = request.resolvedMalId ?: request.canonicalSeed?.malId
        if (candidateMalId != null && candidateMalId > 0) {
            val fetched = fetchByMalId(candidateMalId)?.data?.media
            if (fetched != null && verifySeasonMediaMatches(request, fetched)) {
                return fetched
            }
        }

        if (allowStrictSearch) {
            val top = pickUnambiguousStrictSearchMatch(request, searchRemote)
            if (top != null && top.anilistId > 0) {
                fetchSeason(top.anilistId)?.data?.media?.let { return it }
            }
        }

        return null
    }

    private fun verifySeasonMediaMatches(request: MatchRequest, media: SeasonMedia): Boolean {
        val rawTitle = request.canonicalSeed?.expectedTitle?.takeIf { it.isNotBlank() } ?: request.showTitle
        val (cleanName, titleYear) = TitleUtils.cleanProviderTitle(rawTitle)
        if (cleanName.isBlank()) return false
        val targetYear = request.canonicalSeed?.expectedYear ?: request.year ?: titleYear
        val candidateTitles = buildList {
            media.title?.english?.let(::add)
            media.title?.romaji?.let(::add)
            media.title?.userPreferred?.let(::add)
            media.synonyms?.forEach { if (it.isNotBlank()) add(it) }
        }.distinct()
        val score = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = cleanName,
            candidateTitles = candidateTitles,
            canonicalYear = targetYear,
            candidateYear = media.seasonYear,
            candidateFormat = media.format,
            requestedType = request.tvType,
        )
        return score != null && score >= 0.75
    }

    private suspend fun pickUnambiguousStrictSearchMatch(
        request: MatchRequest,
        searchRemote: suspend (String) -> List<GetSearchMedia>,
    ): AniListCandidate? {
        val scored = scoreSearchCandidates(request, searchRemote)
        val top = scored.firstOrNull() ?: return null
        val topScore = top.matchScore ?: return null
        if (topScore < MIN_STRICT_SIMILARITY) return null

        val runnerUpScore = scored.getOrNull(1)?.matchScore
        if (runnerUpScore != null && abs(topScore - runnerUpScore) < MIN_AMBIGUITY_MARGIN) {
            return null
        }
        return top
    }

    suspend fun searchCandidates(
        query: String,
        year: Int? = null,
        tvType: TvType = TvType.Anime,
        searchRemote: suspend (String) -> List<GetSearchMedia> = { AniListApi.searchShows(it)?.data?.page?.media.orEmpty() },
    ): List<AniListCandidate> {
        val (cleanName, titleYear) = TitleUtils.cleanProviderTitle(query)
        val searchQuery = cleanName.ifBlank { query.trim() }
        if (searchQuery.isBlank()) return emptyList()
        val results = searchRemote(searchQuery)
        val targetYear = year ?: titleYear

        return results.map { media ->
            val candYear = media.seasonYear ?: media.startDate.year?.toIntOrNull()
            val score = scoreSearchMedia(media, searchQuery, targetYear, tvType)
            media.toCandidate(candYear = candYear, matchScore = score)
        }
    }

    private suspend fun findStrictCandidates(
        request: MatchRequest,
        searchRemote: suspend (String) -> List<GetSearchMedia>,
    ): List<AniListCandidate> {
        val scored = scoreSearchCandidates(request, searchRemote)
        if (scored.isNotEmpty()) return scored
        // Fallback: return raw search hits if strict score filtered all of them out (manual user choice).
        val (cleanName, _) = TitleUtils.cleanProviderTitle(request.showTitle)
        return searchRemote(cleanName.ifBlank { request.showTitle }).take(6).map { media ->
            media.toCandidate(
                candYear = media.seasonYear ?: media.startDate.year?.toIntOrNull(),
                matchScore = null,
            )
        }
    }

    private suspend fun scoreSearchCandidates(
        request: MatchRequest,
        searchRemote: suspend (String) -> List<GetSearchMedia>,
    ): List<AniListCandidate> {
        val (cleanName, titleYear) = TitleUtils.cleanProviderTitle(request.showTitle)
        if (cleanName.isBlank()) return emptyList()
        val targetYear = request.year ?: titleYear
        val raw = searchRemote(cleanName)
        return raw.mapNotNull { media ->
            val candYear = media.seasonYear ?: media.startDate.year?.toIntOrNull()
            val score = scoreSearchMedia(media, cleanName, targetYear, request.tvType) ?: return@mapNotNull null
            media.toCandidate(candYear = candYear, matchScore = score)
        }.sortedByDescending { it.matchScore ?: 0.0 }
    }

    private fun scoreSearchMedia(
        media: GetSearchMedia,
        cleanQuery: String,
        targetYear: Int?,
        requestedType: TvType,
    ): Double? {
        val titles = listOfNotNull(
            media.title.english,
            media.title.romaji,
            media.title.userPreferred,
        ).distinct()
        val candYear = media.seasonYear ?: media.startDate.year?.toIntOrNull()
        return AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = cleanQuery,
            candidateTitles = titles,
            canonicalYear = targetYear,
            candidateYear = candYear,
            candidateFormat = media.format,
            requestedType = requestedType,
        )
    }

    private fun GetSearchMedia.toCandidate(candYear: Int?, matchScore: Double?): AniListCandidate =
        AniListCandidate(
            anilistId = id,
            malId = idMal,
            title = title.english ?: title.userPreferred ?: title.romaji ?: "AniList #$id",
            format = format,
            seasonYear = candYear,
            episodes = episodes,
            status = status,
            posterUrl = bannerImage,
            matchScore = matchScore,
        )

    private fun SeasonMedia.displayTitle(): String =
        title?.english ?: title?.userPreferred ?: title?.romaji ?: "AniList #$id"

    private fun SeasonMedia.toCandidate(): AniListCandidate = AniListCandidate(
        anilistId = id ?: 0,
        malId = idMal,
        title = displayTitle(),
        format = format,
        seasonYear = seasonYear,
        episodes = episodes,
        status = status,
        posterUrl = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium,
    )
}
