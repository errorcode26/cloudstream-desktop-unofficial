package com.lagradost.cloudstream3.desktop.explore.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object AniListCatalogClient {
    private const val TAG = "AniListCatalogClient"
    private const val GRAPHQL_URL = "https://graphql.anilist.co"
    private val mapper = jacksonObjectMapper()

    suspend fun fetchAiringNow(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("POPULARITY_DESC"),
            status = "RELEASING",
            page = page,
        )

    suspend fun fetchUpcoming(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("POPULARITY_DESC"),
            status = "NOT_YET_RELEASED",
            page = page,
        )

    suspend fun fetchTopSeries(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("SCORE_DESC"),
            format = "TV",
            page = page,
        )

    suspend fun fetchTopMovies(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("SCORE_DESC"),
            format = "MOVIE",
            page = page,
        )

    suspend fun fetchPopular(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("POPULARITY_DESC"),
            page = page,
        )

    suspend fun fetchAllTime(page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("SCORE_DESC"),
            page = page,
        )

    suspend fun fetchByEra(startYear: Int, endYear: Int, page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("SCORE_DESC"),
            startDateGreater = startYear * 10000,
            startDateLesser = (endYear + 1) * 10000,
            page = page,
        )

    suspend fun fetchByGenre(genre: String, page: Int = 1): List<ExploreItem> =
        fetchAniList(
            sort = listOf("SCORE_DESC"),
            genre = genre,
            page = page,
        )

    private suspend fun fetchAniList(
        sort: List<String> = listOf("POPULARITY_DESC"),
        status: String? = null,
        format: String? = null,
        genre: String? = null,
        startDateGreater: Int? = null,
        startDateLesser: Int? = null,
        page: Int = 1,
        perPage: Int = 24,
    ): List<ExploreItem> = withContext(Dispatchers.IO) {
        val query = """
            query (
                ${'$'}page: Int,
                ${'$'}perPage: Int,
                ${'$'}sort: [MediaSort],
                ${'$'}status: MediaStatus,
                ${'$'}format: MediaFormat,
                ${'$'}genre: String,
                ${'$'}startDate_greater: FuzzyDateInt,
                ${'$'}startDate_lesser: FuzzyDateInt
            ) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(
                        type: ANIME,
                        sort: ${'$'}sort,
                        status: ${'$'}status,
                        format: ${'$'}format,
                        genre: ${'$'}genre,
                        startDate_greater: ${'$'}startDate_greater,
                        startDate_lesser: ${'$'}startDate_lesser
                    ) {
                        $ANILIST_MEDIA_SELECTION
                    }
                }
            }
        """.trimIndent()

        val variables = mutableMapOf<String, Any?>(
            "page" to page,
            "perPage" to perPage,
            "sort" to sort,
        ).apply {
            if (status != null) put("status", status)
            if (format != null) put("format", format)
            if (!genre.isNullOrBlank()) put("genre", genre)
            if (startDateGreater != null) put("startDate_greater", startDateGreater)
            if (startDateLesser != null) put("startDate_lesser", startDateLesser)
        }

        val jsonMap = mapOf(
            "query" to query,
            "variables" to variables,
        )
        val payload = mapper.writeValueAsString(jsonMap).toRequestBody("application/json".toMediaTypeOrNull())

        try {
            val response = app.post(
                GRAPHQL_URL,
                requestBody = payload,
                timeout = 10_000L,
                cacheTime = 60 * 6,
            )
            val root = mapper.readTree(response.text)
            val mediaArray = root["data"]?.get("Page")?.get("media")
            parseMediaItems(mediaArray, useMalPrefix = true)
        } catch (e: Exception) {
            AppLogger.e(TAG, "AniList fetch error: ${e.message}")
            emptyList()
        }
    }

    internal const val ANILIST_MEDIA_SELECTION =
        "id idMal title { english romaji native userPreferred } coverImage { extraLarge large } bannerImage averageScore seasonYear genres description(asHtml: false) format"

    internal fun JsonNode?.asNonBlankTextOrNull(): String? {
        if (this == null || isNull) return null
        return asText().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    internal fun resolvePreferredTitle(
        titleNode: JsonNode?,
        preferredLanguage: String = MetadataConfig.animeTitleLanguage.value,
    ): String? {
        val english = titleNode?.get("english").asNonBlankTextOrNull()
        val romaji = titleNode?.get("romaji").asNonBlankTextOrNull()
        val nativeTitle = titleNode?.get("native").asNonBlankTextOrNull()
        val userPreferred = titleNode?.get("userPreferred").asNonBlankTextOrNull()

        return when (preferredLanguage.trim().lowercase()) {
            "romaji" -> romaji ?: userPreferred ?: english ?: nativeTitle
            "native" -> nativeTitle ?: romaji ?: userPreferred ?: english
            else -> english ?: romaji ?: userPreferred ?: nativeTitle
        }
    }

    internal fun parseMediaItems(
        mediaArray: JsonNode?,
        useMalPrefix: Boolean = true,
        preferredLanguage: String = MetadataConfig.animeTitleLanguage.value,
    ): List<ExploreItem> {
        if (mediaArray == null || !mediaArray.isArray) return emptyList()

        val items = mutableListOf<ExploreItem>()
        for (node in mediaArray) {
            val idNode = node["id"]
            if (idNode == null || idNode.isNull) continue
            val anilistId = idNode.asInt()

            val idMalNode = node["idMal"]
            val idMal = if (idMalNode != null && !idMalNode.isNull) idMalNode.asInt().takeIf { it > 0 } else null

            val title = resolvePreferredTitle(node["title"], preferredLanguage) ?: continue

            val coverNode = node["coverImage"]
            val posterUrl = coverNode?.get("extraLarge").asNonBlankTextOrNull()
                ?: coverNode?.get("large").asNonBlankTextOrNull()
            val bannerUrl = node["bannerImage"].asNonBlankTextOrNull()
            val scoreNode = node["averageScore"]
            val score = if (scoreNode != null && !scoreNode.isNull) {
                scoreNode.asDouble().takeIf { it > 0.0 }?.div(10.0)
            } else {
                null
            }
            val year = node["seasonYear"].asNonBlankTextOrNull()
            val desc = node["description"].asNonBlankTextOrNull()
            val genres = node["genres"]?.mapNotNull { it.asNonBlankTextOrNull() } ?: emptyList()
            val itemFormat = node["format"].asNonBlankTextOrNull()?.lowercase() ?: "series"

            items.add(
                ExploreItem(
                    id = if (useMalPrefix && idMal != null) "mal:$idMal" else "anilist:$anilistId",
                    type = if (itemFormat == "movie") "movie" else "series",
                    name = title,
                    posterUrl = posterUrl,
                    backgroundUrl = bannerUrl,
                    releaseYear = year,
                    description = desc,
                    rating = score,
                    genres = genres,
                    anilistId = anilistId,
                    malId = idMal,
                ),
            )
        }
        return items
    }
}
