package com.lagradost.cloudstream3.desktop.ui.screens.details.contract

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerMediaCandidate
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.common.storage.WatchHistory

sealed interface DetailsUiEvent : UiEvent {
    data object OnLoad : DetailsUiEvent
    data object OnRetry : DetailsUiEvent
    data class OnOpenLinksPanel(val data: Triple<MainAPI, String, WatchHistory>) : DetailsUiEvent
    data object OnCloseLinksPanel : DetailsUiEvent
    data object OnRequestAutoPlay : DetailsUiEvent
    data object OnMarkAutoPlayHandled : DetailsUiEvent
    data class OnPlayEpisode(val ep: com.lagradost.cloudstream3.Episode) : DetailsUiEvent
    data class OnDownloadEpisode(val ep: com.lagradost.cloudstream3.Episode) : DetailsUiEvent
    data class OnToggleEpisodeWatched(val ep: com.lagradost.cloudstream3.Episode, val isWatched: Boolean) : DetailsUiEvent
    data class OnRemoveEpisodeWatched(val ep: com.lagradost.cloudstream3.Episode) : DetailsUiEvent
    data class OnToggleSeasonWatched(val episodes: List<com.lagradost.cloudstream3.Episode>, val isWatched: Boolean) : DetailsUiEvent
    data class OnToggleEpisodesStackedView(val isStacked: Boolean) : DetailsUiEvent
    data class OnSetEpisodeViewMode(val viewMode: Int) : DetailsUiEvent
    data object OnRefresh : DetailsUiEvent
    data class OnAddBookmark(val bookmark: com.lagradost.common.storage.DesktopBookmark) : DetailsUiEvent
    data class OnRemoveBookmark(val id: String) : DetailsUiEvent
    data class OnSelectSeason(val season: Int?) : DetailsUiEvent
    data class OnShowPlaybackError(val message: String?) : DetailsUiEvent
    data object OnDismissPlaybackError : DetailsUiEvent
    data class OnSelectTrailer(val trailer: TrailerData?) : DetailsUiEvent
    data class OnSetPendingExternalUrl(val url: String?) : DetailsUiEvent
    data object OnTogglePinRightColumn : DetailsUiEvent
    data class OnOpenTrackerDrawer(val providerId: String? = null) : DetailsUiEvent
    data object OnDismissTrackerDrawer : DetailsUiEvent
    data class OnSelectTrackerTab(val providerId: String) : DetailsUiEvent
    data class OnRefreshTrackerState(val providerId: String? = null) : DetailsUiEvent
    data class OnTrackerSearchQueryChange(val providerId: String, val query: String) : DetailsUiEvent
    data class OnSearchTrackerCandidates(val providerId: String) : DetailsUiEvent
    data class OnPinTrackerCandidate(val providerId: String, val candidate: TrackerMediaCandidate) : DetailsUiEvent
    data class OnSetTrackerOffset(val providerId: String, val offset: Int) : DetailsUiEvent
    data class OnSetTrackerRemoteStatus(val providerId: String, val status: UnifiedWatchStatus) : DetailsUiEvent
    data class OnSetTrackerRemoteProgress(val providerId: String, val progress: Int) : DetailsUiEvent
    data class OnSetTrackerScore(val providerId: String, val score100: Int) : DetailsUiEvent
    data class OnToggleTrackerFavorite(val providerId: String) : DetailsUiEvent
    data class OnToggleTrackerSearch(val providerId: String) : DetailsUiEvent
    data class OnPushTrackerRemoteUpdate(val providerId: String) : DetailsUiEvent
    data class OnRemoveFromTracker(val providerId: String) : DetailsUiEvent
    data class OnSyncEpisodeToTracker(val providerId: String, val ep: com.lagradost.cloudstream3.Episode) : DetailsUiEvent
    data class OnSyncReleasedEpisodesToTracker(val providerId: String, val episodes: List<com.lagradost.cloudstream3.Episode>) : DetailsUiEvent
    data class OnBlockTrackerShow(val providerId: String) : DetailsUiEvent
    data class OnUnbindTrackerSeason(val providerId: String) : DetailsUiEvent
    data class OnSetTrackerAutoSync(val providerId: String, val enabled: Boolean) : DetailsUiEvent
    data class OnSetTrackerBindPolicy(val providerId: String, val policy: TrackerBindPolicy) : DetailsUiEvent
}
