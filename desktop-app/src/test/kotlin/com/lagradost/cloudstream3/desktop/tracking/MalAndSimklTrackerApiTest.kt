package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaItem
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaKind
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalAndSimklTrackerApiTest {

    @Test
    fun `pkce helpers generate url safe verifier state and s256 challenge`() {
        val verifier = TrackerOAuthLoopback.generateCodeVerifier(64)
        assertEquals(64, verifier.length)
        assertTrue(verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })

        val state = TrackerOAuthLoopback.generateState(24)
        assertEquals(24, state.length)

        val challenge = TrackerOAuthLoopback.sha256Base64Url("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `mal authorize request uses plain pkce method and parses anime and token json`() {
        val malApi = MalApi()
        val authReq = malApi.buildAuthorizeRequest("http://127.0.0.1:41933/mallogin")
        assertTrue(authReq.url.contains("code_challenge_method=plain"))
        assertTrue(authReq.url.contains("code_challenge=${authReq.codeVerifier}"))
        assertTrue(authReq.url.contains("state=${authReq.state}"))

        val tokenJson = """
            {"token_type":"Bearer","expires_in":2678400,"access_token":"mal_access_123","refresh_token":"mal_refresh_456"}
        """.trimIndent()
        val parsedToken = MalApi.parseTokenResponseJson(tokenJson).getOrThrow()
        assertEquals("mal_access_123", parsedToken.accessToken)
        assertEquals("mal_refresh_456", parsedToken.refreshToken)
        assertNotNull(parsedToken.accessTokenLifetime)

        val nodeJson = """
            {
              "id": 52991,
              "title": "Sousou no Frieren",
              "alternative_titles": { "en": "Frieren: Beyond Journey's End", "ja": "葬送のフリーレン" },
              "mean": 9.3,
              "media_type": "tv",
              "status": "currently_airing",
              "num_episodes": 0,
              "start_season": { "year": 2023 },
              "my_list_status": {
                "status": "watching",
                "score": 9,
                "num_episodes_watched": 14,
                "is_rewatching": true,
                "updated_at": "2026-01-15T10:20:30+00:00"
              }
            }
        """.trimIndent()
        val node = MalApi.parseAnimeNodeJson(mapper.readTree(nodeJson), mapper.readTree(nodeJson)["my_list_status"])
        assertNotNull(node)
        assertEquals(52991, node.id)
        assertEquals("Frieren: Beyond Journey's End", node.englishTitle)
        assertEquals("TV", node.normalizedFormat)
        assertEquals("RELEASING", node.normalizedAirStatus)
        assertNull(node.knownEpisodes)
        assertEquals(93, node.meanScore100)
        assertEquals(UnifiedWatchStatus.REWATCHING, node.myListStatus?.toUnifiedStatus())
        assertEquals(14, node.myListStatus?.numEpisodesWatched)
        assertEquals(9, node.myListStatus?.score)

        assertEquals("watching" to true, MalApi.toMalStatusParam(UnifiedWatchStatus.REWATCHING))
        assertEquals("on_hold" to false, MalApi.toMalStatusParam(UnifiedWatchStatus.PAUSED))
        assertEquals("52991", malApi.urlToId("https://myanimelist.net/anime/52991/Sousou_no_Frieren"))
    }

    @Test
    fun `simkl parses typed refs pin payloads library items and enforces aired episode ceiling`() {
        val refAnime = SimklMediaRef.parse("anime:4478")
        assertNotNull(refAnime)
        assertEquals(SimklMediaKind.ANIME, refAnime.kind)
        assertEquals(4478, refAnime.simklId)
        assertEquals("anime:4478", refAnime.encode())

        val pinV2 = SimklApi.parsePinDeviceJson(
            text = """
                {
                  "device_code": "dev_999",
                  "user_code": "ABCD-1234",
                  "verification_uri": "https://simkl.com/pin",
                  "expires_in": 900,
                  "interval": 5
                }
            """.trimIndent(),
            verifier = "ver_abc",
            isAuthV2 = true,
        )
        assertNotNull(pinV2)
        assertTrue(pinV2.isAuthV2)
        assertEquals("ABCD-1234", pinV2.pin.userCode)
        assertEquals("https://simkl.com/pin", pinV2.pin.verificationUrl)

        val allAnimeJson = """
            {
              "anime": [
                {
                  "status": "watching",
                  "user_rating": 8,
                  "watched_episodes_count": 5,
                  "total_episodes_count": 24,
                  "not_aired_episodes_count": 10,
                  "anime_type": "tv",
                  "last_watched_at": "2026-02-01T12:00:00Z",
                  "show": {
                    "title": "Sample Airing Anime",
                    "year": 2026,
                    "poster": "12/3456abcd",
                    "status": "airing",
                    "ids": { "simkl": 8888, "mal": 55555, "anilist": 99999 }
                  }
                }
              ]
            }
        """.trimIndent()
        val parsed = SimklApi.parseAllItemsJson(allAnimeJson, SimklMediaKind.ANIME)
        assertEquals(1, parsed.size)
        val item = parsed.first()
        assertEquals("anime:8888", item.ref.encode())
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/12/3456abcd_w.webp&q=90", item.posterUrl)
        assertEquals(80, item.userScore100)
        assertEquals(5, item.watchedEpisodesCount)
        assertEquals("RELEASING", item.normalizedAirStatus)

        val bounds = item.computeReleaseBounds()
        assertFalse(bounds.canMarkCompleted)
        assertEquals(14, bounds.maxAiredEpisode)
        assertEquals(14, bounds.ceilingEpisode)

        val provider = SimklTrackerProvider()
        assertIs<GuardDecision.Blocked>(
            provider.validateStatusAndProgress(bounds, UnifiedWatchStatus.COMPLETED, 14),
        )
        assertIs<EpisodeSyncCheck.Blocked>(
            provider.canSyncEpisode(bounds, rawEpisodeNumber = 15),
        )
        assertIs<EpisodeSyncCheck.Allowed>(
            provider.canSyncEpisode(bounds, rawEpisodeNumber = 14),
        )
    }

    @Test
    fun `simkl resolver ignores numeric tmdb collision with wrong media kind`() = runBlocking {
        val repo = InMemoryTestTrackingRepo()
        val wrongTypeShowHit = SimklMediaItem(
            ref = SimklMediaRef(SimklMediaKind.SHOW, 777),
            title = "Different TV Series Sharing Same TMDB Numeric ID",
            year = 2024,
            format = "SERIES",
        )
        val correctMovieHit = SimklMediaItem(
            ref = SimklMediaRef(SimklMediaKind.MOVIE, 53536),
            title = "Inception",
            year = 2010,
            format = "MOVIE",
        )

        val resolution = SimklMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "test:inception",
                showTitle = "Inception",
                mappedSeason = 1,
                year = 2010,
                tvType = TvType.Movie,
                canonicalSeed = CanonicalMediaSeed(
                    origin = MediaSeedOrigin.EXPLORE,
                    canonicalId = "tmdb:27205",
                    tmdbId = 27205,
                    expectedTitle = "Inception",
                    expectedYear = 2010,
                ),
                resolvedTmdbId = 27205,
            ),
            repository = repo,
            lookupByIds = { _, _, _, _ -> listOf(wrongTypeShowHit, correctMovieHit) },
            fetchDetails = { it },
            searchRemote = { _, _ -> emptyList() },
        )

        val bound = assertIs<MatchResolution.Bound>(resolution)
        assertEquals("movie:53536", bound.binding.remoteMediaId)
        assertEquals(BindMode.AUTO_VERIFIED, bound.binding.bindMode)
    }

    @Test
    fun `simkl parses simkl_id in search payload and auto binds verified resolved ids without explore seed`() = runBlocking {
        assertEquals("movie", SimklMediaKind.MOVIE.searchEndpoint)
        assertEquals("tv", SimklMediaKind.SHOW.searchEndpoint)

        val searchJson = """
            [
              {
                "title": "The Lord of the Rings: The Rings of Power",
                "year": 2022,
                "poster": "20/2074178531633fb87c",
                "total_episodes": 16,
                "ids": { "simkl_id": 1039812, "slug": "the-lord-of-the-rings-the-rings-of-power" }
              }
            ]
        """.trimIndent()
        val parsedSearch = SimklApi.parseSearchJson(searchJson, SimklMediaKind.SHOW)
        assertEquals(1, parsedSearch.size)
        assertEquals("show:1039812", parsedSearch.first().ref.encode())
        assertEquals(16, parsedSearch.first().totalEpisodesCount)
        assertEquals(
            "https://wsrv.nl/?url=https://simkl.in/posters/20/2074178531633fb87c_w.webp&q=90",
            parsedSearch.first().posterUrl,
        )

        val repo = InMemoryTestTrackingRepo()
        val showHit = parsedSearch.first()
        val resolution = SimklMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "test:lotr_rop",
                showTitle = "The Lord of the Rings: The Rings of Power",
                mappedSeason = 1,
                year = 2022,
                tvType = TvType.TvSeries,
                canonicalSeed = null,
                resolvedTmdbId = 84773,
                resolvedImdbId = "tt7631058",
            ),
            repository = repo,
            lookupByIds = { _, _, _, _ -> listOf(showHit) },
            fetchDetails = { it },
            searchRemote = { _, _ -> emptyList() },
        )

        val bound = assertIs<MatchResolution.Bound>(resolution)
        assertEquals("show:1039812", bound.binding.remoteMediaId)
        assertEquals(BindMode.AUTO_VERIFIED, bound.binding.bindMode)
    }

    @Test
    fun `tracker error helpers surface actionable messages for mal invalid_client and anilist 400 json`() {
        val malErr = MalApi.parseTokenResponseJson(
            """{"error":"invalid_client","message":"Client authentication failed"}""",
        )
        assertTrue(malErr.isFailure)
        val malMsg = malErr.exceptionOrNull()?.message.orEmpty()
        assertTrue(malMsg.contains("Client authentication failed"))
        assertTrue(malMsg.contains("Client Secret"))

        val aniMsg = AniListApi.extractGraphQlError(
            """{"data":{"Viewer":null},"errors":[{"message":"Invalid token","status":400}]}""",
        )
        assertEquals("Invalid token", aniMsg)
    }

    @Test
    fun `simkl auth url uses oauth2 authorize and requests read and write scopes`() {
        val simklApi = SimklApi()
        val req = simklApi.buildAuthorizeRequest("http://127.0.0.1:41934/simkllogin")
        assertTrue(req.url.startsWith("https://simkl.com/oauth2/authorize?"))
        assertTrue(req.url.contains("scope=media%3Aread%20media%3Awrite"))
        assertTrue(req.url.contains("code_challenge_method=S256"))
    }

    @Test
    fun `anilist authorize urls and token response parser support authorization code flow`() {
        val aniListApi = AniListApi()
        val loopbackUrl = aniListApi.buildAuthorizeUrl(AniListOAuthFlow.LOOPBACK_REDIRECT_URI)
        assertTrue(loopbackUrl.startsWith("https://anilist.co/api/v2/oauth/authorize?"))
        assertTrue(loopbackUrl.contains("response_type=code"))
        assertTrue(loopbackUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A41932%2Fanilistlogin"))

        val defaultPageUrl = aniListApi.loginRequest().url
        assertTrue(defaultPageUrl.contains("response_type=code"))

        val validJson = """
            {"token_type":"Bearer","expires_in":31536000,"access_token":"ani_access_789","refresh_token":"ani_refresh_012"}
        """.trimIndent()
        val parsed = AniListApi.parseTokenResponseJson(validJson).getOrThrow()
        assertEquals("ani_access_789", parsed.accessToken)
        assertEquals("ani_refresh_012", parsed.refreshToken)
        assertNotNull(parsed.accessTokenLifetime)

        val invalidClient = AniListApi.parseTokenResponseJson(
            """{"error":"invalid_client","message":"Client authentication failed"}""",
        )
        assertTrue(invalidClient.isFailure)
        assertTrue(invalidClient.exceptionOrNull()?.message.orEmpty().contains("Client Secret"))
    }
}

private class InMemoryTestTrackingRepo : TrackingRepository {
    override val accounts: StateFlow<Map<String, Array<AuthData>>> = MutableStateFlow(emptyMap())
    override val bindingsVersion: StateFlow<Long> = MutableStateFlow(0L)
    override val auditVersion: StateFlow<Long> = MutableStateFlow(0L)
    override val settingsVersion: StateFlow<Long> = MutableStateFlow(0L)

    private val bindings = mutableMapOf<String, TrackerBinding>()

    override fun getAccountsForProvider(idPrefix: String): Array<AuthData>? = null
    override fun updateAccounts(idPrefix: String, accounts: Array<AuthData>) {}
    override fun isAutoSyncEnabled(trackerId: String): Boolean = true
    override suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String) {}
    override fun getBindPolicy(trackerId: String): TrackerBindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY
    override suspend fun setBindPolicy(policy: TrackerBindPolicy, trackerId: String) {}
    override fun isConfirmBeforeSync(trackerId: String): Boolean = false
    override suspend fun setConfirmBeforeSync(enabled: Boolean, trackerId: String) {}

    override suspend fun getBinding(showKey: String, mappedSeason: Int, trackerId: String): TrackerBinding? =
        bindings["$trackerId:$showKey:$mappedSeason"]

    override suspend fun getBindingsForShow(showKey: String, trackerId: String): List<TrackerBinding> =
        bindings.values.filter { it.trackerId == trackerId && it.showKey == showKey }

    override suspend fun getAllBindingsForProfile(trackerId: String): List<TrackerBinding> =
        bindings.values.filter { it.trackerId == trackerId }

    override suspend fun saveBinding(binding: TrackerBinding) {
        bindings["${binding.trackerId}:${binding.showKey}:${binding.mappedSeason}"] = binding
    }

    override suspend fun deleteBinding(showKey: String, mappedSeason: Int, trackerId: String) {
        bindings.remove("$trackerId:$showKey:$mappedSeason")
    }

    override suspend fun recordAudit(
        profileId: Long,
        trackerId: String,
        showName: String,
        remoteMediaId: String,
        episodeNum: Long,
        effectiveRemoteEpisode: Long,
        oldProgress: Long,
        newProgress: Long,
        statusApplied: String?,
        state: SyncState,
        reason: String?,
        timestamp: Long,
    ): Long = 1L

    override suspend fun updateAuditState(rowId: Long, state: SyncState, reason: String?) {}
    override suspend fun getAuditLog(trackerId: String): List<TrackerSyncAuditEntry> = emptyList()
}
