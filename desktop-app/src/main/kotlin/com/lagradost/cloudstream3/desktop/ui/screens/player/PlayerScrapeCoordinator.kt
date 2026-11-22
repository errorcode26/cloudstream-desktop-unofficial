package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEnrichmentService
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerPhase
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiState
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class PlayerScrapeCoordinator(
    private val scope: CoroutineScope,
    private val scraper: PlayerStreamScraper,
    private val getState: () -> PlayerUiState,
    private val updateState: (PlayerUiState.() -> PlayerUiState) -> Unit,
    private val updatePhase: (PlayerPhase, Map<String, String>?) -> Unit,
    private val onPlaybackError: (failedUrl: String, reason: String) -> Unit,
    private val getScrapeFlowControl: () -> String?,
) {
    private var loadLinksJob: Job? = null
    private var scrapeGraceJob: Job? = null

    fun cancelAll() {
        scrapeGraceJob?.cancel()
        scrapeGraceJob = null
        loadLinksJob?.cancel()
        loadLinksJob = null
    }

    fun cancelLoading() {
        scrapeGraceJob?.cancel()
        scrapeGraceJob = null
        loadLinksJob?.cancel()
        updateState {
            copy(
                targetEpisodeData = null,
                phase = PlayerPhase.Idle,
                nextEpisodeError = null,
            )
        }
    }

    fun startScrape(
        provider: MainAPI,
        targetEpisodeId: String,
        baseLaunchData: VideoLaunchData,
        targetEpisodeData: Episode? = null,
    ): Job {
        loadLinksJob?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            scrapeAndPlay(provider, targetEpisodeId, baseLaunchData, targetEpisodeData)
        }
        loadLinksJob = job
        return job
    }

    fun stopBackgroundScraping() {
        scrapeGraceJob?.cancel()
        scrapeGraceJob = null
        loadLinksJob?.cancel()
        loadLinksJob = null
        scope.launch(Dispatchers.IO) {
            val current = getState().launchData
            val startPos = current?.startPositionMs ?: 0L
            val sortedLinks = PlayerStreamSelector.sortLinks(getState().nextEpisodeLinks, startPos)
            if (sortedLinks.isNotEmpty()) {
                val candidateKeys = listOfNotNull(
                    current?.history?.episodeId,
                    getState().targetEpisodeData?.data,
                ).distinct()
                LinkCache.set(candidateKeys, sortedLinks, getState().nextEpisodeSubtitles)
            }
            updateState {
                val currentLaunch = launchData
                val updatedLaunch = if (currentLaunch != null) currentLaunch.copy(links = sortedLinks) else null
                val newPhase = when (val p = phase) {
                    is PlayerPhase.Probing -> p.copy(stillScraping = false)
                    is PlayerPhase.Playing -> p.copy(stillScraping = false)
                    is PlayerPhase.Scraping -> PlayerPhase.Idle
                    else -> p
                }
                copy(
                    nextEpisodeLinks = sortedLinks,
                    launchData = updatedLaunch ?: launchData,
                    phase = newPhase,
                )
            }
        }
    }

    fun cancelScraping() {
        loadLinksJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val state = getState()
            val currentLinks = state.nextEpisodeLinks.ifEmpty { state.launchData?.links ?: emptyList() }
            val current = state.launchData
            val startPos = current?.startPositionMs ?: 0L
            val sortedLinks = PlayerStreamSelector.sortLinks(currentLinks, startPos)
            val best = PlayerStreamSelector.pickBestActiveLink(sortedLinks, state.failedLinks.keys, startPos)

            if (best != null && current != null) {
                val epData = state.targetEpisodeData
                val pastHistory = if (epData != null) {
                    DesktopDataStore.getEpisodeWatched(
                        parentId = current.history.parentId,
                        episodeId = epData.data,
                    )
                } else {
                    null
                }
                val resumeStartPos = if (pastHistory != null && pastHistory.duration > 0 && pastHistory.position < pastHistory.duration - 15) {
                    pastHistory.position * 1000L
                } else {
                    startPos
                }

                val newHistory = if (epData != null) {
                    current.history.copy(
                        episodeId = epData.data,
                        episode = epData.episode,
                        season = epData.season,
                        position = resumeStartPos / 1000L,
                        duration = pastHistory?.duration ?: 0L,
                    )
                } else {
                    current.history
                }

                val newLaunchData = current.copy(
                    links = sortedLinks,
                    subtitles = state.nextEpisodeSubtitles.ifEmpty { current.subtitles },
                    history = newHistory,
                    initialIndex = sortedLinks.indexOfFirst { it.url == best.url }.coerceAtLeast(0),
                    startPositionMs = resumeStartPos,
                    title = if (epData != null) {
                        buildString {
                            append(newHistory.showName)
                            if (newHistory.season != null && newHistory.episode != null) {
                                append(" - S${newHistory.season}E${newHistory.episode}")
                            } else if (newHistory.episode != null) {
                                append(" - E${newHistory.episode}")
                            }
                        }
                    } else {
                        current.title
                    },
                )

                updateState {
                    copy(
                        launchData = newLaunchData,
                        nextEpisodeLinks = sortedLinks,
                        targetEpisodeData = null,
                        nextEpisodeError = null,
                    )
                }
                updatePhase(PlayerPhase.Probing(best, stillScraping = false), null)
            } else {
                var candidateToProbe: ExtractorLink? = null
                var errorReason: String? = null
                val currentFailed = state.failedLinks
                val isScrapingPhase = state.phase is PlayerPhase.Scraping

                if (isScrapingPhase) {
                    val candidate = PlayerStreamSelector.pickBestActiveLink(sortedLinks, currentFailed.keys, startPos)
                    if (candidate != null) {
                        candidateToProbe = candidate
                    } else {
                        errorReason = "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                    }
                }

                updateState {
                    val newPhase = when (val p = phase) {
                        is PlayerPhase.Probing -> p.copy(stillScraping = false)
                        is PlayerPhase.Scraping -> {
                            if (candidateToProbe != null) {
                                PlayerPhase.Probing(candidateToProbe, stillScraping = false)
                            } else {
                                val reason = errorReason ?: "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                                PlayerPhase.Exhausted(reason, failedLinks, "All ${sortedLinks.size} sources returned errors or timed out.")
                            }
                        }
                        is PlayerPhase.Playing -> p.copy(stillScraping = false)
                        else -> p
                    }
                    copy(phase = newPhase)
                }

                if (candidateToProbe != null) {
                    updatePhase(PlayerPhase.Probing(candidateToProbe, stillScraping = false), null)
                } else if (errorReason != null) {
                    onPlaybackError("", errorReason)
                }
            }
        }
    }

    private suspend fun scrapeAndPlay(
        provider: MainAPI,
        targetEpisodeId: String,
        baseLaunchData: VideoLaunchData,
        targetEpisodeData: Episode? = null,
    ) {
        val hasStartedPlaying = AtomicBoolean(false)

        val current = getState().launchData ?: baseLaunchData
        val pastHistory = if (targetEpisodeData != null) {
            DesktopDataStore.getEpisodeWatched(
                parentId = current.history.parentId,
                episodeId = targetEpisodeData.data,
            )
        } else {
            null
        }

        val startPos = if (pastHistory != null && pastHistory.duration > 0 && pastHistory.position < pastHistory.duration - 15) {
            pastHistory.position * 1000L
        } else {
            0L
        }

        val newHistory = if (targetEpisodeData != null) {
            current.history.copy(
                episodeId = targetEpisodeData.data,
                episode = targetEpisodeData.episode,
                season = targetEpisodeData.season,
                position = startPos / 1000L,
                duration = pastHistory?.duration ?: 0L,
                episodeName = targetEpisodeData.name,
                episodeThumbnailUrl = targetEpisodeData.posterUrl ?: current.history.posterUrl,
                episodeDescription = targetEpisodeData.description ?: current.history.episodeDescription,
            )
        } else {
            null
        }

        val autoPlay = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY) ?: true

        val tmdbId = current.loadResponse?.syncData?.get("tmdb")?.toIntOrNull()
        val targetSeason = targetEpisodeData?.season
        val hasDualCast = current.enrichedActors?.any { it.voiceActor != null } == true
        val newSeasonActors = if (!hasDualCast && tmdbId != null && targetSeason != null && targetSeason > 0 && targetSeason != current.history.season) {
            try {
                val fetched = TmdbEnrichmentService.fetchSeasonCredits(tmdbId, targetSeason)
                if (fetched.isNotEmpty()) fetched else current.enrichedActors
            } catch (_: Throwable) {
                current.enrichedActors
            }
        } else {
            current.enrichedActors
        }

        val showUrl = current.loadResponse?.url ?: current.history.showUrl
        val parentId = current.history.parentId
        val episodeData = targetEpisodeData?.data
        val candidateKeys = buildList {
            targetEpisodeId.takeIf { it.isNotBlank() }?.let { add(it) }
            episodeData?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (isEmpty()) {
                showUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                parentId.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()

        val cached = candidateKeys.firstNotNullOfOrNull { LinkCache.get(it) }
        if (cached != null && cached.links.isNotEmpty()) {
            AppLogger.i("EmbeddedPlayerViewModel:${provider.name}", "Using cached links for episode: $targetEpisodeId")
            val sortedLinks = PlayerStreamSelector.sortLinks(cached.links, startPos)

            val newLaunchData = if (targetEpisodeData != null && newHistory != null) {
                current.copy(
                    links = sortedLinks,
                    subtitles = cached.subtitles,
                    history = newHistory.copy(position = startPos / 1000L, duration = pastHistory?.duration ?: 0L),
                    initialIndex = 0,
                    startPositionMs = startPos,
                    enrichedActors = newSeasonActors,
                    title = buildString {
                        append(newHistory.showName)
                        if (newHistory.season != null && newHistory.episode != null) {
                            append(" - S${newHistory.season}E${newHistory.episode}")
                        } else if (newHistory.episode != null) {
                            append(" - E${newHistory.episode}")
                        }
                    },
                )
            } else {
                current.copy(
                    links = sortedLinks,
                    subtitles = cached.subtitles,
                    initialIndex = 0,
                )
            }

            val bestLink = PlayerStreamSelector.pickBestActiveLink(sortedLinks, emptySet(), startPos)
            AppLogger.i("EmbeddedPlayerViewModel:${provider.name}", "Cache hit — picked link: ${bestLink?.url?.take(60)}")
            updateState {
                copy(
                    nextEpisodeLinks = sortedLinks,
                    nextEpisodeSubtitles = cached.subtitles,
                    launchData = newLaunchData,
                    targetEpisodeData = null,
                    nextEpisodeError = null,
                    failedLinks = emptyMap(),
                )
            }
            if (bestLink != null) {
                updatePhase(PlayerPhase.Probing(bestLink, false), null)
            } else {
                updatePhase(PlayerPhase.Idle, null)
            }
            return
        }

        var fastPlayJob: Job? = null

        val triggerFastPlayback: () -> Unit = {
            val isScrapingPhase = getState().phase is PlayerPhase.Scraping
            if (hasStartedPlaying.compareAndSet(false, true) || isScrapingPhase) {
                hasStartedPlaying.set(true)
                var bestLinkToProbe: ExtractorLink? = null
                updateState {
                    val currentLinks = nextEpisodeLinks
                    val sorted = PlayerStreamSelector.sortLinks(currentLinks, startPos)
                    val best = PlayerStreamSelector.pickBestActiveLink(sorted, failedLinks.keys, startPos)
                    if (best != null) {
                        bestLinkToProbe = best
                        val launch = if (targetEpisodeData != null && newHistory != null) {
                            current.copy(
                                links = sorted,
                                subtitles = nextEpisodeSubtitles,
                                history = newHistory.copy(position = startPos / 1000L, duration = pastHistory?.duration ?: 0L),
                                initialIndex = 0,
                                startPositionMs = startPos,
                                enrichedActors = newSeasonActors,
                                title = buildString {
                                    append(newHistory.showName)
                                    if (newHistory.season != null && newHistory.episode != null) {
                                        append(" - S${newHistory.season}E${newHistory.episode}")
                                    } else if (newHistory.episode != null) {
                                        append(" - E${newHistory.episode}")
                                    }
                                },
                            )
                        } else {
                            current.copy(
                                links = sorted,
                                subtitles = nextEpisodeSubtitles,
                                initialIndex = 0,
                                enrichedActors = newSeasonActors,
                            )
                        }
                        copy(
                            nextEpisodeLinks = sorted,
                            launchData = launch,
                            targetEpisodeData = null,
                            nextEpisodeError = null,
                        )
                    } else {
                        this
                    }
                }
                val link = bestLinkToProbe
                if (link != null) {
                    AppLogger.i("EmbeddedPlayerViewModel:${provider.name}", "Fast-play triggered: probing ${link.name} (quality=${link.quality}p)")
                    updatePhase(PlayerPhase.Probing(link, stillScraping = true), null)

                    val flowControl = getScrapeFlowControl()
                    if (flowControl == "immediate") {
                        AppLogger.i("EmbeddedPlayerViewModel", "Scrape Flow Control: Immediate mode active — stopping background scrape.")
                        stopBackgroundScraping()
                    }
                }
            }
        }

        val pendingLinks = ConcurrentLinkedQueue<ExtractorLink>()
        val pendingSubs = ConcurrentLinkedQueue<SubtitleFile>()

        suspend fun flushBatch() = withContext(Dispatchers.Default) {
            if (pendingLinks.isEmpty() && pendingSubs.isEmpty()) return@withContext

            val drainedLinks = mutableListOf<ExtractorLink>()
            while (true) {
                val link = pendingLinks.poll() ?: break
                drainedLinks.add(link)
            }

            val drainedSubs = mutableListOf<SubtitleFile>()
            while (true) {
                val sub = pendingSubs.poll() ?: break
                drainedSubs.add(sub)
            }

            if (drainedLinks.isEmpty() && drainedSubs.isEmpty()) return@withContext

            if (drainedLinks.isNotEmpty()) {
                LinkCache.addLinks(candidateKeys, drainedLinks)
            }
            if (drainedSubs.isNotEmpty()) {
                LinkCache.addLinks(candidateKeys, emptyList(), drainedSubs)
            }

            val isPlaying = hasStartedPlaying.get()
            val currentLinks = getState().nextEpisodeLinks
            val combinedLinks = if (drainedLinks.isNotEmpty()) {
                val merged = (currentLinks + drainedLinks).distinctBy { it.url }
                if (isPlaying) merged else PlayerStreamSelector.sortLinks(merged, startPos)
            } else {
                currentLinks
            }

            val currentSubs = getState().nextEpisodeSubtitles
            val combinedSubs = if (drainedSubs.isNotEmpty()) {
                (currentSubs + drainedSubs).distinctBy { it.url.trim().lowercase() }
            } else {
                currentSubs
            }

            var candidateToProbe: ExtractorLink? = null
            if (getState().phase is PlayerPhase.Scraping && isPlaying) {
                candidateToProbe = PlayerStreamSelector.pickBestActiveLink(combinedLinks, getState().failedLinks.keys, startPos)
            }

            updateState {
                if (!isScrapingLinks && !isPlaying) return@updateState this
                val currentLaunch = launchData
                val updatedLaunch = if (currentLaunch != null && currentLaunch.history.episodeId == targetEpisodeId) {
                    currentLaunch.copy(links = combinedLinks, subtitles = combinedSubs)
                } else {
                    currentLaunch
                }

                copy(
                    nextEpisodeLinks = combinedLinks,
                    nextEpisodeSubtitles = combinedSubs,
                    launchData = updatedLaunch,
                )
            }

            if (candidateToProbe != null) {
                updatePhase(PlayerPhase.Probing(candidateToProbe, stillScraping = true), null)
            }
        }

        val flusherJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(150L)
                flushBatch()
            }
        }

        val result = try {
            scraper.executeScrape(
                provider = provider,
                targetEpisodeId = targetEpisodeId,
                autoPlay = autoPlay,
                currentLaunchData = current,
                targetEpisodeData = targetEpisodeData,
                onSubtitle = { cleanSub ->
                    pendingSubs.add(cleanSub)
                },
                onLink = { link ->
                    pendingLinks.add(link)
                    val isPlaying = hasStartedPlaying.get()
                    if (!isPlaying && autoPlay && getState().phase is PlayerPhase.Scraping) {
                        val score = QualityDataHelper.getLinkScore(link)
                        if (score >= QualityDataHelper.FAST_PLAY_SCORE_THRESHOLD) {
                            fastPlayJob?.cancel()
                            fastPlayJob = scope.launch(Dispatchers.IO) {
                                delay(900)
                                triggerFastPlayback()
                            }
                        } else if (fastPlayJob == null) {
                            fastPlayJob = scope.launch(Dispatchers.IO) {
                                delay(2000)
                                triggerFastPlayback()
                            }
                        }
                    }
                },
            )
        } finally {
            flusherJob.cancel()
            flushBatch()
            fastPlayJob?.cancel()
            scrapeGraceJob?.cancel()
        }

        if (result.isSuccess) {
            val sortedLinks = PlayerStreamSelector.sortLinks(getState().nextEpisodeLinks, startPos)
            if (sortedLinks.isNotEmpty()) {
                LinkCache.set(candidateKeys, sortedLinks, getState().nextEpisodeSubtitles)
            }

            if (hasStartedPlaying.get()) {
                var candidateToProbe: ExtractorLink? = null
                var errorReason: String? = null
                val isScrapingPhase = getState().phase is PlayerPhase.Scraping
                if (isScrapingPhase) {
                    val candidate = PlayerStreamSelector.pickBestActiveLink(sortedLinks, getState().failedLinks.keys, startPos)
                    if (candidate != null) {
                        candidateToProbe = candidate
                    } else {
                        errorReason = "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                    }
                }

                updateState {
                    val newPhase = when (val p = phase) {
                        is PlayerPhase.Probing -> p.copy(stillScraping = false)
                        is PlayerPhase.Scraping -> {
                            if (candidateToProbe != null) {
                                PlayerPhase.Probing(candidateToProbe, stillScraping = false)
                            } else {
                                val reason = errorReason ?: "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                                PlayerPhase.Exhausted(reason, failedLinks, "All ${sortedLinks.size} sources returned errors or timed out.")
                            }
                        }
                        is PlayerPhase.Playing -> p.copy(stillScraping = false)
                        else -> p
                    }
                    val currentLaunch = launchData
                    val updatedLaunch = if (currentLaunch != null && currentLaunch.history.episodeId == targetEpisodeId) {
                        currentLaunch.copy(links = sortedLinks)
                    } else {
                        currentLaunch
                    }
                    copy(
                        phase = newPhase,
                        nextEpisodeLinks = sortedLinks,
                        launchData = updatedLaunch ?: launchData,
                    )
                }

                if (candidateToProbe != null) {
                    updatePhase(PlayerPhase.Probing(candidateToProbe, stillScraping = false), null)
                } else if (errorReason != null) {
                    onPlaybackError("", errorReason)
                }
            } else {
                var bestLinkToProbe: ExtractorLink? = null
                updateState {
                    if (nextEpisodeLinks.isNotEmpty()) {
                        hasStartedPlaying.set(true)
                        val best = PlayerStreamSelector.pickBestActiveLink(sortedLinks, emptySet(), startPos)
                        if (best != null) {
                            bestLinkToProbe = best
                            val launch = if (targetEpisodeData != null && newHistory != null) {
                                current.copy(
                                    links = sortedLinks,
                                    subtitles = nextEpisodeSubtitles,
                                    history = newHistory.copy(position = startPos / 1000L, duration = pastHistory?.duration ?: 0L),
                                    initialIndex = 0,
                                    startPositionMs = startPos,
                                    enrichedActors = newSeasonActors,
                                    title = buildString {
                                        append(newHistory.showName)
                                        if (newHistory.season != null && newHistory.episode != null) {
                                            append(" - S${newHistory.season}E${newHistory.episode}")
                                        } else if (newHistory.episode != null) {
                                            append(" - E${newHistory.episode}")
                                        }
                                    },
                                )
                            } else {
                                current.copy(
                                    links = sortedLinks,
                                    subtitles = nextEpisodeSubtitles,
                                    initialIndex = 0,
                                    enrichedActors = newSeasonActors,
                                )
                            }
                            copy(
                                nextEpisodeLinks = sortedLinks,
                                launchData = launch,
                                targetEpisodeData = null,
                                nextEpisodeError = null,
                            )
                        } else {
                            copy(
                                phase = PlayerPhase.Exhausted(
                                    reason = "No playable sources found.",
                                    failedLinks = emptyMap(),
                                    diagnostics = "Provider '${provider.name}' returned candidate links, but none met playable criteria.",
                                ),
                                targetEpisodeData = null,
                                nextEpisodeError = null,
                            )
                        }
                    } else {
                        copy(
                            phase = PlayerPhase.Exhausted(
                                reason = "No streams discovered.",
                                failedLinks = emptyMap(),
                                diagnostics = "Provider '${provider.name}' returned 0 streams for this title/episode.",
                            ),
                            targetEpisodeData = null,
                            nextEpisodeError = null,
                        )
                    }
                }

                val linkToProbe = bestLinkToProbe
                if (linkToProbe != null) {
                    updatePhase(PlayerPhase.Probing(linkToProbe, false), null)
                }
            }
        } else {
            val ex = result.exceptionOrNull()
            if (!hasStartedPlaying.get()) {
                candidateKeys.forEach { LinkCache.remove(it) }
            }

            val isJsonParseError = ex is com.fasterxml.jackson.core.JsonParseException ||
                ex?.message?.contains("Unrecognized token") == true ||
                ex?.cause is com.fasterxml.jackson.core.JsonParseException

            val currentShowUrl = current.loadResponse?.url ?: current.history.showUrl
            if (!hasStartedPlaying.get() && isJsonParseError && targetEpisodeId.startsWith("http")) {
                AppLogger.w("Plugin:${provider.name}", "Detected invalid episode data payload ($targetEpisodeId). Performing automatic self-healing re-fetch from $currentShowUrl...")
                val healed = scraper.attemptSelfHealing(provider, targetEpisodeId, currentShowUrl)
                if (healed != null) {
                    val (freshDataUrl, freshResp) = healed
                    val newTargetEp = provider.newEpisode(freshDataUrl) {
                        this.name = freshResp.name
                        this.posterUrl = freshResp.posterUrl
                    }
                    val updatedLaunch = current.copy(
                        loadResponse = freshResp,
                        history = current.history.copy(episodeId = freshDataUrl),
                    )
                    scrapeAndPlay(provider, freshDataUrl, updatedLaunch, newTargetEp)
                    return
                }
            }

            if (hasStartedPlaying.get()) {
                val sortedLinks = PlayerStreamSelector.sortLinks(getState().nextEpisodeLinks, startPos)
                if (sortedLinks.isNotEmpty()) {
                    LinkCache.set(targetEpisodeId, sortedLinks, getState().nextEpisodeSubtitles)
                }
                var candidateToProbe: ExtractorLink? = null
                var errorReason: String? = null
                val isScrapingPhase = getState().phase is PlayerPhase.Scraping
                if (isScrapingPhase) {
                    val candidate = PlayerStreamSelector.pickBestActiveLink(sortedLinks, getState().failedLinks.keys, startPos)
                    if (candidate != null) {
                        candidateToProbe = candidate
                    } else {
                        errorReason = "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                    }
                }

                updateState {
                    val newPhase = when (val p = phase) {
                        is PlayerPhase.Probing -> p.copy(stillScraping = false)
                        is PlayerPhase.Scraping -> {
                            if (candidateToProbe != null) {
                                PlayerPhase.Probing(candidateToProbe, stillScraping = false)
                            } else {
                                val reason = errorReason ?: "All ${sortedLinks.size} discovered stream sources failed or could not be loaded"
                                PlayerPhase.Exhausted(reason, failedLinks, "All ${sortedLinks.size} sources returned errors or timed out.")
                            }
                        }
                        is PlayerPhase.Playing -> p.copy(stillScraping = false)
                        else -> p
                    }
                    copy(phase = newPhase)
                }

                if (candidateToProbe != null) {
                    updatePhase(PlayerPhase.Probing(candidateToProbe, stillScraping = false), null)
                } else if (errorReason != null) {
                    onPlaybackError("", errorReason)
                }
            } else {
                var bestFallbackToProbe: ExtractorLink? = null
                updateState {
                    if (nextEpisodeLinks.isNotEmpty()) {
                        hasStartedPlaying.set(true)
                        val sortedLinks = PlayerStreamSelector.sortLinks(nextEpisodeLinks, startPos)
                        val best = PlayerStreamSelector.pickBestActiveLink(sortedLinks, emptySet(), startPos)
                        if (best != null) {
                            bestFallbackToProbe = best
                            val launch = if (targetEpisodeData != null && newHistory != null) {
                                current.copy(
                                    links = sortedLinks,
                                    subtitles = nextEpisodeSubtitles,
                                    history = newHistory.copy(position = startPos / 1000L, duration = pastHistory?.duration ?: 0L),
                                    initialIndex = 0,
                                    startPositionMs = startPos,
                                    enrichedActors = newSeasonActors,
                                    title = buildString {
                                        append(newHistory.showName)
                                        if (newHistory.season != null && newHistory.episode != null) {
                                            append(" - S${newHistory.season}E${newHistory.episode}")
                                        } else if (newHistory.episode != null) {
                                            append(" - E${newHistory.episode}")
                                        }
                                    },
                                )
                            } else {
                                current.copy(
                                    links = sortedLinks,
                                    subtitles = nextEpisodeSubtitles,
                                    initialIndex = 0,
                                    enrichedActors = newSeasonActors,
                                )
                            }
                            LinkCache.set(targetEpisodeId, sortedLinks, nextEpisodeSubtitles)
                            copy(
                                nextEpisodeLinks = sortedLinks,
                                launchData = launch,
                                targetEpisodeData = null,
                                nextEpisodeError = null,
                            )
                        } else {
                            copy(
                                phase = PlayerPhase.Exhausted(
                                    reason = "No playable sources found.",
                                    failedLinks = emptyMap(),
                                    diagnostics = "Provider '${provider.name}' returned candidate links, but none met playable criteria.",
                                ),
                                targetEpisodeData = null,
                                nextEpisodeError = null,
                            )
                        }
                    } else {
                        AppLogger.e("Plugin:${provider.name}", "Failed to load links: ${ex?.message}", ex)
                        copy(
                            phase = PlayerPhase.Exhausted(
                                reason = "Failed to load links: ${ex?.message ?: "Unknown error"}",
                                failedLinks = emptyMap(),
                                diagnostics = PlayerErrorDiagnostics.buildScrapeExceptionReport(provider.name, ex),
                            ),
                            targetEpisodeData = null,
                            nextEpisodeError = null,
                        )
                    }
                }

                val fallbackToProbe = bestFallbackToProbe
                if (fallbackToProbe != null) {
                    updatePhase(PlayerPhase.Probing(fallbackToProbe, false), null)
                }
            }
        }
    }
}
