package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.metadata.MediaSeedOrigin
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListTitleHolder
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetSearchTitle
import com.lagradost.cloudstream3.syncproviders.providers.anilist.MediaTitle
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonData
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonEdge
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonEdges
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonNode
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonResponse
import com.lagradost.cloudstream3.syncproviders.providers.anilist.StartedAt
import com.lagradost.cloudstream3.syncproviders.providers.anilist.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AniListMatchAndSyncEngineTest {

    private class FakeTrackingRepository(
        var autoSync: Boolean = true,
        var bindPolicy: TrackerBindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY,
        var confirmBeforeSync: Boolean = false,
        initialAccount: AuthData? = AuthData(
            user = AuthUser(name = "Tester", id = 1001),
            token = AuthToken(accessToken = "valid_token"),
        ),
    ) : TrackingRepository {
        private val _accounts = MutableStateFlow(
            if (initialAccount != null) mapOf(TrackerIds.ANILIST to arrayOf(initialAccount)) else emptyMap(),
        )
        override val accounts: StateFlow<Map<String, Array<AuthData>>> = _accounts.asStateFlow()

        private val _bindingsVersion = MutableStateFlow(0L)
        override val bindingsVersion: StateFlow<Long> = _bindingsVersion.asStateFlow()

        private val _auditVersion = MutableStateFlow(0L)
        override val auditVersion: StateFlow<Long> = _auditVersion.asStateFlow()

        private val _settingsVersion = MutableStateFlow(0L)
        override val settingsVersion: StateFlow<Long> = _settingsVersion.asStateFlow()

        val bindings = mutableMapOf<String, TrackerBinding>()
        val audits = mutableListOf<TrackerSyncAuditEntry>()
        private var nextAuditId = 1L

        private fun bKey(showKey: String, season: Int, trackerId: String) = "$trackerId:$showKey:$season"

        override fun getAccountsForProvider(idPrefix: String): Array<AuthData>? = _accounts.value[idPrefix]

        override fun updateAccounts(idPrefix: String, accounts: Array<AuthData>) {
            _accounts.value = _accounts.value + (idPrefix to accounts)
        }

        override fun isAutoSyncEnabled(trackerId: String): Boolean = autoSync

        override suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String) {
            autoSync = enabled
            _settingsVersion.update { it + 1L }
        }

        override fun getBindPolicy(trackerId: String): TrackerBindPolicy = bindPolicy

        override suspend fun setBindPolicy(policy: TrackerBindPolicy, trackerId: String) {
            bindPolicy = policy
            _settingsVersion.update { it + 1L }
        }

        override fun isConfirmBeforeSync(trackerId: String): Boolean = confirmBeforeSync

        override suspend fun setConfirmBeforeSync(enabled: Boolean, trackerId: String) {
            confirmBeforeSync = enabled
            _settingsVersion.update { it + 1L }
        }

        override suspend fun getBinding(showKey: String, mappedSeason: Int, trackerId: String): TrackerBinding? =
            bindings[bKey(showKey, mappedSeason, trackerId)]

        override suspend fun getBindingsForShow(showKey: String, trackerId: String): List<TrackerBinding> =
            bindings.values.filter { it.showKey == showKey && it.trackerId == trackerId }

        override suspend fun getAllBindingsForProfile(trackerId: String): List<TrackerBinding> =
            bindings.values.filter { it.trackerId == trackerId }

        override suspend fun saveBinding(binding: TrackerBinding) {
            bindings[bKey(binding.showKey, binding.mappedSeason.toInt(), binding.trackerId)] = binding
            _bindingsVersion.update { it + 1L }
        }

        override suspend fun deleteBinding(showKey: String, mappedSeason: Int, trackerId: String) {
            bindings.remove(bKey(showKey, mappedSeason, trackerId))
            _bindingsVersion.update { it + 1L }
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
        ): Long {
            val id = nextAuditId++
            audits.add(
                TrackerSyncAuditEntry(
                    id = id,
                    profileId = profileId,
                    trackerId = trackerId,
                    showName = showName,
                    remoteMediaId = remoteMediaId,
                    episodeNum = episodeNum,
                    effectiveRemoteEpisode = effectiveRemoteEpisode,
                    oldProgress = oldProgress,
                    newProgress = newProgress,
                    statusApplied = statusApplied,
                    state = state,
                    reason = reason,
                    timestamp = timestamp,
                ),
            )
            _auditVersion.update { it + 1L }
            return id
        }

        override suspend fun updateAuditState(rowId: Long, state: SyncState, reason: String?) {
            val idx = audits.indexOfFirst { it.id == rowId }
            if (idx >= 0) {
                audits[idx] = audits[idx].copy(state = state, reason = reason ?: audits[idx].reason)
                _auditVersion.update { it + 1L }
            }
        }

        override suspend fun getAuditLog(trackerId: String): List<TrackerSyncAuditEntry> =
            audits.filter { it.trackerId == trackerId }.reversed()
    }

    private data class RecordedMutation(
        val id: Int,
        val status: AniListStatusType,
        val score: Score?,
        val progress: Int?,
    )

    private class FakeRemoteGateway(
        private val seasons: Map<Int, SeasonMedia> = emptyMap(),
        private val malToId: Map<Int, Int> = emptyMap(),
        private val searchPool: List<GetSearchMedia> = emptyList(),
        val holders: MutableMap<Int, AniListTitleHolder> = mutableMapOf(),
        var postResult: Boolean = true,
    ) : TrackerRemoteGateway {
        val mutations = mutableListOf<RecordedMutation>()

        override suspend fun getSeason(id: Int): SeasonResponse? =
            seasons[id]?.let { SeasonResponse(SeasonData(it)) }

        override suspend fun getSeasonByMalId(malId: Int): SeasonResponse? =
            malToId[malId]?.let { getSeason(it) }

        override suspend fun searchShows(query: String): List<GetSearchMedia> = searchPool

        override suspend fun getDataAboutId(auth: AuthData, id: Int): AniListTitleHolder? = holders[id]

        override suspend fun postDataAboutId(
            auth: AuthData,
            id: Int,
            type: AniListStatusType,
            score: Score?,
            progress: Int?,
        ): Boolean {
            mutations.add(RecordedMutation(id, type, score, progress))
            return postResult
        }
    }

    private fun seasonMedia(
        id: Int,
        title: String,
        format: String = "TV",
        year: Int = 2024,
        episodes: Int? = 12,
        status: String = "FINISHED",
        edges: List<SeasonEdge> = emptyList(),
    ) = SeasonMedia(
        id = id,
        title = MediaTitle(romaji = title, english = title, native = null, userPreferred = title),
        idMal = id + 1000,
        format = format,
        seasonYear = year,
        status = status,
        nextAiringEpisode = null,
        relations = SeasonEdges(edges),
        coverImage = null,
        duration = 24,
        episodes = episodes,
        genres = listOf("Action"),
        synonyms = null,
        averageScore = 82,
        isAdult = false,
        trailer = null,
        description = null,
        characters = null,
        recommendations = null,
    )

    private fun relEdge(type: String, nodeId: Int, nodeTitle: String, format: String = "TV", episodes: Int = 12) =
        SeasonEdge(
            id = nodeId,
            relationType = type,
            node = SeasonNode(
                id = nodeId,
                format = format,
                title = Title(english = nodeTitle, romaji = nodeTitle, userPreferred = nodeTitle),
                seasonYear = 2024,
                episodes = episodes,
            ),
        )

    @Test
    fun `cour graph traverses SEQUEL from Explore season 1 seed to season 2`() = runBlocking {
        val s1 = seasonMedia(
            id = 101,
            title = "Chronicle Hero",
            episodes = 12,
            edges = listOf(relEdge("SEQUEL", 102, "Chronicle Hero Season 2", episodes = 13)),
        )
        val s2 = seasonMedia(
            id = 102,
            title = "Chronicle Hero Season 2",
            episodes = 13,
            edges = listOf(relEdge("PREQUEL", 101, "Chronicle Hero", episodes = 12)),
        )
        val repo = FakeTrackingRepository(bindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY)
        val gateway = FakeRemoteGateway(seasons = mapOf(101 to s1, 102 to s2))

        val res = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = buildTrackerShowKey("DemoProv", "https://example.invalid/chronicle"),
                showTitle = "Chronicle Hero",
                mappedSeason = 2,
                canonicalSeed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 101),
                bindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY,
                hasMultipleSeasons = true,
            ),
            repository = repo,
            fetchSeason = { gateway.getSeason(it) },
            fetchByMalId = { gateway.getSeasonByMalId(it) },
            searchRemote = { gateway.searchShows(it) },
        )

        val bound = assertIs<MatchResolution.Bound>(res)
        assertEquals("102", bound.binding.remoteMediaId)
        assertEquals(13L, bound.binding.expectedTotalEpisodes)
    }

    @Test
    fun `single season Explore season 2 page stays pinned without walking backward to prequel`() = runBlocking {
        val s1 = seasonMedia(id = 101, title = "Chronicle Hero")
        val s2 = seasonMedia(
            id = 102,
            title = "Chronicle Hero Season 2",
            edges = listOf(relEdge("PREQUEL", 101, "Chronicle Hero")),
        )
        val repo = FakeTrackingRepository()
        val gateway = FakeRemoteGateway(seasons = mapOf(101 to s1, 102 to s2))

        val res = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = buildTrackerShowKey("DemoProv", "https://example.invalid/chronicle-s2"),
                showTitle = "Chronicle Hero Season 2",
                mappedSeason = 1,
                canonicalSeed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 102),
                bindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY,
                hasMultipleSeasons = false,
            ),
            repository = repo,
            fetchSeason = { gateway.getSeason(it) },
            fetchByMalId = { gateway.getSeasonByMalId(it) },
            searchRemote = { gateway.searchShows(it) },
        )

        val bound = assertIs<MatchResolution.Bound>(res)
        assertEquals("102", bound.binding.remoteMediaId)
    }

    @Test
    fun `missing sequel or movie sequel requires user confirmation instead of guessing`(): Unit = runBlocking {
        val s1MergedSplitCour = seasonMedia(
            id = 201,
            title = "Merged Cour Show",
            episodes = 24,
            edges = listOf(relEdge("SEQUEL", 299, "Merged Cour Movie", format = "MOVIE", episodes = 1)),
        )
        val repo = FakeTrackingRepository()
        val gateway = FakeRemoteGateway(seasons = mapOf(201 to s1MergedSplitCour))

        val res = AniListMatchResolver.resolveMatch(
            request = MatchRequest(
                profileId = 1L,
                showKey = buildTrackerShowKey("DemoProv", "https://example.invalid/merged"),
                showTitle = "Merged Cour Show",
                mappedSeason = 2,
                canonicalSeed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 201),
                bindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY,
                hasMultipleSeasons = true,
            ),
            repository = repo,
            fetchSeason = { gateway.getSeason(it) },
            fetchByMalId = { gateway.getSeasonByMalId(it) },
            searchRemote = { gateway.searchShows(it) },
        )

        assertIs<MatchResolution.NeedsConfirmation>(res)
    }

    @Test
    fun `explore or pinned policy refuses auto-binding dirty raw provider entry`() = runBlocking {
        val repo = FakeTrackingRepository(bindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY)
        val searchHit = GetSearchMedia(
            id = 500,
            idMal = 5000,
            format = "TV",
            episodes = 12,
            seasonYear = 2024,
            title = GetSearchTitle(romaji = "Chronicle Hero", english = "Chronicle Hero", userPreferred = "Chronicle Hero"),
            startDate = StartedAt("2024", "1", "1"),
        )
        val gateway = FakeRemoteGateway(
            seasons = mapOf(500 to seasonMedia(500, "Chronicle Hero")),
            searchPool = listOf(searchHit),
        )
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )

        val result = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "RawProv",
                showUrl = "https://example.invalid/raw",
                showTitle = "Chronicle Hero",
                mappedSeason = 1,
                episodeNum = 1,
                year = 2024,
                completionVerified = true,
                canonicalSeed = null, // Not from Explore
            ),
        )

        assertIs<SyncExecutionResult.NeedsConfirmation>(result)
        assertEquals(0, gateway.mutations.size)
    }

    @Test
    fun `sync engine hard blocks failed guard and episode ceiling overflow`() = runBlocking {
        val repo = FakeTrackingRepository()
        val s1 = seasonMedia(id = 301, title = "Bounded Show", episodes = 12, status = "FINISHED")
        val gateway = FakeRemoteGateway(
            seasons = mapOf(301 to s1),
            holders = mutableMapOf(
                301 to AniListTitleHolder(
                    title = Title(english = "Bounded Show", romaji = "Bounded Show"),
                    isFavourite = false,
                    id = 301,
                    progress = 4,
                    episodes = 12,
                    score = 80,
                    type = AniListStatusType.Watching,
                    format = "TV",
                    mediaStatus = "FINISHED",
                ),
            ),
        )
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )

        val seed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 301)

        // Guard failed (short/scrubbed/error stream)
        val guardFail = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = "https://example.invalid/bounded",
                showTitle = "Bounded Show",
                episodeNum = 5,
                completionVerified = false,
                canonicalSeed = seed,
            ),
        )
        assertIs<SyncExecutionResult.Blocked>(guardFail)

        // Episode 13 exceeds AniList 12-ep count
        val epOverflow = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = "https://example.invalid/bounded",
                showTitle = "Bounded Show",
                episodeNum = 13,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        assertIs<SyncExecutionResult.Blocked>(epOverflow)
        assertEquals(0, gateway.mutations.size)
        assertEquals(2, repo.audits.count { it.state == SyncState.BLOCKED })
    }

    @Test
    fun `sync engine respects monotonicity, releasing vs finished status, offset, and score preservation`() = runBlocking {
        val repo = FakeTrackingRepository()
        val showUrl = "https://example.invalid/ongoing"
        val showKey = buildTrackerShowKey("DemoProv", showUrl)

        val ongoingMedia = seasonMedia(id = 401, title = "Ongoing Show", episodes = 12, status = "RELEASING")
        val gateway = FakeRemoteGateway(
            seasons = mapOf(401 to ongoingMedia),
            holders = mutableMapOf(
                401 to AniListTitleHolder(
                    title = Title(english = "Ongoing Show", romaji = "Ongoing Show"),
                    isFavourite = false,
                    id = 401,
                    progress = 8,
                    episodes = 12,
                    score = 90,
                    type = AniListStatusType.Watching,
                    format = "TV",
                    mediaStatus = "RELEASING",
                ),
            ),
        )
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )
        val seed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 401)

        // Monotonicity: ep 6 <= remote progress 8 -> no mutation
        val older = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = showUrl,
                showTitle = "Ongoing Show",
                episodeNum = 6,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        assertIs<SyncExecutionResult.AlreadySynced>(older)
        assertEquals(0, gateway.mutations.size)

        // RELEASING show reaching ep 12 -> Watching (NOT Completed!) and score = null (preserves existing score)
        val ep12Releasing = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = showUrl,
                showTitle = "Ongoing Show",
                episodeNum = 12,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        val syncedReleasing = assertIs<SyncExecutionResult.Synced>(ep12Releasing)
        assertEquals(AniListStatusType.Watching, syncedReleasing.status)
        assertEquals(null, gateway.mutations.last().score)

        // Switch to FINISHED + episodeOffset (-12 on ep 24 -> effective ep 12) -> Completed!
        repo.saveBinding(
            TrackerBinding(
                profileId = 1L,
                trackerId = TrackerIds.ANILIST,
                showKey = showKey,
                mappedSeason = 1L,
                remoteMediaId = "401",
                remoteTitle = "Ongoing Show",
                bindMode = BindMode.USER_PINNED,
                episodeOffset = -12L,
                expectedTotalEpisodes = 12L,
                updatedAt = 1L,
            ),
        )
        gateway.holders[401] = gateway.holders[401]!!.copy(progress = 11, mediaStatus = "FINISHED")
        val ep24OffsetFinished = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = showUrl,
                showTitle = "Ongoing Show",
                episodeNum = 24,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        val syncedFinished = assertIs<SyncExecutionResult.Synced>(ep24OffsetFinished)
        assertEquals(12, syncedFinished.newProgress)
        assertEquals(AniListStatusType.Completed, syncedFinished.status)
    }

    @Test
    fun `grace countdown cancellation marks audit CANCELLED and performs zero remote writes`() = runBlocking {
        val repo = FakeTrackingRepository(confirmBeforeSync = true)
        val gateway = FakeRemoteGateway(
            seasons = mapOf(601 to seasonMedia(601, "Grace Test", episodes = 12)),
            holders = mutableMapOf(
                601 to AniListTitleHolder(
                    title = Title(english = "Grace Test", romaji = "Grace Test"),
                    isFavourite = false,
                    id = 601,
                    progress = 2,
                    episodes = 12,
                    score = null,
                    type = AniListStatusType.Watching,
                    format = "TV",
                    mediaStatus = "FINISHED",
                ),
            ),
        )
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            graceDelayMs = 4000L,
            onGlobalToast = { _, _, _, _ -> },
        )

        var result: SyncExecutionResult? = null
        val job: Job = launch {
            result = engine.onEpisodeCompleted(
                SyncCompletionRequest(
                    profileId = 1L,
                    apiName = "DemoProv",
                    showUrl = "https://example.invalid/grace",
                    showTitle = "Grace Test",
                    episodeNum = 3,
                    completionVerified = true,
                    canonicalSeed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 601),
                ),
            )
        }

        // Wait until pending countdown starts, then cancel via undo API
        var waitedMs = 0L
        while (engine.pendingSync.value == null && waitedMs < 1500L) {
            delay(25L)
            waitedMs += 25L
        }
        val pending = engine.pendingSync.value
        assertTrue(pending != null)
        engine.cancelPendingSync(auditRowId = pending.auditRowId)
        job.join()

        assertIs<SyncExecutionResult.Cancelled>(result)
        assertEquals(0, gateway.mutations.size)
        assertEquals(SyncState.CANCELLED, repo.audits.single().state)
    }

    @Test
    fun `releasing anime blocks syncing unreleased episode beyond nextAiringEpisode`() = runBlocking {
        val repo = FakeTrackingRepository()
        val s1 = seasonMedia(id = 701, title = "Weekly Airing Show", episodes = 12, status = "RELEASING")
        val gateway = FakeRemoteGateway(
            seasons = mapOf(701 to s1),
            holders = mutableMapOf(
                701 to AniListTitleHolder(
                    title = Title(english = "Weekly Airing Show", romaji = "Weekly Airing Show"),
                    isFavourite = false,
                    id = 701,
                    progress = 4,
                    episodes = 12,
                    score = 80,
                    type = AniListStatusType.Watching,
                    format = "TV",
                    mediaStatus = "RELEASING",
                    nextAiringEpisode = com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonNextAiringEpisode(
                        episode = 6,
                        timeUntilAiring = 86400,
                    ),
                ),
            ),
        )
        val engine = TrackerSyncEngine(
            repository = repo,
            remoteGateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            graceDelayMs = 0L,
            onGlobalToast = { _, _, _, _ -> },
        )
        val seed = CanonicalMediaSeed(origin = MediaSeedOrigin.EXPLORE, anilistId = 701)

        // Episode 6 has not aired yet (nextAiringEpisode = 6 -> maxAired = 5)
        val blockedFuture = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = "https://example.invalid/weekly",
                showTitle = "Weekly Airing Show",
                episodeNum = 6,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        assertIs<SyncExecutionResult.Blocked>(blockedFuture)
        assertEquals(0, gateway.mutations.size)

        // Episode 5 has aired -> allowed, stays Watching (never Completed while RELEASING)
        val allowedEp5 = engine.onEpisodeCompleted(
            SyncCompletionRequest(
                profileId = 1L,
                apiName = "DemoProv",
                showUrl = "https://example.invalid/weekly",
                showTitle = "Weekly Airing Show",
                episodeNum = 5,
                completionVerified = true,
                canonicalSeed = seed,
            ),
        )
        val synced = assertIs<SyncExecutionResult.Synced>(allowedEp5)
        assertEquals(5, synced.newProgress)
        assertEquals(AniListStatusType.Watching, synced.status)
    }
}
