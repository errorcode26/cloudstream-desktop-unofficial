package com.lagradost.cloudstream3.desktop.library

import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.computeLocalBookmarkProgress
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerBindingRecord
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryWatchTab
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.SortOption
import com.lagradost.cloudstream3.desktop.ui.screens.library.recomputeLibraryState
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.common.storage.WatchHistory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryViewModelTest {

    private var activeVm: LibraryViewModel? = null

    @AfterEach
    fun tearDown() {
        activeVm?.dispose()
        activeVm = null
    }

    private fun bookmark(
        id: String,
        name: String,
        apiName: String = "TestProvider",
        watchType: Int = DesktopWatchType.WATCHING.id,
        dateAdded: Long = 0L,
    ) = DesktopBookmark(
        id = id,
        name = name,
        url = "https://example.com/$id",
        apiName = apiName,
        posterUrl = null,
        watchType = watchType,
        dateAdded = dateAdded,
    )

    private fun trackerEntry(
        providerId: String = TrackerIds.ANILIST,
        remoteId: String,
        title: String,
        status: UnifiedWatchStatus = UnifiedWatchStatus.WATCHING,
        progress: Int = 1,
        score100: Int = 0,
        year: Int? = null,
        updatedAt: Long = 0L,
        malId: Int? = null,
    ) = UnifiedLibraryEntry(
        providerId = providerId,
        remoteMediaId = remoteId,
        malId = malId,
        title = title,
        format = "TV",
        mediaStatus = "FINISHED",
        seasonYear = year,
        posterUrl = "https://img.example.com/$remoteId.jpg",
        progress = progress,
        totalEpisodes = 12,
        score100 = score100,
        averageScore = 80,
        isFavourite = false,
        updatedAt = updatedAt,
        status = status,
        releaseBounds = ReleaseBounds(canMarkCompleted = true),
    )

    private val aniListDescriptor = TrackerDescriptor(
        id = TrackerIds.ANILIST,
        displayName = "AniList",
        shortTag = "AL",
        accentColorHex = 0xFF3DB4F2,
        syncedColorHex = 0xFF4ADE80,
    )

    private val malDescriptor = TrackerDescriptor(
        id = TrackerIds.MAL,
        displayName = "MyAnimeList",
        shortTag = "MAL",
        accentColorHex = 0xFF2E51A2,
        syncedColorHex = 0xFF60A5FA,
    )

    private val simklDescriptor = TrackerDescriptor(
        id = TrackerIds.SIMKL,
        displayName = "SIMKL",
        shortTag = "SK",
        accentColorHex = 0xFF00B7FF,
        syncedColorHex = 0xFF22D3EE,
        supportsTvAndMovies = true,
    )

    @Test
    fun testInitialState_Defaults() {
        val state = LibraryUiState()
        assertEquals(LibrarySection.LocalBookmarks, state.selectedSection)
        assertEquals(LibraryWatchTab.WATCHING, state.selectedTab)
        assertEquals("", state.searchQuery)
        assertEquals(SortOption.RECENTLY_UPDATED, state.sortOption)
        assertNull(state.selectedProvider)
        assertTrue(state.bookmarks.isEmpty())
        assertTrue(state.filteredBookmarks.isEmpty())
        assertTrue(state.filteredTrackerEntries.isEmpty())
        assertTrue(state.installedProviderNames.isEmpty())
        assertTrue(state.providerMap.isEmpty())
        assertEquals(190, state.posterWidthDp)
        assertNull(state.streamLookupTarget)
    }

    @Test
    fun testSeparation_LocalBookmarksAndTrackersStayStrictlySeparateWithVerifiedLinks() {
        val linkedBm = bookmark("bm_1", "Frieren", apiName = "HiAnime", watchType = DesktopWatchType.PLANTOWATCH.id)
        val localOnly = bookmark("bm_2", "Breaking Bad", apiName = "FlixProvider", watchType = DesktopWatchType.WATCHING.id)
        val unlinkedSameTitleBm = bookmark("bm_3", "Solo Leveling", apiName = "HiAnime", watchType = DesktopWatchType.WATCHING.id)

        val linkedAl = trackerEntry(TrackerIds.ANILIST, "154587", "Frieren", UnifiedWatchStatus.WATCHING, progress = 5, score100 = 90)
        val remoteSolo = trackerEntry(TrackerIds.ANILIST, "151807", "Solo Leveling", UnifiedWatchStatus.WATCHING, progress = 8)
        val malEntry = trackerEntry(TrackerIds.MAL, "52991", "Sousou no Frieren", UnifiedWatchStatus.WATCHING, progress = 6)

        val baseState = LibraryUiState(
            bookmarks = listOf(linkedBm, localOnly, unlinkedSameTitleBm),
            trackerEntriesByProvider = mapOf(
                TrackerIds.ANILIST to listOf(linkedAl, remoteSolo),
                TrackerIds.MAL to listOf(malEntry),
            ),
            verifiedBindings = listOf(
                LibraryTrackerBindingRecord(TrackerIds.ANILIST, "bm_1", "154587"),
                LibraryTrackerBindingRecord(TrackerIds.MAL, "bm_1", "52991"),
            ),
            connectedTrackers = listOf(aniListDescriptor, malDescriptor),
            selectedSection = LibrarySection.LocalBookmarks,
            selectedTab = LibraryWatchTab.WATCHING,
        ).recomputeLibraryState()

        // Local Bookmarks only shows local bookmarks matching local WATCHING (bm_2, bm_3 — NOT bm_1 whose local category is PLANTOWATCH, and NOT remoteSolo)
        assertEquals(listOf("bm_2", "bm_3"), baseState.filteredBookmarks.map { it.id }.sorted())
        assertTrue(baseState.filteredTrackerEntries.isEmpty())

        // Verified links attach to bm_1 without auto-merging unlinked bm_3 by title
        assertEquals(listOf(TrackerIds.ANILIST, TrackerIds.MAL), baseState.linkedTrackersByBookmarkId["bm_1"]?.map { it.providerId })
        assertNull(baseState.linkedTrackersByBookmarkId["bm_3"])
        assertEquals("bm_1", baseState.linkedBookmarkByTrackerKey["${TrackerIds.ANILIST}:154587"]?.id)
        assertNull(baseState.linkedBookmarkByTrackerKey["${TrackerIds.ANILIST}:151807"])

        // Switching to AniList tab only displays AniList entries
        val anilistState = baseState.copy(selectedSection = LibrarySection.Tracker(TrackerIds.ANILIST)).recomputeLibraryState()
        assertEquals(setOf("154587", "151807"), anilistState.filteredTrackerEntries.map { it.remoteMediaId }.toSet())
        assertFalse(anilistState.filteredTrackerEntries.any { it.providerId == TrackerIds.MAL })

        // Switching to MAL tab only displays MAL entries
        val malState = baseState.copy(selectedSection = LibrarySection.Tracker(TrackerIds.MAL)).recomputeLibraryState()
        assertEquals(listOf("52991"), malState.filteredTrackerEntries.map { it.remoteMediaId })
    }

    @Test
    fun testTabsCountsAndSimklRewatchingOmitted() {
        val state = LibraryUiState(
            bookmarks = listOf(
                bookmark("1", "Show A", watchType = DesktopWatchType.WATCHING.id),
                bookmark("2", "Show B", watchType = DesktopWatchType.COMPLETED.id),
                bookmark("3", "Show C", watchType = DesktopWatchType.REWATCHING.id),
            ),
            trackerEntriesByProvider = mapOf(
                TrackerIds.SIMKL to listOf(
                    trackerEntry(TrackerIds.SIMKL, "anime:10", "Simkl Watch", UnifiedWatchStatus.WATCHING),
                    trackerEntry(TrackerIds.SIMKL, "movie:20", "Simkl Movie", UnifiedWatchStatus.COMPLETED),
                ),
            ),
            connectedTrackers = listOf(simklDescriptor),
            selectedSection = LibrarySection.LocalBookmarks,
            selectedTab = LibraryWatchTab.COMPLETED,
        ).recomputeLibraryState()

        assertEquals(3, state.localTabCounts[LibraryWatchTab.ALL])
        assertEquals(1, state.localTabCounts[LibraryWatchTab.COMPLETED])
        assertEquals(listOf("2"), state.filteredBookmarks.map { it.id })
        assertTrue(LibraryWatchTab.REWATCHING in state.watchTabsForActiveSection())

        val simklState = state.copy(
            selectedSection = LibrarySection.Tracker(TrackerIds.SIMKL),
            selectedTab = LibraryWatchTab.ALL,
        ).recomputeLibraryState()
        assertEquals(2, simklState.trackerTabCounts[LibraryWatchTab.ALL])
        assertEquals(1, simklState.trackerTabCounts[LibraryWatchTab.COMPLETED])
        assertFalse(LibraryWatchTab.REWATCHING in simklState.watchTabsForActiveSection())
    }

    @Test
    fun testServerPaginationMetadataProvidesAccurateTabCountsAndHasMoreState() {
        val pageOneItem = trackerEntry(TrackerIds.MAL, "52991", "Frieren", UnifiedWatchStatus.WATCHING)
        val state = LibraryUiState(
            connectedTrackers = listOf(malDescriptor),
            selectedSection = LibrarySection.Tracker(TrackerIds.MAL),
            selectedTab = LibraryWatchTab.WATCHING,
            trackerEntriesByProvider = mapOf(TrackerIds.MAL to listOf(pageOneItem)),
            trackerHasMoreByProvider = mapOf(TrackerIds.MAL to true),
            trackerTotalCountByProvider = mapOf(TrackerIds.MAL to 140),
            trackerStatusCountsByProvider = mapOf(
                TrackerIds.MAL to mapOf(
                    UnifiedWatchStatus.WATCHING to 12,
                    UnifiedWatchStatus.COMPLETED to 105,
                    UnifiedWatchStatus.PLAN_TO_WATCH to 20,
                    UnifiedWatchStatus.PAUSED to 3,
                ),
            ),
        ).recomputeLibraryState()

        assertTrue(state.activeTrackerHasMore)
        assertEquals(140, state.activeTrackerTotalCount)
        assertEquals(140, state.trackerTabCounts[LibraryWatchTab.ALL])
        assertEquals(12, state.trackerTabCounts[LibraryWatchTab.WATCHING])
        assertEquals(105, state.trackerTabCounts[LibraryWatchTab.COMPLETED])
        assertEquals(listOf("52991"), state.filteredTrackerEntries.map { it.remoteMediaId })
    }

    @Test
    fun testSearchProviderAndLocalHistoryProgressSorting() {
        val bOld = bookmark("1", "Alpha Old", apiName = "ProvA", dateAdded = 1_700_000_000_000L)
        val bPlayed = bookmark("2", "Beta Played", apiName = "ProvB", dateAdded = 1_699_000_000_000L)

        val history = listOf(
            WatchHistory(
                parentId = "2",
                episodeId = "ep_4",
                episodeName = "Episode 4",
                season = 1,
                episode = 4,
                position = 600_000L,
                duration = 1_200_000L,
                updateTime = 1_700_000_500_000L,
                showUrl = "https://example.com/2",
                showName = "Beta Played",
                apiName = "ProvB",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
            ),
        )
        val progressMap = computeLocalBookmarkProgress(history)
        assertEquals("S1 E4", progressMap["2"]?.label)
        assertEquals(0.5f, progressMap["2"]?.progressFraction)

        val sortedLocal = LibraryUiState(
            bookmarks = listOf(bOld, bPlayed),
            localProgressByBookmarkId = progressMap,
            selectedSection = LibrarySection.LocalBookmarks,
            selectedTab = LibraryWatchTab.WATCHING,
            sortOption = SortOption.RECENTLY_UPDATED,
        ).recomputeLibraryState()
        assertEquals(listOf("2", "1"), sortedLocal.filteredBookmarks.map { it.id })

        val filteredProvider = sortedLocal.copy(selectedProvider = "ProvA").recomputeLibraryState()
        assertEquals(listOf("1"), filteredProvider.filteredBookmarks.map { it.id })
    }

    @Test
    fun testViewModel_SectionSwitchingAndBasicEvents() {
        val vm = LibraryViewModel().also { activeVm = it }
        assertEquals(LibrarySection.LocalBookmarks, vm.uiState.value.selectedSection)

        vm.onEvent(LibraryUiEvent.OnSelectSection(LibrarySection.TorboxCloud))
        assertEquals(LibrarySection.LocalBookmarks, vm.uiState.value.selectedSection)

        vm.onEvent(LibraryUiEvent.OnSelectTab(LibraryWatchTab.COMPLETED))
        assertEquals(LibraryWatchTab.COMPLETED, vm.uiState.value.selectedTab)

        vm.onEvent(LibraryUiEvent.OnSearchQueryChange("Frieren"))
        assertEquals("Frieren", vm.uiState.value.searchQuery)

        vm.onEvent(LibraryUiEvent.OnProviderFilterChange("ProviderAlpha"))
        assertEquals("ProviderAlpha", vm.uiState.value.selectedProvider)

        vm.onEvent(LibraryUiEvent.OnDismissStreamPicker)
        assertNull(vm.uiState.value.streamLookupTarget)
    }
}
