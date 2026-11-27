package com.lagradost.cloudstream3.desktop.explore.viewmodel

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.desktop.explore.client.ExploreCollectionDetail
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ExploreSearchScope
import com.lagradost.cloudstream3.desktop.explore.models.ExploreShelf
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.explore.models.StreamingPlatform
import com.lagradost.cloudstream3.desktop.explore.search.ExploreSearchResults
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.common.storage.WatchHistory

val EXPLORE_YEAR_OPTIONS = listOf(
    "All Years",
    "2026",
    "2025",
    "2024",
    "2023",
    "2022",
    "2021",
    "2020",
    "2010s",
    "2000s",
    "1990s & Older",
)

@Immutable
data class ExploreUiState(
    val isInitializing: Boolean = true,
    val allCatalogs: List<ManifestCatalogDescriptor> = emptyList(),
    val availableTypes: List<String> = emptyList(),
    val selectedType: String = "addons",
    val filteredCatalogs: List<ManifestCatalogDescriptor> = emptyList(),
    val selectedCatalog: ManifestCatalogDescriptor? = null,
    val selectedGenre: String = "All",
    val selectedYear: String = "All Years",
    val searchQuery: String = "",
    val rawItems: List<ExploreItem> = emptyList(),
    val displayItems: List<ExploreItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val selectedItemForMatch: ExploreItem? = null,
    val providerMatches: List<ProviderMatch> = emptyList(),
    val isSearchingProviders: Boolean = false,
    val watchHistoryMap: Map<String, WatchHistory> = emptyMap(),
    val isShelvesMode: Boolean = true,
    val allShelves: List<ExploreShelf> = emptyList(),
    val shelves: List<ExploreShelf> = emptyList(),
    val heroItems: List<ExploreItem> = emptyList(),
    val isShelvesLoading: Boolean = false,
    val drilledCatalog: ManifestCatalogDescriptor? = null,
    val platformShelves: List<ExploreShelf> = emptyList(),
    val isPlatformShelvesLoading: Boolean = false,
    val drilledPlatform: StreamingPlatform? = null,
    val searchResults: ExploreSearchResults? = null,
    val isLiveSearching: Boolean = false,
    val drilledSearchCategory: String? = null,
    val availableAddons: List<String> = emptyList(),
    val selectedAddon: String = "All Sources",
    val selectedCollection: ExploreCollectionDetail? = null,
    val isCollectionLoading: Boolean = false,
    val isCustomizeDialogOpen: Boolean = false,
    val searchScope: ExploreSearchScope = ExploreSearchScope.ALL,
) : UiState

sealed interface ExploreUiEvent : UiEvent {
    data class SelectType(val type: String) : ExploreUiEvent
    data class SelectAddon(val addonName: String) : ExploreUiEvent
    data class SelectCatalog(val catalog: ManifestCatalogDescriptor) : ExploreUiEvent
    data class SelectGenre(val genre: String) : ExploreUiEvent
    data class SelectYear(val year: String) : ExploreUiEvent
    data class SelectSearchScope(val scope: ExploreSearchScope) : ExploreUiEvent
    data class UpdateSearchQuery(val query: String) : ExploreUiEvent
    data object ClearSearchQuery : ExploreUiEvent
    data class OpenProviderPicker(val item: ExploreItem) : ExploreUiEvent
    data object CloseProviderPicker : ExploreUiEvent
    data class SelectProviderMatch(val match: ProviderMatch) : ExploreUiEvent
    data object LoadMore : ExploreUiEvent
    data object RefreshCatalogs : ExploreUiEvent
    data class ToggleViewMode(val isShelves: Boolean) : ExploreUiEvent
    data class DrillIntoCatalog(val catalog: ManifestCatalogDescriptor) : ExploreUiEvent
    data class DrillIntoPlatform(val platform: StreamingPlatform) : ExploreUiEvent
    data object ReturnToShelves : ExploreUiEvent
    data class DrillIntoSearchCategory(val category: String) : ExploreUiEvent
    data object ReturnFromSearchCategory : ExploreUiEvent
    data class OpenCollection(val collectionId: Int) : ExploreUiEvent
    data object CloseCollection : ExploreUiEvent
    data object OpenCustomizeDialog : ExploreUiEvent
    data object CloseCustomizeDialog : ExploreUiEvent
}

sealed interface ExploreUiEffect : UiEffect {
    data class OpenDetails(
        val providerName: String,
        val url: String,
        val title: String,
        val canonicalSeed: CanonicalMediaSeed? = null,
    ) : ExploreUiEffect
}
