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
                    seasonYear
                    startDate { year month day }
                    title {
                        romaji
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

    fun buildSeasonQuery(id: Int): String = """
        query (${"$"}id: Int = $id) {
            Media (id: ${"$"}id, type: ANIME) {
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
            }
        }
    """.trimIndent()

    fun buildDataAboutIdQuery(id: Int): String = """
        query (${"$"}id: Int = $id) {
            Media (id: ${"$"}id, type: ANIME) {
                id
                episodes
                isFavourite
                mediaListEntry {
                    progress
                    status
                    score (format: POINT_100)
                }
                title {
                    english
                    romaji
                }
            }
        }
    """.trimIndent()

    fun buildFullListQuery(userId: Int, mediaType: String = "ANIME"): String = """
        query (${"$"}userID: Int = $userId, ${"$"}MEDIA: MediaType = $mediaType) {
            MediaListCollection (userId: ${"$"}userID, type: ${"$"}MEDIA) { 
                lists {
                    status
                    entries {
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
                            episodes
                            chapters
                            title {
                                english
                                romaji
                            }
                            coverImage { extraLarge large medium }
                            synonyms
                            nextAiringEpisode {
                                timeUntilAiring
                                episode
                            }
                        }
                    }
                }
            }
        }
    """.trimIndent()

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
    ): String {
        val scoreArg = if (score != null) ", ${"$" }scoreRaw: Int = ${score.toInt(100)}" else ""
        val progressArg = if (progress != null) ", ${"$"}progress: Int = $progress" else ""
        val scoreField = if (score != null) ", scoreRaw: ${"$"}scoreRaw" else ""
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
