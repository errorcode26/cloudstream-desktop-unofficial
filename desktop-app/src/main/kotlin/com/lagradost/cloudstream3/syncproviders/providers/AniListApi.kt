package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.tracking.AniListOAuthFlow
import com.lagradost.cloudstream3.desktop.tracking.TrackerPropertiesConfig
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimitException
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimiter
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.providers.anilist.*
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopTrackerStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "anilist"

    private val key: String by lazy { loadBakedClientId() }
    private val bakedClientSecret: String by lazy {
        TrackerPropertiesConfig.bakedProperty(TrackerPropertiesConfig.PROP_ANILIST_CLIENT_SECRET)
    }
    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val icon = 0
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    fun customClientId(): String =
        TrackerPropertiesConfig.customClientId(DesktopTrackerStore.KEY_TRACKER_ANILIST_CUSTOM_CLIENT_ID)

    fun setCustomClientId(clientId: String) =
        TrackerPropertiesConfig.setCustomClientId(DesktopTrackerStore.KEY_TRACKER_ANILIST_CUSTOM_CLIENT_ID, clientId)

    fun effectiveClientId(): String = customClientId().ifBlank { key }

    fun customClientSecret(): String =
        TrackerPropertiesConfig.customClientId(TrackerPropertiesConfig.KEY_TRACKER_ANILIST_CUSTOM_CLIENT_SECRET)

    fun setCustomClientSecret(secret: String) =
        TrackerPropertiesConfig.setCustomClientId(TrackerPropertiesConfig.KEY_TRACKER_ANILIST_CUSTOM_CLIENT_SECRET, secret)

    fun effectiveClientSecret(): String = customClientSecret().ifBlank { bakedClientSecret }

    fun hasBakedClientId(): Boolean = key.isNotBlank()

    fun hasClientId(): Boolean = effectiveClientId().isNotBlank()

    override fun loginRequest(): AuthLoginPage =
        AuthLoginPage(buildAuthorizeUrl(redirectUri = null))

    fun buildAuthorizeUrl(redirectUri: String? = AniListOAuthFlow.LOOPBACK_REDIRECT_URI): String {
        val encodedClientId = URLEncoder.encode(effectiveClientId(), StandardCharsets.UTF_8)
        val base = "https://anilist.co/api/v2/oauth/authorize?client_id=$encodedClientId&response_type=code"
        return if (redirectUri.isNullOrBlank()) {
            base
        } else {
            "$base&redirect_uri=${URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)}"
        }
    }

    suspend fun exchangeCodeForToken(
        code: String,
        redirectUri: String = AniListOAuthFlow.LOOPBACK_REDIRECT_URI,
    ): Result<AuthToken> = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("AniList Client ID is not configured."))
        }
        val clientSecret = effectiveClientSecret()
        if (clientSecret.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("AniList Client Secret is not configured. Expand Advanced below to set your Client Secret."),
            )
        }
        try {
            val bodyJson = mapper.writeValueAsString(
                buildMap {
                    put("grant_type", "authorization_code")
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                    put("redirect_uri", redirectUri)
                    put("code", code)
                },
            ).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val response = app.post(
                oauthTokenUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
                requestBody = bodyJson,
                cacheTime = 0,
                timeout = 12_000L,
            )
            if (response.code !in 200..299) {
                AppLogger.w(TAG, "AniList token exchange HTTP ${response.code}: ${response.text.take(200)}")
            }
            parseTokenResponseJson(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "AniList token exchange failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun exchangeCodeAndAuthorize(
        redirectOrCode: String,
        defaultRedirectUri: String = AniListOAuthFlow.LOOPBACK_REDIRECT_URI,
    ): Result<AuthData> {
        val trimmed = redirectOrCode.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("No authorization code or redirect URL provided."))
        }
        val params = splitRedirectUrl(trimmed)
        val oauthError = params["error"]
        if (!oauthError.isNullOrBlank()) {
            val desc = params["error_description"] ?: params["message"] ?: oauthError
            return Result.failure(IllegalStateException("AniList authorization error: $desc"))
        }
        val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: trimmed.takeIf { it.startsWith("code=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.substringBefore('&')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: trimmed.takeIf { !it.contains("://") && !it.contains('?') && !it.contains(' ') && !it.contains('.') }
            ?: return Result.failure(IllegalArgumentException("No authorization code found in input."))

        val redirectUri = trimmed.substringBefore('?').substringBefore('#')
            .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: defaultRedirectUri

        val token = exchangeCodeForToken(code = code, redirectUri = redirectUri).getOrElse { err ->
            return Result.failure(err)
        }
        return verifyTokenAndBuildAuthData(token)
    }

    /**
     * Parses a raw token, Bearer header, or #access_token redirect URL, verifies it against
     * AniList's Viewer query, and returns full AuthData ready for AccountManager storage.
     */
    suspend fun authorizeWithToken(rawInput: String): Result<AuthData> {
        val authToken = parseTokenInput(rawInput)
            ?: return Result.failure(IllegalArgumentException("No valid access token found in input"))
        return verifyTokenAndBuildAuthData(authToken)
    }

    private suspend fun verifyTokenAndBuildAuthData(authToken: AuthToken): Result<AuthData> = try {
        val (user, errorDetail) = getUserDetailed(authToken)
        if (user == null) {
            val reason = errorDetail?.takeIf { it.isNotBlank() }
                ?: "AniList rejected the token or Viewer query returned no user"
            Result.failure(IllegalStateException(reason))
        } else {
            Result.success(
                AuthData(
                    user = AuthUser(
                        id = user.id,
                        name = user.name,
                        profilePicture = user.picture,
                    ),
                    token = authToken,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logError(e)
        Result.failure(e)
    }

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val sanitizer = splitRedirectUrl(redirectUrl)
        val accessToken = sanitizer["access_token"]
        if (!accessToken.isNullOrBlank()) {
            return AuthToken(
                accessToken = accessToken,
                accessTokenLifetime = APIHolder.unixTime + (sanitizer["expires_in"]?.toLongOrNull() ?: 31536000L),
            )
        }
        val code = sanitizer["code"] ?: return null
        val redirectUri = redirectUrl.substringBefore('?').substringBefore('#')
            .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: payload?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: AniListOAuthFlow.LOOPBACK_REDIRECT_URI
        return exchangeCodeForToken(code = code, redirectUri = redirectUri).getOrNull()
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? = withContext(Dispatchers.IO) {
        val refresh = token.refreshToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val clientId = effectiveClientId()
        val clientSecret = effectiveClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null
        try {
            val bodyJson = mapper.writeValueAsString(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refresh,
                ),
            ).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val response = app.post(
                oauthTokenUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
                requestBody = bodyJson,
                cacheTime = 0,
                timeout = 12_000L,
            )
            parseTokenResponseJson(response.text, fallbackRefreshToken = refresh).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "AniList refreshToken failed: ${e.message}")
            null
        }
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
        val safeAuth = auth ?: return null
        val numericId = parseAnimeId(id) ?: return null
        val holder = getDataAboutId(safeAuth, numericId, allowCache = false) ?: return null
        val watchType = holder.type?.toSyncWatchType() ?: SyncWatchType.NONE
        return SyncAPI.SyncStatus(
            status = watchType,
            score = holder.score?.let { Score.from100(it) },
            watchedEpisodes = holder.progress,
            isFavorite = holder.isFavourite,
            maxEpisodes = holder.episodes,
        )
    }

    override suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus,
    ): Boolean {
        val safeAuth = auth ?: return false
        val numericId = parseAnimeId(id) ?: return false
        return postDataAboutId(
            auth = safeAuth,
            id = numericId,
            type = newStatus.status.toAniListStatusType(),
            score = newStatus.score,
            progress = newStatus.watchedEpisodes,
        )
    }

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val safeAuth = auth ?: return null
        val full = getFullAniListList(safeAuth) ?: return null
        val rawLists = full.data?.mediaListCollection?.lists.orEmpty()
        val statusLabels = mapOf(
            "CURRENT" to "Watching",
            "REPEATING" to "Rewatching",
            "PLANNING" to "Plan to Watch",
            "COMPLETED" to "Completed",
            "PAUSED" to "Paused",
            "DROPPED" to "Dropped",
        )
        val orderedKeys = listOf("CURRENT", "REPEATING", "PLANNING", "COMPLETED", "PAUSED", "DROPPED")
        val grouped = linkedMapOf<String, MutableList<SyncAPI.LibraryItem>>()
        orderedKeys.forEach { key -> grouped[key] = mutableListOf() }
        for (list in rawLists) {
            val rawStatus = list.status?.uppercase(Locale.ROOT)
            val entries = list.entries
            for (entry in entries) {
                val entryStatus = entry.status?.uppercase(Locale.ROOT) ?: rawStatus ?: "CURRENT"
                val item = entry.toLibraryItem() ?: continue
                grouped.getOrPut(entryStatus) { mutableListOf() }.add(item)
            }
        }
        val libraryLists = grouped.mapNotNull { (key, items) ->
            if (items.isEmpty()) return@mapNotNull null
            SyncAPI.LibraryList(
                name = UiText.DynamicString(statusLabels[key] ?: key),
                items = items,
            )
        }
        requireLibraryRefresh = false
        return SyncAPI.LibraryMetadata(
            allLibraryLists = libraryLists,
            supportedListSorting = setOf(
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.RatingHigh,
                ListSorting.RatingLow,
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
            ),
        )
    }

    suspend fun getFullAniListList(auth: AuthData): FullAnilistList? {
        val mergedLists = ArrayList<Lists>()
        var chunk = 1
        while (chunk <= 20) {
            val query = AniListQueries.buildFullListQuery(auth.user.id, chunk = chunk, perChunk = 500)
            val text = postApi(auth.token, query) ?: return if (mergedLists.isEmpty()) null else {
                FullAnilistList(Data(MediaListCollection(hasNextChunk = false, lists = mergedLists)))
            }
            val parsed = tryParseJson<FullAnilistList>(text) ?: break
            val col = parsed.data?.mediaListCollection ?: break
            mergedLists += col.lists
            if (col.hasNextChunk != true) break
            chunk++
        }
        return if (mergedLists.isEmpty()) {
            FullAnilistList(Data(MediaListCollection(hasNextChunk = false, lists = emptyList())))
        } else {
            FullAnilistList(Data(MediaListCollection(hasNextChunk = false, lists = mergedLists)))
        }
    }

    suspend fun getPagedAniListList(
        auth: AuthData,
        page: Int,
        perPage: Int,
        status: AniListStatusType? = null,
        sortGraphQl: String = "UPDATED_TIME_DESC",
        includeStatistics: Boolean = false,
    ): Result<PagedAniListRoot> {
        val graphQlStatus = status?.takeIf { it != AniListStatusType.None }?.toGraphQlStatus()
        val query = AniListQueries.buildPagedListQuery(
            userId = auth.user.id,
            page = page,
            perPage = perPage,
            graphQlStatus = graphQlStatus,
            sortGraphQl = sortGraphQl,
            includeStatistics = includeStatistics,
        )
        val (text, error) = postApiWithError(auth.token, query, cache = false)
        if (text.isNullOrBlank()) {
            return Result.failure(IllegalStateException(error ?: "Unable to load AniList library right now."))
        }
        val parsed = tryParseJson<PagedAniListRoot>(text)
            ?: return Result.failure(IllegalStateException("Invalid AniList library response."))
        return Result.success(parsed)
    }

    suspend fun toggleLike(auth: AuthData, id: Int): Boolean {
        val q = AniListQueries.buildToggleLikeMutation(id)
        val data = postApi(auth.token, q)
        val ok = !data.isNullOrBlank()
        if (ok) requireLibraryRefresh = true
        return ok
    }

    suspend fun getDataAboutId(
        auth: AuthData,
        id: Int,
        allowCache: Boolean = false,
    ): AniListTitleHolder? {
        val q = AniListQueries.buildDataAboutIdQuery(id)
        val data = postApi(auth.token, q, allowCache) ?: return null
        val d = tryParseJson<GetDataRoot>(data) ?: return null

        val main = d.data?.media ?: return null
        val coverUrl = main.coverImage?.extraLarge ?: main.coverImage?.large ?: main.coverImage?.medium
        return if (main.mediaListEntry != null) {
            AniListTitleHolder(
                title = main.title,
                id = id,
                isFavourite = main.isFavourite,
                progress = main.mediaListEntry.progress,
                episodes = main.episodes,
                score = main.mediaListEntry.score,
                type = fromIntToAnimeStatus(aniListStatusString.indexOf(main.mediaListEntry.status)),
                format = main.format,
                mediaStatus = main.status,
                seasonYear = main.seasonYear,
                coverImageUrl = coverUrl,
                nextAiringEpisode = main.nextAiringEpisode,
            )
        } else {
            AniListTitleHolder(
                title = main.title,
                id = id,
                isFavourite = main.isFavourite,
                progress = 0,
                episodes = main.episodes,
                score = 0,
                type = AniListStatusType.None,
                format = main.format,
                mediaStatus = main.status,
                seasonYear = main.seasonYear,
                coverImageUrl = coverUrl,
                nextAiringEpisode = main.nextAiringEpisode,
            )
        }
    }

    suspend fun postDataAboutId(
        auth: AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?,
        clearScore: Boolean = false,
    ): Boolean {
        val q = if (type == AniListStatusType.None) {
            val idQuery = AniListQueries.buildMediaListIdQuery(auth.user.id, id)
            val response = postApi(auth.token, idQuery)
            val listId = tryParseJson<MediaListItemRoot>(response ?: return false)?.data?.mediaList?.id ?: return false
            AniListQueries.buildDeleteMediaListEntryMutation(listId)
        } else {
            AniListQueries.buildSaveMediaListEntryMutation(
                id = id,
                status = type.toGraphQlStatus(),
                score = score,
                progress = progress,
                clearScore = clearScore,
            )
        }

        val data = postApi(auth.token, q)
        val ok = !data.isNullOrBlank()
        if (ok) requireLibraryRefresh = true
        return ok
    }

    private suspend fun getUser(token: AuthToken): AniListUser? = getUserDetailed(token).first

    private suspend fun getUserDetailed(token: AuthToken): Pair<AniListUser?, String?> {
        val (data, errorMessage) = postApiWithError(token, AniListQueries.VIEWER_QUERY)
        if (data.isNullOrBlank()) return null to errorMessage
        val userData = tryParseJson<AniListRoot>(data) ?: return null to "Invalid AniList Viewer response."
        val u = userData.data?.viewer ?: return null to "AniList Viewer response contained no user."
        return AniListUser(
            u.id,
            u.name,
            u.avatar?.large ?: u.avatar?.medium,
        ) to null
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

    private suspend fun postApi(token: AuthToken, q: String, cache: Boolean = false): String? =
        postApiWithError(token, q, cache).first

    private suspend fun postApiWithError(
        token: AuthToken,
        q: String,
        cache: Boolean = false,
    ): Pair<String?, String?> {
        val bearer = token.accessToken?.takeIf { it.isNotBlank() } ?: return null to "Missing AniList access token."
        val isMutation = q.trimStart().startsWith("mutation", ignoreCase = true)
        val cacheKey = "$bearer\u0000$q"

        if (!isMutation && cache) {
            getCachedResponse(authReadCache, cacheKey, AUTH_CACHE_TTL_MS)?.let { cached ->
                return cached to null
            }
        }

        val waitSecs = remainingRateLimitSeconds()
        if (waitSecs > 0L) {
            return null to "AniList rate limited (HTTP 429). Retry in ${waitSecs}s."
        }

        val executeRequest: suspend () -> Pair<String?, String?> = {
            val payload = mapper.writeValueAsString(mapOf("query" to q))
            val result = executeGraphQlPost(
                payload = payload,
                bearerToken = bearer,
                cacheHeader = if (cache) "max-stale=$MAX_STALE" else "no-cache",
            )
            val normalized = result.first
            if (normalized != null) {
                if (isMutation) {
                    invalidateAuthCache()
                } else {
                    putCachedResponse(authReadCache, cacheKey, normalized)
                }
            }
            result
        }

        return if (isMutation) {
            executeRequest()
        } else {
            runSingleFlight(inFlightAuth, cacheKey, executeRequest)
        }
    }

    companion object {
        private const val TAG = "AniListApi"
        internal const val DEFAULT_GRAPHQL_URL = "https://graphql.anilist.co"
        internal const val DEFAULT_OAUTH_TOKEN_URL = "https://anilist.co/api/v2/oauth/token"

        @Volatile
        internal var graphQlUrl: String = DEFAULT_GRAPHQL_URL

        @Volatile
        internal var oauthTokenUrl: String = DEFAULT_OAUTH_TOKEN_URL

        internal fun resetForTesting() {
            synchronized(publicCache) { publicCache.clear() }
            synchronized(authReadCache) { authReadCache.clear() }
            inFlightPublic.clear()
            inFlightAuth.clear()
            rateLimiter.clear()
            graphQlUrl = DEFAULT_GRAPHQL_URL
            oauthTokenUrl = DEFAULT_OAUTH_TOKEN_URL
        }
        const val MAX_STALE = 60 * 10
        private const val PUBLIC_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val PUBLIC_CACHE_MAX_ENTRIES = 256
        private const val AUTH_CACHE_TTL_MS = 30L * 1000L
        private const val AUTH_CACHE_MAX_ENTRIES = 128
        private const val DEFAULT_RATE_LIMIT_MS = 60_000L

        private data class CachedGraphQlResponse(
            val body: String,
            val cachedAtMs: Long,
        )

        private val publicCache = object : LinkedHashMap<String, CachedGraphQlResponse>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedGraphQlResponse>?): Boolean =
                size > PUBLIC_CACHE_MAX_ENTRIES
        }

        private val authReadCache = object : LinkedHashMap<String, CachedGraphQlResponse>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedGraphQlResponse>?): Boolean =
                size > AUTH_CACHE_MAX_ENTRIES
        }

        private val inFlightPublic = ConcurrentHashMap<String, CompletableDeferred<String?>>()
        private val inFlightAuth = ConcurrentHashMap<String, CompletableDeferred<Pair<String?, String?>>>()

        internal val rateLimiter = TrackerRateLimiter(
            providerName = "AniList",
            minIntervalMs = 700L,
            maxBurst = 4,
            defaultCooldownMs = DEFAULT_RATE_LIMIT_MS,
        )

        private fun remainingRateLimitSeconds(): Long = rateLimiter.remainingCooldownSeconds()

        private fun getCachedResponse(
            cacheMap: MutableMap<String, CachedGraphQlResponse>,
            key: String,
            ttlMs: Long,
        ): String? = synchronized(cacheMap) {
            val entry = cacheMap[key] ?: return@synchronized null
            if (System.currentTimeMillis() - entry.cachedAtMs <= ttlMs) {
                entry.body
            } else {
                cacheMap.remove(key)
                null
            }
        }

        private fun putCachedResponse(
            cacheMap: MutableMap<String, CachedGraphQlResponse>,
            key: String,
            body: String,
        ) {
            synchronized(cacheMap) {
                cacheMap[key] = CachedGraphQlResponse(body = body, cachedAtMs = System.currentTimeMillis())
            }
        }

        private fun invalidateAuthCache() {
            synchronized(authReadCache) {
                authReadCache.clear()
            }
        }

        private suspend fun executeGraphQlPost(
            payload: String,
            bearerToken: String? = null,
            cacheHeader: String? = null,
            logErrorBody: (Exception) -> Unit = { e -> AppLogger.w(TAG, "GraphQL postApi error: ${e.message}") },
        ): Pair<String?, String?> = withContext(Dispatchers.IO) {
            try {
                rateLimiter.acquireSlot()
                val headers = buildMap {
                    if (bearerToken != null) put("Authorization", "Bearer $bearerToken")
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    if (cacheHeader != null) put("Cache-Control", cacheHeader)
                }
                val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val response = app.post(
                    graphQlUrl,
                    headers = headers,
                    requestBody = requestBody,
                    cacheTime = 0,
                    timeout = 8000L,
                )
                val resetEpoch = runCatching {
                    response.okhttpResponse.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
                }.getOrNull()
                if (response.code == 429) {
                    val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
                    rateLimiter.recordRateLimit(retryAfterHeader = retryAfter, resetEpochSec = resetEpoch)
                    val cooldownSecs = remainingRateLimitSeconds().coerceAtLeast(1L)
                    return@withContext null to "AniList rate limited (HTTP 429). Retry in ${cooldownSecs}s."
                }
                val bodyText = response.text
                if (response.code !in 200..299 || hasGraphQlErrors(bodyText)) {
                    val detail = extractGraphQlError(bodyText)
                    val summary = detail ?: "HTTP ${response.code}"
                    AppLogger.w(TAG, "GraphQL request failed (http=${response.code}): $summary")
                    return@withContext null to "AniList error (HTTP ${response.code}): $summary"
                }
                val remaining = runCatching {
                    response.okhttpResponse.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                }.getOrNull()
                rateLimiter.recordSuccess(rateLimitRemaining = remaining, resetEpochSec = resetEpoch)
                bodyText.replace("\\/", "/") to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrackerRateLimitException) {
                null to (e.message ?: "AniList rate limited (HTTP 429).")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logErrorBody(e)
                null to (e.message ?: "Network error contacting AniList")
            }
        }

        private suspend fun <T> runSingleFlight(
            inFlight: ConcurrentHashMap<String, CompletableDeferred<T>>,
            key: String,
            block: suspend () -> T,
        ): T {
            while (true) {
                currentCoroutineContext().ensureActive()
                val created = CompletableDeferred<T>()
                val existing = inFlight.putIfAbsent(key, created)
                if (existing == null) {
                    return try {
                        val value = block()
                        inFlight.remove(key, created)
                        created.complete(value)
                        value
                    } catch (e: CancellationException) {
                        inFlight.remove(key, created)
                        created.cancel(e)
                        throw e
                    } catch (t: Throwable) {
                        inFlight.remove(key, created)
                        created.completeExceptionally(t)
                        throw t
                    }
                }
                try {
                    return existing.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }

        internal fun loadBakedClientId(): String = try {
            AniListApi::class.java.getResourceAsStream("/tracker.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("anilist.client_id")?.trim().orEmpty()
            }.orEmpty()
        } catch (_: Exception) {
            ""
        }

        internal fun parseTokenResponseJson(
            text: String,
            fallbackRefreshToken: String? = null,
        ): Result<AuthToken> {
            if (text.isBlank()) {
                return Result.failure(IllegalStateException("Empty token response from AniList."))
            }
            val root = runCatching { mapper.readTree(text) }.getOrNull()
                ?: return Result.failure(IllegalStateException("Invalid JSON response from AniList token endpoint."))

            val err = root["error"]?.asText()?.takeIf { it.isNotBlank() }
            if (err != null) {
                val hint = root["hint"]?.asText()?.takeIf { it.isNotBlank() }
                    ?: root["message"]?.asText()?.takeIf { it.isNotBlank() }
                    ?: err
                val enrichedHint = if (err.equals("invalid_client", ignoreCase = true) ||
                    hint.contains("Client authentication failed", ignoreCase = true)
                ) {
                    "$hint — verify your AniList Client ID and Client Secret under Advanced."
                } else {
                    hint
                }
                return Result.failure(IllegalStateException("AniList token error: $enrichedHint"))
            }

            val accessToken = root["access_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.failure(IllegalStateException("AniList response did not contain an access_token."))
            val refreshToken = root["refresh_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackRefreshToken
            val expiresIn = root["expires_in"]?.asLong(31536000L) ?: 31536000L
            return Result.success(
                AuthToken(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenLifetime = APIHolder.unixTime + expiresIn,
                ),
            )
        }

        val aniListStatusString =
            arrayOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")

        const val ANILIST_CACHED_LIST: String = "anilist_cached_list"

        fun fromIntToAnimeStatus(inp: Int): AniListStatusType = AniListStatusType.fromInt(inp)

        fun parseAnimeId(id: String): Int? =
            (Regex("anilist\\.co/anime/(\\d+)").find(id)?.groupValues?.getOrNull(1) ?: id.trim())
                .toIntOrNull()

        /**
         * Extracts an AniList AuthToken from a raw JWT token, `Bearer <token>`, or a
         * full redirect URL containing `#access_token=...` / `?access_token=...`.
         */
        fun parseTokenInput(rawInput: String?): AuthToken? {
            val cleaned = rawInput?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
            val defaultLifetimeSec = 31536000L

            if (cleaned.contains("access_token=")) {
                val params = splitRedirectUrl(cleaned)
                val token = params["access_token"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
                val expiresIn = params["expires_in"]?.toLongOrNull() ?: defaultLifetimeSec
                return AuthToken(
                    accessToken = token,
                    accessTokenLifetime = APIHolder.unixTime + expiresIn,
                )
            }

            if (cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }

            val stripped = if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
                cleaned.substring(7).trim()
            } else {
                cleaned
            }

            if (stripped.isBlank() ||
                stripped.any { it.isWhitespace() } ||
                stripped.startsWith("<") ||
                stripped.startsWith("{")
            ) {
                return null
            }

            return AuthToken(
                accessToken = stripped,
                accessTokenLifetime = APIHolder.unixTime + defaultLifetimeSec,
            )
        }

        internal fun hasGraphQlErrors(responseText: String?): Boolean {
            if (responseText.isNullOrBlank()) return true
            return try {
                val tree = mapper.readTree(responseText)
                val errors = tree["errors"]
                errors != null && errors.isArray && errors.size() > 0
            } catch (_: Exception) {
                true
            }
        }

        internal fun extractGraphQlError(responseText: String?): String? {
            if (responseText.isNullOrBlank()) return null
            return try {
                val tree = mapper.readTree(responseText)
                val firstError = tree["errors"]?.takeIf { it.isArray && it.size() > 0 }?.get(0)
                firstError?.get("message")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: tree["message"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: responseText.trim().take(200)
            } catch (_: Exception) {
                responseText.trim().take(200).takeIf { it.isNotEmpty() }
            }
        }

        internal fun AniListStatusType.toGraphQlStatus(): String = when (this) {
            AniListStatusType.Watching -> "CURRENT"
            AniListStatusType.Completed -> "COMPLETED"
            AniListStatusType.Paused -> "PAUSED"
            AniListStatusType.Dropped -> "DROPPED"
            AniListStatusType.Planning -> "PLANNING"
            AniListStatusType.ReWatching -> "REPEATING"
            AniListStatusType.None -> "CURRENT"
        }

        fun AniListStatusType.toSyncWatchType(): SyncWatchType = when (this) {
            AniListStatusType.Watching, AniListStatusType.ReWatching -> SyncWatchType.WATCHING
            AniListStatusType.Completed -> SyncWatchType.COMPLETED
            AniListStatusType.Paused -> SyncWatchType.ON_HOLD
            AniListStatusType.Dropped -> SyncWatchType.DROPPED
            AniListStatusType.Planning -> SyncWatchType.PLAN_TO_WATCH
            AniListStatusType.None -> SyncWatchType.NONE
        }

        fun SyncWatchType.toAniListStatusType(): AniListStatusType = when (this) {
            SyncWatchType.WATCHING -> AniListStatusType.Watching
            SyncWatchType.COMPLETED -> AniListStatusType.Completed
            SyncWatchType.ON_HOLD -> AniListStatusType.Paused
            SyncWatchType.DROPPED -> AniListStatusType.Dropped
            SyncWatchType.PLAN_TO_WATCH -> AniListStatusType.Planning
            SyncWatchType.NONE -> AniListStatusType.None
        }

        private fun fixName(name: String): String {
            return name.lowercase(Locale.ROOT).replace(" ", "")
                .replace("[^a-zA-Z0-9]".toRegex(), "")
        }

        private suspend fun postPublicGraphQl(
            query: String,
            variables: Map<String, Any?>? = null,
        ): String? {
            val payloadMap = buildMap<String, Any> {
                put("query", query)
                if (variables != null) put("variables", variables)
            }
            val payload = mapper.writeValueAsString(payloadMap)
            getCachedResponse(publicCache, payload, PUBLIC_CACHE_TTL_MS)?.let { cached ->
                return cached
            }
            if (remainingRateLimitSeconds() > 0L) {
                return null
            }

            return runSingleFlight(inFlightPublic, payload) {
                val (normalized, _) = executeGraphQlPost(
                    payload = payload,
                    logErrorBody = { e -> logError(e) },
                )
                if (normalized != null) {
                    putCachedResponse(publicCache, payload, normalized)
                }
                normalized
            }
        }

        suspend fun searchShows(name: String): GetSearchRoot? {
            val res = postPublicGraphQl(
                query = AniListQueries.SEARCH_QUERY,
                variables = mapOf(
                    "search" to name,
                    "page" to 1,
                    "type" to "ANIME",
                ),
            ) ?: return null
            return tryParseJson<GetSearchRoot>(res)
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

            val targetYear = year?.toString()
            val cleanQuery = fixName(name.replace(blackListRegex, ""))
            val filtered = shows?.data?.page?.media?.filter { media ->
                val candYear = media.startDate.year
                targetYear == null || candYear == null || candYear == targetYear
            }
            filtered?.firstOrNull { media ->
                val romaji = media.title.romaji
                romaji != null && fixName(romaji) == cleanQuery
            }?.let { return it }

            return null
        }

        suspend fun getSeason(id: Int): SeasonResponse {
            val data = postPublicGraphQl(AniListQueries.buildSeasonQuery(id))
                ?: throw ErrorLoadingException("Failed to fetch AniList season $id")
            return tryParseJson<SeasonResponse>(data) ?: throw ErrorLoadingException("Error parsing $data")
        }

        suspend fun getSeasonOrNull(id: Int): SeasonResponse? {
            val data = postPublicGraphQl(AniListQueries.buildSeasonQuery(id)) ?: return null
            return tryParseJson<SeasonResponse>(data)
        }

        suspend fun getSeasonByMalId(malId: Int): SeasonResponse? {
            val data = postPublicGraphQl(AniListQueries.buildSeasonByMalIdQuery(malId)) ?: return null
            return tryParseJson<SeasonResponse>(data)
        }
    }
}
