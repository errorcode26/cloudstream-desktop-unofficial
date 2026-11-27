package com.lagradost.cloudstream3.desktop.ui.navigation

import com.lagradost.cloudstream3.ActorData

sealed class Config {
    data object Home : Config()
    data object Explore : Config()
    data object History : Config()
    data object Search : Config()
    data class Extensions(val initialTab: Int = 0) : Config()
    data object Library : Config()
    data object Downloads : Config()
    data object Settings : Config()
    data class Details(
        val providerName: String,
        val url: String,
        val preloadedName: String? = null,
        val preloadedPoster: String? = null,
        val preloadedBg: String? = null,
        val autoPlay: Boolean = false,
        val targetSeason: Int? = null,
        val targetEpisodeId: String? = null,
        val canonicalSeed: com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed? = null,
    ) : Config()
    data class CategoryGrid(
        val providerName: String,
        val title: String,
    ) : Config()
    data class Person(
        val name: String,
        val image: String? = null,
        val tmdbId: Int? = null,
    ) : Config()
    data class Studio(
        val name: String,
        val companyId: Int? = null,
        val logoUrl: String? = null,
        val originCountry: String? = null,
        val isNetwork: Boolean = false,
    ) : Config()
    data class FullCast(
        val mediaTitle: String,
        val providerName: String? = null,
        val cast: List<ActorData> = emptyList(),
        val directors: List<ActorData> = emptyList(),
        val writers: List<ActorData> = emptyList(),
        val producers: List<ActorData> = emptyList(),
        val tmdbId: Int? = null,
        val availableSeasons: List<Int> = emptyList(),
        val initialSeason: Int? = null,
    ) : Config()
    data class Links(
        val providerName: String,
        val dataUrl: String,
        val history: com.lagradost.common.storage.WatchHistory,
        val loadResponse: com.lagradost.cloudstream3.LoadResponse? = null,
        val enrichedActors: List<ActorData>? = null,
        val enrichedLogoUrl: String? = null,
        val enrichedBackdropUrl: String? = null,
        val canonicalSeed: com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed? = null,
        val resolvedAnilistId: Int? = null,
        val resolvedMalId: Int? = null,
        val resolvedYear: Int? = null,
        val hasMultipleSeasons: Boolean = false,
    ) : Config()
    data class ProfileEdit(
        val profileId: Int? = null,
        val switchOnCreate: Boolean = false,
    ) : Config()
}
