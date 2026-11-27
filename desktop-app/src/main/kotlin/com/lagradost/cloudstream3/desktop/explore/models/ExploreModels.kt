package com.lagradost.cloudstream3.desktop.explore.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin

data class ExploreItem(
    val id: String,
    val type: String,
    val name: String,
    val posterUrl: String?,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
    val releaseYear: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val posterShape: String? = null,
    val providerName: String? = null,
    val itemUrl: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
) {
    fun toCanonicalSeed(origin: MediaSeedOrigin = MediaSeedOrigin.EXPLORE): CanonicalMediaSeed? {
        val parsedYear = releaseYear?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return CanonicalMediaSeed.fromCanonicalId(
            rawId = id,
            anilistId = anilistId,
            malId = malId,
            tmdbId = tmdbId,
            imdbId = imdbId,
            expectedTitle = name,
            expectedYear = parsedYear,
            mediaType = type,
            origin = origin,
        )
    }
}

enum class ExploreSearchScope(val label: String) {
    ALL("All Sources"),
    ADDONS("Addons Only"),
    PLUGINS("Plugins Only"),
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestCatalogDescriptor(
    val addonName: String = "",
    val addonBaseUrl: String = "",
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val genre: String? = null,
    val genres: List<String> = emptyList(),
    val supportsSearch: Boolean = false,
    val posterShape: String? = null,
    val queryParams: String = "",
    val isBuiltIn: Boolean = false,
) {
    val key: String
        get() = "$addonName:$type:$id:${genre.orEmpty()}"
}

data class ProviderMatch(
    val providerName: String,
    val searchResponse: SearchResponse,
    val displayTitle: String,
    val qualityText: String? = null,
    val hasSub: Boolean = false,
    val hasDub: Boolean = false,
)
