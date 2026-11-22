package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor

/**
 * Built-in parametric and curated catalog descriptors for TMDB,
 * AniList, streaming networks, and collections.
 */
internal object ExploreNativeCatalogs {

    fun buildNativeCatalogs(): List<ManifestCatalogDescriptor> {
        val discovered = mutableListOf<ManifestCatalogDescriptor>()

        // Native Curated Movie Feeds (TMDB Parametric Discovery)
        val movieGenres = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western")
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://movie/in_theaters",
                type = "movie",
                id = "tmdb_in_theaters",
                name = "In Theaters Now",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://movie/critics_picks",
                type = "movie",
                id = "tmdb_critics_picks",
                name = "Critics' Picks",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://movie/hidden_gems",
                type = "movie",
                id = "tmdb_hidden_gems",
                name = "Hidden Gems",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://movie/under_ninety",
                type = "movie",
                id = "tmdb_under_ninety",
                name = "Quick Watches (Under 90)",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Decades",
                addonBaseUrl = "tmdb://movie/decade_2010s",
                type = "movie",
                id = "tmdb_decade_2010s",
                name = "Defining the 2010s",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Decades",
                addonBaseUrl = "tmdb://movie/decade_90s",
                type = "movie",
                id = "tmdb_decade_90s",
                name = "Essential 90s",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Decades",
                addonBaseUrl = "tmdb://movie/decade_80s",
                type = "movie",
                id = "tmdb_decade_80s",
                name = "80s Classics",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "World Cinema",
                addonBaseUrl = "tmdb://movie/lang_jp",
                type = "movie",
                id = "tmdb_lang_jp",
                name = "Japanese Cinema",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "World Cinema",
                addonBaseUrl = "tmdb://movie/lang_kr",
                type = "movie",
                id = "tmdb_lang_kr",
                name = "Korean Cinema",
                genres = movieGenres,
                supportsSearch = false,
            ),
        )

        // Native Curated TV Series Feeds (TMDB Parametric Discovery & Networks)
        val tvGenres = listOf("Action & Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Kids", "Mystery", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics", "Western")
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://series/trending",
                type = "series",
                id = "tmdb_series_trending",
                name = "Trending Shows This Week",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Featured",
                addonBaseUrl = "tmdb://series/airing_today",
                type = "series",
                id = "tmdb_series_airing_today",
                name = "On Tonight / Airing Today",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_hbo",
                type = "series",
                id = "tmdb_net_hbo",
                name = "From HBO",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_netflix",
                type = "series",
                id = "tmdb_net_netflix",
                name = "Netflix Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_apple",
                type = "series",
                id = "tmdb_net_apple",
                name = "Apple TV+ Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_disney",
                type = "series",
                id = "tmdb_net_disney",
                name = "Disney+ Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_amazon",
                type = "series",
                id = "tmdb_net_amazon",
                name = "Prime Video Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_fx",
                type = "series",
                id = "tmdb_net_fx",
                name = "FX Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Networks",
                addonBaseUrl = "tmdb://series/net_amc",
                type = "series",
                id = "tmdb_net_amc",
                name = "AMC Originals",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Spotlight",
                addonBaseUrl = "tmdb://series/prestige_drama",
                type = "series",
                id = "tmdb_prestige_drama",
                name = "Prestige Drama",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Spotlight",
                addonBaseUrl = "tmdb://series/kdrama",
                type = "series",
                id = "tmdb_kdrama",
                name = "Korean Dramas & Romances",
                genres = tvGenres,
                supportsSearch = false,
            ),
        )

        // Native Curated Anime Feeds (AniList & Jikan Engines)
        val animeGenres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mecha", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://airing",
                type = "anime",
                id = "mal_airing",
                name = "Currently Airing",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://trending",
                type = "anime",
                id = "mal_trending",
                name = "Trending Now",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://top",
                type = "anime",
                id = "mal_top",
                name = "All-Time Highest Rated",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://popular",
                type = "anime",
                id = "mal_popular",
                name = "Most Popular Anime",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://upcoming",
                type = "anime",
                id = "mal_upcoming",
                name = "Anticipated Upcoming",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://top_movies",
                type = "anime",
                id = "mal_top_movies",
                name = "Acclaimed Anime Films",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://era_2020s",
                type = "anime",
                id = "mal_era_2020s",
                name = "2020s Anime",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://era_2010s",
                type = "anime",
                id = "mal_era_2010s",
                name = "2010s Classics",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://genre_action",
                type = "anime",
                id = "mal_genre_action",
                name = "Action & Adventure",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "AniList",
                addonBaseUrl = "anilist://genre_fantasy",
                type = "anime",
                id = "mal_genre_fantasy",
                name = "Fantasy & Isekai",
                genres = animeGenres,
                supportsSearch = false,
            ),
        )

        // Native Curated Franchise & Sagas Feeds
        val collectionCategories = listOf("Sagas", "Superheroes", "Action", "Sci-Fi", "Animation", "Horror", "Crime")
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/sagas",
                type = "collections",
                id = "tmdb_coll_sagas",
                name = "Epic Sagas & Universes",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/superheroes",
                type = "collections",
                id = "tmdb_coll_superheroes",
                name = "Superhero Universes",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/action",
                type = "collections",
                id = "tmdb_coll_action",
                name = "Action & Adrenaline Franchises",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/scifi",
                type = "collections",
                id = "tmdb_coll_scifi",
                name = "Sci-Fi & Cyberpunk Universes",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/animation",
                type = "collections",
                id = "tmdb_coll_animation",
                name = "Animated Sagas & Family Universes",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/horror",
                type = "collections",
                id = "tmdb_coll_horror",
                name = "Horror & Dark Universes",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        discovered.add(
            ManifestCatalogDescriptor(
                addonName = "Franchises",
                addonBaseUrl = "tmdb://collection/crime",
                type = "collections",
                id = "tmdb_coll_crime",
                name = "Crime, Heists & Mob Classics",
                genres = collectionCategories,
                supportsSearch = false,
                posterShape = "landscape",
            ),
        )
        return discovered.map { it.copy(isBuiltIn = true) }
    }
}
