package com.lagradost.cloudstream3.desktop.ui.screens.library

import com.lagradost.cloudstream3.desktop.data.tracking.TrackingRepositoryImpl
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.RemoveBookmark
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.ToggleBookmark
import com.lagradost.cloudstream3.desktop.domain.bookmarks.repository.BookmarksRepository
import com.lagradost.cloudstream3.desktop.domain.category.interactor.SetItemCategory
import com.lagradost.cloudstream3.desktop.domain.category.model.Category
import com.lagradost.cloudstream3.desktop.domain.category.repository.CategoryRepository
import com.lagradost.cloudstream3.desktop.domain.history.repository.WatchHistoryRepository
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryWatchTab
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaKind
import com.lagradost.cloudstream3.syncproviders.providers.SimklMediaRef
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTrackerRefreshTest {

    private val testMainDispatcher = UnconfinedTestDispatcher()
    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private var server: MockWebServer? = null
    private lateinit var repository: TrackingRepositoryImpl
    private var viewModel: LibraryViewModel? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testMainDispatcher)
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_lib_refresh_test_").toFile()
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
        AccountManager.simklApi.apiBaseUrl = "$baseUrl/simkl"
        AccountManager.simklApi.setCustomClientId("test_simkl_client_id")
        AccountManager.reloadFromDataStore()

        repository = TrackingRepositoryImpl()
    }

    @AfterEach
    fun tearDown() {
        viewModel?.dispose()
        viewModel = null
        runBlocking {
            AccountManager.malApi.resetForTesting()
            AccountManager.simklApi.resetForTesting()
        }
        AniListApi.resetForTesting()
        runCatching { server?.shutdown() }
        server = null
        DesktopDataStore.resetForTesting()
        if (prevDataDir == null) {
            System.clearProperty("cloudstream.data.dir")
        } else {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        }
        runCatching { tempDir.deleteRecursively() }
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LibraryViewModel {
        val fakeBookmarksRepo = FakeBookmarksRepository()
        val fakeHistoryRepo = FakeWatchHistoryRepository()
        val fakeCategoryRepo = FakeCategoryRepository()
        return LibraryViewModel(
            getBookmarks = GetBookmarks(fakeBookmarksRepo),
            toggleBookmark = ToggleBookmark(fakeBookmarksRepo),
            removeBookmark = RemoveBookmark(fakeBookmarksRepo),
            setItemCategory = SetItemCategory(fakeCategoryRepo),
            watchHistoryRepository = fakeHistoryRepo,
            trackingRepository = repository,
            registry = TrackerRegistry,
        ).also { viewModel = it }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            delay(25L)
        }
        assertTrue(predicate(), "Timed out waiting for LibraryViewModel state update")
    }

    @Test
    fun `external tracker audit and resume refresh update simkl library entries and movie quick edit resets progress`() = runBlocking {
        val includeAddedPlannedMovie = AtomicBoolean(false)
        val removeReturnsDeleted = AtomicBoolean(false)
        val moviesStamp = AtomicInteger(1)
        val moviesFetches = AtomicInteger(0)
        val historyRemoveCount = AtomicInteger(0)

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/simkl/sync/activities") -> MockResponse().setResponseCode(200).setBody(
                        """{"anime":{"all":"2026-11-20T00:00:00Z"},"tv_shows":{"all":"2026-11-20T00:00:00Z"},"movies":{"all":"2026-11-27T00:0${moviesStamp.get()}:00Z"}}""",
                    )
                    path.startsWith("/simkl/sync/all-items/anime") -> MockResponse().setResponseCode(200).setBody("""{"anime":[]}""")
                    path.startsWith("/simkl/sync/all-items/shows") -> MockResponse().setResponseCode(200).setBody("""{"shows":[]}""")
                    path.startsWith("/simkl/sync/all-items/movies") -> {
                        moviesFetches.incrementAndGet()
                        val itemsJson = buildString {
                            append(
                                """
                                {
                                  "status": "completed",
                                  "last_watched_at": "2026-11-26T10:00:00Z",
                                  "movie": {
                                    "title": "Completed Baseline Movie",
                                    "year": 2024,
                                    "ids": { "simkl": 8001 }
                                  }
                                }
                                """.trimIndent(),
                            )
                            if (includeAddedPlannedMovie.get()) {
                                append(",")
                                append(
                                    """
                                    {
                                      "status": "plantowatch",
                                      "movie": {
                                        "title": "Newly Added Planned Movie",
                                        "year": 2025,
                                        "ids": { "simkl": 8002 }
                                      }
                                    }
                                    """.trimIndent(),
                                )
                            }
                        }
                        MockResponse().setResponseCode(200).setBody("""{"movies":[$itemsJson]}""")
                    }
                    path.startsWith("/simkl/sync/add-to-list") -> MockResponse().setResponseCode(200).setBody("""{"added":{"movies":1}}""")
                    path.startsWith("/simkl/sync/history/remove") -> {
                        historyRemoveCount.incrementAndGet()
                        if (removeReturnsDeleted.get()) {
                            includeAddedPlannedMovie.set(false)
                            moviesStamp.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(
                                """{"deleted":{"movies":1,"shows":0,"episodes":0},"not_found":{"movies":[],"shows":[],"episodes":[]}}""",
                            )
                        } else {
                            MockResponse().setResponseCode(200).setBody(
                                """{"deleted":{"movies":0,"shows":0,"episodes":0},"not_found":{"movies":[],"shows":[],"episodes":[]}}""",
                            )
                        }
                    }
                    path.startsWith("/simkl/sync/remove-from-list") -> {
                        error("Deprecated /sync/remove-from-list must never be called")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{}""")
                }
            }
        }

        val simklAuth = AuthData(
            user = AuthUser(id = 10, name = "SimklUser"),
            token = AuthToken(accessToken = "simkl_token_lib"),
        )
        repository.updateAccounts(TrackerIds.SIMKL, arrayOf(simklAuth))

        val vm = createViewModel()
        vm.onEvent(LibraryUiEvent.OnSelectSection(LibrarySection.Tracker(TrackerIds.SIMKL)))
        vm.onEvent(LibraryUiEvent.OnSelectTab(LibraryWatchTab.ALL))

        awaitCondition {
            vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]?.size == 1
        }
        assertEquals(1, moviesFetches.get())

        // Simulate adding a new movie from Details / Playback outside LibraryScreen
        includeAddedPlannedMovie.set(true)
        moviesStamp.set(2)
        val addedOk = AccountManager.simklApi.updateRemoteEntry(
            auth = simklAuth,
            ref = SimklMediaRef(SimklMediaKind.MOVIE, 8002),
            status = UnifiedWatchStatus.PLAN_TO_WATCH,
            progress = 0,
            score100 = null,
        )
        assertTrue(addedOk)
        repository.recordAudit(
            profileId = 0L,
            trackerId = TrackerIds.SIMKL,
            showName = "Newly Added Planned Movie",
            remoteMediaId = SimklMediaRef(SimklMediaKind.MOVIE, 8002).encode(),
            episodeNum = 0L,
            effectiveRemoteEpisode = 0L,
            oldProgress = 0L,
            newProgress = 0L,
            statusApplied = UnifiedWatchStatus.PLAN_TO_WATCH.name,
            state = SyncState.SENT,
            reason = "Added from details",
        )

        awaitCondition {
            vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]?.size == 2
        }
        val plannedEntry = vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]!!
            .firstOrNull { it.remoteMediaId == "movie:8002" }
        assertNotNull(plannedEntry)
        assertEquals(UnifiedWatchStatus.PLAN_TO_WATCH, plannedEntry.status)
        assertEquals(0, plannedEntry.progress)

        // Verify quick-edit status switch on Completed movie resets progress to 0 for PLAN_TO_WATCH
        val completedEntry = vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]!!
            .first { it.remoteMediaId == "movie:8001" }
        assertEquals(1, completedEntry.progress)
        vm.onEvent(LibraryUiEvent.OnStartQuickEdit(completedEntry))
        vm.onEvent(LibraryUiEvent.OnEditQuickStatus(UnifiedWatchStatus.PLAN_TO_WATCH))
        val quickEdit = vm.uiState.value.quickEdit
        assertNotNull(quickEdit)
        assertEquals(UnifiedWatchStatus.PLAN_TO_WATCH, quickEdit.status)
        assertEquals(0, quickEdit.progress)

        // Flag pending refresh without firing an audit event and confirm OnResumeLibrary refreshes
        val beforeResumeFetches = moviesFetches.get()
        moviesStamp.set(3)
        // Simulate remote write flagging requireLibraryRefresh via add-to-list
        val droppedOk = AccountManager.simklApi.updateRemoteEntry(
            auth = simklAuth,
            ref = SimklMediaRef(SimklMediaKind.MOVIE, 8002),
            status = UnifiedWatchStatus.DROPPED,
            progress = 0,
            score100 = null,
        )
        assertTrue(droppedOk)
        assertTrue(AccountManager.simklApi.requireLibraryRefresh)
        vm.onEvent(LibraryUiEvent.OnResumeLibrary)
        awaitCondition {
            moviesFetches.get() > beforeResumeFetches
        }

        // Verify SIMKL removal rejects 0-deleted response, then removes + reconciles on deleted=1
        val effects = java.util.Collections.synchronizedList(mutableListOf<LibraryUiEffect>())
        val effectJob = launch {
            vm.effectFlow.collect { effects += it }
        }
        try {
            vm.onEvent(LibraryUiEvent.OnRequestRemoveTrackerEntry(plannedEntry))
            vm.onEvent(LibraryUiEvent.OnConfirmRemoveTrackerEntry)
            awaitCondition {
                effects.any { it is LibraryUiEffect.ShowToast && it.isError }
            }
            assertEquals(1, historyRemoveCount.get())
            assertEquals(2, vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]?.size)

            effects.clear()
            removeReturnsDeleted.set(true)
            val beforeRemoveFetches = moviesFetches.get()
            vm.onEvent(LibraryUiEvent.OnRequestRemoveTrackerEntry(plannedEntry))
            vm.onEvent(LibraryUiEvent.OnConfirmRemoveTrackerEntry)
            awaitCondition {
                vm.uiState.value.trackerEntriesByProvider[TrackerIds.SIMKL]?.size == 1 &&
                    moviesFetches.get() > beforeRemoveFetches &&
                    effects.any { it is LibraryUiEffect.ShowToast && !it.isError }
            }
            assertEquals(2, historyRemoveCount.get())
        } finally {
            effectJob.cancel()
        }
    }

    private class FakeBookmarksRepository : BookmarksRepository {
        private val flow = MutableStateFlow<Map<String, DesktopBookmark>>(emptyMap())
        override fun subscribeAll(): StateFlow<Map<String, DesktopBookmark>> = flow
        override suspend fun getAll(): List<DesktopBookmark> = flow.value.values.toList()
        override suspend fun getById(id: String): DesktopBookmark? = flow.value[id]
        override suspend fun isBookmarked(id: String): Boolean = flow.value.containsKey(id)
        override suspend fun addBookmark(bookmark: DesktopBookmark) {
            flow.value = flow.value + (bookmark.id to bookmark)
        }
        override suspend fun removeBookmark(id: String) {
            flow.value = flow.value - id
        }
    }

    private class FakeWatchHistoryRepository : WatchHistoryRepository {
        private val flow = MutableStateFlow<List<WatchHistory>>(emptyList())
        override fun subscribeAll(): Flow<List<WatchHistory>> = flow
        override fun subscribeByParent(parentId: String): Flow<List<WatchHistory>> = MutableStateFlow(emptyList())
        override suspend fun getByParent(parentId: String): List<WatchHistory> = emptyList()
        override suspend fun getByEpisode(parentId: String, episodeId: String): WatchHistory? = null
        override suspend fun upsert(history: WatchHistory, forceNotify: Boolean) {}
        override suspend fun deleteByParent(parentId: String) {}
        override suspend fun deleteByEpisode(
            parentId: String,
            episodeId: String,
            season: Int?,
            episode: Int?,
            extraEpisodeIds: List<String>,
        ) {}
        override suspend fun deleteAll() {}
    }

    private class FakeCategoryRepository : CategoryRepository {
        private val flow = MutableStateFlow<List<Category>>(emptyList())
        override fun subscribeCategories(): Flow<List<Category>> = flow
        override suspend fun getCategories(): List<Category> = emptyList()
        override suspend fun createCategory(name: String): Category = Category(id = 1, name = name, order = 0)
        override suspend fun renameCategory(id: Int, name: String): Boolean = false
        override suspend fun reorderCategories(categories: List<Category>): Boolean = false
        override suspend fun deleteCategory(id: Int): Boolean = false
        override suspend fun setItemCategory(bookmarkId: String, categoryId: Int): Boolean = false
    }
}
