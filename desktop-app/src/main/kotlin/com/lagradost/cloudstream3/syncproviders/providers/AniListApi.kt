package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.providers.anilist.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import java.util.Locale

class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "anilist"

    private val key = ""
    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val icon = 0
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    override fun loginRequest(): AuthLoginPage =
        AuthLoginPage("https://anilist.co/api/v2/oauth/authorize?client_id=$key&response_type=token")

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken {
        val sanitizer = splitRedirectUrl(redirectUrl)
        return AuthToken(
            accessToken = sanitizer["access_token"]
                ?: throw ErrorLoadingException("No access token"),
            accessTokenLifetime = APIHolder.unixTime + (sanitizer["expires_in"]?.toLongOrNull() ?: 31536000L),
        )
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        return super.refreshToken(token)
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null)
            ?: throw ErrorLoadingException("Unable to fetch user data")

        return AuthUser(
            id = user.id,
            name = user.name,
            profilePicture = user.picture,
        )
    }

    override fun urlToId(url: String): String =
        url.removePrefix("$mainUrl/anime/").removeSuffix("/")

    private fun getUrlFromId(id: Int): String {
        return "$mainUrl/anime/$id"
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        val data = searchShows(query) ?: return null
        return data.data?.page?.media?.mapNotNull {
            val title = it.title.romaji ?: return@mapNotNull null
            SyncAPI.SyncSearchResult(
                title,
                this.name,
                it.id.toString(),
                getUrlFromId(it.id),
                it.bannerImage,
            )
        }
    }

    override suspend fun load(auth: AuthData?, id: String): SyncAPI.SyncResult {
        val internalId = (
            Regex("anilist\\.co/anime/(\\d*)").find(id)?.groupValues?.getOrNull(1)
                ?: id
        ).toIntOrNull() ?: throw ErrorLoadingException("Invalid internalId")
        val season = getSeason(internalId).data.media
        return SyncAPI.SyncResult(
            season.id.toString(),
            nextAiring = season.nextAiringEpisode?.let {
                NextAiring(
                    it.episode ?: return@let null,
                    (it.timeUntilAiring ?: return@let null) + APIHolder.unixTime,
                )
            },
            title = season.title?.userPreferred,
            synonyms = season.synonyms,
            isAdult = season.isAdult,
            totalEpisodes = season.episodes,
            synopsis = season.description,
            actors = season.characters?.edges?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                ActorData(
                    actor = Actor(
                        name = node.name?.userPreferred ?: node.name?.full ?: node.name?.native
                            ?: return@mapNotNull null,
                        image = node.image?.large ?: node.image?.medium,
                    ),
                    role = when (edge.role) {
                        "MAIN" -> ActorRole.Main
                        "SUPPORTING" -> ActorRole.Supporting
                        "BACKGROUND" -> ActorRole.Background
                        else -> null
                    },
                    voiceActor = edge.voiceActors?.firstNotNullOfOrNull { staff ->
                        Actor(
                            name = staff.name?.userPreferred ?: staff.name?.full
                                ?: staff.name?.native
                                ?: return@mapNotNull null,
                            image = staff.image?.large ?: staff.image?.medium,
                        )
                    },
                )
            },
            publicScore = Score.from100(season.averageScore),
            recommendations = season.recommendations?.edges?.mapNotNull { rec ->
                val recMedia = rec.node.mediaRecommendation
                SyncAPI.SyncSearchResult(
                    name = recMedia?.title?.userPreferred ?: return@mapNotNull null,
                    this.name,
                    recMedia.id?.toString() ?: return@mapNotNull null,
                    getUrlFromId(recMedia.id),
                    recMedia.coverImage?.extraLarge ?: recMedia.coverImage?.large
                        ?: recMedia.coverImage?.medium,
                )
            },
            trailers = when (season.trailer?.site?.lowercase()?.trim()) {
                "youtube" -> listOf("https://www.youtube.com/watch?v=${season.trailer.id}")
                else -> null
            },
        )
    }

    override suspend fun status(auth: AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        return null
    }

    override suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus,
    ): Boolean {
        return false
    }

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        return null
    }

    suspend fun getFullAniListList(auth: AuthData): FullAnilistList? {
        val query = AniListQueries.buildFullListQuery(auth.user.id)
        val text = postApi(auth.token, query) ?: return null
        return tryParseJson<FullAnilistList>(text)
    }

    suspend fun toggleLike(auth: AuthData, id: Int): Boolean {
        val q = AniListQueries.buildToggleLikeMutation(id)
        val data = postApi(auth.token, q)
        return !data.isNullOrBlank()
    }

    suspend fun getDataAboutId(auth: AuthData, id: Int): AniListTitleHolder? {
        val q = AniListQueries.buildDataAboutIdQuery(id)
        val data = postApi(auth.token, q, true)
        val d = parseJson<GetDataRoot>(data ?: return null)

        val main = d.data?.media
        return if (main?.mediaListEntry != null) {
            AniListTitleHolder(
                title = main.title,
                id = id,
                isFavourite = main.isFavourite,
                progress = main.mediaListEntry.progress,
                episodes = main.episodes,
                score = main.mediaListEntry.score,
                type = fromIntToAnimeStatus(aniListStatusString.indexOf(main.mediaListEntry.status)),
            )
        } else {
            AniListTitleHolder(
                title = main?.title,
                id = id,
                isFavourite = main?.isFavourite,
                progress = 0,
                episodes = main?.episodes,
                score = 0,
                type = AniListStatusType.None,
            )
        }
    }

    suspend fun postDataAboutId(
        auth: AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?,
    ): Boolean {
        val q = if (type == AniListStatusType.None) {
            val idQuery = AniListQueries.buildMediaListIdQuery(auth.user.id, id)
            val response = postApi(auth.token, idQuery)
            val listId = tryParseJson<MediaListItemRoot>(response ?: return false)?.data?.mediaList?.id ?: return false
            AniListQueries.buildDeleteMediaListEntryMutation(listId)
        } else {
            AniListQueries.buildSaveMediaListEntryMutation(id, type.name, score, progress)
        }

        val data = postApi(auth.token, q)
        return !data.isNullOrBlank()
    }

    private suspend fun getUser(token: AuthToken): AniListUser? {
        val data = postApi(token, AniListQueries.VIEWER_QUERY)
        if (data.isNullOrBlank()) return null
        val userData = parseJson<AniListRoot>(data)
        val u = userData.data?.viewer ?: return null
        return AniListUser(
            u.id,
            u.name,
            u.avatar?.large,
        )
    }

    suspend fun getAllSeasons(id: Int): List<SeasonResponse?> {
        val seasons = mutableListOf<SeasonResponse?>()
        suspend fun getSeasonRecursive(id: Int) {
            val season = getSeason(id)
            seasons.add(season)
            if (season.data.media.format?.startsWith("TV") == true) {
                season.data.media.relations?.edges?.forEach {
                    if (it.node?.format != null) {
                        if (it.relationType == "SEQUEL" && it.node.format.startsWith("TV")) {
                            getSeasonRecursive(it.node.id)
                            return@forEach
                        }
                    }
                }
            }
        }
        getSeasonRecursive(id)
        return seasons.toList()
    }

    private suspend fun postApi(token: AuthToken, q: String, cache: Boolean = false): String? {
        return app.post(
            "https://graphql.anilist.co/",
            headers = mapOf(
                "Authorization" to "Bearer ${token.accessToken ?: return null}",
                if (cache) "Cache-Control" to "max-stale=$MAX_STALE" else "Cache-Control" to "no-cache",
            ),
            cacheTime = 0,
            data = mapOf(
                "query" to URLEncoder.encode(q, "UTF-8"),
            ),
            timeout = 5,
        ).text.replace("\\/", "/")
    }

    companion object {
        const val MAX_STALE = 60 * 10
        val aniListStatusString =
            arrayOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")

        const val ANILIST_CACHED_LIST: String = "anilist_cached_list"

        fun fromIntToAnimeStatus(inp: Int): AniListStatusType = AniListStatusType.fromInt(inp)

        private fun fixName(name: String): String {
            return name.lowercase(Locale.ROOT).replace(" ", "")
                .replace("[^a-zA-Z0-9]".toRegex(), "")
        }

        suspend fun searchShows(name: String): GetSearchRoot? {
            try {
                val data = mapOf(
                    "query" to AniListQueries.SEARCH_QUERY,
                    "variables" to Variables(
                        search = name,
                        page = 1,
                        type = "ANIME",
                    ).toJson(),
                )

                val res = app.post(
                    "https://graphql.anilist.co/",
                    data = data,
                    timeout = 5000,
                ).text.replace("\\", "")
                return parseJson<GetSearchRoot>(res)
            } catch (e: Exception) {
                logError(e)
            }
            return null
        }

        suspend fun getShowId(malId: String?, name: String, year: Int?): GetSearchMedia? {
            val blackList = listOf(
                "TV Dubbed",
                "(Dub)",
                "Subbed",
                "(TV)",
                "(Uncensored)",
                "(Censored)",
                "(\\d+)",
            )
            val blackListRegex = Regex(
                """ (${blackList.joinToString(separator = "|").replace("(", "\\(").replace(")", "\\)")})""",
            )
            val shows = searchShows(name.replace(blackListRegex, ""))

            shows?.data?.page?.media?.find {
                (malId ?: "NONE") == it.idMal.toString()
            }?.let { return it }

            val filtered = shows?.data?.page?.media?.filter {
                (it.startDate.year ?: year.toString()) == year.toString() || year == null
            }
            filtered?.forEach {
                it.title.romaji?.let { romaji ->
                    if (fixName(romaji) == fixName(name)) return it
                }
            }

            return filtered?.firstOrNull()
        }

        suspend fun getSeason(id: Int): SeasonResponse {
            val q = AniListQueries.buildSeasonQuery(id)
            val data = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to q),
                cacheTime = 0,
            ).text

            return tryParseJson<SeasonResponse>(data) ?: throw ErrorLoadingException("Error parsing $data")
        }
    }
}
