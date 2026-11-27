package com.lagradost.cloudstream3.desktop.ui.screens.library.contract

import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType

sealed interface LibraryUiEvent : UiEvent {
    data class OnSelectSection(val section: LibrarySection) : LibraryUiEvent
    data object OnRefreshTorboxStatus : LibraryUiEvent
    data object OnResumeLibrary : LibraryUiEvent
    data class OnSelectTab(val tab: LibraryWatchTab) : LibraryUiEvent
    data class OnSearchQueryChange(val query: String) : LibraryUiEvent
    data class OnSortOptionChange(val sortOption: SortOption) : LibraryUiEvent
    data class OnProviderFilterChange(val provider: String?) : LibraryUiEvent
    data object OnRefreshTrackers : LibraryUiEvent
    data object OnLoadMoreTrackerEntries : LibraryUiEvent
    data class OnOpenBookmark(val bookmark: DesktopBookmark) : LibraryUiEvent
    data class OnDeleteBookmark(val bookmarkId: String) : LibraryUiEvent
    data class OnChangeWatchType(val bookmarkId: String, val newType: DesktopWatchType) : LibraryUiEvent
    data class OnStartReLink(val bookmark: DesktopBookmark) : LibraryUiEvent
    data class OnOpenTrackerEntry(val entry: UnifiedLibraryEntry) : LibraryUiEvent
    data class OnFindStreamForTracker(val entry: UnifiedLibraryEntry) : LibraryUiEvent
    data class OnIncrementTrackerEpisode(val entry: UnifiedLibraryEntry) : LibraryUiEvent
    data class OnSelectStreamMatch(
        val target: LibraryStreamTarget,
        val match: ProviderMatch,
    ) : LibraryUiEvent
    data class OnSelectStreamAddon(
        val target: LibraryStreamTarget,
        val exploreItem: ExploreItem,
    ) : LibraryUiEvent
    data object OnDismissStreamPicker : LibraryUiEvent
    data class OnSearchGlobal(val title: String) : LibraryUiEvent
    data class OnStartQuickEdit(val entry: UnifiedLibraryEntry) : LibraryUiEvent
    data class OnSwitchQuickEditTracker(val providerId: String) : LibraryUiEvent
    data object OnDismissQuickEdit : LibraryUiEvent
    data class OnEditQuickStatus(val status: UnifiedWatchStatus) : LibraryUiEvent
    data class OnEditQuickProgress(val progress: Int) : LibraryUiEvent
    data class OnEditQuickScore(val score100: Int) : LibraryUiEvent
    data object OnToggleQuickFavorite : LibraryUiEvent
    data object OnSaveQuickEdit : LibraryUiEvent
    data class OnRequestRemoveTrackerEntry(val entry: UnifiedLibraryEntry) : LibraryUiEvent
    data object OnDismissRemoveTrackerConfirm : LibraryUiEvent
    data object OnConfirmRemoveTrackerEntry : LibraryUiEvent
    data object OnDismissError : LibraryUiEvent
}
