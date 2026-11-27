package com.lagradost.cloudstream3.desktop.ui.screens.library.contract

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType

enum class SortOption(val title: String) {
    RECENTLY_UPDATED("Recently Updated / Added"),
    DATE_ADDED_ASC("Date Added (Oldest)"),
    ALPHA_ASC("Alphabetical (A-Z)"),
    ALPHA_DESC("Alphabetical (Z-A)"),
    SCORE_DESC("My Score (High-Low)"),
    YEAR_DESC("Release Year (Newest)"),
}

@Immutable
sealed interface LibrarySection {
    data object LocalBookmarks : LibrarySection
    data class Tracker(val providerId: String) : LibrarySection
    data object TorboxCloud : LibrarySection
}

enum class LibraryWatchTab(val label: String, val filterStatus: UnifiedWatchStatus?) {
    ALL("All", null),
    WATCHING("Watching", UnifiedWatchStatus.WATCHING),
    PLAN_TO_WATCH("Plan to Watch", UnifiedWatchStatus.PLAN_TO_WATCH),
    COMPLETED("Completed", UnifiedWatchStatus.COMPLETED),
    ON_HOLD("On Hold", UnifiedWatchStatus.PAUSED),
    DROPPED("Dropped", UnifiedWatchStatus.DROPPED),
    REWATCHING("Re-watching", UnifiedWatchStatus.REWATCHING),
}

fun DesktopWatchType.toUnifiedStatus(): UnifiedWatchStatus = when (this) {
    DesktopWatchType.WATCHING -> UnifiedWatchStatus.WATCHING
    DesktopWatchType.COMPLETED -> UnifiedWatchStatus.COMPLETED
    DesktopWatchType.ONHOLD -> UnifiedWatchStatus.PAUSED
    DesktopWatchType.DROPPED -> UnifiedWatchStatus.DROPPED
    DesktopWatchType.PLANTOWATCH -> UnifiedWatchStatus.PLAN_TO_WATCH
    DesktopWatchType.REWATCHING -> UnifiedWatchStatus.REWATCHING
}

fun Int.toUnifiedWatchStatus(): UnifiedWatchStatus =
    DesktopWatchType.entries.firstOrNull { it.id == this }?.toUnifiedStatus()
        ?: UnifiedWatchStatus.WATCHING

fun normalizeLibraryEpochMillis(timestamp: Long): Long =
    if (timestamp in 1L..99_999_999_999L) timestamp * 1000L else timestamp

@Immutable
data class LocalBookmarkProgress(
    val label: String,
    val progressFraction: Float? = null,
    val isCompleted: Boolean = false,
    val lastWatchedAtMs: Long = 0L,
)

@Immutable
data class LocalTrackerLinkInfo(
    val providerId: String,
    val descriptor: TrackerDescriptor,
    val remoteMediaId: String,
    val progress: Int? = null,
    val totalEpisodes: Int? = null,
)

@Immutable
data class LibraryTrackerBindingRecord(
    val trackerId: String,
    val showKey: String,
    val remoteMediaId: String,
)

enum class LibraryStreamTargetKind {
    BOOKMARK_RELINK,
    TRACKER_ENTRY,
}

@Immutable
data class LibraryStreamTarget(
    val kind: LibraryStreamTargetKind,
    val title: String,
    val posterUrl: String?,
    val isMovie: Boolean = false,
    val year: Int? = null,
    val bookmark: DesktopBookmark? = null,
    val trackerEntry: UnifiedLibraryEntry? = null,
    val canonicalSeed: CanonicalMediaSeed? = null,
)

@Immutable
data class LibraryTrackerQuickEdit(
    val itemKey: String,
    val entry: UnifiedLibraryEntry,
    val descriptor: TrackerDescriptor,
    val availableEntries: List<UnifiedLibraryEntry>,
    val status: UnifiedWatchStatus,
    val progress: Int,
    val score100: Int,
    val isFavourite: Boolean,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

@Immutable
data class LibraryUiState(
    val bookmarks: List<DesktopBookmark> = emptyList(),
    val filteredBookmarks: List<DesktopBookmark> = emptyList(),
    val localProgressByBookmarkId: Map<String, LocalBookmarkProgress> = emptyMap(),
    val localTabCounts: Map<LibraryWatchTab, Int> = emptyMap(),
    val trackerEntriesByProvider: Map<String, List<UnifiedLibraryEntry>> = emptyMap(),
    val filteredTrackerEntries: List<UnifiedLibraryEntry> = emptyList(),
    val trackerTabCounts: Map<LibraryWatchTab, Int> = emptyMap(),
    val trackerNextCursorByProvider: Map<String, String?> = emptyMap(),
    val trackerHasMoreByProvider: Map<String, Boolean> = emptyMap(),
    val trackerTotalCountByProvider: Map<String, Int?> = emptyMap(),
    val trackerStatusCountsByProvider: Map<String, Map<UnifiedWatchStatus, Int>> = emptyMap(),
    val trackerLoadedAllByProvider: Map<String, Boolean> = emptyMap(),
    val trackerQueryKeyByProvider: Map<String, String> = emptyMap(),
    val isLoadingMoreTrackers: Boolean = false,
    val verifiedBindings: List<LibraryTrackerBindingRecord> = emptyList(),
    val linkedTrackersByBookmarkId: Map<String, List<LocalTrackerLinkInfo>> = emptyMap(),
    val linkedBookmarkByTrackerKey: Map<String, DesktopBookmark> = emptyMap(),
    val connectedTrackers: List<TrackerDescriptor> = emptyList(),
    val isTorboxConfigured: Boolean = false,
    val selectedSection: LibrarySection = LibrarySection.LocalBookmarks,
    val selectedTab: LibraryWatchTab = LibraryWatchTab.WATCHING,
    val sortOption: SortOption = SortOption.RECENTLY_UPDATED,
    val searchQuery: String = "",
    val availableProviders: List<String> = emptyList(),
    val selectedProvider: String? = null,
    val installedProviderNames: Set<String> = emptySet(),
    val providerMap: Map<String, MainAPI> = emptyMap(),
    val posterWidthDp: Int = 190,
    val isLoadingTrackers: Boolean = false,
    val trackersError: String? = null,
    val incrementingTrackerKey: String? = null,
    val quickEdit: LibraryTrackerQuickEdit? = null,
    val pendingRemoveTrackerEntry: UnifiedLibraryEntry? = null,
    val streamLookupTarget: LibraryStreamTarget? = null,
    val streamExploreItem: ExploreItem? = null,
    val isSearchingMatches: Boolean = false,
    val matchedResults: List<ProviderMatch> = emptyList(),
    val showError: String? = null,
) : UiState {
    val activeTrackerDescriptor: TrackerDescriptor?
        get() = (selectedSection as? LibrarySection.Tracker)?.let { sec ->
            connectedTrackers.firstOrNull { it.id == sec.providerId }
        }

    val activeTrackerHasMore: Boolean
        get() = (selectedSection as? LibrarySection.Tracker)?.let { sec ->
            trackerHasMoreByProvider[sec.providerId] == true
        } == true

    val activeTrackerTotalCount: Int?
        get() = (selectedSection as? LibrarySection.Tracker)?.let { sec ->
            trackerTotalCountByProvider[sec.providerId] ?: trackerEntriesByProvider[sec.providerId]?.size
        }

    fun watchTabsForActiveSection(): List<LibraryWatchTab> {
        val sec = selectedSection
        return if (sec is LibrarySection.Tracker && sec.providerId == TrackerIds.SIMKL) {
            LibraryWatchTab.entries.filter { it != LibraryWatchTab.REWATCHING }
        } else {
            LibraryWatchTab.entries
        }
    }

    fun activeTabCounts(): Map<LibraryWatchTab, Int> =
        if (selectedSection is LibrarySection.Tracker) trackerTabCounts else localTabCounts
}
