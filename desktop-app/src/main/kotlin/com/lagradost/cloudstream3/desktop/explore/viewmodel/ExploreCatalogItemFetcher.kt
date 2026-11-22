package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.explore.client.AniListCatalogClient
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCatalogClient
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCollectionsHub
import com.lagradost.cloudstream3.desktop.explore.client.ExploreHubClient
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.explore.models.StreamingPlatform

/**
 * Handles network fetching and endpoint routing for catalogs across
 * TMDB parametric feeds, AniList, Jikan, Collections, and Stremio add-ons.
 */
internal object ExploreCatalogItemFetcher {

    suspend fun fetchItemsForCatalog(
        cat: ManifestCatalogDescriptor,
        genreArg: String?,
        skip: Int,
    ): List<ExploreItem> {
        return when {
            cat.addonBaseUrl.startsWith("tmdb://platform/") -> {
                val fullUrl = cat.addonBaseUrl.removePrefix("tmdb://platform/")
                val platformId = fullUrl.substringBefore("?")
                val queryParams = if (fullUrl.contains("?")) {
                    fullUrl.substringAfter("?").split("&").associate {
                        val parts = it.split("=")
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
                } else {
                    emptyMap()
                }

                val platform = StreamingPlatform.fromIdOrNull(platformId) ?: StreamingPlatform.NETFLIX
                val page = (skip / 20) + 1

                if (platform == StreamingPlatform.CRUNCHYROLL) {
                    val sort = queryParams["sort"]?.takeIf { it.isNotBlank() } ?: "TRENDING_DESC"
                    val genre = queryParams["genre"]?.takeIf { it.isNotBlank() } ?: genreArg
                    ExploreHubClient.fetchAnilistShelfRow(sort = sort, genre = genre, page = page, perPage = 24)
                } else {
                    val rowType = queryParams["row"]
                    val activeGenre = genreArg?.takeIf { !it.equals("All", ignoreCase = true) }
                    val movieGenreId = activeGenre?.let { ExploreHubClient.getGenreId(it, isTv = false) }
                    val tvGenreId = activeGenre?.let { ExploreHubClient.getGenreId(it, isTv = true) }
                    when (rowType) {
                        "trending" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "popularity.desc", targetType = "both", movieGenreId = movieGenreId, tvGenreId = tvGenreId, page = page)
                        "new_movies" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "primary_release_date.desc", targetType = "movie", movieGenreId = movieGenreId, page = page)
                        "new_series" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "first_air_date.desc", targetType = "series", tvGenreId = tvGenreId, page = page)
                        "top_rated" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "vote_average.desc", targetType = "both", movieGenreId = movieGenreId, tvGenreId = tvGenreId, minVoteCount = 150, page = page)
                        "action" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "popularity.desc", targetType = "both", movieGenreId = movieGenreId ?: 28, tvGenreId = tvGenreId ?: 10759, page = page)
                        "drama" -> ExploreHubClient.fetchPlatformShelfRow(platform, sortBy = "popularity.desc", targetType = "both", movieGenreId = movieGenreId ?: 18, tvGenreId = tvGenreId ?: 18, page = page)
                        else -> ExploreHubClient.fetchPlatformItems(
                            platform = platform,
                            page = page,
                            genreName = genreArg,
                            targetType = cat.type,
                        )
                    }
                }
            }
            cat.addonBaseUrl.startsWith("tmdb://movie/") -> {
                val page = (skip / 20) + 1
                val endpoint = cat.addonBaseUrl.removePrefix("tmdb://movie/")
                when (endpoint) {
                    "in_theaters" -> ExploreHubClient.fetchInTheaters(page, genreArg)
                    "critics_picks" -> ExploreHubClient.fetchCriticsPicks(page, genreArg)
                    "hidden_gems" -> ExploreHubClient.fetchHiddenGems(page, genreArg)
                    "under_ninety" -> ExploreHubClient.fetchUnderNinety(page, genreArg)
                    "decade_2010s" -> ExploreHubClient.fetchDecadeMovies(2010, 2019, 1000, 7.5, page, genreArg)
                    "decade_90s" -> ExploreHubClient.fetchDecadeMovies(1990, 1999, 800, 7.5, page, genreArg)
                    "decade_80s" -> ExploreHubClient.fetchDecadeMovies(1980, 1989, 500, 7.4, page, genreArg)
                    "lang_jp" -> ExploreHubClient.fetchForeignCinema("ja", 7.4, page, genreArg)
                    "lang_kr" -> ExploreHubClient.fetchForeignCinema("ko", 7.4, page, genreArg)
                    else -> ExploreHubClient.fetchCriticsPicks(page, genreArg)
                }
            }
            cat.addonBaseUrl.startsWith("tmdb://series/") -> {
                val page = (skip / 20) + 1
                val endpoint = cat.addonBaseUrl.removePrefix("tmdb://series/")
                when (endpoint) {
                    "trending" -> ExploreHubClient.fetchTrendingTv(page, genreArg)
                    "airing_today" -> ExploreHubClient.fetchAiringTodayTv(page, genreArg)
                    "net_hbo" -> ExploreHubClient.fetchNetworkTv(49, minVotes = 200, minRating = 7.5, page = page, genreName = genreArg)
                    "net_netflix" -> ExploreHubClient.fetchNetworkTv(213, minVotes = 200, minRating = 7.0, page = page, genreName = genreArg)
                    "net_apple" -> ExploreHubClient.fetchNetworkTv(2552, minVotes = 100, minRating = 7.0, page = page, genreName = genreArg)
                    "net_disney" -> ExploreHubClient.fetchNetworkTv(2739, minVotes = 100, minRating = 6.8, page = page, genreName = genreArg)
                    "net_amazon" -> ExploreHubClient.fetchNetworkTv(1024, minVotes = 150, minRating = 7.0, page = page, genreName = genreArg)
                    "net_fx" -> ExploreHubClient.fetchNetworkTv(88, minVotes = 150, minRating = 7.2, page = page, genreName = genreArg)
                    "net_amc" -> ExploreHubClient.fetchNetworkTv(174, minVotes = 150, minRating = 7.2, page = page, genreName = genreArg)
                    "prestige_drama" -> ExploreHubClient.fetchCuratedTvGenre(18, minVotes = 500, minRating = 7.8, page = page, genreName = genreArg)
                    "kdrama" -> ExploreHubClient.fetchForeignTv("KR", isCountry = true, minRating = 7.5, minVotes = 100, page = page, genreName = genreArg)
                    else -> ExploreHubClient.fetchTrendingTv(page, genreArg)
                }
            }
            cat.addonBaseUrl.startsWith("anilist://") || cat.addonBaseUrl.startsWith("jikan://") -> {
                val page = (skip / 24) + 1
                val endpoint = cat.addonBaseUrl
                    .removePrefix("anilist://")
                    .removePrefix("jikan://")
                val hasAniListOnlyGenre = !genreArg.isNullOrBlank() && !genreArg.equals("All", ignoreCase = true)
                if (hasAniListOnlyGenre) {
                    val aniSort = if (endpoint.contains("top")) "SCORE_DESC" else "TRENDING_DESC"
                    ExploreHubClient.fetchAnilistShelfRow(sort = aniSort, genre = genreArg, page = page, perPage = 24)
                } else when (endpoint) {
                    "airing" -> AniListCatalogClient.fetchAiringNow(page)
                    "upcoming" -> AniListCatalogClient.fetchUpcoming(page)
                    "top_series" -> AniListCatalogClient.fetchTopSeries(page)
                    "top_movies" -> AniListCatalogClient.fetchTopMovies(page)
                    "popular" -> AniListCatalogClient.fetchPopular(page)
                    "all_time" -> AniListCatalogClient.fetchAllTime(page)
                    "era_2020s" -> AniListCatalogClient.fetchByEra(2020, 2029, page)
                    "era_2010s" -> AniListCatalogClient.fetchByEra(2010, 2019, page)
                    "genre_action" -> AniListCatalogClient.fetchByGenre(genreArg ?: "Action", page)
                    "genre_fantasy" -> AniListCatalogClient.fetchByGenre(genreArg ?: "Fantasy", page)
                    "trending" -> ExploreHubClient.fetchTrendingAnime(page = page, perPage = 24)
                    "top" -> ExploreHubClient.fetchAnilistShelfRow(sort = "SCORE_DESC", genre = genreArg, page = page, perPage = 24)
                    else -> {
                        val sort = if (cat.addonBaseUrl.contains("top")) "SCORE_DESC" else "TRENDING_DESC"
                        ExploreHubClient.fetchAnilistShelfRow(sort = sort, genre = genreArg, page = page, perPage = 24)
                    }
                }
            }
            cat.addonBaseUrl.startsWith("tmdb://collection/") -> {
                val page = (skip / 12) + 1
                val categoryKey = cat.addonBaseUrl.removePrefix("tmdb://collection/")
                val categoryName = when (categoryKey) {
                    "sagas" -> "Sagas"
                    "superheroes" -> "Superheroes"
                    "action" -> "Action"
                    "scifi" -> "Sci-Fi"
                    "animation" -> "Animation"
                    "horror" -> "Horror"
                    "crime" -> "Crime"
                    else -> genreArg ?: "All"
                }
                ExploreCollectionsHub.fetchCategoryShelf(categoryName, page)
            }
            else -> {
                ExploreCatalogClient.fetchCatalogItems(
                    baseUrl = cat.addonBaseUrl,
                    type = cat.type,
                    catalogId = cat.id,
                    genre = genreArg,
                    skip = skip,
                    queryParams = cat.queryParams,
                )
            }
        }
    }

    fun getPlatformDescriptors(platform: StreamingPlatform): List<ManifestCatalogDescriptor> {
        return if (platform == StreamingPlatform.CRUNCHYROLL) {
            listOf(
                ManifestCatalogDescriptor(
                    addonName = "Crunchyroll",
                    addonBaseUrl = "tmdb://platform/crunchyroll?sort=TRENDING_DESC",
                    type = "anime",
                    id = "cr_trending",
                    name = "Trending on Crunchyroll",
                ),
                ManifestCatalogDescriptor(
                    addonName = "Crunchyroll",
                    addonBaseUrl = "tmdb://platform/crunchyroll?sort=SCORE_DESC",
                    type = "anime",
                    id = "cr_top_rated",
                    name = "Top Rated Anime",
                ),
                ManifestCatalogDescriptor(
                    addonName = "Crunchyroll",
                    addonBaseUrl = "tmdb://platform/crunchyroll?sort=POPULARITY_DESC",
                    type = "anime",
                    id = "cr_popular",
                    name = "Most Popular Anime",
                ),
                ManifestCatalogDescriptor(
                    addonName = "Crunchyroll",
                    addonBaseUrl = "tmdb://platform/crunchyroll?sort=POPULARITY_DESC&genre=Action",
                    type = "anime",
                    id = "cr_action",
                    name = "Action & Adventure",
                ),
                ManifestCatalogDescriptor(
                    addonName = "Crunchyroll",
                    addonBaseUrl = "tmdb://platform/crunchyroll?sort=POPULARITY_DESC&genre=Fantasy",
                    type = "anime",
                    id = "cr_fantasy",
                    name = "Fantasy & Supernatural",
                ),
            )
        } else {
            listOf(
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=trending",
                    type = "movie",
                    id = "${platform.id}_trending",
                    name = "Trending on ${platform.displayName}",
                ),
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=new_movies",
                    type = "movie",
                    id = "${platform.id}_new_movies",
                    name = "New Movies",
                ),
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=new_series",
                    type = "series",
                    id = "${platform.id}_new_series",
                    name = "Popular Series",
                ),
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=top_rated",
                    type = "movie",
                    id = "${platform.id}_top_rated",
                    name = "Top Rated & Acclaimed",
                ),
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=action",
                    type = "movie",
                    id = "${platform.id}_action",
                    name = "Action & Adrenaline",
                ),
                ManifestCatalogDescriptor(
                    addonName = platform.displayName,
                    addonBaseUrl = "tmdb://platform/${platform.id}?row=drama",
                    type = "movie",
                    id = "${platform.id}_drama",
                    name = "Drama & Emotion",
                ),
            )
        }
    }
}
