package com.lagradost.cloudstream3.syncproviders.providers.anilist

import com.lagradost.cloudstream3.Score

object AniListQueries {

    val VIEWER_QUERY: String = """
        {
            Viewer {
                id
                name
                avatar {
                    large
                }
                favourites {
                    anime {
                        nodes {
                            id
                        }
                    }
                }
            }
        }
    """.trimIndent()

    val SEARCH_QUERY: String = """
        query (${"$"}id: Int, ${"$"}page: Int, ${"$"}search: String, ${"$"}type: MediaType) {
            Page (page: ${"$"}page, perPage: 10) {
                media (id: ${"$"}id, search: ${"$"}search, type: ${"$"}type) {
                    id
                    idMal
                    format
                    status
                    episodes
                    seasonYear
                    startDate { year month day }
                    title {
                        romaji
                        english
                        userPreferred
                    }
                    averageScore
                    meanScore
                    nextAiringEpisode {
                        timeUntilAiring
                        episode
                    }
                    trailer { id site thumbnail }
                    bannerImage
                    recommendations {
                        nodes {
                            id
                            mediaRecommendation {
                                id
                                title {
                                    english
                                    romaji
                                }
                                idMal
                                coverImage { medium large extraLarge }
                                averageScore
                            }
                        }
                    }
                    relations {
                        edges {
                            id
                            relationType(version: 2)
                            node {
                                format
                                id
                                idMal
                                coverImage { medium large extraLarge }
                                averageScore
                                title {
                                    english
                                    romaji
                                }
                            }
                        }
                    }
                }
            }
        }
    """.trimIndent()

    private const val SEASON_MEDIA_FIELDS: String = """
                id
                idMal
                coverImage {
                    extraLarge
                    large
                    medium
                    color
                }
                title {
                    romaji
                    english
                    native
                    userPreferred
                }
                duration
                episodes
                seasonYear
                status
                genres
                synonyms
                averageScore
                isAdult
                description(asHtml: false)
                characters(sort: ROLE page: 1 perPage: 20) {
                    edges {
                        role
                        voiceActors {
                            name {
                                userPreferred
                                full
                                native
                            }
                            age
                            image {
                                large
                                medium
                            }
                        }
                        node {
                            name {
                                userPreferred
                                full
                                native
                            }
                            age
                            image {
                                large
                                medium
                            }
                        }
                    }
                }
                trailer {
                    id
                    site
                    thumbnail
                }
                relations {
                    edges {
                        id
                        relationType(version: 2)
                        node {
                            id
                            format
                            seasonYear
                            episodes
                            title {
                                romaji
                                userPreferred
                            }
                            coverImage {
                                extraLarge
                                large
                                medium
                                color
                            }
                        }
                    }
                }
                recommendations {
                    edges {
                        node {
                            mediaRecommendation {
                                id
                                coverImage {
                                    extraLarge
                                    large
                                    medium
                                    color
                                }
                                title {
                                    romaji
                                    english
                                    native
                                    userPreferred
                                }
                            }
                        }
                    }
                }
                nextAiringEpisode {
                    timeUntilAiring
                    episode
                }
                format
    """

    fun buildSeasonQuery(id: Int): String = """
        query (${"$"}id: Int = $id) {
            Media (id: ${"$"}id, type: ANIME) {
                $SEASON_MEDIA_FIELDS
            }
        }
    """.trimIndent()

    fun buildSeasonByMalIdQuery(malId: Int): String = """
        query (${"$"}idMal: Int = $malId) {
            Media (idMal: ${"$"}idMal, type: ANIME) {
                $SEASON_MEDIA_FIELDS
            }
        }
    """.trimIndent()

    fun buildDataAboutIdQuery(id: Int): String = """
        query (${"$"}id: Int = $id) {
            Media (id: ${"$"}id, type: ANIME) {
                id
                episodes
                format
                status
                seasonYear
                isFavourite
                coverImage {
                    extraLarge
                    large
                    medium
                }
                nextAiringEpisode {
                    timeUntilAiring
                    episode
                }
                mediaListEntry {
                    progress
                    status
                    score (format: POINT_100)
                }
                title {
                    english
                    romaji
                    userPreferred
                }
            }
        }
    """.trimIndent()

    private const val MEDIA_LIST_ENTRY_FIELDS: String = """
                        status
                        completedAt { year month day }
                        startedAt { year month day }
                        updatedAt
                        progress
                        score (format: POINT_100)
                        private
                        media {
                            id
                            idMal
                            season
                            seasonYear
                            format
                            status
                            isFavourite
                            averageScore
                            episodes
                            chapters
                            title {
                                english
                                romaji
                                userPreferred
                            }
                            coverImage { extraLarge large medium }
                            synonyms
                            nextAiringEpisode {
                                timeUntilAiring
                                episode
                            }
                        }
    """

    fun buildFullListQuery(
        userId: Int,
        mediaType: String = "ANIME",
        chunk: Int? = null,
        perChunk: Int = 500,
    ): String {
        val chunkArgs = if (chunk != null) ", chunk: $chunk, perChunk: ${perChunk.coerceIn(1, 500)}" else ""
        return """
        query (${"$"}userID: Int = $userId, ${"$"}MEDIA: MediaType = $mediaType) {
            MediaListCollection (userId: ${"$"}userID, type: ${"$"}MEDIA$chunkArgs) { 
                hasNextChunk
                lists {
                    status
                    entries {
                        $MEDIA_LIST_ENTRY_FIELDS
                    }
                }
            }
        }
        """.trimIndent()
    }

    fun buildPagedListQuery(
        userId: Int,
        page: Int,
        perPage: Int,
        graphQlStatus: String? = null,
        sortGraphQl: String = "UPDATED_TIME_DESC",
        includeStatistics: Boolean = false,
    ): String {
        val statusArg = graphQlStatus?.let { ", status: $it" }.orEmpty()
        val statsBlock = if (includeStatistics) {
            """
            User (id: $userId) {
                statistics {
                    anime {
                        count
                        statuses {
                            status
                            count
                        }
                    }
                }
            }
            """.trimIndent()
        } else {
            ""
        }
        return """
        query {
            Page (page: ${page.coerceAtLeast(1)}, perPage: ${perPage.coerceIn(1, 100)}) {
                pageInfo {
                    total
                    currentPage
                    lastPage
                    hasNextPage
                }
                mediaList (userId: $userId, type: ANIME$statusArg, sort: [$sortGraphQl]) {
                    $MEDIA_LIST_ENTRY_FIELDS
                }
            }
            $statsBlock
        }
        """.trimIndent()
    }

    fun buildToggleLikeMutation(id: Int): String = """
        mutation (${"$"}animeId: Int = $id) {
            ToggleFavourite (animeId: ${"$"}animeId) {
                anime {
                    nodes {
                        id
                        title {
                            romaji
                        }
                    }
                }
            }
        }
    """.trimIndent()

    fun buildMediaListIdQuery(userId: Int, mediaId: Int): String = """
        query MediaList(${"$"}userId: Int = $userId, ${"$"}mediaId: Int = $mediaId) {
            MediaList(userId: ${"$"}userId, mediaId: ${"$"}mediaId) {
                id
            }
        }
    """.trimIndent()

    fun buildDeleteMediaListEntryMutation(listId: Long): String = """
        mutation(${"$"}id: Int = $listId) {
            DeleteMediaListEntry(id: ${"$"}id) {
                deleted
            }
        }
    """.trimIndent()

    fun buildSaveMediaListEntryMutation(
        id: Int,
        status: String,
        score: Score?,
        progress: Int?,
        clearScore: Boolean = false,
    ): String {
        val rawScore = when {
            score != null -> score.toInt(100)
            clearScore -> 0
            else -> null
        }
        val scoreArg = if (rawScore != null) ", ${"$"}scoreRaw: Int = $rawScore" else ""
        val progressArg = if (progress != null) ", ${"$"}progress: Int = $progress" else ""
        val scoreField = if (rawScore != null) ", scoreRaw: ${"$"}scoreRaw" else ""
        val progressField = if (progress != null) ", progress: ${"$"}progress" else ""
        return """
            mutation (${"$"}id: Int = $id, ${"$"}status: MediaListStatus = $status$scoreArg$progressArg) {
                SaveMediaListEntry (mediaId: ${"$"}id, status: ${"$"}status$scoreField$progressField) {
                    id
                    status
                    progress
                    score
                }
            }
        """.trimIndent()
    }
}
