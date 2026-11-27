package com.lagradost.cloudstream3.desktop.metadata

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.ReviewData
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.SeasonMetadata
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.TrailerData

/**
 * Represents a resolved universal media match from a metadata resolver.
 */
data class MetadataMatch(
    val providerId: String,
    val matchedTitle: String,
    val matchedYear: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val rating: Double? = null,
    val rawData: Any? = null,
)

enum class MediaSeedOrigin {
    EXPLORE,
    PROVIDER,
    DIRECT_SYNC_DATA,
}

/**
 * Canonical external identity hint passed from verified catalog entries (e.g. Explore)
 * or direct provider syncData. Never blindly auto-committed without verification.
 */
data class CanonicalMediaSeed(
    val origin: MediaSeedOrigin = MediaSeedOrigin.EXPLORE,
    val canonicalId: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val expectedTitle: String? = null,
    val expectedYear: Int? = null,
    val mediaType: String? = null,
) {
    companion object {
        fun fromCanonicalId(
            rawId: String?,
            anilistId: Int? = null,
            malId: Int? = null,
            tmdbId: Int? = null,
            imdbId: String? = null,
            expectedTitle: String? = null,
            expectedYear: Int? = null,
            mediaType: String? = null,
            origin: MediaSeedOrigin = MediaSeedOrigin.EXPLORE,
        ): CanonicalMediaSeed? {
            val trimmed = rawId?.trim().orEmpty()
            val cleanPrefixId = trimmed.substringBefore(":").trim()
            val parsedImdb = imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
                ?: cleanPrefixId.takeIf { it.startsWith("tt", ignoreCase = true) }
            val parsedTmdb = tmdbId?.takeIf { it > 0 }
                ?: trimmed.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
                    ?.substringAfter(":")?.toIntOrNull()?.takeIf { it > 0 }
            val parsedAnilist = anilistId?.takeIf { it > 0 }
                ?: trimmed.takeIf { it.startsWith("anilist:", ignoreCase = true) }
                    ?.substringAfter(":")?.toIntOrNull()?.takeIf { it > 0 }
            val parsedMal = malId?.takeIf { it > 0 }
                ?: trimmed.takeIf { it.startsWith("mal:", ignoreCase = true) }
                    ?.substringAfter(":")?.toIntOrNull()?.takeIf { it > 0 }

            if (parsedImdb == null && parsedTmdb == null && parsedAnilist == null && parsedMal == null) {
                return null
            }

            return CanonicalMediaSeed(
                origin = origin,
                canonicalId = trimmed.ifBlank { null },
                anilistId = parsedAnilist,
                malId = parsedMal,
                tmdbId = parsedTmdb,
                imdbId = parsedImdb,
                expectedTitle = expectedTitle?.takeIf { it.isNotBlank() },
                expectedYear = expectedYear,
                mediaType = mediaType?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Context passed to metadata providers during enrichment.
 */
data class MetadataEnrichmentContext(
    val rawUrl: String,
    val isDummy: Boolean = false,
    val fetchCast: Boolean = true,
    val overwrite: Boolean = false,
    val directImdbId: String? = null,
    val directTmdbId: Int? = null,
    val directAnilistId: Int? = null,
    val directMalId: Int? = null,
    val canonicalSeed: CanonicalMediaSeed? = null,
)

enum class RatingSource {
    IMDB,
    TMDB,
    TVMAZE,
    ANILIST,
    KITSU,
    PROVIDER,
}

/**
 * Unified callbacks for streaming progressive metadata updates to the UI layer.
 */
data class MetadataEnrichmentCallbacks(
    val onLogoLoaded: (String) -> Unit = {},
    val onBackdropLoaded: (String) -> Unit = {},
    val onScreenshotsLoaded: (List<String>) -> Unit = {},
    val onActorsLoaded: (List<ActorData>) -> Unit = {},
    val onTrailersLoaded: (List<TrailerData>) -> Unit = {},
    val onReviewsLoaded: (List<ReviewData>) -> Unit = {},
    val onEpisodeThumbnailsEnriched: () -> Unit = {},
    val onEpisodeRatingSourcesLoaded: (Map<Pair<Int, Int>, RatingSource>) -> Unit = {},
    val onRatingsLoaded: (imdb: Double?, tmdb: Double?, anilist: Double?) -> Unit = { _, _, _ -> },
    val onIdentityResolved: (tmdbId: Int?, imdbId: String?, anilistId: Int?, malId: Int?) -> Unit = { _, _, _, _ -> },
    val onMetadataLoaded: (
        tagline: String?,
        status: String?,
        studios: List<String>,
        collectionName: String?,
        collectionBg: String?,
        seasonsCount: Int?,
        episodesCount: Int?,
        seasons: List<SeasonMetadata>?,
        originalLang: String?,
        releaseDate: String?,
        country: String?,
        collectionItems: List<SearchResponse>,
        budget: Long?,
        revenue: Long?,
        networks: List<String>?,
        year: Int?,
        duration: Int?,
        tags: List<String>?,
        actors: List<ActorData>?,
        productionCompanies: List<com.lagradost.cloudstream3.desktop.ui.screens.details.contract.ProductionCompany>?,
        networkCompanies: List<com.lagradost.cloudstream3.desktop.ui.screens.details.contract.ProductionCompany>?,
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    val onEnrichmentComplete: () -> Unit = {},
)
