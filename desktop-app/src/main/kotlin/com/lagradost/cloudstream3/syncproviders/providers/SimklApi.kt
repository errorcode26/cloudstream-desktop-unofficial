package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.tracking.AniListOAuthFlow
import com.lagradost.cloudstream3.desktop.tracking.AniListReleaseGuard
import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.TrackerOAuthLoopback
import com.lagradost.cloudstream3.desktop.tracking.TrackerPropertiesConfig
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimitException
import com.lagradost.cloudstream3.desktop.tracking.TrackerRateLimiter
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.tracking.toSyncWatchType
import com.lagradost.cloudstream3.desktop.tracking.toUnifiedWatchStatus
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthPinData
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

enum class SimklMediaKind(
    val prefix: String,
    val listKey: String,
    val webSegment: String,
    val searchEndpoint: String,
    val detailEndpoint: String,
) {
    ANIME(
        prefix = "anime",
        listKey = "anime",
        webSegment = "anime",
        searchEndpoint = "anime",
        detailEndpoint = "anime",
    ),
    SHOW(
        prefix = "show",
        listKey = "shows",
        webSegment = "tv",
        searchEndpoint = "tv",
        detailEndpoint = "tv",
    ),
    MOVIE(
        prefix = "movie",
        listKey = "movies",
        webSegment = "movies",
        searchEndpoint = "movie",
        detailEndpoint = "movies",
    );

    companion object {
        fun fromString(raw: String?): SimklMediaKind? = when (raw?.trim()?.lowercase()) {
            "anime" -> ANIME
            "show", "shows", "tv", "series" -> SHOW
            "movie", "movies" -> MOVIE
            else -> null
        }

        fun defaultForTvType(tvType: TvType): SimklMediaKind = when (tvType) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> ANIME
            TvType.Movie -> MOVIE
            else -> SHOW
        }
    }
}

data class SimklMediaRef(
    val kind: SimklMediaKind,
    val simklId: Int,
) {
    fun encode(): String = "${kind.prefix}:$simklId"

    companion object {
        fun parse(raw: String, fallbackKind: SimklMediaKind = SimklMediaKind.ANIME): SimklMediaRef? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val kind = SimklMediaKind.fromString(trimmed.substring(0, colonIdx)) ?: fallbackKind
                val id = trimmed.substring(colonIdx + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
                return SimklMediaRef(kind, id)
            }
            val numericId = trimmed.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return SimklMediaRef(fallbackKind, numericId)
        }
    }
}

data class SimklAuthRequest(
    val url: String,
    val codeVerifier: String,
    val state: String,
    val redirectUri: String,
)

data class SimklPinSession(
    val pin: AuthPinData,
    val codeVerifier: String?,
    val isAuthV2: Boolean,
)

sealed interface SimklPinPollResult {
    data object Pending : SimklPinPollResult
    data class Authorized(val authData: AuthData) : SimklPinPollResult
    data class Failed(val message: String, val expired: Boolean = false) : SimklPinPollResult
}

data class SimklUser(
    val id: Int,
    val name: String,
    val avatar: String? = null,
)

data class SimklExternalIds(
    val simkl: Int,
    val mal: Int? = null,
    val tmdb: Int? = null,
    val imdb: String? = null,
    val anilist: Int? = null,
    val tvdb: Int? = null,
)

data class SimklEpisodeSlot(
    val ordinal: Int,
    val season: Int,
    val episode: Int,
    val aired: Boolean,
    val airDateEpochSec: Long?,
)

data class SimklMediaItem(
    val ref: SimklMediaRef,
    val title: String,
    val year: Int? = null,
    val format: String? = null,
    val normalizedAirStatus: String? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val runtimeMin: Int? = null,
    val rating100: Int? = null,
    val genres: List<String> = emptyList(),
    val externalIds: SimklExternalIds? = null,
    val watchStatus: UnifiedWatchStatus = UnifiedWatchStatus.NONE,
    val watchedEpisodesCount: Int = 0,
    val totalEpisodesCount: Int? = null,
    val notAiredEpisodesCount: Int? = null,
    val userScore100: Int = 0,
    val lastWatchedEpochSec: Long = 0L,
) {
    fun computeReleaseBounds(episodeSlots: List<SimklEpisodeSlot> = emptyList()): ReleaseBounds {
        val effectiveFormat = when {
            ref.kind == SimklMediaKind.MOVIE -> "MOVIE"
            !format.isNullOrBlank() -> format
            ref.kind == SimklMediaKind.SHOW -> "SERIES"
            else -> "TV"
        }

        if (episodeSlots.isNotEmpty() && effectiveFormat != "MOVIE") {
            val nowSec = System.currentTimeMillis() / 1000L
            val airedCount = episodeSlots.count { it.aired }
            val nextUpcoming = episodeSlots.firstOrNull { !it.aired && (it.airDateEpochSec ?: Long.MAX_VALUE) > nowSec }
            val total = totalEpisodesCount ?: episodeSlots.size
            val derivedStatus = when {
                normalizedAirStatus != null -> normalizedAirStatus
                airedCount == 0 && episodeSlots.isNotEmpty() -> "NOT_YET_RELEASED"
                nextUpcoming != null || airedCount < total -> "RELEASING"
                else -> "FINISHED"
            }
            val base = AniListReleaseGuard.evaluate(
                mediaStatus = derivedStatus,
                format = effectiveFormat,
                totalEpisodes = total,
                nextAiring = null,
            )
            return base.copy(
                nextAiringEpisode = nextUpcoming?.ordinal,
                nextAiringTimeUntilSec = nextUpcoming?.airDateEpochSec?.let { (it - nowSec).coerceAtLeast(0L).toInt() },
                maxAiredEpisode = airedCount,
                ceilingEpisode = airedCount,
            )
        }

        val base = AniListReleaseGuard.evaluate(
            mediaStatus = normalizedAirStatus,
            format = effectiveFormat,
            totalEpisodes = totalEpisodesCount,
            nextAiring = null,
        )
        val total = totalEpisodesCount
        val notAired = notAiredEpisodesCount
        if (total != null && notAired != null && effectiveFormat != "MOVIE") {
            val aired = (total - notAired).coerceAtLeast(0)
            return base.copy(
                maxAiredEpisode = aired,
                ceilingEpisode = aired,
            )
        }
        return base
    }
}

class SimklApi(
    internal var apiBaseUrl: String = DEFAULT_API_BASE_URL,
) : SyncAPI() {
    override val name = "Simkl"
    override val idPrefix = "simkl"
    override val requiresLogin = true
    override val hasOAuth2 = true
    override val hasPin = true
    override val mainUrl = "https://simkl.com"
    override val createAccountUrl = "https://simkl.com/signup"
    override val redirectUrlIdentifier = "simkllogin"
    override val icon = 0
    override var requireLibraryRefresh = true

    private data class CachedKindSnapshot(
        val items: List<SimklMediaItem>,
        val activityStamp: String?,
    )

    private val writeMutex = Mutex()
    private val cacheMutex = Mutex()
    private var cachedLibraryKey: String? = null
    private var cachedLibraryFetchedAtMs: Long = 0L
    private val cachedByKind = mutableMapOf<SimklMediaKind, CachedKindSnapshot>()

    private val bakedClientId: String by lazy {
        TrackerPropertiesConfig.bakedProperty(TrackerPropertiesConfig.PROP_SIMKL_CLIENT_ID)
    }

    fun customClientId(): String =
        TrackerPropertiesConfig.customClientId(TrackerPropertiesConfig.KEY_TRACKER_SIMKL_CUSTOM_CLIENT_ID)

    fun setCustomClientId(clientId: String) =
        TrackerPropertiesConfig.setCustomClientId(TrackerPropertiesConfig.KEY_TRACKER_SIMKL_CUSTOM_CLIENT_ID, clientId)

    fun effectiveClientId(): String = customClientId().ifBlank { bakedClientId }

    fun hasBakedClientId(): Boolean = bakedClientId.isNotBlank()

    fun hasClientId(): Boolean = effectiveClientId().isNotBlank()

    /**
     * Initiates SIMKL Device PIN login. Prefers Auth V2 Device Flow with PKCE and
     * automatically falls back to V1 PIN flow for legacy client registrations.
     */
    suspend fun requestDevicePin(): Result<SimklPinSession> = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("SIMKL Client ID is not configured."))
        }

        val v2Result = runCatching {
            val payload = mapper.writeValueAsString(
                mapOf(
                    "client_id" to clientId,
                    "scope" to OAUTH_SCOPE,
                ),
            ).toRequestBody(JSON_MEDIA_TYPE)
            val response = app.post(
                "$apiBaseUrl/oauth2/device",
                headers = baseHeaders(clientId),
                requestBody = payload,
                cacheTime = 0,
                timeout = 10_000L,
            )
            if (response.code in 200..299 && response.text.isNotBlank()) {
                parsePinDeviceJson(response.text, verifier = null, isAuthV2 = true)
            } else {
                AppLogger.w(TAG, "SIMKL /oauth2/device returned HTTP ${response.code}: ${response.text.take(200)}")
                null
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLogger.w(TAG, "SIMKL /oauth2/device failed: ${it.message}")
        }.getOrNull()

        if (v2Result != null) {
            return@withContext Result.success(v2Result)
        }

        try {
            val v1Resp = app.get(
                "$apiBaseUrl/oauth/pin?client_id=${urlEncode(clientId)}",
                headers = baseHeaders(clientId),
                cacheTime = 0,
                timeout = 10_000L,
            )
            if (v1Resp.code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("SIMKL rejected PIN request (HTTP ${v1Resp.code}). Verify Client ID."),
                )
            }
            val session = parsePinDeviceJson(v1Resp.text, verifier = null, isAuthV2 = false)
                ?: return@withContext Result.failure(IllegalStateException("Invalid PIN response from SIMKL."))
            Result.success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollDevicePin(session: SimklPinSession): SimklPinPollResult = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) {
            return@withContext SimklPinPollResult.Failed("SIMKL Client ID is missing.")
        }
        try {
            if (session.isAuthV2) {
                val payload = mapper.writeValueAsString(
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to session.pin.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                ).toRequestBody(JSON_MEDIA_TYPE)
                val response = app.post(
                    "$apiBaseUrl/oauth2/token",
                    headers = baseHeaders(clientId),
                    requestBody = payload,
                    cacheTime = 0,
                    timeout = 10_000L,
                )
                val root = runCatching { mapper.readTree(response.text) }.getOrNull()
                val err = root?.get("error")?.asText()?.trim()?.lowercase()
                when {
                    err == "authorization_pending" || err == "slow_down" -> SimklPinPollResult.Pending
                    err == "expired_token" || err == "access_denied" -> SimklPinPollResult.Failed(
                        root["error_description"]?.asText() ?: "PIN expired or was denied.",
                        expired = true,
                    )
                    err != null -> SimklPinPollResult.Failed(
                        root["error_description"]?.asText() ?: err,
                    )
                    else -> {
                        val token = parseTokenJson(response.text, fallbackRefresh = null).getOrElse { e ->
                            return@withContext SimklPinPollResult.Failed(e.message ?: "Failed to parse SIMKL token.")
                        }
                        val user = getUser(token)
                            ?: return@withContext SimklPinPollResult.Failed("Authorized, but failed to load SIMKL profile.")
                        SimklPinPollResult.Authorized(
                            AuthData(
                                user = AuthUser(id = user.id, name = user.name, profilePicture = user.avatar),
                                token = token,
                            ),
                        )
                    }
                }
            } else {
                val response = app.get(
                    "$apiBaseUrl/oauth/pin/${urlEncode(session.pin.userCode)}?client_id=${urlEncode(clientId)}",
                    headers = baseHeaders(clientId),
                    cacheTime = 0,
                    timeout = 10_000L,
                )
                val root = runCatching { mapper.readTree(response.text) }.getOrNull()
                    ?: return@withContext SimklPinPollResult.Failed("Invalid PIN poll response.")
                val resultFlag = root["result"]?.asText()?.trim()?.uppercase()
                if (resultFlag == "OK") {
                    val access = root["access_token"]?.asText()?.trim().orEmpty()
                    if (access.isEmpty()) {
                        return@withContext SimklPinPollResult.Failed("PIN confirmed without an access token.")
                    }
                    val token = AuthToken(accessToken = access)
                    val user = getUser(token)
                        ?: return@withContext SimklPinPollResult.Failed("Authorized, but failed to load SIMKL profile.")
                    return@withContext SimklPinPollResult.Authorized(
                        AuthData(
                            user = AuthUser(id = user.id, name = user.name, profilePicture = user.avatar),
                            token = token,
                        ),
                    )
                }
                val message = root["message"]?.asText().orEmpty()
                if (message.contains("expired", ignoreCase = true) ||
                    message.contains("not found", ignoreCase = true) ||
                    message.contains("invalid", ignoreCase = true)
                ) {
                    SimklPinPollResult.Failed(message.ifBlank { "PIN expired." }, expired = true)
                } else {
                    SimklPinPollResult.Pending
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL PIN poll error: ${e.message}")
            SimklPinPollResult.Pending
        }
    }

    override suspend fun pinRequest(): AuthPinData? = requestDevicePin().getOrNull()?.pin

    override suspend fun login(payload: AuthPinData): AuthToken? {
        val poll = pollDevicePin(SimklPinSession(pin = payload, codeVerifier = null, isAuthV2 = false))
        return (poll as? SimklPinPollResult.Authorized)?.authData?.token
    }

    fun buildAuthorizeRequest(
        redirectUri: String = AniListOAuthFlow.SIMKL_LOOPBACK_REDIRECT_URI,
    ): SimklAuthRequest {
        val verifier = TrackerOAuthLoopback.generateCodeVerifier(64)
        val challenge = TrackerOAuthLoopback.sha256Base64Url(verifier)
        val state = TrackerOAuthLoopback.generateState(24)
        val url = buildString {
            append("$OAUTH_AUTHORIZE_URL?response_type=code")
            append("&client_id=").append(urlEncode(effectiveClientId()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&scope=").append(urlEncode(OAUTH_SCOPE))
            append("&code_challenge=").append(urlEncode(challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(urlEncode(state))
        }
        return SimklAuthRequest(
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
        val redirectUri = parts.getOrNull(2) ?: AniListOAuthFlow.SIMKL_LOOPBACK_REDIRECT_URI
        return exchangeCodeAndAuthorize(
            redirectOrCode = redirectUrl,
            expectedState = state,
            codeVerifier = verifier,
            redirectUri = redirectUri,
        ).getOrNull()?.token
    }

    suspend fun exchangeCodeAndAuthorize(
        redirectOrCode: String,
        expectedState: String? = null,
        codeVerifier: String?,
        redirectUri: String? = AniListOAuthFlow.SIMKL_LOOPBACK_REDIRECT_URI,
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        val trimmed = redirectOrCode.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No authorization code or redirect URL provided."))
        }
        val params = splitRedirectUrl(trimmed)
        val err = params["error"]
        if (!err.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("SIMKL authorization error: ${params["error_description"] ?: err}"),
            )
        }
        val stateParam = params["state"]
        if (!expectedState.isNullOrBlank() && !stateParam.isNullOrBlank() && stateParam != expectedState) {
            return@withContext Result.failure(IllegalStateException("OAuth state mismatch. Please retry sign-in."))
        }
        val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: trimmed.takeIf { !it.contains("://") && !it.contains('?') && !it.contains(' ') }
            ?: return@withContext Result.failure(IllegalArgumentException("No authorization code found in redirect URL."))
        val verifier = codeVerifier?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.failure(IllegalStateException("Missing PKCE verifier for SIMKL exchange."))

        val clientId = effectiveClientId()
        if (clientId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("SIMKL Client ID is not configured."))
        }

        try {
            val bodyJson = mapper.writeValueAsString(
                buildMap {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("client_id", clientId)
                    put("code_verifier", verifier)
                    if (!redirectUri.isNullOrBlank()) put("redirect_uri", redirectUri)
                },
            ).toRequestBody(JSON_MEDIA_TYPE)

            val response = app.post(
                "$apiBaseUrl/oauth2/token",
                headers = baseHeaders(clientId),
                requestBody = bodyJson,
                cacheTime = 0,
                timeout = 12_000L,
            )
            val token = parseTokenJson(response.text, fallbackRefresh = null).getOrElse { e ->
                return@withContext Result.failure(e)
            }
            val user = getUser(token)
                ?: return@withContext Result.failure(IllegalStateException("Signed in, but could not load SIMKL profile."))
            Result.success(
                AuthData(
                    user = AuthUser(id = user.id, name = user.name, profilePicture = user.avatar),
                    token = token,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? = withContext(Dispatchers.IO) {
        val refresh = token.refreshToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext null
        try {
            val payload = mapper.writeValueAsString(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refresh,
                ),
            ).toRequestBody(JSON_MEDIA_TYPE)
            val response = app.post(
                "$apiBaseUrl/oauth2/token",
                headers = baseHeaders(clientId),
                requestBody = payload,
                cacheTime = 0,
                timeout = 12_000L,
            )
            parseTokenJson(response.text, fallbackRefresh = refresh).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL refreshToken failed: ${e.message}")
            null
        }
    }

    suspend fun authorizeWithToken(rawInput: String): Result<AuthData> {
        val token = AniListApi.parseTokenInput(rawInput)
            ?: return Result.failure(IllegalArgumentException("No valid access token found in input."))
        return try {
            val user = getUser(token)
                ?: return Result.failure(IllegalStateException("SIMKL rejected the token or /users/settings returned no user."))
            Result.success(
                AuthData(
                    user = AuthUser(id = user.id, name = user.name, profilePicture = user.avatar),
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
        return AuthUser(id = user.id, name = user.name, profilePicture = user.avatar)
    }

    private suspend fun simklGet(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 10_000L,
    ): Pair<Int, String> {
        readLimiter.acquireSlot()
        val response = app.get(url, headers = headers, cacheTime = 0, timeout = timeoutMs)
        if (response.code == 429) {
            val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
            readLimiter.recordRateLimit(retryAfter)
            throw TrackerRateLimitException("SIMKL", readLimiter.remainingCooldownSeconds())
        }
        if (response.code in 200..299) {
            readLimiter.recordSuccess()
        }
        return response.code to response.text
    }

    suspend fun getUser(token: AuthToken): SimklUser? = withContext(Dispatchers.IO) {
        val access = token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val clientId = effectiveClientId()
        try {
            val (code, text) = simklGet(
                url = "$apiBaseUrl/users/settings",
                headers = authHeaders(clientId, access),
                timeoutMs = 10_000L,
            )
            if (code !in 200..299) return@withContext null
            val root = mapper.readTree(text)
            val userNode = root["user"]
            val accountNode = root["account"]
            val username = userNode?.get("name")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val id = accountNode?.get("id")?.asInt(0)?.takeIf { it > 0 }
                ?: userNode["id"]?.asInt(0)?.takeIf { it > 0 }
                ?: kotlin.math.abs(username.hashCode()).coerceAtLeast(1)
            val avatar = userNode["avatar"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            SimklUser(id = id, name = username, avatar = avatar)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL getUser failed: ${e.message}")
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
        return if (auth.token.refreshToken.isNullOrBlank() || !auth.token.isAccessTokenExpired(marginSec = 0L)) {
            auth
        } else {
            null
        }
    }

    private suspend fun invalidateLibraryCache(kind: SimklMediaKind? = null) {
        cacheMutex.withLock {
            cachedLibraryFetchedAtMs = 0L
            if (kind == null) {
                cachedByKind.clear()
            } else {
                cachedByKind.remove(kind)
            }
        }
    }

    private suspend fun fetchSyncActivities(headers: Map<String, String>): Map<SimklMediaKind, String> {
        return runCatching {
            val (code, text) = simklGet("$apiBaseUrl/sync/activities", headers = headers, timeoutMs = 8_000L)
            if (code == 401) {
                throw IllegalStateException("SIMKL session expired or unauthorized (HTTP 401).")
            }
            if (code !in 200..299 || text.isBlank()) emptyMap() else parseActivitiesTimestamps(text)
        }.onFailure {
            if (it is CancellationException || it is TrackerRateLimitException) throw it
            if (it.message?.contains("401") == true) throw it
        }.getOrDefault(emptyMap())
    }

    suspend fun getFullLibrary(
        auth: AuthData,
        forceRefresh: Boolean = false,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): List<SimklMediaItem> = withContext(Dispatchers.IO) {
        val activeAuth = ensureValidAuth(auth, persistAuth)
            ?: throw IllegalStateException("SIMKL authorization is invalid. Please reconnect.")
        val access = activeAuth.token.accessToken.orEmpty()
        val cacheKey = "${activeAuth.user.id}:${access.takeLast(8)}"
        val now = System.currentTimeMillis()
        val kinds = listOf(SimklMediaKind.ANIME, SimklMediaKind.SHOW, SimklMediaKind.MOVIE)

        val cachedSnapshot = cacheMutex.withLock {
            if (cachedLibraryKey != cacheKey) {
                cachedByKind.clear()
                cachedLibraryKey = cacheKey
                cachedLibraryFetchedAtMs = 0L
                null
            } else if (!forceRefresh &&
                !requireLibraryRefresh &&
                cachedByKind.size == kinds.size &&
                (now - cachedLibraryFetchedAtMs) < LIBRARY_CACHE_TTL_MS
            ) {
                kinds.flatMap { cachedByKind[it]?.items.orEmpty() }
            } else {
                null
            }
        }
        if (cachedSnapshot != null) {
            return@withContext cachedSnapshot
        }

        val clientId = effectiveClientId()
        val headers = authHeaders(clientId, access)
        val remoteActivities = fetchSyncActivities(headers)

        val updatedKinds = LinkedHashMap<SimklMediaKind, CachedKindSnapshot>(kinds.size)
        for (kind in kinds) {
            val existing = cacheMutex.withLock {
                if (cachedLibraryKey == cacheKey) cachedByKind[kind] else null
            }
            val remoteStamp = remoteActivities[kind]
            if (existing != null && remoteStamp != null && existing.activityStamp == remoteStamp) {
                updatedKinds[kind] = existing
                continue
            }

            val url = "$apiBaseUrl/sync/all-items/${kind.listKey}?extended=full"
            val (code, text) = simklGet(url, headers = headers, timeoutMs = 15_000L)
            if (code == 401) {
                throw IllegalStateException("SIMKL session expired or unauthorized (HTTP 401).")
            }
            if (code !in 200..299) {
                if (existing != null) {
                    updatedKinds[kind] = existing
                }
                continue
            }
            val parsed = if (text.isBlank()) emptyList() else parseAllItemsJson(text, kind)
            updatedKinds[kind] = CachedKindSnapshot(items = parsed, activityStamp = remoteStamp)
        }

        cacheMutex.withLock {
            cachedLibraryKey = cacheKey
            cachedLibraryFetchedAtMs = System.currentTimeMillis()
            cachedByKind.clear()
            cachedByKind.putAll(updatedKinds)
        }

        val combined = ArrayList<SimklMediaItem>()
        val seenRefs = HashSet<String>()
        for (kind in kinds) {
            for (item in updatedKinds[kind]?.items.orEmpty()) {
                if (seenRefs.add(item.ref.encode())) {
                    combined += item
                }
            }
        }
        requireLibraryRefresh = false
        combined
    }

    suspend fun getMediaDetails(ref: SimklMediaRef): SimklMediaItem? = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext null
        val url = "$apiBaseUrl/${ref.kind.detailEndpoint}/${ref.simklId}?extended=full&client_id=${urlEncode(clientId)}"
        try {
            val (code, text) = simklGet(url, headers = baseHeaders(clientId), timeoutMs = 10_000L)
            if (code !in 200..299 || text.isBlank()) return@withContext null
            val node = mapper.readTree(text)
            parseDirectMediaDetailJson(node, ref.kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "SIMKL rate limited")
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL getMediaDetails(${ref.encode()}) failed: ${e.message}")
            null
        }
    }

    suspend fun getEpisodeSlots(ref: SimklMediaRef): List<SimklEpisodeSlot> = withContext(Dispatchers.IO) {
        if (ref.kind == SimklMediaKind.MOVIE) return@withContext emptyList()
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext emptyList()
        val endpoint = if (ref.kind == SimklMediaKind.ANIME) "anime" else "tv"
        val url = "$apiBaseUrl/$endpoint/episodes/${ref.simklId}?extended=full&client_id=${urlEncode(clientId)}"
        try {
            val (code, text) = simklGet(url, headers = baseHeaders(clientId), timeoutMs = 10_000L)
            if (code !in 200..299 || text.isBlank()) return@withContext emptyList()
            parseEpisodeSlotsJson(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "SIMKL rate limited")
            emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL getEpisodeSlots(${ref.encode()}) failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchByExternalIds(
        tmdbId: Int? = null,
        imdbId: String? = null,
        malId: Int? = null,
        anilistId: Int? = null,
    ): List<SimklMediaItem> = withContext(Dispatchers.IO) {
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext emptyList()

        val queryParts = buildList {
            if (tmdbId != null && tmdbId > 0) add("tmdb=$tmdbId")
            if (!imdbId.isNullOrBlank()) add("imdb=${urlEncode(imdbId.trim())}")
            if (malId != null && malId > 0) add("mal=$malId")
            if (anilistId != null && anilistId > 0) add("anilist=$anilistId")
            add("extended=full")
            add("client_id=${urlEncode(clientId)}")
        }
        if (queryParts.size <= 2) return@withContext emptyList()

        val url = "$apiBaseUrl/search/id?${queryParts.joinToString("&")}"
        try {
            val (code, text) = simklGet(url, headers = baseHeaders(clientId), timeoutMs = 10_000L)
            if (code !in 200..299 || text.isBlank()) return@withContext emptyList()
            parseIdLookupJson(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "SIMKL rate limited")
            emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL searchByExternalIds failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchMedia(
        query: String,
        kinds: List<SimklMediaKind> = listOf(SimklMediaKind.ANIME),
        limit: Int = 15,
        accessToken: String? = null,
    ): List<SimklMediaItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val clientId = effectiveClientId()
        if (clientId.isBlank()) return@withContext emptyList()

        val headers = accessToken?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { authHeaders(clientId, it) }
            ?: baseHeaders(clientId)
        val collected = mutableListOf<SimklMediaItem>()
        val seenRefs = HashSet<String>()
        for (kind in kinds.distinct()) {
            val url = "$apiBaseUrl/search/${kind.searchEndpoint}?q=${urlEncode(trimmed)}&limit=${limit.coerceIn(1, 25)}&extended=full&client_id=${urlEncode(clientId)}"
            val items = runCatching {
                val (code, text) = simklGet(url, headers = headers, timeoutMs = 10_000L)
                if (code in 200..299 && text.isNotBlank()) {
                    parseSearchJson(text, kind)
                } else {
                    AppLogger.w(TAG, "SIMKL search /search/${kind.searchEndpoint} HTTP $code: ${text.take(200)}")
                    emptyList()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                AppLogger.w(TAG, "SIMKL search /search/${kind.searchEndpoint} failed: ${it.message}")
            }.getOrDefault(emptyList())

            for (item in items) {
                if (seenRefs.add(item.ref.encode())) {
                    collected += item
                }
            }
        }
        collected
    }

    suspend fun getRemoteEntryStatus(
        auth: AuthData,
        ref: SimklMediaRef,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): Pair<SimklMediaItem, List<SimklEpisodeSlot>>? {
        val activeAuth = ensureValidAuth(auth, persistAuth) ?: return null
        val library = runCatching { getFullLibrary(activeAuth, forceRefresh = requireLibraryRefresh, persistAuth = persistAuth) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val existing = library.firstOrNull { it.ref == ref }
        val details = getMediaDetails(ref)
        val mergedItem = when {
            existing != null && details != null -> existing.copy(
                title = existing.title.ifBlank { details.title },
                year = existing.year ?: details.year,
                format = details.format ?: existing.format,
                normalizedAirStatus = details.normalizedAirStatus ?: existing.normalizedAirStatus,
                posterUrl = existing.posterUrl ?: details.posterUrl,
                totalEpisodesCount = details.totalEpisodesCount ?: existing.totalEpisodesCount,
                rating100 = details.rating100 ?: existing.rating100,
                externalIds = details.externalIds ?: existing.externalIds,
            )
            existing != null -> existing
            details != null -> details
            else -> return null
        }
        val slots = if (ref.kind != SimklMediaKind.MOVIE) getEpisodeSlots(ref) else emptyList()
        return mergedItem to slots
    }

    /**
     * Serializes SIMKL sync writes via [writeMutex] because SIMKL rejects concurrent mutations.
     */
    suspend fun updateRemoteEntry(
        auth: AuthData,
        ref: SimklMediaRef,
        status: UnifiedWatchStatus,
        progress: Int?,
        score100: Int?,
        clearScore: Boolean = false,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val activeAuth = ensureValidAuth(auth, persistAuth) ?: return@withContext false
            val access = activeAuth.token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
            val clientId = effectiveClientId()
            if (clientId.isBlank()) return@withContext false

            if (status == UnifiedWatchStatus.NONE) {
                return@withContext removeEntryInternal(clientId, access, ref)
            }

            if (ref.kind == SimklMediaKind.MOVIE &&
                status in setOf(UnifiedWatchStatus.WATCHING, UnifiedWatchStatus.PAUSED, UnifiedWatchStatus.REWATCHING)
            ) {
                AppLogger.w(TAG, "SIMKL movies do not support list status $status")
                return@withContext false
            }

            val currentLibrary = runCatching {
                getFullLibrary(activeAuth, forceRefresh = requireLibraryRefresh, persistAuth = persistAuth)
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
            val existing = currentLibrary.firstOrNull { it.ref == ref }

            var allSucceeded = true
            var anyWriteSucceeded = false
            val headers = authHeaders(clientId, access)

            val oldProgress = existing?.watchedEpisodesCount ?: 0
            val effectiveTargetProgress = when {
                progress != null -> progress.coerceAtLeast(0)
                ref.kind == SimklMediaKind.MOVIE && status == UnifiedWatchStatus.PLAN_TO_WATCH -> 0
                else -> null
            }
            val shouldClearMovieHistory = ref.kind == SimklMediaKind.MOVIE &&
                effectiveTargetProgress == 0 &&
                (oldProgress > 0 || (existing?.lastWatchedEpochSec ?: 0L) > 0L)

            // SIMKL's /sync/history/remove deletes a movie's watchlist row and rating along with
            // its watch record, so it must execute before re-adding to the target status/rating.
            var clearedMovieHistory = false
            if (shouldClearMovieHistory) {
                if (postSyncPayload(
                        endpoint = "$apiBaseUrl/sync/history/remove",
                        payload = buildIdsListPayload("movies", ref.simklId),
                        headers = headers,
                        expectedEnvelopeKey = "deleted",
                    )
                ) {
                    anyWriteSucceeded = true
                    clearedMovieHistory = true
                } else {
                    allSucceeded = false
                }
            }

            val desiredSimklStatus = toSimklListStatus(status)
            if (existing == null || existing.watchStatus != status || clearedMovieHistory) {
                val statusPayload = mapOf(
                    ref.kind.listKey to listOf(
                        mapOf(
                            "to" to desiredSimklStatus,
                            "ids" to mapOf("simkl" to ref.simklId),
                        ),
                    ),
                )
                if (postSyncPayload(
                        endpoint = "$apiBaseUrl/sync/add-to-list",
                        payload = statusPayload,
                        headers = headers,
                        expectedEnvelopeKey = "added",
                    )
                ) {
                    anyWriteSucceeded = true
                } else {
                    allSucceeded = false
                }
            }

            val oldScore100 = existing?.userScore100 ?: 0
            val targetScore100 = when {
                clearScore || (score100 != null && score100 <= 0) -> score100
                score100 != null && score100 > 0 -> score100
                clearedMovieHistory && oldScore100 > 0 -> oldScore100
                else -> null
            }
            if (!clearedMovieHistory && (clearScore || (targetScore100 != null && targetScore100 <= 0 && oldScore100 > 0))) {
                if (postSyncPayload(
                        endpoint = "$apiBaseUrl/sync/ratings/remove",
                        payload = buildIdsListPayload(ref.kind.listKey, ref.simklId),
                        headers = headers,
                        expectedEnvelopeKey = "deleted",
                    )
                ) {
                    anyWriteSucceeded = true
                } else {
                    allSucceeded = false
                }
            } else if (targetScore100 != null && targetScore100 > 0 && (targetScore100 != oldScore100 || clearedMovieHistory)) {
                val rating10 = (targetScore100 / 10.0).roundToInt().coerceIn(1, 10)
                val ratingPayload = mapOf(
                    ref.kind.listKey to listOf(
                        mapOf(
                            "rating" to rating10,
                            "ids" to mapOf("simkl" to ref.simklId),
                        ),
                    ),
                )
                if (postSyncPayload(
                        endpoint = "$apiBaseUrl/sync/ratings",
                        payload = ratingPayload,
                        headers = headers,
                        expectedEnvelopeKey = "added",
                    )
                ) {
                    anyWriteSucceeded = true
                } else {
                    allSucceeded = false
                }
            }

            if (effectiveTargetProgress != null &&
                !shouldClearMovieHistory &&
                effectiveTargetProgress != oldProgress
            ) {
                val targetProgress = effectiveTargetProgress
                if (ref.kind == SimklMediaKind.MOVIE) {
                    if (targetProgress > 0) {
                        val moviePayload = mapOf(
                            "movies" to listOf(
                                mapOf(
                                    "ids" to mapOf("simkl" to ref.simklId),
                                    "watched_at" to Instant.now().toString(),
                                ),
                            ),
                        )
                        if (postSyncPayload(
                                endpoint = "$apiBaseUrl/sync/history",
                                payload = moviePayload,
                                headers = headers,
                                expectedEnvelopeKey = "added",
                            )
                        ) {
                            anyWriteSucceeded = true
                        } else {
                            allSucceeded = false
                        }
                    }
                } else {
                    val slots = getEpisodeSlots(ref)
                    val isAdding = targetProgress > oldProgress
                    val range = if (isAdding) {
                        (oldProgress + 1)..targetProgress
                    } else {
                        (targetProgress + 1)..oldProgress
                    }
                    val historyEndpoint = if (isAdding) "$apiBaseUrl/sync/history" else "$apiBaseUrl/sync/history/remove"
                    val historyPayload = buildEpisodeHistoryPayload(
                        ref = ref,
                        ordinalRange = range,
                        slots = slots,
                        watchedAtIso = Instant.now().toString(),
                    )
                    if (postSyncPayload(
                            endpoint = historyEndpoint,
                            payload = historyPayload,
                            headers = headers,
                            expectedEnvelopeKey = if (isAdding) "added" else "deleted",
                        )
                    ) {
                        anyWriteSucceeded = true
                    } else {
                        allSucceeded = false
                    }
                }
            }

            if (anyWriteSucceeded) {
                invalidateLibraryCache(ref.kind)
                requireLibraryRefresh = true
            }
            allSucceeded
        }
    }

    suspend fun removeRemoteEntry(
        auth: AuthData,
        ref: SimklMediaRef,
        persistAuth: ((AuthData) -> Unit)? = null,
    ): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val activeAuth = ensureValidAuth(auth, persistAuth) ?: return@withContext false
            val access = activeAuth.token.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
            val clientId = effectiveClientId()
            if (clientId.isBlank()) return@withContext false
            removeEntryInternal(clientId, access, ref)
        }
    }

    private suspend fun removeEntryInternal(
        clientId: String,
        access: String,
        ref: SimklMediaRef,
    ): Boolean {
        val ok = postSyncPayload(
            endpoint = "$apiBaseUrl/sync/history/remove",
            payload = buildIdsListPayload(ref.kind.listKey, ref.simklId),
            headers = authHeaders(clientId, access),
            expectedEnvelopeKey = "deleted",
            requireNonZeroCount = true,
        )
        invalidateLibraryCache(ref.kind)
        requireLibraryRefresh = true
        return ok
    }

    private suspend fun postSyncPayload(
        endpoint: String,
        payload: Any,
        headers: Map<String, String>,
        expectedEnvelopeKey: String? = null,
        requireNonZeroCount: Boolean = false,
    ): Boolean {
        return try {
            writeLimiter.acquireSlot()
            val jsonBody = mapper.writeValueAsString(payload).toRequestBody(JSON_MEDIA_TYPE)
            val response = app.post(
                endpoint,
                headers = headers,
                requestBody = jsonBody,
                cacheTime = 0,
                timeout = 12_000L,
            )
            if (response.code == 429) {
                val retryAfter = runCatching { response.okhttpResponse.header("Retry-After") }.getOrNull()
                writeLimiter.recordRateLimit(retryAfter)
                false
            } else if (response.code !in 200..299) {
                AppLogger.w(TAG, "SIMKL POST $endpoint failed HTTP ${response.code}: ${response.text.take(200)}")
                false
            } else {
                val rejection = hasSimklPayloadRejection(
                    responseText = response.text,
                    expectedEnvelopeKey = expectedEnvelopeKey,
                    requireNonZeroCount = requireNonZeroCount,
                )
                if (rejection != null) {
                    AppLogger.w(TAG, "SIMKL POST $endpoint rejected payload ($rejection): ${response.text.take(200)}")
                    false
                } else {
                    writeLimiter.recordSuccess()
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerRateLimitException) {
            AppLogger.w(TAG, e.message ?: "SIMKL write rate limited")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "SIMKL POST $endpoint exception: ${e.message}")
            false
        }
    }

    override fun urlToId(url: String): String? {
        val trimmed = url.trim()
        Regex("simkl\\.com/(anime|tv|movies)/(\\d+)", RegexOption.IGNORE_CASE).find(trimmed)?.let { m ->
            val kind = SimklMediaKind.fromString(m.groupValues[1]) ?: SimklMediaKind.ANIME
            return "${kind.prefix}:${m.groupValues[2]}"
        }
        return SimklMediaRef.parse(trimmed)?.encode()
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult> {
        return searchMedia(
            query = query,
            kinds = listOf(SimklMediaKind.ANIME, SimklMediaKind.SHOW, SimklMediaKind.MOVIE),
        ).map { item ->
            SyncSearchResult(
                name = item.title,
                apiName = this.name,
                syncId = item.ref.encode(),
                url = "$mainUrl/${item.ref.kind.webSegment}/${item.ref.simklId}",
                posterUrl = item.posterUrl,
                type = when (item.ref.kind) {
                    SimklMediaKind.MOVIE -> TvType.Movie
                    SimklMediaKind.SHOW -> TvType.TvSeries
                    SimklMediaKind.ANIME -> if (item.format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                },
                score = Score.from100(item.rating100),
            )
        }
    }

    override suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? {
        val currentAuth = auth ?: return null
        val ref = SimklMediaRef.parse(id) ?: return null
        val (item, _) = getRemoteEntryStatus(currentAuth, ref) ?: return null
        return SyncStatus(
            status = item.watchStatus.toSyncWatchType(),
            score = item.userScore100.takeIf { it > 0 }?.let { Score.from100(it) },
            watchedEpisodes = item.watchedEpisodesCount,
            maxEpisodes = item.totalEpisodesCount,
        )
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        val currentAuth = auth ?: return false
        val ref = SimklMediaRef.parse(id) ?: return false
        return updateRemoteEntry(
            auth = currentAuth,
            ref = ref,
            status = newStatus.status.toUnifiedWatchStatus(),
            progress = newStatus.watchedEpisodes,
            score100 = newStatus.score?.toInt(100),
        )
    }

    override suspend fun load(auth: AuthData?, id: String): SyncResult {
        val ref = SimklMediaRef.parse(id) ?: return SyncResult(id = id)
        val item = getMediaDetails(ref) ?: return SyncResult(id = id)
        return SyncResult(
            id = item.ref.encode(),
            totalEpisodes = item.totalEpisodesCount,
            title = item.title,
            publicScore = Score.from100(item.rating100),
            duration = item.runtimeMin,
            synopsis = item.overview,
            genres = item.genres,
            posterUrl = item.posterUrl,
        )
    }

    override suspend fun library(auth: AuthData?): LibraryMetadata? {
        val currentAuth = auth ?: return null
        val entries = getFullLibrary(currentAuth)
        val statusLabels = linkedMapOf(
            UnifiedWatchStatus.WATCHING to "Watching",
            UnifiedWatchStatus.PLAN_TO_WATCH to "Plan to Watch",
            UnifiedWatchStatus.COMPLETED to "Completed",
            UnifiedWatchStatus.PAUSED to "On Hold",
            UnifiedWatchStatus.DROPPED to "Dropped",
        )
        val grouped = linkedMapOf<UnifiedWatchStatus, MutableList<LibraryItem>>()
        statusLabels.keys.forEach { grouped[it] = mutableListOf() }

        for (entry in entries) {
            val st = entry.watchStatus
            grouped.getOrPut(st) { mutableListOf() }.add(
                LibraryItem(
                    name = entry.title,
                    url = "$mainUrl/${entry.ref.kind.webSegment}/${entry.ref.simklId}",
                    syncId = entry.ref.encode(),
                    episodesCompleted = entry.watchedEpisodesCount,
                    episodesTotal = entry.totalEpisodesCount,
                    personalRating = entry.userScore100.takeIf { it > 0 }?.let { Score.from100(it) },
                    lastUpdatedUnixTime = entry.lastWatchedEpochSec,
                    apiName = this.name,
                    type = when (entry.ref.kind) {
                        SimklMediaKind.MOVIE -> TvType.Movie
                        SimklMediaKind.SHOW -> TvType.TvSeries
                        SimklMediaKind.ANIME -> if (entry.format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                    },
                    posterUrl = entry.posterUrl,
                    posterHeaders = null,
                    quality = null,
                    releaseDate = null,
                ),
            )
        }
        val lists = grouped.mapNotNull { (status, items) ->
            if (items.isEmpty()) return@mapNotNull null
            LibraryList(name = UiText.DynamicString(statusLabels[status] ?: status.label), items = items)
        }
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
        cacheMutex.withLock {
            cachedLibraryKey = null
            cachedLibraryFetchedAtMs = 0L
            cachedByKind.clear()
        }
        readLimiter.clear()
        writeLimiter.clear()
        requireLibraryRefresh = true
        apiBaseUrl = DEFAULT_API_BASE_URL
    }

    companion object {
        private const val TAG = "SimklApi"
        private const val USER_AGENT = "CloudStreamDesktop/1.0"
        private const val OAUTH_AUTHORIZE_URL = "https://simkl.com/oauth2/authorize"
        private const val OAUTH_SCOPE = "media:read media:write"
        internal const val DEFAULT_API_BASE_URL = "https://api.simkl.com"
        private const val POSTER_CDN_PREFIX = "https://wsrv.nl/?url=https://simkl.in/posters/"
        private const val DEFAULT_TOKEN_LIFETIME_SEC = 30L * 24L * 3600L
        private const val LIBRARY_CACHE_TTL_MS = 45_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal val readLimiter = TrackerRateLimiter(
            providerName = "SIMKL",
            minIntervalMs = 150L,
            maxBurst = 6,
            defaultCooldownMs = 30_000L,
        )

        internal val writeLimiter = TrackerRateLimiter(
            providerName = "SIMKL",
            minIntervalMs = 1_050L,
            maxBurst = 1,
            defaultCooldownMs = 30_000L,
        )

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%7E", "~")

        private fun baseHeaders(clientId: String): Map<String, String> = mapOf(
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "simkl-api-key" to clientId,
        )

        private fun authHeaders(clientId: String, accessToken: String): Map<String, String> = mapOf(
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "simkl-api-key" to clientId,
            "Authorization" to "Bearer $accessToken",
        )

        fun buildPosterUrl(rawPoster: String?): String? {
            val cleaned = rawPoster?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true)
            ) {
                return cleaned
            }
            return "$POSTER_CDN_PREFIX${cleaned}_m.webp&q=90"
        }

        internal fun parseActivitiesTimestamps(text: String): Map<SimklMediaKind, String> {
            val root = runCatching { mapper.readTree(text) }.getOrNull() ?: return emptyMap()
            val out = LinkedHashMap<SimklMediaKind, String>(3)
            root["anime"]?.get("all")?.asText()?.takeIf { it.isNotBlank() }?.let {
                out[SimklMediaKind.ANIME] = it
            }
            (root["tv_shows"] ?: root["shows"])?.get("all")?.asText()?.takeIf { it.isNotBlank() }?.let {
                out[SimklMediaKind.SHOW] = it
            }
            root["movies"]?.get("all")?.asText()?.takeIf { it.isNotBlank() }?.let {
                out[SimklMediaKind.MOVIE] = it
            }
            return out
        }

        fun normalizeAirStatus(raw: String?): String? = when (raw?.trim()?.lowercase()) {
            "airing", "continuing", "returning series", "currently airing" -> "RELEASING"
            "ended", "finished", "released", "finished airing" -> "FINISHED"
            "upcoming", "tba", "planned", "in production", "not yet aired" -> "NOT_YET_RELEASED"
            "canceled", "cancelled" -> "CANCELLED"
            else -> null
        }

        fun parseWatchStatus(raw: String?): UnifiedWatchStatus = when (raw?.trim()?.lowercase()) {
            "watching" -> UnifiedWatchStatus.WATCHING
            "completed" -> UnifiedWatchStatus.COMPLETED
            "plantowatch", "plan_to_watch" -> UnifiedWatchStatus.PLAN_TO_WATCH
            "hold", "on_hold", "onhold" -> UnifiedWatchStatus.PAUSED
            "dropped" -> UnifiedWatchStatus.DROPPED
            else -> UnifiedWatchStatus.NONE
        }

        fun toSimklListStatus(status: UnifiedWatchStatus): String = when (status) {
            UnifiedWatchStatus.WATCHING, UnifiedWatchStatus.REWATCHING -> "watching"
            UnifiedWatchStatus.COMPLETED -> "completed"
            UnifiedWatchStatus.PLAN_TO_WATCH -> "plantowatch"
            UnifiedWatchStatus.PAUSED -> "hold"
            UnifiedWatchStatus.DROPPED -> "dropped"
            UnifiedWatchStatus.NONE -> "plantowatch"
        }

        internal fun buildIdsListPayload(listKey: String, simklId: Int): Map<String, Any> = mapOf(
            listKey to listOf(mapOf("ids" to mapOf("simkl" to simklId))),
        )

        internal fun buildEpisodeHistoryPayload(
            ref: SimklMediaRef,
            ordinalRange: IntRange,
            slots: List<SimklEpisodeSlot>,
            watchedAtIso: String,
        ): Map<String, Any> {
            val seasonMap = linkedMapOf<Int, MutableList<Map<String, Any>>>()
            for (ordinal in ordinalRange) {
                val matchedSlot = slots.firstOrNull { it.ordinal == ordinal }
                val seasonNum = matchedSlot?.season ?: 1
                val epNum = matchedSlot?.episode ?: ordinal
                seasonMap.getOrPut(seasonNum) { mutableListOf() }.add(
                    mapOf(
                        "number" to epNum,
                        "watched_at" to watchedAtIso,
                    ),
                )
            }
            val seasonsArray = seasonMap.entries.map { (seasonNum, eps) ->
                mapOf(
                    "number" to seasonNum,
                    "episodes" to eps,
                )
            }
            val topKey = if (ref.kind == SimklMediaKind.ANIME) "anime" else "shows"
            return mapOf(
                topKey to listOf(
                    mapOf(
                        "ids" to mapOf("simkl" to ref.simklId),
                        "seasons" to seasonsArray,
                    ),
                ),
            )
        }

        internal fun parsePinDeviceJson(
            text: String,
            verifier: String?,
            isAuthV2: Boolean,
        ): SimklPinSession? {
            val root = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
            val userCode = root["user_code"]?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val deviceCode = root["device_code"]?.asText()?.trim().orEmpty().ifBlank { userCode }
            val verificationUrl = root["verification_uri_complete"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: root["verification_uri"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: root["verification_url"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "https://simkl.com/pin"
            val expiresIn = root["expires_in"]?.asInt(900) ?: 900
            val interval = (root["interval"]?.asInt(5) ?: 5).coerceAtLeast(3)
            return SimklPinSession(
                pin = AuthPinData(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = verificationUrl,
                    expiresIn = expiresIn,
                    interval = interval,
                ),
                codeVerifier = verifier,
                isAuthV2 = isAuthV2,
            )
        }

        internal fun parseTokenJson(
            text: String,
            fallbackRefresh: String?,
        ): Result<AuthToken> {
            if (text.isBlank()) {
                return Result.failure(IllegalStateException("Empty response from SIMKL token endpoint."))
            }
            val root = runCatching { mapper.readTree(text) }.getOrNull()
                ?: return Result.failure(IllegalStateException("Invalid JSON response from SIMKL token endpoint."))
            val err = root["error"]?.asText()?.takeIf { it.isNotBlank() }
            if (err != null) {
                val msg = root["error_description"]?.asText() ?: root["message"]?.asText() ?: err
                return Result.failure(IllegalStateException("SIMKL token error: $msg"))
            }
            val accessToken = root["access_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.failure(IllegalStateException("SIMKL response did not contain an access_token."))
            val refresh = root["refresh_token"]?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackRefresh
            val expiresIn = root["expires_in"]?.asLong()?.takeIf { it > 0L }
            return Result.success(
                AuthToken(
                    accessToken = accessToken,
                    refreshToken = refresh,
                    accessTokenLifetime = expiresIn?.let { APIHolder.unixTime + it },
                ),
            )
        }

        internal fun parseIsoEpochSeconds(raw: String?): Long {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
            return runCatching { OffsetDateTime.parse(cleaned).toEpochSecond() }
                .recoverCatching { Instant.parse(cleaned).epochSecond }
                .getOrDefault(0L)
        }

        internal fun parseExternalIds(idsNode: JsonNode?): SimklExternalIds? {
            if (idsNode == null || idsNode.isNull || idsNode.isMissingNode) return null
            val simklId = idsNode["simkl"]?.asText()?.toIntOrNull()?.takeIf { it > 0 }
                ?: idsNode["simkl"]?.asInt(0)?.takeIf { it > 0 }
                ?: idsNode["simkl_id"]?.asText()?.toIntOrNull()?.takeIf { it > 0 }
                ?: idsNode["simkl_id"]?.asInt(0)?.takeIf { it > 0 }
                ?: 0
            if (simklId <= 0) return null
            return SimklExternalIds(
                simkl = simklId,
                mal = idsNode["mal"]?.asText()?.toIntOrNull() ?: idsNode["mal"]?.asInt(0)?.takeIf { it > 0 },
                tmdb = idsNode["tmdb"]?.asText()?.toIntOrNull() ?: idsNode["tmdb"]?.asInt(0)?.takeIf { it > 0 },
                imdb = idsNode["imdb"]?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                anilist = idsNode["anilist"]?.asText()?.toIntOrNull() ?: idsNode["anilist"]?.asInt(0)?.takeIf { it > 0 },
                tvdb = idsNode["tvdb"]?.asText()?.toIntOrNull() ?: idsNode["tvdb"]?.asInt(0)?.takeIf { it > 0 },
            )
        }

        internal fun hasSimklPayloadRejection(
            responseText: String,
            expectedEnvelopeKey: String? = null,
            requireNonZeroCount: Boolean = false,
        ): String? {
            if (responseText.isBlank()) {
                return if (expectedEnvelopeKey != null) "empty_response" else null
            }
            val root = runCatching { mapper.readTree(responseText) }.getOrNull()
                ?.takeIf { it.isObject }
                ?: return "invalid_json_response"
            val topError = root["error"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            if (topError != null) return topError
            val notFound = root["not_found"]
            if (notFound != null && notFound.isObject) {
                val fields = notFound.fields()
                while (fields.hasNext()) {
                    val (bucket, node) = fields.next()
                    if (node.isArray && node.size() > 0) {
                        return "not_found[$bucket]: ${node.size()}"
                    }
                }
            }
            if (expectedEnvelopeKey != null) {
                val envelope = root[expectedEnvelopeKey]
                if (envelope == null || !envelope.isObject) {
                    return "missing_$expectedEnvelopeKey"
                }
                if (requireNonZeroCount) {
                    var totalAffected = 0
                    val fields = envelope.fields()
                    while (fields.hasNext()) {
                        val (_, value) = fields.next()
                        totalAffected += when {
                            value.isArray -> value.size()
                            value.isNumber -> value.asInt(0).coerceAtLeast(0)
                            else -> 0
                        }
                    }
                    if (totalAffected <= 0) {
                        return "zero_$expectedEnvelopeKey"
                    }
                }
            }
            return null
        }

        internal fun parseAllItemsJson(text: String, kind: SimklMediaKind): List<SimklMediaItem> {
            val root = runCatching { mapper.readTree(text) }.getOrNull() ?: return emptyList()
            val array = root[kind.listKey] ?: return emptyList()
            if (!array.isArray) return emptyList()

            return array.mapNotNull { entry ->
                val mediaNode = when (kind) {
                    SimklMediaKind.MOVIE -> entry["movie"]
                    SimklMediaKind.ANIME, SimklMediaKind.SHOW -> entry["show"]
                } ?: return@mapNotNull null

                val ids = parseExternalIds(mediaNode["ids"]) ?: return@mapNotNull null
                val title = mediaNode["title"]?.asText()?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null

                val status = parseWatchStatus(entry["status"]?.asText())
                val watchedCount = when (kind) {
                    SimklMediaKind.MOVIE -> when (status) {
                        UnifiedWatchStatus.COMPLETED -> 1
                        UnifiedWatchStatus.PLAN_TO_WATCH -> 0
                        else -> if (entry["last_watched_at"]?.asText()?.isNotBlank() == true) 1 else 0
                    }
                    else -> entry["watched_episodes_count"]?.asInt(0) ?: 0
                }
                val totalEps = when (kind) {
                    SimklMediaKind.MOVIE -> 1
                    else -> entry["total_episodes_count"]?.asInt(0)?.takeIf { it > 0 }
                }
                val notAiredEps = entry["not_aired_episodes_count"]?.asInt(0)?.takeIf { it >= 0 }
                val animeType = entry["anime_type"]?.asText()?.uppercase()?.takeIf { it.isNotEmpty() }
                val rawAirStatus = mediaNode["status"]?.asText() ?: when {
                    totalEps != null && notAiredEps != null && notAiredEps == 0 -> "ended"
                    totalEps != null && notAiredEps != null && notAiredEps in 1 until totalEps -> "airing"
                    totalEps != null && notAiredEps != null && notAiredEps >= totalEps -> "upcoming"
                    else -> null
                }
                val score100 = (entry["user_rating"]?.asInt(0) ?: 0).coerceIn(0, 10) * 10

                SimklMediaItem(
                    ref = SimklMediaRef(kind, ids.simkl),
                    title = title,
                    year = mediaNode["year"]?.asInt(0)?.takeIf { it > 0 },
                    format = when (kind) {
                        SimklMediaKind.MOVIE -> "MOVIE"
                        SimklMediaKind.SHOW -> "SERIES"
                        SimklMediaKind.ANIME -> animeType ?: "TV"
                    },
                    normalizedAirStatus = normalizeAirStatus(rawAirStatus),
                    posterUrl = buildPosterUrl(mediaNode["poster"]?.asText()),
                    runtimeMin = mediaNode["runtime"]?.asInt(0)?.takeIf { it > 0 },
                    externalIds = ids,
                    watchStatus = status,
                    watchedEpisodesCount = watchedCount,
                    totalEpisodesCount = totalEps,
                    notAiredEpisodesCount = notAiredEps,
                    userScore100 = score100,
                    lastWatchedEpochSec = parseIsoEpochSeconds(entry["last_watched_at"]?.asText()),
                )
            }
        }

        internal fun parseDirectMediaDetailJson(node: JsonNode, kind: SimklMediaKind): SimklMediaItem? {
            val ids = parseExternalIds(node["ids"]) ?: return null
            val title = node["title"]?.asText()?.trim().orEmpty()
            if (title.isEmpty()) return null
            val totalEps = when (kind) {
                SimklMediaKind.MOVIE -> 1
                else -> node["total_episodes"]?.asInt(0)?.takeIf { it > 0 }
            }
            val rating100 = node["ratings"]?.get("simkl")?.get("rating")?.asDouble()
                ?.takeIf { it > 0.0 }
                ?.let { (it * 10.0).roundToInt() }
            val animeType = node["anime_type"]?.asText()?.uppercase()?.takeIf { it.isNotEmpty() }
            val genres = node["genres"]?.takeIf { it.isArray }
                ?.mapNotNull { it.asText()?.takeIf { g -> g.isNotBlank() } }
                .orEmpty()

            return SimklMediaItem(
                ref = SimklMediaRef(kind, ids.simkl),
                title = title,
                year = node["year"]?.asInt(0)?.takeIf { it > 0 },
                format = when (kind) {
                    SimklMediaKind.MOVIE -> "MOVIE"
                    SimklMediaKind.SHOW -> "SERIES"
                    SimklMediaKind.ANIME -> animeType ?: "TV"
                },
                normalizedAirStatus = normalizeAirStatus(node["status"]?.asText()),
                posterUrl = buildPosterUrl(node["poster"]?.asText()),
                overview = node["overview"]?.asText()?.takeIf { it.isNotBlank() },
                runtimeMin = node["runtime"]?.asInt(0)?.takeIf { it > 0 },
                rating100 = rating100,
                genres = genres,
                externalIds = ids,
                totalEpisodesCount = totalEps,
            )
        }

        internal fun parseIdLookupJson(text: String): List<SimklMediaItem> {
            val array = runCatching { mapper.readTree(text) }.getOrNull() ?: return emptyList()
            if (!array.isArray) return emptyList()
            return array.mapNotNull { entry ->
                val kind = SimklMediaKind.fromString(entry["type"]?.asText()) ?: return@mapNotNull null
                val ids = parseExternalIds(entry["ids"]) ?: return@mapNotNull null
                val title = entry["title"]?.asText()?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                SimklMediaItem(
                    ref = SimklMediaRef(kind, ids.simkl),
                    title = title,
                    year = entry["year"]?.asInt(0)?.takeIf { it > 0 },
                    format = when (kind) {
                        SimklMediaKind.MOVIE -> "MOVIE"
                        SimklMediaKind.SHOW -> "SERIES"
                        SimklMediaKind.ANIME -> "TV"
                    },
                    posterUrl = buildPosterUrl(entry["poster"]?.asText()),
                    externalIds = ids,
                )
            }
        }

        internal fun parseSearchJson(text: String, kind: SimklMediaKind): List<SimklMediaItem> {
            val array = runCatching { mapper.readTree(text) }.getOrNull() ?: return emptyList()
            if (!array.isArray) return emptyList()
            return array.mapNotNull { entry ->
                val ids = parseExternalIds(entry["ids"]) ?: return@mapNotNull null
                val title = entry["title"]?.asText()?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val totalEps = when (kind) {
                    SimklMediaKind.MOVIE -> 1
                    else -> entry["ep_count"]?.asInt(0)?.takeIf { it > 0 }
                        ?: entry["total_episodes"]?.asInt(0)?.takeIf { it > 0 }
                }
                val rating100 = entry["ratings"]?.get("simkl")?.get("rating")?.asDouble()
                    ?.takeIf { it > 0.0 }
                    ?.let { (it * 10.0).roundToInt() }
                SimklMediaItem(
                    ref = SimklMediaRef(kind, ids.simkl),
                    title = title,
                    year = entry["year"]?.asInt(0)?.takeIf { it > 0 },
                    format = when (kind) {
                        SimklMediaKind.MOVIE -> "MOVIE"
                        SimklMediaKind.SHOW -> "SERIES"
                        SimklMediaKind.ANIME -> entry["anime_type"]?.asText()?.uppercase() ?: "TV"
                    },
                    normalizedAirStatus = normalizeAirStatus(entry["status"]?.asText()),
                    posterUrl = buildPosterUrl(entry["poster"]?.asText()),
                    rating100 = rating100,
                    externalIds = ids,
                    totalEpisodesCount = totalEps,
                )
            }
        }

        internal fun parseEpisodeSlotsJson(text: String): List<SimklEpisodeSlot> {
            val array = runCatching { mapper.readTree(text) }.getOrNull() ?: return emptyList()
            if (!array.isArray) return emptyList()
            val nowSec = System.currentTimeMillis() / 1000L
            var ordinal = 0
            return array.mapNotNull { node ->
                val type = node["type"]?.asText()?.lowercase()
                if (type != null && type != "episode") return@mapNotNull null
                val ep = node["episode"]?.asInt(0) ?: 0
                val season = node["season"]?.asInt(1) ?: 1
                if (ep <= 0 || season <= 0) return@mapNotNull null
                ordinal++
                val dateSec = parseIsoEpochSeconds(node["date"]?.asText()).takeIf { it > 0L }
                val explicitAired = node["aired"]?.takeIf { it.isBoolean }?.asBoolean()
                val isAired = explicitAired ?: (dateSec == null || dateSec <= nowSec)
                SimklEpisodeSlot(
                    ordinal = ordinal,
                    season = season,
                    episode = ep,
                    aired = isAired,
                    airDateEpochSec = dateSec,
                )
            }
        }
    }
}
