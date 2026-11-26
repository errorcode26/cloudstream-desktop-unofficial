package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.providers.MalAnimeNode
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListTitleHolder
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchTitle
import com.lagradost.cloudstream3.syncproviders.providers.anilist.MediaTitle
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonData
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonResponse
import com.lagradost.cloudstream3.syncproviders.providers.anilist.StartedAt
import com.lagradost.cloudstream3.syncproviders.providers.anilist.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrackerAutoMatchAndSyncTest {

    @Test
    fun `bind policy round trips strict automatic and manual modes cleanly`() {
        assertEquals(TrackerBindPolicy.STRICT_AUTO_AND_PINNED, TrackerBindPolicy.fromString("STRICT_AUTO_AND_PINNED"))
        assertEquals(TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY, TrackerBindPolicy.fromString("EXPLORE_OR_PINNED_ONLY"))
        assertEquals(TrackerBindPolicy.MANUAL_PIN_ONLY, TrackerBindPolicy.fromString("MANUAL_PIN_ONLY"))
        assertEquals(TrackerBindPolicy.STRICT_AUTO_AND_PINNED, TrackerBindPolicy.fromString(null))
        assertTrue(TrackerBindPolicy.STRICT_AUTO_AND_PINNED.displayName != TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY.displayName)
    }

    @Test
    fun `anilist resolver auto binds via enriched id and unambiguous search without explore seed`() = runBlocking {
        val repo = TestAutoTrackingRepository()
        val mhaMedia = seasonMedia(id = 21459, title = "My Hero Academia", year = 2016, episodes = 13)

        val enrichedResolution = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "prov:mha",
                showTitle = "My Hero Academia",
                mappedSeason = 1,
                year = 2016,
                tvType = TvType.Anime,
                canonicalSeed = null,
                resolvedAnilistId = 21459,
                bindPolicy = TrackerBindPolicy.STRICT_AUTO_AND_PINNED,
            ),
            repository = repo,
            fetchSeason = { id -> if (id == 21459) SeasonResponse(SeasonData(mhaMedia)) else null },
            fetchByMalId = { null },
            searchRemote = { emptyList() },
        )
        val enrichedBound = assertIs<MatchResolution.Bound>(enrichedResolution)
        assertEquals("21459", enrichedBound.binding.remoteMediaId)

        val repoSearch = TestAutoTrackingRepository()
        val searchResolution = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "prov:frieren",
                showTitle = "Frieren Beyond Journeys End",
                mappedSeason = 1,
                year = 2023,
                tvType = TvType.Anime,
                canonicalSeed = null,
                bindPolicy = TrackerBindPolicy.STRICT_AUTO_AND_PINNED,
            ),
            repository = repoSearch,
            fetchSeason = { id ->
                if (id == 154587) {
                    SeasonResponse(
                        SeasonData(
                            seasonMedia(id = 154587, title = "Frieren: Beyond Journey's End", year = 2023, episodes = 28),
                        ),
                    )
                } else {
                    null
                }
            },
            fetchByMalId = { null },
            searchRemote = {
                listOf(
                    GetSearchMedia(
                        id = 154587,
                        format = "TV",
                        status = "FINISHED",
                        episodes = 28,
                        seasonYear = 2023,
                        title = GetSearchTitle(english = "Frieren: Beyond Journey's End", romaji = "Sousou no Frieren"),
                        startDate = StartedAt(year = "2023"),
                    ),
                )
            },
        )
        val searchBound = assertIs<MatchResolution.Bound>(searchResolution)
        assertEquals("154587", searchBound.binding.remoteMediaId)
    }

    @Test
    fun `manual pin mode still preloads ranked candidates for anilist and mal`() = runBlocking {
        val repo = TestAutoTrackingRepository()
        val aniManual = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "prov:mha",
                showTitle = "My Hero Academia",
                mappedSeason = 1,
                year = 2016,
                tvType = TvType.Anime,
                bindPolicy = TrackerBindPolicy.MANUAL_PIN_ONLY,
            ),
            repository = repo,
            fetchSeason = { null },
            fetchByMalId = { null },
            searchRemote = {
                listOf(
                    GetSearchMedia(
                        id = 21459,
                        format = "TV",
                        episodes = 13,
                        seasonYear = 2016,
                        title = GetSearchTitle(english = "My Hero Academia"),
                        startDate = StartedAt(year = "2016"),
                    ),
                )
            },
        )
        val aniConfirm = assertIs<MatchResolution.NeedsConfirmation>(aniManual)
        assertEquals(1, aniConfirm.candidates.size)
        assertEquals(21459, aniConfirm.candidates.first().anilistId)

        val malStrictSearch = MalMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = "prov:mal_search",
                showTitle = "Steins Gate",
                mappedSeason = 1,
                year = 2011,
                tvType = TvType.Anime,
                bindPolicy = TrackerBindPolicy.STRICT_AUTO_AND_PINNED,
            ),
            repository = repo,
            fetchDetails = { _, _ -> null },
            fetchMalIdFromAniList = { null },
            searchRemote = { _, _ ->
                listOf(
                    MalAnimeNode(
                        id = 9253,
                        title = "Steins;Gate",
                        englishTitle = "Steins;Gate",
                        normalizedFormat = "TV",
                        knownEpisodes = 24,
                        startSeasonYear = 2011,
                    ),
                )
            },
        )
        val malBound = assertIs<MatchResolution.Bound>(malStrictSearch)
        assertEquals("9253", malBound.binding.remoteMediaId)
    }

    @Test
    fun `sync engine allows verified anime movie completion without episode number`() = runBlocking {
        val repo = TestAutoTrackingRepository()
        repo.setAccount(
            TrackerIds.ANILIST,
            AuthData(
                user = AuthUser(name = "Tester", id = 1, profilePicture = null),
                token = AuthToken(accessToken = "valid_token"),
            ),
        )
        repo.saveBinding(
            TrackerBinding(
                profileId = 1L,
                trackerId = TrackerIds.ANILIST,
                showKey = buildTrackerShowKey("TestProvider", "https://example.com/your-name"),
                mappedSeason = 1L,
                remoteMediaId = "21519",
                remoteTitle = "Your Name.",
                bindMode = BindMode.USER_PINNED,
                episodeOffset = 0L,
                expectedTotalEpisodes = 1L,
                updatedAt = 1L,
            ),
        )

        var postedProgress: Int? = null
        var postedStatus: AniListStatusType? = null
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = object : TrackerRemoteGateway {
                override suspend fun getSeason(id: Int): SeasonResponse? = null
                override suspend fun getSeasonByMalId(malId: Int): SeasonResponse? = null
                override suspend fun searchShows(query: String): List<GetSearchMedia> = emptyList()
                override suspend fun getDataAboutId(auth: AuthData, id: Int): AniListTitleHolder = AniListTitleHolder(
                    title = Title(english = "Your Name."),
                    isFavourite = false,
                    id = 21519,
                    progress = 0,
                    episodes = 1,
                    score = 0,
                    type = AniListStatusType.Planning,
                    format = "MOVIE",
                    mediaStatus = "FINISHED",
                    seasonYear = 2016,
                )

                override suspend fun postDataAboutId(
                    auth: AuthData,
                    id: Int,
                    type: AniListStatusType,
                    score: Score?,
                    progress: Int?,
                ): Boolean {
                    postedProgress = progress
                    postedStatus = type
                    return true
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )

        val result = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                trackerId = TrackerIds.ANILIST,
                apiName = "TestProvider",
                showUrl = "https://example.com/your-name",
                showTitle = "Your Name.",
                mappedSeason = 1,
                episodeNum = null,
                year = 2016,
                tvType = TvType.AnimeMovie,
                completionVerified = true,
            ),
        )

        val synced = assertIs<SyncExecutionResult.Synced>(result)
        assertEquals(1, synced.newProgress)
        assertEquals(1, postedProgress)
        assertEquals(AniListStatusType.Completed, postedStatus)
    }

    private fun seasonMedia(id: Int, title: String, year: Int, episodes: Int): SeasonMedia = SeasonMedia(
        id = id,
        title = MediaTitle(romaji = title, english = title, native = null, userPreferred = title),
        idMal = null,
        format = "TV",
        seasonYear = year,
        status = "FINISHED",
        nextAiringEpisode = null,
        relations = null,
        coverImage = null,
        duration = 24,
        episodes = episodes,
        genres = emptyList(),
        synonyms = emptyList(),
        averageScore = 85,
        isAdult = false,
        trailer = null,
        description = null,
        characters = null,
        recommendations = null,
    )
}

private class TestAutoTrackingRepository : TrackingRepository {
    private val _accounts = MutableStateFlow<Map<String, Array<AuthData>>>(emptyMap())
    override val accounts: StateFlow<Map<String, Array<AuthData>>> = _accounts
    override val bindingsVersion: StateFlow<Long> = MutableStateFlow(0L)
    override val auditVersion: StateFlow<Long> = MutableStateFlow(0L)
    override val settingsVersion: StateFlow<Long> = MutableStateFlow(0L)

    private val bindings = mutableMapOf<String, TrackerBinding>()

    fun setAccount(idPrefix: String, auth: AuthData) {
        _accounts.value = _accounts.value + (idPrefix to arrayOf(auth))
    }

    override fun getAccountsForProvider(idPrefix: String): Array<AuthData>? = _accounts.value[idPrefix]
    override fun updateAccounts(idPrefix: String, accounts: Array<AuthData>) {
        _accounts.value = _accounts.value + (idPrefix to accounts)
    }

    override fun isAutoSyncEnabled(trackerId: String): Boolean = true
    override suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String) {}
    override fun getBindPolicy(trackerId: String): TrackerBindPolicy = TrackerBindPolicy.STRICT_AUTO_AND_PINNED
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
