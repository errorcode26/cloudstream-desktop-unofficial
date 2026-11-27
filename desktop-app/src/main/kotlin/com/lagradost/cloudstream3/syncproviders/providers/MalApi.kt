package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.tracking.AniListOAuthFlow
import com.lagradost.cloudstream3.desktop.tracking.TrackerOAuthLoopback
import com.lagradost.cloudstream3.desktop.tracking.TrackerPropertiesConfig
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimitException
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimiter
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.tracking.toUnifiedWatchStatus
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class MalAuthRequest(
    val url: String,
    val codeVerifier: String,
    val state: String,
    val redirectUri: String,
)

data class MalUser(
    val id: Int,
    val name: String,
    val picture: String? = null,
    val animeStats: MalAnimeStats? = null,
)

data class MalAnimeStats(
    val watching: Int = 0,
    val completed: Int = 0,
    val onHold: Int = 0,
    val dropped: Int = 0,
    val planToWatch: Int = 0,
    val total: Int = 0,
)

data class MalLibraryPageResult(
    val nodes: List<MalAnimeNode>,
    val nextOffset: Int?,
    val hasMore: Boolean,
    val stats: MalAnimeStats? = null,
)

data class MalRelatedEdge(
    val relationType: String,
    val id: Int,
    val title: String,
    val normalizedFormat: String? = null,
    val knownEpisodes: Int? = null,
    val normalizedAirStatus: String? = null,
)

data class MalListStatus(
    val status: String,
    val score: Int = 0,
    val numEpisodesWatched: Int = 0,
    val isRewatching: Boolean = false,
    val updatedAtEpochSec: Long = 0L,
) {
    fun toUnifiedStatus(): UnifiedWatchStatus = when {
        status == "watching" && isRewatching -> UnifiedWatchStatus.REWATCHING
        status == "watching" -> UnifiedWatchStatus.WATCHING
        status == "completed" -> UnifiedWatchStatus.COMPLETED
        status == "on_hold" -> UnifiedWatchStatus.PAUSED
        status == "dropped" -> UnifiedWatchStatus.DROPPED
        status == "plan_to_watch" -> UnifiedWatchStatus.PLAN_TO_WATCH
        else -> UnifiedWatchStatus.NONE
    }

    fun toSyncWatchType(): SyncWatchType = when (status) {
        "watching" -> SyncWatchType.WATCHING
        "completed" -> SyncWatchType.COMPLETED
        "on_hold" -> SyncWatchType.ON_HOLD
        "dropped" -> SyncWatchType.DROPPED
        "plan_to_watch" -> SyncWatchType.PLAN_TO_WATCH
        else -> SyncWatchType.NONE
    }
}

data class MalAnimeNode(
    val id: Int,
    val title: String,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val synonyms: List<String> = emptyList(),
    val posterUrl: String? = null,
    val normalizedFormat: String? = null,
    val normalizedAirStatus: String? = null,
    val knownEpisodes: Int? = null,
    val startSeasonYear: Int? = null,
    val meanScore100: Int? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val relatedAnime: List<MalRelatedEdge> = emptyList(),
    val myListStatus: MalListStatus? = null,
) {
    fun allTitles(): List<String> =
        buildList {
            add(title)
            if (!englishTitle.isNullOrBlank()) add(englishTitle)
            if (!japaneseTitle.isNullOrBlank()) add(japaneseTitle)
            addAll(synonyms)
        }.distinct()
}

class MalApi(
    internal var apiBaseUrl: String = DEFAULT_API_BASE_URL,
    internal var oauthTokenUrl: String = DEFAULT_OAUTH_TOKEN_URL,
) : SyncAPI() {
    override val name = "MyAnimeList"
    override val idPrefix = "mal"
    override val requiresLogin = true
    override val hasOAuth2 = true
    override val mainUrl = "https://myanimelist.net"
    override val createAccountUrl = "https://myanimelist.net/register.php"
    override val redirectUrlIdentifier = "mallogin"
    override val icon = 0
    override var requireLibraryRefresh = true

    private val statsMutex = Mutex()
    private var cachedStatsKey: String? = null
    private var cachedStatsAtMs: Long = 0L
    private var cachedStats: MalAnimeStats? = null

    private val bakedClientId: String by lazy {
        TrackerPropertiesConfig.bakedProperty(TrackerPropertiesConfig.PROP_MAL_CLIENT_ID)
    }

    private val bakedClientSecret: String by lazy {
        TrackerPropertiesConfig.bakedProperty(TrackerPropertiesConfig.PROP_MAL_CLIENT_SECRET)
    }

    fun customClientId(): String =
        TrackerPropertiesConfig.customClientId(TrackerPropertiesConfig.KEY_TRACKER_MAL_CUSTOM_CLIENT_ID)

    fun setCustomClientId(clientId: String) =
        TrackerPropertiesConfig.setCustomClientId(TrackerPropertiesConfig.KEY_TRACKER_MAL_CUSTOM_CLIENT_ID, clientId)

    fun effectiveClientId(): String = customClientId().ifBlank { bakedClientId }

    fun customClientSecret(): String =
        TrackerPropertiesConfig.customClientId(TrackerPropertiesConfig.KEY_TRACKER_MAL_CUSTOM_CLIENT_SECRET)

    fun setCustomClientSecret(clientSecret: String) =
        TrackerPropertiesConfig.setCustomClientId(TrackerPropertiesConfig.KEY_TRACKER_MAL_CUSTOM_CLIENT_SECRET, clientSecret)

    fun effectiveClientSecret(): String = customClientSecret().ifBlank { bakedClientSecret }

    fun hasBakedClientId(): Boolean = bakedClientId.isNotBlank()

    fun hasClientId(): Boolean = effectiveClientId().isNotBlank()

    /**
     * MAL API v2 OAuth2 strictly uses `code_challenge_method=plain` (PKCE S256 is rejected).
     */
    fun buildAuthorizeRequest(
        redirectUri: String = AniListOAuthFlow.MAL_LOOPBACK_REDIRECT_URI,
    ): MalAuthRequest {
        val verifier = TrackerOAuthLoopback.generateCodeVerifier(64)
        val state = TrackerOAuthLoopback.generateState(24)
        val url = buildString {
            append("$OAUTH_AUTHORIZE_URL?response_type=code")
            append("&client_id=").append(urlEncode(effectiveClientId()))
            append("&code_challenge=").append(urlEncode(verifier))
            append("&code_challenge_method=plain")
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&state=").append(urlEncode(state))
        }
        return MalAuthRequest(
            url = url,
            codeVerifier = verifier,
            state = state,
            redirectUri = redirectUri,
        )
    }

    override fun loginRequest(): AuthLoginPage {
        val req = buildAuthorizeRequest()
        return AuthLoginPage(
            url = req.url,
            payload = "${req.codeVerifier}\n${req.state}\n${req.redirectUri}",
        )
    }

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val parts = payload?.split('\n').orEmpty()
        val verifier = parts.getOrNull(0)
        val state = parts.getOrNull(1)
        val redirectUri = parts.getOrNull(2) ?: AniListOAuthFlow.MAL_LOOPBACK_REDIRECT_URI
        val params = splitRedirectUrl(redirectUrl)
        val code = params["code"] ?: return null
        if (!state.isNullOrBlank() && !params["state"].isNullOrBlank() && params["state"] != state) {
            return null
        }
        return exchangeCodeForToken(
            code = code,
            codeVerifier = verifier ?: return null,
            redirectUri = redirectUri,
        ).getOrNull()
    }

    suspend fun exchangeCodeAndAuthorize(
        redirectOrCode: String,
        expectedState: String? = null,
        codeVerifier: String?,
        redirectUri: String? = AniListOAuthFlow.MAL_LOOPBACK_REDIRECT_URI,
    ): Result<AuthData> {
        val trimmed = redirectOrCode.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("No authorization code or redirect URL provided."))
        }

        val params = splitRedirectUrl(trimmed)
        val oauthError = params["error"]
        if (!oauthError.isNullOrBlank()) {
            val desc = params["error_description"] ?: params["message"] ?: oauthError
            return Result.failure(IllegalStateException("MyAnimeList authorization error: $desc"))
        }

        val stateParam = params["state"]
        if (!expectedState.isNullOrBlank() && !stateParam.isNullOrBlank() && stateParam != expectedState) {
            return Result.failure(IllegalStateException("OAuth state mismatch. Please retry sign-in."))
        }

        val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: trimmed.takeIf { !it.contains("://") && !it.contains('?') && !it.contains(' ') }
            ?: return Result.failure(IllegalArgumentException("No authorization code found in redirect URL."))

        val verifier = codeVerifier?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("Missing PKCE verifier for code exchange."))

        val token = exchangeCodeForToken(code, verifier, redirectUri).getOrElse { err ->
            return Result.failure(err)
        }
        val user = getUser(token)
            ?: return Result.failure(IllegalStateException("Signed in, but could not fetch MyAnimeList profile."))

        return Result.success(
            AuthData(
                user = AuthUser(id = user.id, name = user.name, profilePicture = user.picture),
                token = token,
            ),
        )
    }

    private suspend fun exchangeCodeForToken(
        code: String,
        codeVerifier: String,
        redirectUri: String?,
    ): Result<AuthToken> = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("MyAnimeList Client ID is not configured."))
        }
        val clientSecret = effectiveClientSecret()
        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .add("grant_type", "authorization_code")
                .apply {
                    if (clientSecret.isNotBlank()) {
                        add("client_secret", clientSecret)
                    }
                    if (!redirectUri.isNullOrBlank()) {
                        add("redirect_uri", redirectUri)
                    }
                }
                .build()
            val response = app.post(
                oauthTokenUrl,
                headers = mapOf("Accept" to "application/json"),
                requestBody = form,
                cacheTime = 0,
                timeout = 12_000L,
            )
            if (response.code !in 200..299) {
                AppLogger.w(TAG, "MAL token exchange HTTP ${response.code}: ${response.text.take(200)}")
            }
            parseTokenResponseJson(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL token exchange failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? = withContext(Dispatchers.IO) {
        val refresh = token.refreshToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext null
        val clientSecret = effectiveClientSecret()
        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .apply {
                    if (clientSecret.isNotBlank()) {
                        add("client_secret", clientSecret)
                    }
                }
                .build()
            val response = app.post(
                oauthTokenUrl,
                headers = mapOf("Accept" to "application/json"),
                requestBody = form,
                cacheTime = 0,
                timeout = 12_000L,
            )
            parseTokenResponseJson(response.text, fallbackRefreshToken = refresh).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL refreshToken failed: ${e.message}")
            null
        }
    }

    suspend fun authorizeWithToken(rawInput: String): Result<AuthData> {
        val token = AniListApi.parseTokenInput(rawInput)
            ?: return Result.failure(IllegalArgumentException("No valid access token found in input."))
        return try {
            val user = getUser(token)
                ?: return Result.failure(IllegalStateException("MyAnimeList rejected the token or /users/@me returned no user."))
            Result.success(
                AuthData(
                    user = AuthUser(id = user.id, name = user.name, profilePicture = user.picture),
                    token = token,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null) ?: return null
        return AuthUser(id = user.id, name = user.name, profilePicture = user.picture)
    }

    suspend fun getUser(token: AuthToken, includeStats: Boolean = false): MalUser? = withContext(Dispatchers.IO) {
        val access = token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val fields = if (includeStats) "id,name,picture,anime_statistics" else "id,name,picture"
        try {
            readLimiter.acquireSlot()
            val response = app.get(
                "$apiBaseUrl/users/@me?fields=$fields",
                headers = bearerHeaders(access),
                cacheTime = 0,
                timeout = 10_000L,
            )
            if (response.code == 429) {
                val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
                readLimiter.recordRateLimit(retryAfter)
                return@withContext null
            }
            if (response.code !in 200..299) return@withContext null
            readLimiter.recordSuccess()
            val root = mapper.readTree(response.text)
            val id = root["id"]?.asInt(0) ?: 0
            val username = root["name"]?.asText()?.trim().orEmpty()
            if (id <= 0 || username.isEmpty()) return@withContext null
            val stats = if (includeStats) parseAnimeStats(root["anime_statistics"]) else null
            MalUser(
                id = id,
                name = username,
                picture = root["picture"]?.asText()?.takeIf { it.isNotBlank() },
                animeStats = stats,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "MAL rate limited")
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL getUser failed: ${e.message}")
            null
        }
    }

    suspend fun ensureValidAuth(
        auth: AuthData,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): AuthData? {
        if (auth.token.accessToken.isNullOrBlank()) return null
        if (!auth.token.isAccessTokenExpired(marginSec = 60L)) {
            return auth
        }
        val refreshed = refreshToken(auth.token)
        if (refreshed != null) {
            val updated = auth.copy(token = refreshed)
            withContext(Dispatchers.IO) {
                persistAuth?.invoke(updated)
            }
            return updated
        }
        return if (!auth.token.isAccessTokenExpired(marginSec = 0L)) auth else null
    }

    private suspend fun authorizedGet(
        url: String,
        auth: AuthData?,
        allowClientIdFallback: Boolean,
        persistAuth: ((AuthData) -> Unit)?,
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val validAuth = if (auth != null) ensureValidAuth(auth, persistAuth) else null
        val headers = authHeaders(validAuth, allowClientIdFallback) ?: return@withContext null
        readLimiter.acquireSlot()
        var response = app.get(url, headers = headers, cacheTime = 0, timeout = 12_000L)
        if (response.code == 401 && auth != null) {
            val refreshed = refreshToken(auth.token)
            if (refreshed != null) {
                val updated = auth.copy(token = refreshed)
                persistAuth?.invoke(updated)
                val retryHeaders = authHeaders(updated, allowClientIdFallback) ?: return@withContext null
                readLimiter.acquireSlot()
                response = app.get(url, headers = retryHeaders, cacheTime = 0, timeout = 12_000L)
            }
        }
        if (response.code == 429) {
            val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
            readLimiter.recordRateLimit(retryAfter)
        } else if (response.code in 200..299) {
            readLimiter.recordSuccess()
        }
        response.code to response.text
    }

    private fun authHeaders(auth: AuthData?, allowClientIdFallback: Boolean): Map<String, String>? {
        val access = auth?.token?.accessToken?.trim()?.takeIf { it.isNotEmpty() }
        if (access != null) {
            return bearerHeaders(access)
        }
        if (allowClientIdFallback) {
            val clientId = effectiveClientId()
            if (clientId.isNotBlank()) {
                return mapOf(
                    "X-MAL-CLIENT-ID" to clientId,
                    "Accept" to "application/json",
                )
            }
        }
        return null
    }

    private fun bearerHeaders(accessToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Accept" to "application/json",
    )

    suspend fun getAnimeDetails(
        auth: AuthData?,
        malId: Int,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): MalAnimeNode? = withContext(Dispatchers.IO) {
        if (malId <= 0) return@withContext null
        val fields = "id,title,main_picture,alternative_titles,start_date,start_season,mean,media_type,status,num_episodes,synopsis,genres,studios,related_anime{node{id,title,media_type,num_episodes,status,start_season,main_picture},relation_type},my_list_status{status,score,num_episodes_watched,is_rewatching,updated_at}"
        val url = "$apiBaseUrl/anime/$malId?fields=${urlEncode(fields)}"
        try {
            val (code, text) = authorizedGet(url, auth, allowClientIdFallback = true, persistAuth) ?: return@withContext null
            if (code !in 200..299 || text.isBlank()) return@withContext null
            val root = mapper.readTree(text)
            parseAnimeNodeJson(root, root["my_list_status"])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL getAnimeDetails($malId) failed: ${e.message}")
            null
        }
    }

    suspend fun searchAnime(
        auth: AuthData?,
        query: String,
        limit: Int = 15,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): List<MalAnimeNode> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        // MAL API v2 /v2/anime requires `q` length >= 3 characters.
        if (trimmed.length < 3) return@withContext emptyList()
        val fields = "id,title,main_picture,alternative_titles,start_date,start_season,mean,media_type,status,num_episodes"
        val url = "$apiBaseUrl/anime?q=${urlEncode(trimmed)}&limit=${limit.coerceIn(1, 50)}&fields=${urlEncode(fields)}"
        try {
            val (code, text) = authorizedGet(url, auth, allowClientIdFallback = true, persistAuth) ?: return@withContext emptyList()
            if (code !in 200..299 || text.isBlank()) return@withContext emptyList()
            val dataArray = mapper.readTree(text)["data"] ?: return@withContext emptyList()
            if (!dataArray.isArray) return@withContext emptyList()
            dataArray.mapNotNull { row ->
                val node = row["node"] ?: return@mapNotNull null
                parseAnimeNodeJson(node, row["list_status"] ?: node["my_list_status"])
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL searchAnime('$trimmed') failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getUserAnimeListPage(
        auth: AuthData,
        statusParam: String? = null,
        sortParam: String = "list_updated_at",
        offset: Int = 0,
        limit: Int = 50,
        includeStats: Boolean = false,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): MalLibraryPageResult = withContext(Dispatchers.IO) {
        var currentAuth = ensureValidAuth(auth, persistAuth)
            ?: throw IllegalStateException("MyAnimeList authorization expired. Please reconnect.")
        val trackingPersist: (AuthData) -> Unit = { updated ->
            currentAuth = updated
            persistAuth?.invoke(updated)
        }
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val statusQuery = statusParam?.trim()?.takeIf { it.isNotEmpty() }?.let { "&status=${urlEncode(it)}" }.orEmpty()
        val url = "$apiBaseUrl/users/@me/animelist?fields=${urlEncode(LIST_FIELDS)}$statusQuery&sort=${urlEncode(sortParam)}&limit=$safeLimit&offset=$safeOffset&nsfw=true"

        val (code, text) = authorizedGet(url, currentAuth, allowClientIdFallback = false, trackingPersist)
            ?: throw IllegalStateException("MyAnimeList authorization expired. Please reconnect.")
        if (code == 429) {
            throw TrackerRateLimitException("MyAnimeList", readLimiter.remainingCooldownSeconds())
        }
        if (code !in 200..299) {
            throw IllegalStateException("MyAnimeList library request failed (HTTP $code).")
        }

        val root = mapper.readTree(text)
        val nodes = parseListPageNodes(root["data"])
        val nextUrl = root["paging"]?.get("next")?.asText()?.takeIf { it.isNotBlank() }
        val nextOffset = nextUrl?.let { extractOffsetFromUrl(it) }

        val stats = if (includeStats) {
            getCachedAnimeStats(currentAuth)
        } else {
            null
        }

        MalLibraryPageResult(
            nodes = nodes,
            nextOffset = nextOffset,
            hasMore = nextOffset != null,
            stats = stats,
        )
    }

    private suspend fun getCachedAnimeStats(auth: AuthData): MalAnimeStats? {
        val access = auth.token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = "${auth.user.id}:${access.takeLast(8)}"
        val now = System.currentTimeMillis()
        statsMutex.withLock {
            if (cachedStatsKey == key && (now - cachedStatsAtMs) < STATS_CACHE_TTL_MS && cachedStats != null) {
                return cachedStats
            }
        }
        val fetchedStats = getUser(auth.token, includeStats = true)?.animeStats ?: return null
        statsMutex.withLock {
            cachedStatsKey = key
            cachedStatsAtMs = System.currentTimeMillis()
            cachedStats = fetchedStats
        }
        return fetchedStats
    }

    private suspend fun invalidateStatsCache() {
        statsMutex.withLock {
            cachedStatsAtMs = 0L
            cachedStats = null
        }
    }

    suspend fun getFullUserAnimeList(
        auth: AuthData,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): List<MalAnimeNode> = withContext(Dispatchers.IO) {
        var nextUrl: String? = "$apiBaseUrl/users/@me/animelist?fields=${urlEncode(LIST_FIELDS)}&limit=500&nsfw=true"
        val collected = mutableListOf<MalAnimeNode>()
        var pageCount = 0

        while (nextUrl != null && pageCount < MAX_LIST_PAGES) {
            pageCount++
            val (code, text) = authorizedGet(nextUrl, auth, allowClientIdFallback = false, persistAuth)
                ?: throw IllegalStateException("MyAnimeList authorization expired. Please reconnect.")
            if (code == 429) {
                throw TrackerRateLimitException("MyAnimeList", readLimiter.remainingCooldownSeconds())
            }
            if (code !in 200..299) {
                throw IllegalStateException("MyAnimeList library request failed (HTTP $code).")
            }
            val root = mapper.readTree(text)
            collected += parseListPageNodes(root["data"])
            nextUrl = root["paging"]?.get("next")?.asText()?.takeIf { it.isNotBlank() }
        }
        collected
    }

    suspend fun updateAnimeListStatus(
        auth: AuthData,
        malId: Int,
        status: UnifiedWatchStatus,
        progress: Int?,
        score100: Int?,
        clearScore: Boolean = false,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (malId <= 0) return@withContext false
        if (status == UnifiedWatchStatus.NONE) {
            return@withContext deleteAnimeListStatus(auth, malId, persistAuth)
        }
        val activeAuth = ensureValidAuth(auth, persistAuth) ?: return@withContext false
        val access = activeAuth.token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false

        val (malStatus, isRewatching) = toMalStatusParam(status)
        val formBuilder = FormBody.Builder()
            .add("status", malStatus)
            .add("is_rewatching", isRewatching.toString())

        if (progress != null) {
            formBuilder.add("num_watched_episodes", progress.coerceAtLeast(0).toString())
        }
        if (clearScore) {
            formBuilder.add("score", "0")
        } else if (score100 != null) {
            val malScore = (score100 / 10.0).roundToInt().coerceIn(0, 10)
            formBuilder.add("score", malScore.toString())
        }

        val url = "$apiBaseUrl/anime/$malId/my_list_status"
        val body = formBuilder.build()
        try {
            writeLimiter.acquireSlot()
            var response = app.put(url, headers = bearerHeaders(access), requestBody = body, cacheTime = 0, timeout = 10_000L)
            if (response.code == 401) {
                val refreshed = refreshToken(activeAuth.token)
                if (refreshed != null) {
                    val updated = activeAuth.copy(token = refreshed)
                    persistAuth?.invoke(updated)
                    val nextAccess = refreshed.accessToken.orEmpty()
                    writeLimiter.acquireSlot()
                    response = app.put(url, headers = bearerHeaders(nextAccess), requestBody = body, cacheTime = 0, timeout = 10_000L)
                }
            }
            if (response.code == 429) {
                val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
                writeLimiter.recordRateLimit(retryAfter)
                return@withContext false
            }
            val ok = response.code in 200..299
            if (ok) {
                writeLimiter.recordSuccess()
                invalidateStatsCache()
                requireLibraryRefresh = true
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "MAL rate limited")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL updateAnimeListStatus($malId) failed: ${e.message}")
            false
        }
    }

    suspend fun deleteAnimeListStatus(
        auth: AuthData,
        malId: Int,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (malId <= 0) return@withContext false
        val activeAuth = ensureValidAuth(auth, persistAuth) ?: return@withContext false
        val access = activeAuth.token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
        val url = "$apiBaseUrl/anime/$malId/my_list_status"
        try {
            writeLimiter.acquireSlot()
            var response = app.delete(url, headers = bearerHeaders(access), cacheTime = 0, timeout = 10_000L)
            if (response.code == 401) {
                val refreshed = refreshToken(activeAuth.token)
                if (refreshed != null) {
                    val updated = activeAuth.copy(token = refreshed)
                    persistAuth?.invoke(updated)
                    val nextAccess = refreshed.accessToken.orEmpty()
                    writeLimiter.acquireSlot()
                    response = app.delete(url, headers = bearerHeaders(nextAccess), cacheTime = 0, timeout = 10_000L)
                }
            }
            if (response.code == 429) {
                val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
                writeLimiter.recordRateLimit(retryAfter)
                return@withContext false
            }
            val ok = response.code in 200..299 || response.code == 404
            if (ok) {
                writeLimiter.recordSuccess()
                invalidateStatsCache()
                requireLibraryRefresh = true
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "MAL rate limited")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "MAL deleteAnimeListStatus($malId) failed: ${e.message}")
            false
        }
    }

    override fun urlToId(url: String): String? =
        Regex("myanimelist\\.net/anime/(\\d+)").find(url)?.groupValues?.getOrNull(1)
            ?: url.trim().toIntOrNull()?.toString()

    override suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult> {
        return searchAnime(auth, query).map { node ->
            SyncSearchResult(
                name = node.englishTitle ?: node.title,
                apiName = this.name,
                syncId = node.id.toString(),
                url = "$mainUrl/anime/${node.id}",
                posterUrl = node.posterUrl,
                type = if (node.normalizedFormat == "MOVIE") TvType.AnimeMovie else TvType.Anime,
                score = Score.from100(node.meanScore100),
            )
        }
    }

    override suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? {
        val malId = urlToId(id)?.toIntOrNull() ?: return null
        val details = getAnimeDetails(auth, malId) ?: return null
        val listStatus = details.myListStatus ?: return SyncStatus(
            status = SyncWatchType.NONE,
            score = null,
            watchedEpisodes = 0,
            maxEpisodes = details.knownEpisodes,
        )
        return SyncStatus(
            status = listStatus.toSyncWatchType(),
            score = listStatus.score.takeIf { it > 0 }?.let { Score.from100(it * 10) },
            watchedEpisodes = listStatus.numEpisodesWatched,
            maxEpisodes = details.knownEpisodes,
        )
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        val currentAuth = auth ?: return false
        val malId = urlToId(id)?.toIntOrNull() ?: return false
        return updateAnimeListStatus(
            auth = currentAuth,
            malId = malId,
            status = newStatus.status.toUnifiedWatchStatus(),
            progress = newStatus.watchedEpisodes,
            score100 = newStatus.score?.toInt(100),
        )
    }

    override suspend fun load(auth: AuthData?, id: String): SyncResult {
        val malId = urlToId(id)?.toIntOrNull() ?: return SyncResult(id = id)
        val details = getAnimeDetails(auth, malId) ?: return SyncResult(id = id)
        return SyncResult(
            id = details.id.toString(),
            totalEpisodes = details.knownEpisodes,
            title = details.englishTitle ?: details.title,
            publicScore = Score.from100(details.meanScore100),
            synopsis = details.synopsis,
            airStatus = when (details.normalizedAirStatus) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            },
            studio = details.studios,
            genres = details.genres,
            synonyms = details.synonyms,
            posterUrl = details.posterUrl,
        )
    }

    override suspend fun library(auth: AuthData?): LibraryMetadata? {
        val currentAuth = auth ?: return null
        val entries = getFullUserAnimeList(currentAuth)
        val statusLabels = linkedMapOf(
            "watching" to "Watching",
            "completed" to "Completed",
            "on_hold" to "On Hold",
            "dropped" to "Dropped",
            "plan_to_watch" to "Plan to Watch",
        )
        val grouped = linkedMapOf<String, MutableList<LibraryItem>>()
        statusLabels.keys.forEach { grouped[it] = mutableListOf() }

        for (entry in entries) {
            val key = entry.myListStatus?.status ?: "plan_to_watch"
            grouped.getOrPut(key) { mutableListOf() }.add(
                LibraryItem(
                    name = entry.englishTitle ?: entry.title,
                    url = "$mainUrl/anime/${entry.id}",
                    syncId = entry.id.toString(),
                    episodesCompleted = entry.myListStatus?.numEpisodesWatched,
                    episodesTotal = entry.knownEpisodes,
                    personalRating = entry.myListStatus?.score?.takeIf { it > 0 }?.let { Score.from100(it * 10) },
                    lastUpdatedUnixTime = entry.myListStatus?.updatedAtEpochSec,
                    apiName = this.name,
                    type = if (entry.normalizedFormat == "MOVIE") TvType.AnimeMovie else TvType.Anime,
                    posterUrl = entry.posterUrl,
                    posterHeaders = null,
                    quality = null,
                    releaseDate = null,
                ),
            )
        }
        val lists = grouped.mapNotNull { (key, items) ->
            if (items.isEmpty()) return@mapNotNull null
            LibraryList(name = UiText.DynamicString(statusLabels[key] ?: key), items = items)
        }
        requireLibraryRefresh = false
        return LibraryMetadata(
            allLibraryLists = lists,
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

    suspend fun resetForTesting() {
        invalidateStatsCache()
        readLimiter.clear()
        writeLimiter.clear()
        requireLibraryRefresh = true
        apiBaseUrl = DEFAULT_API_BASE_URL
        oauthTokenUrl = DEFAULT_OAUTH_TOKEN_URL
    }

    companion object {
        private const val TAG = "MalApi"
        private const val OAUTH_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize"
        internal const val DEFAULT_OAUTH_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        internal const val DEFAULT_API_BASE_URL = "https://api.myanimelist.net/v2"
        private const val DEFAULT_TOKEN_LIFETIME_SEC = 30L * 24L * 3600L
        private const val MAX_LIST_PAGES = 20
        private const val STATS_CACHE_TTL_MS = 60_000L
        private const val LIST_FIELDS =
            "list_status{status,score,num_episodes_watched,is_rewatching,updated_at},num_episodes,status,media_type,start_season,start_date,alternative_titles,mean,main_picture"
        private val OFFSET_QUERY_REGEX = Regex("[?&]offset=(\\d+)")

        internal val readLimiter = TrackerRateLimiter(
            providerName = "MyAnimeList",
            minIntervalMs = 350L,
            maxBurst = 3,
            defaultCooldownMs = 45_000L,
        )

        internal val writeLimiter = TrackerRateLimiter(
            providerName = "MyAnimeList",
            minIntervalMs = 500L,
            maxBurst = 2,
            defaultCooldownMs = 45_000L,
        )

        internal fun extractOffsetFromUrl(url: String): Int? =
            OFFSET_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

        internal fun parseAnimeStats(node: JsonNode?): MalAnimeStats? {
            if (node == null || node.isNull || node.isMissingNode) return null
            return MalAnimeStats(
                watching = node["num_items_watching"]?.asInt(0) ?: 0,
                completed = node["num_items_completed"]?.asInt(0) ?: 0,
                onHold = node["num_items_on_hold"]?.asInt(0) ?: 0,
                dropped = node["num_items_dropped"]?.asInt(0) ?: 0,
                planToWatch = node["num_items_plan_to_watch"]?.asInt(0) ?: 0,
                total = node["num_items"]?.asInt(0) ?: 0,
            )
        }

        private fun parseListPageNodes(dataArray: JsonNode?): List<MalAnimeNode> {
            if (dataArray == null || !dataArray.isArray) return emptyList()
            val out = ArrayList<MalAnimeNode>(dataArray.size())
            for (row in dataArray) {
                val node = row["node"] ?: continue
                val parsed = parseAnimeNodeJson(node, row["list_status"])
                if (parsed != null) out += parsed
            }
            return out
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%7E", "~")

        fun normalizeFormat(raw: String?): String? = when (raw?.trim()?.lowercase()) {
            "tv" -> "TV"
            "movie" -> "MOVIE"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special", "tv_special" -> "SPECIAL"
            "music" -> "MUSIC"
            else -> raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        }

        fun normalizeAirStatus(raw: String?): String? = when (raw?.trim()?.lowercase()) {
            "currently_airing" -> "RELEASING"
            "finished_airing" -> "FINISHED"
            "not_yet_aired" -> "NOT_YET_RELEASED"
            else -> null
        }

        fun toMalStatusParam(status: UnifiedWatchStatus): Pair<String, Boolean> = when (status) {
            UnifiedWatchStatus.WATCHING -> "watching" to false
            UnifiedWatchStatus.REWATCHING -> "watching" to true
            UnifiedWatchStatus.COMPLETED -> "completed" to false
            UnifiedWatchStatus.PAUSED -> "on_hold" to false
            UnifiedWatchStatus.DROPPED -> "dropped" to false
            UnifiedWatchStatus.PLAN_TO_WATCH -> "plan_to_watch" to false
            UnifiedWatchStatus.NONE -> "plan_to_watch" to false
        }

        internal fun parseTokenResponseJson(
            text: String,
            fallbackRefreshToken: String? = null,
        ): Result<AuthToken> {
            if (text.isBlank()) {
                return Result.failure(IllegalStateException("Empty token response from MyAnimeList."))
            }
            val root = runCatching { mapper.readTree(text) }.getOrNull()
                ?: return Result.failure(IllegalStateException("Invalid JSON response from MyAnimeList token endpoint."))

            val err = root["error"]?.asText()?.takeIf { it.isNotBlank() }
            if (err != null) {
                val hint = root["hint"]?.asText() ?: root["message"]?.asText() ?: err
                val enrichedHint = if (err.equals("invalid_client", ignoreCase = true) ||
                    hint.contains("Client authentication failed", ignoreCase = true)
                ) {
                    "$hint — if your MAL App Type is 'Web', enter your Client Secret under Advanced (or recreate the app as 'Other')."
                } else {
                    hint
                }
                return Result.failure(IllegalStateException("MyAnimeList token error: $enrichedHint"))
            }

            val accessToken = root["access_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.failure(IllegalStateException("MyAnimeList response did not contain an access_token."))
            val refreshToken = root["refresh_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackRefreshToken
            val expiresIn = root["expires_in"]?.asLong(DEFAULT_TOKEN_LIFETIME_SEC) ?: DEFAULT_TOKEN_LIFETIME_SEC
            return Result.success(
                AuthToken(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenLifetime = APIHolder.unixTime + expiresIn,
                ),
            )
        }

        internal fun parseIsoEpochSeconds(raw: String?): Long {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
            return runCatching { OffsetDateTime.parse(cleaned).toEpochSecond() }.getOrDefault(0L)
        }

        internal fun parseAnimeNodeJson(node: JsonNode, statusNode: JsonNode? = null): MalAnimeNode? {
            val id = node["id"]?.asInt(0) ?: 0
            val title = node["title"]?.asText()?.trim().orEmpty()
            if (id <= 0 || title.isEmpty()) return null

            val alt = node["alternative_titles"]
            val englishTitle = alt?.get("en")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            val japaneseTitle = alt?.get("ja")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            val synonyms = alt?.get("synonyms")?.takeIf { it.isArray }
                ?.mapNotNull { it.asText()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()

            val pic = node["main_picture"]
            val poster = pic?.get("large")?.asText()?.takeIf { it.isNotBlank() }
                ?: pic?.get("medium")?.asText()?.takeIf { it.isNotBlank() }

            val rawEpisodes = node["num_episodes"]?.asInt(0) ?: 0
            val knownEpisodes = rawEpisodes.takeIf { it > 0 }
            val seasonYear = node["start_season"]?.get("year")?.asInt(0)?.takeIf { it > 0 }
                ?: node["start_date"]?.asText()?.take(4)?.toIntOrNull()
            val mean100 = node["mean"]?.asDouble()?.takeIf { it > 0.0 }?.let { (it * 10.0).roundToInt() }

            val genres = node["genres"]?.takeIf { it.isArray }
                ?.mapNotNull { it["name"]?.asText()?.takeIf { g -> g.isNotBlank() } }
                .orEmpty()
            val studios = node["studios"]?.takeIf { it.isArray }
                ?.mapNotNull { it["name"]?.asText()?.takeIf { s -> s.isNotBlank() } }
                .orEmpty()

            val related = node["related_anime"]?.takeIf { it.isArray }?.mapNotNull { edge ->
                val relNode = edge["node"] ?: return@mapNotNull null
                val relId = relNode["id"]?.asInt(0) ?: 0
                val relTitle = relNode["title"]?.asText()?.trim().orEmpty()
                if (relId <= 0 || relTitle.isEmpty()) return@mapNotNull null
                MalRelatedEdge(
                    relationType = edge["relation_type"]?.asText()?.trim().orEmpty(),
                    id = relId,
                    title = relTitle,
                    normalizedFormat = normalizeFormat(relNode["media_type"]?.asText()),
                    knownEpisodes = relNode["num_episodes"]?.asInt(0)?.takeIf { it > 0 },
                    normalizedAirStatus = normalizeAirStatus(relNode["status"]?.asText()),
                )
            }.orEmpty()

            val parsedListStatus = statusNode?.takeIf { !it.isNull && !it.isMissingNode }?.let { st ->
                val rawSt = st["status"]?.asText()?.trim().orEmpty()
                if (rawSt.isEmpty()) {
                    null
                } else {
                    MalListStatus(
                        status = rawSt,
                        score = st["score"]?.asInt(0) ?: 0,
                        numEpisodesWatched = st["num_episodes_watched"]?.asInt(0) ?: 0,
                        isRewatching = st["is_rewatching"]?.asBoolean(false) ?: false,
                        updatedAtEpochSec = parseIsoEpochSeconds(st["updated_at"]?.asText()),
                    )
                }
            }

            return MalAnimeNode(
                id = id,
                title = title,
                englishTitle = englishTitle,
                japaneseTitle = japaneseTitle,
                synonyms = synonyms,
                posterUrl = poster,
                normalizedFormat = normalizeFormat(node["media_type"]?.asText()),
                normalizedAirStatus = normalizeAirStatus(node["status"]?.asText()),
                knownEpisodes = knownEpisodes,
                startSeasonYear = seasonYear,
                meanScore100 = mean100,
                synopsis = node["synopsis"]?.asText()?.takeIf { it.isNotBlank() },
                genres = genres,
                studios = studios,
                relatedAnime = related,
                myListStatus = parsedListStatus,
            )
        }
    }
}
