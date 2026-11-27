package com.lagradost.cloudstream3.desktop.library

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.tracking.EpisodeSyncCheck
import com.lagradost.cloudstream3.desktop.tracking.GuardDecision
import com.lagradost.cloudstream3.desktop.tracking.MatchRequest
import com.lagradost.cloudstream3.desktop.tracking.MatchResolution
import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerLibraryPage
import com.lagradost.cloudstream3.desktop.tracking.TrackerMediaCandidate
import com.lagradost.cloudstream3.desktop.tracking.TrackerProvider
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.tracking.TrackerStatusUpdate
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncAuditEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedTrackerStatus
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelCoroutineTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private val testDispatcher = StandardTestDispatcher()
    private val originalMalProvider = TrackerRegistry.get(TrackerIds.MAL)
    private var activeVm: LibraryViewModel? = null

    @BeforeEach
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_lib_vm_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        activeVm?.dispose()
        activeVm = null
        originalMalProvider?.let { TrackerRegistry.register(it) }
        Dispatchers.resetMain()
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `tracker section pagination appends unique pages and emits toast on load-more failure`() = runTest(testDispatcher) {
        val fakeRepo = FakeLibraryTrackingRepo()
        fakeRepo.setAccount(
            TrackerIds.MAL,
            AuthData(
                user = AuthUser(id = 1, name = "PagedTester"),
                token = AuthToken(accessToken = "mal_tok"),
            ),
        )

        val page2Gate = CompletableDeferred<Result<TrackerLibraryPage>>()
        val provider = object : TrackerProvider {
            override val descriptor = TrackerDescriptor(
                id = TrackerIds.MAL,
                displayName = "MyAnimeList",
                shortTag = "MAL",
                accentColorHex = 0xFF2E51A2L,
                syncedColorHex = 0xFF60A5FAL,
            )

            override fun isEligibleForMedia(tvType: TvType, hasAnimeSeed: Boolean, hasExistingBinding: Boolean) = true
            override fun webUrl(remoteMediaId: String) = "https://myanimelist.net/anime/$remoteMediaId"
            override suspend fun resolveMatch(request: MatchRequest, repository: TrackingRepository) = MatchResolution.Skipped("n/a")
            override suspend fun searchCandidates(query: String, year: Int?, tvType: TvType) = emptyList<TrackerMediaCandidate>()
            override suspend fun getRemoteStatus(repository: TrackingRepository, remoteMediaId: String, fallbackTotalEpisodes: Int?) = null
            override suspend fun updateRemoteStatus(repository: TrackingRepository, remoteMediaId: String, update: TrackerStatusUpdate, bounds: ReleaseBounds) = true
            override suspend fun removeEntry(repository: TrackingRepository, remoteMediaId: String) = true
            override suspend fun toggleFavorite(repository: TrackingRepository, remoteMediaId: String) = null
            override suspend fun fetchLibrary(repository: TrackingRepository) = Result.success(emptyList<UnifiedLibraryEntry>())
            override fun requiresLibraryRefresh() = false
            override fun validateStatusAndProgress(bounds: ReleaseBounds, status: UnifiedWatchStatus, progress: Int?) = GuardDecision.Allowed
            override fun canSyncEpisode(bounds: ReleaseBounds, rawEpisodeNumber: Int?, episodeOffset: Int, localReleaseBlocked: Boolean) =
                EpisodeSyncCheck.Allowed(rawEpisodeNumber ?: 1)
            override fun nextStatusForManualEpisodeSync(bounds: ReleaseBounds, currentStatus: UnifiedWatchStatus, effectiveEpisode: Int) =
                UnifiedWatchStatus.WATCHING

            override suspend fun fetchLibraryPage(
                repository: TrackingRepository,
                statusFilter: UnifiedWatchStatus?,
                sortKey: String?,
                cursor: String?,
                pageSize: Int,
            ): Result<TrackerLibraryPage> {
                return when (cursor) {
                    null -> Result.success(
                        TrackerLibraryPage(
                            entries = listOf(entry("101", "Alpha"), entry("102", "Beta")),
                            nextCursor = "2",
                            hasMore = true,
                            statusCounts = mapOf(UnifiedWatchStatus.WATCHING to 3),
                            totalCount = 3,
                            supportsServerTabFilter = true,
                        ),
                    )
                    "2" -> page2Gate.await()
                    "3" -> Result.failure(IllegalStateException("Upstream timeout"))
                    else -> Result.success(TrackerLibraryPage(entries = emptyList(), hasMore = false))
                }
            }
        }
        TrackerRegistry.register(provider)

        val vm = LibraryViewModel(trackingRepository = fakeRepo).also { activeVm = it }
        try {
            advanceUntilIdle()

            vm.onEvent(LibraryUiEvent.OnSelectSection(LibrarySection.Tracker(TrackerIds.MAL)))
            advanceUntilIdle()

            val deadline1 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline1 &&
                (vm.uiState.value.isLoadingTrackers || vm.uiState.value.trackerEntriesByProvider[TrackerIds.MAL] == null)
            ) {
                Thread.sleep(10)
                advanceUntilIdle()
            }

            assertEquals(listOf("101", "102"), vm.uiState.value.filteredTrackerEntries.map { it.remoteMediaId })
            assertTrue(vm.uiState.value.activeTrackerHasMore)
            assertEquals(3, vm.uiState.value.activeTrackerTotalCount)

            // Trigger load-more with duplicate entry 102 + new entry 103
            vm.onEvent(LibraryUiEvent.OnLoadMoreTrackerEntries)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isLoadingMoreTrackers)

            page2Gate.complete(
                Result.success(
                    TrackerLibraryPage(
                        entries = listOf(entry("102", "Beta Duplicate"), entry("103", "Gamma")),
                        nextCursor = "3",
                        hasMore = true,
                        supportsServerTabFilter = true,
                    ),
                ),
            )
            val deadline2 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline2 && vm.uiState.value.isLoadingMoreTrackers) {
                Thread.sleep(10)
                advanceUntilIdle()
            }

            assertFalse(vm.uiState.value.isLoadingMoreTrackers)
            assertEquals(listOf("101", "102", "103"), vm.uiState.value.filteredTrackerEntries.map { it.remoteMediaId })
            assertTrue(vm.uiState.value.activeTrackerHasMore)

            // Trigger page 3 failure and verify error toast without losing existing entries
            val toastDeferred = async(start = CoroutineStart.UNDISPATCHED) { vm.effectFlow.first() }
            vm.onEvent(LibraryUiEvent.OnLoadMoreTrackerEntries)
            val deadline3 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline3 &&
                (!toastDeferred.isCompleted || vm.uiState.value.isLoadingMoreTrackers)
            ) {
                Thread.sleep(10)
                advanceUntilIdle()
            }

            assertTrue(toastDeferred.isCompleted, "Expected error toast effect within 5s")
            val toast = assertIs<LibraryUiEffect.ShowToast>(toastDeferred.await())
            assertTrue(toast.isError)
            assertTrue(toast.message.contains("Upstream timeout"))
            assertEquals(listOf("101", "102", "103"), vm.uiState.value.filteredTrackerEntries.map { it.remoteMediaId })
            assertFalse(vm.uiState.value.isLoadingMoreTrackers)
        } finally {
            vm.dispose()
        }
    }

    private fun entry(id: String, title: String) = UnifiedLibraryEntry(
        providerId = TrackerIds.MAL,
        remoteMediaId = id,
        malId = id.toInt(),
        title = title,
        format = "TV",
        mediaStatus = "FINISHED",
        seasonYear = 2024,
        posterUrl = null,
        progress = 1,
        totalEpisodes = 12,
        score100 = 80,
        averageScore = 82,
        isFavourite = false,
        updatedAt = 1_700_000_000_000L,
        status = UnifiedWatchStatus.WATCHING,
        releaseBounds = ReleaseBounds(canMarkCompleted = true),
    )

    private class FakeLibraryTrackingRepo : TrackingRepository {
        private val _accounts = MutableStateFlow<Map<String, Array<AuthData>>>(emptyMap())
        override val accounts: StateFlow<Map<String, Array<AuthData>>> = _accounts
        override val bindingsVersion: StateFlow<Long> = MutableStateFlow(0L)
        override val auditVersion: StateFlow<Long> = MutableStateFlow(0L)
        override val settingsVersion: StateFlow<Long> = MutableStateFlow(0L)

        fun setAccount(id: String, auth: AuthData) {
            _accounts.value = _accounts.value + (id to arrayOf(auth))
        }

        override fun getAccountsForProvider(idPrefix: String): Array<AuthData>? = _accounts.value[idPrefix]
        override fun updateAccounts(idPrefix: String, accounts: Array<AuthData>) {
            _accounts.value = _accounts.value + (idPrefix to accounts)
        }

        override fun isAutoSyncEnabled(trackerId: String) = false
        override suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String) {}
        override fun getBindPolicy(trackerId: String) = TrackerBindPolicy.STRICT_AUTO_AND_PINNED
        override suspend fun setBindPolicy(policy: TrackerBindPolicy, trackerId: String) {}
        override fun isConfirmBeforeSync(trackerId: String) = false
        override suspend fun setConfirmBeforeSync(enabled: Boolean, trackerId: String) {}
        override suspend fun getBinding(showKey: String, mappedSeason: Int, trackerId: String): TrackerBinding? = null
        override suspend fun getBindingsForShow(showKey: String, trackerId: String): List<TrackerBinding> = emptyList()
        override suspend fun getAllBindingsForProfile(trackerId: String): List<TrackerBinding> = emptyList()
        override suspend fun saveBinding(binding: TrackerBinding) {}
        override suspend fun deleteBinding(showKey: String, mappedSeason: Int, trackerId: String) {}
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
}
