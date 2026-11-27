package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.metadata.RatingSource
import com.lagradost.cloudstream3.newEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TmdbEpisodeEnricher {

    private val dummyApi = object : MainAPI() {
        override var name = "TMDB"
        override var mainUrl = "https://www.themoviedb.org"
    }

    /**
     * Enriches episode metadata (thumbnails, descriptions, air dates, runtimes, scores)
     * and synthesizes unreleased future episodes from the TMDB season data.
     * Mutates [loaded] on the Main dispatcher.
     */
    suspend fun enrich(
        loaded: LoadResponse,
        tmdbData: JsonNode,
        isMovie: Boolean,
        overwrite: Boolean,
        onEpisodeThumbnailsEnriched: () -> Unit,
        onEpisodeRatingSourcesLoaded: (Map<Pair<Int, Int>, RatingSource>) -> Unit = {},
    ) {
        if (isMovie) {
            onEpisodeThumbnailsEnriched()
            return
        }

        val allEpisodes = when (loaded) {
            is TvSeriesLoadResponse -> loaded.episodes
            is AnimeLoadResponse -> loaded.episodes.values.flatten()
            else -> emptyList()
        }

        val realEpisodes = allEpisodes.filter { !it.data.startsWith("unreleased_") }
        val allSeasonNumbers = (realEpisodes.mapNotNull { it.season } + listOf(1)).distinct()
        val combinedSources = LinkedHashMap<Pair<Int, Int>, RatingSource>()

        allSeasonNumbers.forEach { seasonNum ->
            val seasonNode = tmdbData.get("season/$seasonNum") ?: return@forEach
            val sources = enrichSeasonNode(
                loaded = loaded,
                seasonNum = seasonNum,
                seasonNode = seasonNode,
                overwrite = overwrite,
            )
            combinedSources.putAll(sources)
        }

        if (combinedSources.isNotEmpty()) {
            onEpisodeRatingSourcesLoaded(combinedSources)
        }
        onEpisodeThumbnailsEnriched()
    }

    /**
     * Applies metadata from a single TMDB season payload onto matching [loaded] episodes
     * and synthesizes missing episodes, performing all [LoadResponse] mutations on Main.
     */
    suspend fun enrichSeasonNode(
        loaded: LoadResponse,
        seasonNum: Int,
        seasonNode: JsonNode,
        overwrite: Boolean,
    ): Map<Pair<Int, Int>, RatingSource> {
        if (!seasonNode.isObject) return emptyMap()
        val episodesNode = seasonNode.get("episodes")
        if (episodesNode == null || !episodesNode.isArray) return emptyMap()

        val allEpisodes = when (loaded) {
            is TvSeriesLoadResponse -> loaded.episodes
            is AnimeLoadResponse -> loaded.episodes.values.flatten()
            else -> emptyList()
        }
        val realEpisodes = allEpisodes.filter { !it.data.startsWith("unreleased_") }
        val existingEpNumbersInSeason = realEpisodes
            .filter { (it.season ?: 1) == seasonNum }
            .mapNotNull { it.episode }
            .toSet()

        data class ExistingEpPatch(
            val epNum: Int,
            val poster: String?,
            val overview: String?,
            val releaseDate: String?,
            val name: String?,
            val runtime: Int?,
            val vote: Double?,
        )

        val patches = mutableListOf<ExistingEpPatch>()
        val newEpisodesToAdd = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val tmdbRatedKeys = LinkedHashMap<Pair<Int, Int>, RatingSource>()
        val now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)

        episodesNode.forEach { epNode ->
            val epNum = epNode.get("episode_number")?.asInt() ?: return@forEach
            val epPosterPath = epNode.get("still_path")?.asText()
            val epPoster = TmdbEnrichmentService.tmdbImageUrl(epPosterPath, "original")
            val epOverview = epNode.get("overview")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
            val epReleaseDate = epNode.get("air_date")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
            val epName = epNode.get("name")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
            val epRuntime = epNode.get("runtime")?.asInt()?.takeIf { it > 0 }
            val epVote = epNode.get("vote_average")?.asDouble()?.takeIf { it > 0.0 }

            if (existingEpNumbersInSeason.contains(epNum)) {
                patches.add(ExistingEpPatch(epNum, epPoster, epOverview, epReleaseDate, epName, epRuntime, epVote))
            } else {
                val parsedAirDate = epReleaseDate?.let { dateStr ->
                    try { java.time.LocalDate.parse(dateStr.take(10)) } catch (_: Exception) { null }
                }
                val isFuture = parsedAirDate?.isAfter(now) ?: false
                val isPastAndMissing = parsedAirDate != null && !isFuture

                if (isFuture || isPastAndMissing) {
                    val prefix = if (isFuture) "unreleased_s${seasonNum}_e$epNum" else "synthetic_missing_s${seasonNum}_e$epNum"
                    val descWithDate = "||DATE:$epReleaseDate||${epOverview ?: ""}"
                    val synthetic = dummyApi.newEpisode(prefix) {
                        this.name = epName ?: "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epPoster
                        this.description = descWithDate
                        this.runTime = epRuntime
                        this.score = epVote?.let { Score.from10(it) }
                    }
                    if (epVote != null) {
                        tmdbRatedKeys[seasonNum to epNum] = RatingSource.TMDB
                    }
                    newEpisodesToAdd.add(synthetic)
                }
            }
        }

        val distinctNewEpisodes = newEpisodesToAdd.distinctBy { Pair(it.season ?: 1, it.episode ?: 0) }
        if (patches.isNotEmpty() || distinctNewEpisodes.isNotEmpty()) {
            withContext(Dispatchers.Main.immediate) {
                for (patch in patches) {
                    val targets = realEpisodes.filter { (it.season ?: 1) == seasonNum && it.episode == patch.epNum }
                    for (existingEp in targets) {
                        if (patch.poster != null) existingEp.posterUrl = patch.poster
                        if ((existingEp.description.isNullOrBlank() || overwrite) && patch.overview != null) {
                            existingEp.description = patch.overview
                        }
                        if (patch.releaseDate != null) {
                            val cleanDesc = (existingEp.description ?: "").replace(Regex("\\|\\|DATE:.*?\\|\\|"), "")
                            existingEp.description = "||DATE:${patch.releaseDate}||$cleanDesc"
                        }
                        if (patch.name != null) {
                            val currentName = existingEp.name?.trim() ?: ""
                            val isGenericTitle = currentName.isBlank() || currentName.matches(Regex("""^(?i)Episode[\s]*\d+$"""))
                            if (isGenericTitle || overwrite || !patch.name.matches(Regex("""^(?i)Episode[\s]*\d+$"""))) {
                                existingEp.name = patch.name
                            }
                        }
                        if (patch.runtime != null) existingEp.runTime = patch.runtime
                        if (patch.vote != null && (existingEp.score == null || existingEp.score!!.toDouble(10) <= 0.0)) {
                            existingEp.score = Score.from10(patch.vote)
                            tmdbRatedKeys[seasonNum to patch.epNum] = RatingSource.TMDB
                        }
                    }
                }

                if (distinctNewEpisodes.isNotEmpty()) {
                    when (loaded) {
                        is TvSeriesLoadResponse -> {
                            val base = loaded.episodes.filter { !it.data.startsWith("unreleased_") }
                            loaded.episodes = (base + distinctNewEpisodes)
                                .distinctBy { Pair(it.season ?: 1, it.episode ?: 0) }
                                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                                .toMutableList()
                        }
                        is AnimeLoadResponse -> {
                            val mutableMap = loaded.episodes.toMutableMap()
                            if (mutableMap.isEmpty()) {
                                mutableMap[DubStatus.Subbed] = distinctNewEpisodes
                            } else {
                                mutableMap.keys.forEach { dubKey ->
                                    val current = mutableMap[dubKey].orEmpty().filter { !it.data.startsWith("unreleased_") }
                                    mutableMap[dubKey] = (current + distinctNewEpisodes)
                                        .distinctBy { Pair(it.season ?: 1, it.episode ?: 0) }
                                        .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                                }
                            }
                            loaded.episodes = mutableMap
                        }
                        else -> {}
                    }
                }
            }
        }
        return tmdbRatedKeys
    }
}
