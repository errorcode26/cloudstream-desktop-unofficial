package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.data.providers.LazyPluginProvider
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ProviderMatch
import com.lagradost.cloudstream3.desktop.explore.search.AddonSearchResultGroup
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient
import com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.ui.badges.CardTitleSanitizer
import com.lagradost.common.logging.AppLogger
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Handles provider discovery, plugin searching, Stremio metadata enrichment,
 * and fuzzy title matching against scraping providers.
 */
internal object ExploreProviderMatcher {

    fun getResolvedRealProviders(): List<MainAPI> {
        var allProviders = ActiveProviderRepository.allRealProviders.value
        val hasLazyStubs = allProviders.any { it is LazyPluginProvider }
        val loadedProviders = allProviders.filter {
            ActiveProviderRepository.isRealContentProvider(it) &&
                it !is LazyPluginProvider
        }
        if (hasLazyStubs || loadedProviders.isEmpty()) {
            com.lagradost.cloudstream3.desktop.init.loadAllRemainingPlugins()
            ActiveProviderRepository.refreshProviders()
            allProviders = ActiveProviderRepository.allRealProviders.value
        }
        return allProviders.filter {
            ActiveProviderRepository.isRealContentProvider(it) &&
                it !is LazyPluginProvider
        }.ifEmpty {
            APIHolder.allProviders.filter {
                ActiveProviderRepository.isRealContentProvider(it) &&
                    it !is LazyPluginProvider
            }
        }
    }

    suspend fun searchPlugins(
        query: String,
        searchSemaphore: Semaphore,
    ): List<AddonSearchResultGroup> = coroutineScope {
        val activeProviders = getResolvedRealProviders()
        if (activeProviders.isEmpty()) return@coroutineScope emptyList()
        val p2pReady = DesktopTorrentEngine.isP2pReady
        val targetProviders = activeProviders.filter {
            (it.hasMainPage || it.supportedTypes.isNotEmpty()) &&
                (p2pReady || !DesktopTorrentEngine.isPureTorrentProvider(it))
        }

        val pluginGroups = CopyOnWriteArrayList<AddonSearchResultGroup>()
        val jobs = targetProviders.map { provider ->
            launch {
                searchSemaphore.withPermit {
                    try {
                        val res = SafePluginInvoker.invokeOrNull(
                            tag = "Explore:PluginSearch:${provider.name}",
                            timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                        ) {
                            provider.search(query, 1)
                        }
                        val items = res?.items
                        if (!items.isNullOrEmpty()) {
                            val exploreItems = items.map { searchRes ->
                                val itemType = when (searchRes.type) {
                                    TvType.TvSeries -> "series"
                                    TvType.Anime,
                                    TvType.AnimeMovie,
                                    TvType.OVA,
                                    -> "anime"
                                    TvType.Movie -> "movie"
                                    else -> "movie"
                                }
                                ExploreItem(
                                    id = searchRes.url,
                                    type = itemType,
                                    name = searchRes.name,
                                    posterUrl = searchRes.posterUrl,
                                    providerName = provider.name,
                                    itemUrl = searchRes.url,
                                    genres = emptyList(),
                                )
                            }
                            pluginGroups.add(AddonSearchResultGroup(addonName = provider.name, items = exploreItems))
                        }
                    } catch (e: Exception) {
                        AppLogger.d("ExploreProviderMatcher", "Live provider search error for ${provider.name}: ${e.message}")
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        pluginGroups.toList()
    }

    suspend fun resolveProviderMatches(
        item: ExploreItem,
        searchSemaphore: Semaphore,
        onEnrichmentUpdated: (ExploreItem) -> Unit,
        onMatchesUpdated: (List<ProviderMatch>) -> Unit,
    ) = coroutineScope {
        // 1. Asynchronously enrich metadata (logo, backdrop, etc.)
        launch {
            try {
                val meta = StremioAddonClient.getMeta(item.id, item.type)
                if (meta != null) {
                    val enriched = item.copy(
                        logoUrl = meta.logo?.takeIf { it.isNotBlank() } ?: item.logoUrl,
                        backgroundUrl = (meta.background?.replace("t/p/original//", "t/p/original/"))?.takeIf { it.isNotBlank() } ?: item.backgroundUrl,
                        posterUrl = meta.poster?.takeIf { it.isNotBlank() } ?: item.posterUrl,
                        description = meta.description?.takeIf { it.isNotBlank() } ?: item.description,
                        rating = meta.imdbRating?.toDoubleOrNull() ?: item.rating,
                        releaseYear = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.releaseYear,
                        genres = if (meta.genres.isNullOrEmpty()) item.genres else meta.genres,
                    )
                    onEnrichmentUpdated(enriched)
                }
            } catch (e: Exception) {
                AppLogger.d("ExploreProviderMatcher", "Stremio meta enrichment skipped: ${e.message}")
            }
        }

        // 2. Query active content providers
        val activeProviders = getResolvedRealProviders()
        if (activeProviders.isEmpty()) {
            return@coroutineScope
        }

        val cleanTitle = CardTitleSanitizer.sanitize(item.name).displayTitle
        val searchTitle = cleanTitle.ifBlank { item.name }
        val aggregatedMatches = CopyOnWriteArrayList<ProviderMatch>()

        val jobs = activeProviders.map { provider ->
            launch {
                searchSemaphore.withPermit {
                    try {
                        val res = SafePluginInvoker.invokeOrNull(
                            tag = "Explore:Search:${provider.name}",
                            timeoutMs = SafePluginInvoker.TIMEOUT_SEARCH_MS,
                        ) {
                            provider.search(searchTitle, 1)
                        }

                        val searchItems = res?.items
                        if (!searchItems.isNullOrEmpty()) {
                            val validMatches = mutableListOf<ProviderMatch>()
                            for (searchRes in searchItems) {
                                if (ExploreFilterEngine.isTitleRelevant(searchTitle, searchRes.name)) {
                                    val meta = CardTitleSanitizer.sanitize(searchRes.name)
                                    validMatches.add(
                                        ProviderMatch(
                                            providerName = provider.name,
                                            searchResponse = searchRes,
                                            displayTitle = meta.displayTitle,
                                            qualityText = meta.qualityText,
                                            hasSub = meta.hasSub,
                                            hasDub = meta.hasDub,
                                        ),
                                    )
                                }
                            }
                            if (validMatches.isNotEmpty()) {
                                aggregatedMatches.addAll(validMatches)
                                onMatchesUpdated(aggregatedMatches.toList())
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLogger.d("ExploreProviderMatcher", "Provider search error for ${provider.name}: ${e.message}")
                    }
                }
            }
        }

        jobs.joinAll()
        onMatchesUpdated(aggregatedMatches.toList())
    }
}
