package com.lagradost.cloudstream3.desktop.ui.screens.details.contract

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.metadata.RatingSource
import com.lagradost.cloudstream3.desktop.tracking.MatchResolution
import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerMediaCandidate
import com.lagradost.cloudstream3.desktop.tracking.UnifiedTrackerStatus
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.WatchHistory

@Immutable
data class DetailsTrackerEntryState(
    val descriptor: TrackerDescriptor,
    val isConnected: Boolean = false,
    val isEligible: Boolean = false,
    val showKey: String? = null,
    val mappedSeason: Int = 1,
    val resolution: MatchResolution? = null,
    val boundEntry: TrackerBinding? = null,
    val remoteStatus: UnifiedTrackerStatus? = null,
    val releaseBounds: ReleaseBounds = ReleaseBounds(),
    val isLoadingStatus: Boolean = false,
    val isSaving: Boolean = false,
    val syncingEpisodeNum: Int? = null,
    val editStatus: UnifiedWatchStatus = UnifiedWatchStatus.WATCHING,
    val editProgress: Int = 0,
    val editScore: Int = 0,
    val isFavourite: Boolean = false,
    val editOffset: Int = 0,
    val searchQuery: String = "",
    val searchResults: List<TrackerMediaCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val showSearchDrawer: Boolean = false,
    val isAutoSyncEnabled: Boolean = true,
    val bindPolicy: com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy =
        com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy.STRICT_AUTO_AND_PINNED,
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
)

@Immutable
data class DetailsTrackersUiState(
    val entries: Map<String, DetailsTrackerEntryState> = emptyMap(),
    val isDrawerOpen: Boolean = false,
    val activeProviderId: String? = null,
) {
    val availableTrackers: List<DetailsTrackerEntryState>
        get() = entries.values.filter { it.isConnected && it.isEligible }

    val activeEntry: DetailsTrackerEntryState?
        get() = activeProviderId?.let { entries[it] }?.takeIf { it.isConnected && it.isEligible }
            ?: availableTrackers.firstOrNull()

    /**
     * Strictest maximum local episode number allowed across all bound trackers
     * after accounting for each tracker's episode offset.
     */
    val strictLocalReleaseCeiling: Int?
        get() = entries.values
            .filter { it.isConnected && it.boundEntry != null }
            .mapNotNull { entry ->
                entry.releaseBounds.ceilingEpisode?.let { ceiling -> ceiling - entry.editOffset }
            }
            .minOrNull()
}

@Immutable
sealed interface EnrichmentPhase {
    data object Idle : EnrichmentPhase
    data object InProgress : EnrichmentPhase
    data object Complete : EnrichmentPhase
}

@Immutable
data class SeasonMetadata(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int?,
    val posterUrl: String?,
)

@Immutable
data class ReviewData(
    val author: String,
    val content: String,
    val rating: Double?,
    val avatarUrl: String?,
    val createdAt: String?,
    val url: String?,
)

@Immutable
data class DetailsUiState(
    val preloadedName: String? = null,
    val response: LoadResponse? = null,
    val fakeData: LoadResponse? = null,
    val isLoading: Boolean = true,
    val fetchFailed: Boolean = false,
    val watchHistory: Map<String, WatchHistory> = emptyMap(),
    val activeLinkData: Triple<MainAPI, String, WatchHistory>? = null,
    val isPanelOpen: Boolean = false,
    val screenshots: List<String>? = null,
    val enrichmentPhase: EnrichmentPhase = EnrichmentPhase.Idle,
    val enrichedLogoUrl: String? = null,
    val enrichedBackdropUrl: String? = null,
    val enrichedTagline: String? = null,
    val enrichedStatus: String? = null,
    val enrichedStudios: List<String> = emptyList(),
    val enrichedProductionCompanies: List<ProductionCompany> = emptyList(),
    val enrichedNetworksList: List<ProductionCompany> = emptyList(),
    val enrichedCollectionName: String? = null,
    val enrichedCollectionBackdrop: String? = null,
    val enrichedSeasonsCount: Int? = null,
    val enrichedEpisodesCount: Int? = null,
    val enrichedSeasonsMetadata: List<SeasonMetadata> = emptyList(),
    val enrichedOriginalLanguage: String? = null,
    val enrichedReleaseDate: String? = null,
    val enrichedCountry: String? = null,
    val enrichedCollectionItems: List<SearchResponse> = emptyList(),
    val isEnriching: Boolean = false,
    val error: String? = null,
    val enrichedBudget: Long? = null,
    val enrichedRevenue: Long? = null,
    val enrichedNetworks: List<String> = emptyList(),
    val enrichedYear: Int? = null,
    val enrichedDuration: Int? = null,
    val enrichedTags: List<String>? = null,
    val enrichedActors: List<ActorData>? = null,
    val enrichedImdbRating: Double? = null,
    val enrichedTmdbRating: Double? = null,
    val enrichedAniListRating: Double? = null,
    val enrichedReviews: List<ReviewData> = emptyList(),
    val enrichedTrailers: List<TrailerData> = emptyList(),
    val enrichedTrailerUrl: String? = null,
    val bookmarks: Map<String, DesktopBookmark> = emptyMap(),
    val autoPlayEnabled: Boolean = true,
    val hasAutoPlayed: Boolean = false,
    val isInitialized: Boolean = false,
    val backupSeasonHistory: Map<String, WatchHistory> = emptyMap(),
    val isEpisodesStackedView: Boolean = false,
    val episodeViewMode: Int = 0,
    // Bumped each time episode thumbnail URLs are mutated in-place by enrichment.
    // Compose observes this to trigger recomposition of episode cards.
    val episodeThumbnailVersion: Int = 0,
    val episodeRatingSources: Map<Pair<Int, Int>, RatingSource> = emptyMap(),
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val canonicalSeed: com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed? = null,
    val selectedSeason: Int? = null,
    val hasUserSelectedSeason: Boolean = false,
    val seasonCredits: Map<Int, List<ActorData>> = emptyMap(),
    val playbackError: String? = null,
    val activeTrailer: TrailerData? = null,
    val pendingExternalUrl: String? = null,
    val isRightColumnPinned: Boolean = false,
    val trackers: DetailsTrackersUiState = DetailsTrackersUiState(),
) : UiState

data class ProductionCompany(
    val id: Int = 0,
    val name: String,
    val logoUrl: String? = null,
    val originCountry: String? = null,
    val isNetwork: Boolean = false,
)

enum class DetailsSectionKey(val displayName: String, val description: String) {
    EPISODES("Episodes & Content", "Episode grid, season selector, and watch progress"),
    CAST("Cast & Crew", "Actors, characters, directors, and creators"),
    TRAILERS("Videos & Trailers", "Categorized trailers, teasers, and clips"),
    SCREENSHOTS("Screenshots Gallery", "High-resolution production backdrops"),
    COLLECTION("Franchise Collection", "Franchise sequels, prequels, and sagas"),
    RECOMMENDATIONS("Similar Content", "Recommendations and similar media"),
    INFO("Details & Technical Info", "Release date, runtime, status, certification, budget, and language"),
    STUDIOS("Studios", "Animation studios, film producers, and production companies"),
    NETWORKS("Networks", "Broadcasters, television networks, and streaming channels"),
    REVIEWS("Community Reviews", "User star ratings and written reviews"),
    ;

    companion object {
        val defaultOrder = listOf(
            EPISODES,
            NETWORKS,
            CAST,
            TRAILERS,
            SCREENSHOTS,
            INFO,
            STUDIOS,
            COLLECTION,
            REVIEWS,
            RECOMMENDATIONS,
        )

        fun parseOrder(raw: String?): List<DetailsSectionKey> {
            if (raw.isNullOrBlank()) return defaultOrder
            val list = raw.split(",").mapNotNull { name ->
                entries.find { it.name.equals(name.trim(), ignoreCase = true) }
            }
            val missing = defaultOrder.filter { it !in list }
            return (list + missing).distinct()
        }

        fun parseDisabled(raw: String?): Set<DetailsSectionKey> {
            if (raw.isNullOrBlank() || raw.equals("NONE", ignoreCase = true)) return emptySet()
            return raw.split(",").mapNotNull { name ->
                entries.find { it.name.equals(name.trim(), ignoreCase = true) }
            }.toSet()
        }

        fun serialize(list: Collection<DetailsSectionKey>): String {
            return if (list.isEmpty()) "NONE" else list.joinToString(",") { it.name }
        }
    }
}
