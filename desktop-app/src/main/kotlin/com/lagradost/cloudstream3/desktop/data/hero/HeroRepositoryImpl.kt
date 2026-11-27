package com.lagradost.cloudstream3.desktop.data.hero

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository
import com.lagradost.cloudstream3.desktop.domain.hero.repository.HeroRepository.HeroUpdate
import com.lagradost.cloudstream3.desktop.repo.HERO_CACHE_TTL_MS
import com.lagradost.cloudstream3.desktop.repo.HeroCache
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsCache
import com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsRepository
import com.lagradost.cloudstream3.desktop.ui.screens.details.HybridEnrichmentService
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class HeroRepositoryImpl : HeroRepository {
    private val prefetchingUrls = ConcurrentHashMap.newKeySet<String>()
    private val backgroundSemaphore = Semaphore(2)

    init {
        // Clean up legacy herometa keys from persistent storage to eliminate bloat
        try {
            val legacyKeys = DesktopDataStore.getAllKeysWithPrefix("herometa_")
            if (legacyKeys.isNotEmpty()) {
                legacyKeys.forEach { DesktopDataStore.removeKey(it) }
                AppLogger.i("HeroRepository", "Cleaned up ${legacyKeys.size} legacy herometa storage keys")
            }
        } catch (e: Throwable) {
            AppLogger.w("HeroRepository", "Failed to clean legacy herometa keys", e)
        }
    }

    override fun cleanHeroTitle(title: String): String = TitleUtils.cleanProviderTitle(title).first

    override suspend fun prefetchTopHistory(topHistory: List<WatchHistory>, providers: List<MainAPI>) {
        withContext(Dispatchers.IO) {
            for (history in topHistory) {
                val provider = DetailsRepository.findProviderForUrl(history.showUrl) ?: providers.find { it.name == history.apiName } ?: APIHolder.getApiFromNameNull(history.apiName)
                val cacheKey = "${history.apiName}_${history.showUrl}"
                if (provider != null && !DetailsCache.containsKey(history.showUrl) && HeroCache.get(cacheKey) == null) {
                    if (!prefetchingUrls.add(cacheKey)) continue
                    try {
                        val raw = DetailsRepository.fetchRaw(
                            provider = provider,
                            url = history.showUrl,
                            fallbackName = history.showName,
                            notifyUserOnError = false,
                        )
                        if (raw != null) {
                            HybridEnrichmentService.enrich(raw, history.showUrl, fetchCast = false, onScreenshotsLoaded = {})
                        }
                    } catch (e: Exception) {
                        AppLogger.e("HeroRepository", "Failed to prefetch history item", e)
                    } finally {
                        prefetchingUrls.remove(cacheKey)
                    }
                }
            }
        }
    }

    override fun prefetchHeroItem(
        provider: MainAPI?,
        item: SearchResponse,
    ): Flow<HeroUpdate> = callbackFlow {
        val cacheKey = "${provider?.name}_${item.url}"
        val existing = HeroCache.get(cacheKey)

        if (existing != null) {
            trySend(HeroUpdate.Meta(item.url, existing))
            val colorTarget = existing.backdropUrl ?: provider?.fixUrlNull(item.posterUrl)
            if (colorTarget != null) trySend(HeroUpdate.ColorTarget(item.url, colorTarget))
            close()
            return@callbackFlow
        }

        if (!prefetchingUrls.add(cacheKey)) {
            close()
            return@callbackFlow
        }

        try {
            backgroundSemaphore.withPermit {
                val isSeriesPattern = Regex("""(?i)\b(?:season|series|s\d{1,2}|episodes?|complete|all-episodes|web-series|tv-series)\b""").containsMatchIn(item.name) ||
                    Regex("""(?i)\b(?:season|series|s\d{1,2}|episodes?|all-episodes|web-series|tv-series)\b""").containsMatchIn(item.url) ||
                    Regex("""(?i)["']type["']\s*:\s*["'](?:tv|series|show)["']""").containsMatchIn(item.url)
                val itemType = item.type
                val isAnime = itemType == com.lagradost.cloudstream3.TvType.Anime || itemType == com.lagradost.cloudstream3.TvType.AnimeMovie
                val actualType = when {
                    isAnime -> com.lagradost.cloudstream3.TvType.Anime
                    isSeriesPattern -> com.lagradost.cloudstream3.TvType.TvSeries
                    itemType != null && itemType != com.lagradost.cloudstream3.TvType.Movie -> itemType
                    else -> com.lagradost.cloudstream3.TvType.Movie
                }

                val (dummyTitle, parsedYear) = TitleUtils.cleanProviderTitle(item.name)
                AppLogger.i("Enrichment", "[HERO] prefetch | raw='${item.name}' | clean='$dummyTitle' | type=$actualType | url=${item.url}")

                if (provider != null) {
                    // Fetch authentic scraped provider details like upstream Android
                    val raw = try {
                        DetailsRepository.fetchRaw(
                            provider = provider,
                            url = item.url,
                            fallbackName = item.name,
                            notifyUserOnError = false,
                        )
                    } catch (e: Exception) {
                        AppLogger.w("HeroRepository", "fetchRaw failed for ${item.name}, using fallback dummy", e)
                        null
                    }

                    val target = raw ?: if (actualType == com.lagradost.cloudstream3.TvType.TvSeries || actualType == com.lagradost.cloudstream3.TvType.Anime) {
                        provider.newTvSeriesLoadResponse(
                            name = item.name,
                            url = item.url,
                            type = actualType,
                            episodes = emptyList(),
                        ) {
                            this.posterUrl = item.posterUrl
                            if (this.year == null && parsedYear != null) this.year = parsedYear
                        }
                    } else {
                        provider.newMovieLoadResponse(
                            name = item.name,
                            url = item.url,
                            type = actualType,
                            dataUrl = item.url,
                        ) {
                            this.posterUrl = item.posterUrl
                            if (this.year == null && parsedYear != null) this.year = parsedYear
                        }
                    }

                    HybridEnrichmentService.enrich(
                        loaded = target,
                        url = item.url,
                        fetchCast = false,
                        onScreenshotsLoaded = {},
                    )

                    val backdropUrl = target.backgroundPosterUrl?.takeIf { it.isNotBlank() }?.let { provider.fixUrlNull(it) }
                    val logoUrl = target.logoUrl?.takeIf { it.isNotBlank() }?.let { provider.fixUrlNull(it) }
                    val title = target.name.takeIf { it.isNotBlank() && it != dummyTitle && it != item.name } ?: dummyTitle
                    val tags = target.tags?.take(4) ?: emptyList()
                    val plot = TitleUtils.cleanPlot(target.plot)?.replace(Regex("""\s*\n+\s*"""), " ")?.take(250)
                    val score = target.score?.toString()

                    val meta = HeroMeta(title, backdropUrl, logoUrl, tags, plot, score, target.year, target.type, target.contentRating, target.duration, cachedAtMs = System.currentTimeMillis())
                    AppLogger.i("Enrichment", "[HERO] RESULT | title='$title' | backdrop=${backdropUrl != null} | logo=${logoUrl != null} | tags=$tags | score=$score")
                    HeroCache.put(cacheKey, meta)
                    trySend(HeroUpdate.Meta(item.url, meta))

                    val colorTarget = backdropUrl ?: provider.fixUrlNull(item.posterUrl)
                    if (colorTarget != null) trySend(HeroUpdate.ColorTarget(item.url, colorTarget))
                } else {
                    val meta = HeroMeta(dummyTitle, null, null, emptyList(), null, null, null, null, null, null)
                    HeroCache.put(cacheKey, meta)
                    trySend(HeroUpdate.Meta(item.url, meta))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HeroCache.remove(cacheKey)
            AppLogger.e("Enrichment", "[HERO] CRASHED for '${item.name}' — ${e::class.simpleName}: ${e.message}", e)
        } finally {
            prefetchingUrls.remove(cacheKey)
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO.limitedParallelism(2))
}
