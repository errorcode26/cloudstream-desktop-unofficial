package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.data.tracking.TrackingRepositoryImpl
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaKind
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackerWireAndSyncE2ETest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private var server: MockWebServer? = null
    private lateinit var repository: TrackingRepositoryImpl
    private var engineScope: CoroutineScope? = null

    @BeforeEach
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_tracker_e2e_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()

        AniListApi.resetForTesting()
        runBlocking {
            AccountManager.malApi.resetForTesting()
            AccountManager.simklApi.resetForTesting()
        }

        val mockServer = MockWebServer()
        mockServer.start()
        server = mockServer

        val baseUrl = mockServer.url("").toString().trimEnd('/')
        AniListApi.graphQlUrl = "$baseUrl/graphql"
        AniListApi.oauthTokenUrl = "$baseUrl/anilist/oauth/token"

        AccountManager.malApi.apiBaseUrl = "$baseUrl/mal/v2"
        AccountManager.malApi.oauthTokenUrl = "$baseUrl/mal/oauth/token"
        AccountManager.malApi.setCustomClientId("test_mal_client_id")
        AccountManager.malApi.setCustomClientSecret("")

        AccountManager.simklApi.apiBaseUrl = "$baseUrl/simkl"
        AccountManager.simklApi.setCustomClientId("test_simkl_client_id")

        AccountManager.reloadFromDataStore()
        repository = TrackingRepositoryImpl()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        engineScope?.cancel()
        engineScope = null
        server?.shutdown()
        server = null
        AniListApi.resetForTesting()
        runBlocking {
            AccountManager.malApi.resetForTesting()
            AccountManager.simklApi.resetForTesting()
        }
        AccountManager.updateAccounts(TrackerIds.ANILIST, emptyArray())
        AccountManager.updateAccounts(TrackerIds.MAL, emptyArray())
        AccountManager.updateAccounts(TrackerIds.SIMKL, emptyArray())
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `anilist fetches paginated graphql library derives status counts and enters 429 cooldown without spamming server`() = runBlocking {
        repository.updateAccounts(
            TrackerIds.ANILIST,
            arrayOf(
                AuthData(
                    user = AuthUser(id = 777, name = "E2EUser"),
                    token = AuthToken(accessToken = "al_tok", accessTokenLifetime = Long.MAX_VALUE),
                ),
            ),
        )

        val mockServer = server!!
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": {
                    "Page": {
                      "pageInfo": { "total": 17, "currentPage": 1, "lastPage": 2, "hasNextPage": true },
                      "mediaList": [
                        {
                          "status": "CURRENT",
                          "progress": 4,
                          "score": 85,
                          "updatedAt": 1700001000,
                          "media": {
                            "id": 154587,
                            "idMal": 52991,
                            "format": "TV",
                            "status": "FINISHED",
                            "episodes": 28,
                            "seasonYear": 2023,
                            "averageScore": 91,
                            "isFavourite": true,
                            "title": { "userPreferred": "Frieren: Beyond Journey's End" },
                            "coverImage": { "large": "https://cdn.example.org/frieren.jpg" }
                          }
                        }
                      ]
                    },
                    "User": {
                      "statistics": {
                        "anime": {
                          "count": 17,
                          "statuses": [
                            { "status": "CURRENT", "count": 5 },
                            { "status": "COMPLETED", "count": 12 }
                          ]
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": {
                    "Page": {
                      "pageInfo": { "total": 17, "currentPage": 2, "lastPage": 2, "hasNextPage": false },
                      "mediaList": [
                        {
                          "status": "COMPLETED",
                          "progress": 24,
                          "score": 90,
                          "updatedAt": 1700000500,
                          "media": {
                            "id": 9253,
                            "idMal": 9253,
                            "format": "TV",
                            "status": "FINISHED",
                            "episodes": 24,
                            "seasonYear": 2011,
                            "title": { "userPreferred": "Steins;Gate" }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "25")
                .setBody("""{"errors":[{"message":"Too Many Requests"}]}"""),
        )

        val provider = AniListTrackerProvider()
        val page1 = provider.fetchLibraryPage(repository, cursor = null, pageSize = 50).getOrThrow()
        assertTrue(page1.hasMore)
        assertEquals("2", page1.nextCursor)
        assertEquals(17, page1.totalCount)
        assertEquals(5, page1.statusCounts?.get(UnifiedWatchStatus.WATCHING))
        assertEquals(12, page1.statusCounts?.get(UnifiedWatchStatus.COMPLETED))
        assertEquals(listOf("154587"), page1.entries.map { it.remoteMediaId })
        assertTrue(page1.entries.first().isFavourite)

        val req1 = mockServer.takeRequest()
        assertEquals("Bearer al_tok", req1.getHeader("Authorization"))

        val page2 = provider.fetchLibraryPage(repository, cursor = page1.nextCursor, pageSize = 50).getOrThrow()
        assertFalse(page2.hasMore)
        assertEquals(listOf("9253"), page2.entries.map { it.remoteMediaId })
        mockServer.takeRequest()

        // Hit 429 on 3rd call and verify cooldown blocks the 4th call locally before wire execution
        val rateLimited = provider.fetchLibraryPage(repository, statusFilter = UnifiedWatchStatus.PLAN_TO_WATCH, cursor = null, pageSize = 50)
        assertTrue(rateLimited.isFailure)
        assertTrue(AniListApi.rateLimiter.remainingCooldownSeconds() >= 1L)
        assertEquals(3, mockServer.requestCount)

        val blockedLocally = provider.fetchLibraryPage(repository, statusFilter = UnifiedWatchStatus.COMPLETED, cursor = null, pageSize = 50)
        assertTrue(blockedLocally.isFailure)
        assertTrue(blockedLocally.exceptionOrNull()?.message?.contains("429") == true)
        assertEquals(3, mockServer.requestCount, "Subsequent request during cooldown must short-circuit without network I/O")
    }

    @Test
    fun `end to end playback completion syncs anilist episode mutation and writes sqlite audit row`() = runBlocking {
        repository.updateAccounts(
            TrackerIds.ANILIST,
            arrayOf(
                AuthData(
                    user = AuthUser(id = 777, name = "SyncUser"),
                    token = AuthToken(accessToken = "anilist_e2e_token", accessTokenLifetime = Long.MAX_VALUE),
                ),
            ),
        )
        repository.setAutoSyncEnabled(true, TrackerIds.ANILIST)
        repository.setConfirmBeforeSync(false, TrackerIds.ANILIST)

        val showKey = buildTrackerShowKey("AnimeProvider", "https://example.org/shows/frieren")
        repository.saveBinding(
            TrackerBinding(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = TrackerIds.ANILIST,
                showKey = showKey,
                mappedSeason = 1L,
                remoteMediaId = "154587",
                remoteTitle = "Frieren: Beyond Journey's End",
                bindMode = BindMode.USER_PINNED,
                episodeOffset = 0L,
                expectedTotalEpisodes = 28L,
                updatedAt = 1_700_000_000_000L,
            ),
        )

        // 1) Remote status check returns Ep 2/28 CURRENT
        val mockServer = server!!
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": {
                    "Media": {
                      "isFavourite": false,
                      "episodes": 28,
                      "format": "TV",
                      "status": "FINISHED",
                      "seasonYear": 2023,
                      "title": { "userPreferred": "Frieren: Beyond Journey's End", "romaji": "Sousou no Frieren" },
                      "coverImage": { "large": "https://cdn.example.org/frieren.jpg" },
                      "mediaListEntry": { "status": "CURRENT", "progress": 2, "score": 0 }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        // 2) Mutation succeeds
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"SaveMediaListEntry":{"id":999}}}"""),
        )

        val engine = TrackerSyncEngine(
            repository = repository,
            scope = engineScope!!,
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )

        val result = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = DesktopDataStore.activeProfileId.toLong(),
                trackerId = TrackerIds.ANILIST,
                apiName = "AnimeProvider",
                showUrl = "https://example.org/shows/frieren",
                showTitle = "Frieren: Beyond Journey's End",
                mappedSeason = 1,
                episodeNum = 3,
                tvType = TvType.Anime,
                completionVerified = true,
            ),
        )

        val synced = assertIs<SyncExecutionResult.Synced>(result)
        assertEquals(154587, synced.remoteMediaId)
        assertEquals(3, synced.newProgress)
        assertEquals(AniListStatusType.Watching, synced.status)

        val statusReq = mockServer.takeRequest()
        val mutationReq = mockServer.takeRequest()
        assertEquals("Bearer anilist_e2e_token", statusReq.getHeader("Authorization"))
        assertEquals("Bearer anilist_e2e_token", mutationReq.getHeader("Authorization"))
        val mutationBody = mutationReq.body.readUtf8()
        assertTrue(mutationBody.contains("SaveMediaListEntry"))
        assertTrue(mutationBody.contains("= 3"), "Expected progress 3 variable in GraphQL payload: $mutationBody")

        val auditLog = repository.getAuditLog(TrackerIds.ANILIST)
        assertEquals(1, auditLog.size)
        assertEquals(SyncState.SENT, auditLog.first().state)
        assertEquals(2L, auditLog.first().oldProgress)
        assertEquals(3L, auditLog.first().newProgress)
        assertEquals(synced.auditRowId, auditLog.first().id)
    }

    @Test
    fun `mal refreshes token transparently on 401 paginates via offset and merges anime statistics`() = runBlocking {
        repository.updateAccounts(
            TrackerIds.MAL,
            arrayOf(
                AuthData(
                    user = AuthUser(id = 42, name = "MalTester"),
                    token = AuthToken(
                        accessToken = "stale_mal_token",
                        refreshToken = "mal_refresh_tok",
                        accessTokenLifetime = System.currentTimeMillis() / 1000L + 3600L,
                    ),
                ),
            ),
        )

        val mockServer = server!!
        val nextPageUrl = mockServer.url("/mal/v2/users/@me/animelist?offset=50&limit=50").toString()
        mockServer.dispatcher = object : Dispatcher() {
            val animelistCalls = AtomicInteger(0)
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/mal/v2/users/@me/animelist") -> {
                        val callNum = animelistCalls.incrementAndGet()
                        if (callNum == 1) {
                            assertEquals("Bearer stale_mal_token", request.getHeader("Authorization"))
                            MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}""")
                        } else {
                            assertEquals("Bearer fresh_mal_token", request.getHeader("Authorization"))
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "data": [
                                    {
                                      "node": {
                                        "id": 52991,
                                        "title": "Sousou no Frieren",
                                        "alternative_titles": { "en": "Frieren: Beyond Journey's End" },
                                        "media_type": "tv",
                                        "status": "finished_airing",
                                        "num_episodes": 28,
                                        "main_picture": { "large": "https://cdn.mal.example/52991l.jpg" }
                                      },
                                      "list_status": {
                                        "status": "watching",
                                        "score": 9,
                                        "num_episodes_watched": 6,
                                        "is_rewatching": false,
                                        "updated_at": "2026-01-10T12:00:00+00:00"
                                      }
                                    }
                                  ],
                                  "paging": { "next": "$nextPageUrl" }
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    path.startsWith("/mal/oauth/token") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"token_type":"Bearer","expires_in":2678400,"access_token":"fresh_mal_token","refresh_token":"fresh_mal_refresh"}""",
                        )
                    }
                    path.startsWith("/mal/v2/users/@me?fields=") -> {
                        assertEquals("Bearer fresh_mal_token", request.getHeader("Authorization"))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "id": 42,
                              "name": "MalTester",
                              "anime_statistics": {
                                "num_items_watching": 4,
                                "num_items_completed": 20,
                                "num_items_on_hold": 2,
                                "num_items_dropped": 1,
                                "num_items_plan_to_watch": 7,
                                "num_items": 34
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val provider = MalTrackerProvider()
        val page = provider.fetchLibraryPage(repository, cursor = null, pageSize = 50).getOrThrow()

        assertTrue(page.hasMore)
        assertEquals("50", page.nextCursor)
        assertEquals(34, page.totalCount)
        assertEquals(4, page.statusCounts?.get(UnifiedWatchStatus.WATCHING))
        assertEquals(20, page.statusCounts?.get(UnifiedWatchStatus.COMPLETED))
        assertEquals("52991", page.entries.single().remoteMediaId)
        assertEquals(6, page.entries.single().progress)

        val persisted = repository.getAccountsForProvider(TrackerIds.MAL)?.firstOrNull()
        assertNotNull(persisted)
        assertEquals("fresh_mal_token", persisted.token.accessToken)
        assertEquals("fresh_mal_refresh", persisted.token.refreshToken)
    }

    @Test
    fun `simkl uses activities delta timestamps to skip unchanged categories and keeps _m webp posters`() = runBlocking {
        val auth = AuthData(
            user = AuthUser(id = 88, name = "SimklTester"),
            token = AuthToken(accessToken = "simkl_e2e_token"),
        )
        repository.updateAccounts(TrackerIds.SIMKL, arrayOf(auth))

        var animeActivityStamp = "2026-11-26T10:00:00Z"
        val animeFetches = AtomicInteger(0)
        val showFetches = AtomicInteger(0)
        val movieFetches = AtomicInteger(0)

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/simkl/sync/activities") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "anime": { "all": "$animeActivityStamp" },
                          "tv_shows": { "all": "2026-11-26T10:00:00Z" },
                          "movies": { "all": "2026-11-26T10:00:00Z" }
                        }
                        """.trimIndent(),
                    )
                    path.startsWith("/simkl/sync/all-items/anime") -> {
                        animeFetches.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "anime": [
                                {
                                  "status": "watching",
                                  "watched_episodes_count": 3,
                                  "total_episodes_count": 13,
                                  "user_rating": 8,
                                  "last_watched_at": "2026-11-26T09:00:00Z",
                                  "show": {
                                    "title": "My Hero Academia",
                                    "poster": "42/429999abcdef",
                                    "year": 2016,
                                    "ids": { "simkl": 4478, "mal": 31964 }
                                  }
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/simkl/sync/all-items/shows") -> {
                        showFetches.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"shows":[]}""")
                    }
                    path.startsWith("/simkl/sync/all-items/movies") -> {
                        movieFetches.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"movies":[]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val initial = AccountManager.simklApi.getFullLibrary(auth, forceRefresh = true)
        assertEquals(1, initial.size)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/42/429999abcdef_m.webp&q=90", initial.single().posterUrl)
        assertEquals(1, animeFetches.get())
        assertEquals(1, showFetches.get())
        assertEquals(1, movieFetches.get())

        // Force-refresh with identical timestamps -> activities checked, zero all-items requests made
        AccountManager.simklApi.getFullLibrary(auth, forceRefresh = true)
        assertEquals(1, animeFetches.get())
        assertEquals(1, showFetches.get())
        assertEquals(1, movieFetches.get())

        // Advance only anime stamp -> ONLY /sync/all-items/anime is refetched
        animeActivityStamp = "2026-11-27T11:30:00Z"
        val reloaded = AccountManager.simklApi.getFullLibrary(auth, forceRefresh = true)
        assertEquals(1, reloaded.size)
        assertEquals(2, animeFetches.get())
        assertEquals(1, showFetches.get())
        assertEquals(1, movieFetches.get())
    }

    @Test
    fun `simkl movie moved to plan_to_watch clears history bypasses cache and handles not_found rejections`() = runBlocking {
        val auth = AuthData(
            user = AuthUser(id = 2, name = "MovieTester"),
            token = AuthToken(accessToken = "simkl_token_movie"),
        )
        var movieStatusOnServer: String? = "completed"
        var movieWatchedTimestamp: String? = "2026-11-25T10:00:00Z"
        var movieUserRating: Int = 8
        var activityVersion = "2026-11-27T01:00:00Z"
        val historyRemoveCalls = AtomicInteger(0)
        val addToListStatuses = mutableListOf<String>()
        val requestLog = mutableListOf<String>()
        var rejectWithNotFound = false
        var removeMode = "normal" // "normal" | "zero" | "html"

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                return when {
                    path.startsWith("/simkl/sync/activities") -> MockResponse().setResponseCode(200).setBody(
                        """{"anime":{"all":"2026-11-20T00:00:00Z"},"tv_shows":{"all":"2026-11-20T00:00:00Z"},"movies":{"all":"$activityVersion"}}""",
                    )
                    path.startsWith("/simkl/sync/all-items/anime") -> MockResponse().setResponseCode(200).setBody("""{"anime":[]}""")
                    path.startsWith("/simkl/sync/all-items/shows") -> MockResponse().setResponseCode(200).setBody("""{"shows":[]}""")
                    path.startsWith("/simkl/sync/all-items/movies") -> {
                        val moviesArray = if (movieStatusOnServer == null) {
                            "[]"
                        } else {
                            """
                            [
                              {
                                "status": "$movieStatusOnServer",
                                "user_rating": $movieUserRating,
                                "last_watched_at": ${movieWatchedTimestamp?.let { "\"$it\"" } ?: "null"},
                                "movie": {
                                  "title": "Sample Feature Film",
                                  "year": 2025,
                                  "ids": { "simkl": 5551 }
                                }
                              }
                            ]
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody("""{"movies":$moviesArray}""")
                    }
                    path.startsWith("/simkl/sync/add-to-list") -> {
                        requestLog += "add-to-list"
                        if (rejectWithNotFound) {
                            return MockResponse().setResponseCode(200).setBody(
                                """{"not_found":{"movies":[{"ids":{"simkl":5551}}]}}""",
                            )
                        }
                        if (body.contains("plantowatch")) {
                            addToListStatuses += "plantowatch"
                            movieStatusOnServer = "plantowatch"
                        } else if (body.contains("completed")) {
                            addToListStatuses += "completed"
                            movieStatusOnServer = "completed"
                        }
                        activityVersion = "2026-11-27T02:00:00Z"
                        MockResponse().setResponseCode(200).setBody("""{"added":{"movies":1}}""")
                    }
                    path.startsWith("/simkl/sync/ratings") -> {
                        requestLog += "ratings"
                        if (body.contains("\"rating\":8")) movieUserRating = 8
                        activityVersion = "2026-11-27T03:00:00Z"
                        MockResponse().setResponseCode(200).setBody("""{"added":{"movies":[{"rating":8,"ids":{"simkl":5551}}]}}""")
                    }
                    path.startsWith("/simkl/sync/history/remove") -> {
                        requestLog += "history/remove"
                        historyRemoveCalls.incrementAndGet()
                        when (removeMode) {
                            "zero" -> MockResponse().setResponseCode(200).setBody(
                                """{"deleted":{"movies":0,"shows":0,"episodes":0},"not_found":{"movies":[],"shows":[],"episodes":[]}}""",
                            )
                            "html" -> MockResponse().setResponseCode(200).setBody("<html>200</html>")
                            else -> {
                                // Per SIMKL OpenAPI spec, removing a movie without episodes clears watchlist, history, and rating.
                                movieStatusOnServer = null
                                movieWatchedTimestamp = null
                                movieUserRating = 0
                                activityVersion = "2026-11-27T04:00:00Z"
                                MockResponse().setResponseCode(200).setBody("""{"deleted":{"movies":1,"shows":0,"episodes":0},"not_found":{"movies":[],"shows":[],"episodes":[]}}""")
                            }
                        }
                    }
                    path.startsWith("/simkl/sync/remove-from-list") -> {
                        error("Deprecated /sync/remove-from-list must never be called")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{}""")
                }
            }
        }

        val simkl = AccountManager.simklApi
        val ref = com.lagradost.cloudstream3.syncproviders.providers.SimklMediaRef(SimklMediaKind.MOVIE, 5551)

        val firstFetch = simkl.getFullLibrary(auth, forceRefresh = true)
        assertEquals(UnifiedWatchStatus.COMPLETED, firstFetch.single().watchStatus)
        assertEquals(1, firstFetch.single().watchedEpisodesCount)
        assertEquals(80, firstFetch.single().userScore100)

        // Switching movie to PLAN_TO_WATCH must clear history BEFORE re-adding to plantowatch and preserve rating
        val ok = simkl.updateRemoteEntry(
            auth = auth,
            ref = ref,
            status = UnifiedWatchStatus.PLAN_TO_WATCH,
            progress = 0,
            score100 = null,
        )
        assertTrue(ok)
        assertEquals(listOf("plantowatch"), addToListStatuses)
        assertEquals(1, historyRemoveCalls.get())
        assertEquals(listOf("history/remove", "add-to-list", "ratings"), requestLog)
        assertTrue(simkl.requireLibraryRefresh)

        // Even with forceRefresh = false within 45s cache window, requireLibraryRefresh must bypass cache
        val refreshed = simkl.getFullLibrary(auth, forceRefresh = false)
        assertEquals(UnifiedWatchStatus.PLAN_TO_WATCH, refreshed.single().watchStatus)
        assertEquals(0, refreshed.single().watchedEpisodesCount)
        assertEquals(80, refreshed.single().userScore100)
        assertFalse(simkl.requireLibraryRefresh)

        // Removing entry must call /sync/history/remove and reject deleted=0 or HTML 200 responses
        removeMode = "zero"
        assertFalse(simkl.removeRemoteEntry(auth, ref))
        removeMode = "html"
        assertFalse(simkl.removeRemoteEntry(auth, ref))

        removeMode = "normal"
        assertTrue(simkl.removeRemoteEntry(auth, ref))
        val afterDelete = simkl.getFullLibrary(auth, forceRefresh = false)
        assertTrue(afterDelete.isEmpty())

        // Verify silent not_found payloads cause updateRemoteEntry to return false
        rejectWithNotFound = true
        val rejected = simkl.updateRemoteEntry(
            auth = auth,
            ref = ref,
            status = UnifiedWatchStatus.COMPLETED,
            progress = 1,
            score100 = null,
        )
        assertFalse(rejected)
    }
}
