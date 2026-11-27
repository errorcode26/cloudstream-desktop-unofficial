package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.player.LanguagePriorityHelper
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler

object EpisodeSectionState {

    fun resolveDubStatuses(data: LoadResponse): List<DubStatus> {
        return if (data is AnimeLoadResponse) {
            data.episodes.filter { it.value.isNotEmpty() }.keys.toList()
        } else {
            emptyList()
        }
    }

    fun resolveInitialDub(data: LoadResponse, latestHistory: WatchHistory?, dubStatuses: List<DubStatus>): DubStatus? {
        if (data !is AnimeLoadResponse) return null
        if (latestHistory != null) {
            return dubStatuses.find { dub -> data.episodes[dub]?.any { it.matchesHistory(latestHistory) } == true }
                ?: dubStatuses.firstOrNull()
        }
        val topAudio = LanguagePriorityHelper.getOrderedAudioLanguages().firstOrNull() ?: "auto"
        val prefersDub = topAudio !in listOf("jpn,ja", "original", "auto", "")
        val preferredDubStatus = if (prefersDub) DubStatus.Dubbed else DubStatus.Subbed
        return dubStatuses.find { it == preferredDubStatus } ?: dubStatuses.firstOrNull()
    }

    fun resolveSeasons(data: LoadResponse): List<Int> {
        val rawList = when (data) {
            is TvSeriesLoadResponse -> data.episodes.mapNotNull { it.season }.distinct()
            is AnimeLoadResponse -> data.episodes.values.flatten().mapNotNull { it.season }.distinct()
            else -> emptyList()
        }
        val list = rawList.filter { it > 0 }.sorted() + rawList.filter { it <= 0 }.sorted()
        return if (list.isEmpty() && (data is TvSeriesLoadResponse || data is AnimeLoadResponse)) {
            listOf(1)
        } else {
            list
        }
    }

    fun resolveInitialSeason(data: LoadResponse, latestHistory: WatchHistory?, seasons: List<Int>): Int {
        if (data !is TvSeriesLoadResponse && data !is AnimeLoadResponse) return 1
        return latestHistory?.season?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.firstOrNull()
            ?: 1
    }

    fun resolveRawEpisodes(data: LoadResponse, selectedDub: DubStatus?): List<Episode> {
        return when (data) {
            is AnimeLoadResponse -> {
                selectedDub?.let { data.episodes[it] }?.takeIf { it.isNotEmpty() }
                    ?: data.episodes.values.firstOrNull { it.isNotEmpty() }
                    ?: emptyList()
            }
            is TvSeriesLoadResponse -> data.episodes
            else -> emptyList()
        }
    }

    fun filterAndSortEpisodes(
        rawEpisodes: List<Episode>,
        effectiveSeason: Int,
        seasons: List<Int>,
        isSortAscending: Boolean,
    ): List<Episode> {
        val filtered = if (seasons.size <= 1) {
            rawEpisodes
        } else {
            rawEpisodes.filter { ep ->
                ep.season == effectiveSeason || (ep.season == null && effectiveSeason == seasons.first())
            }
        }
        val resolved = if (filtered.isEmpty() && rawEpisodes.isNotEmpty()) rawEpisodes else filtered
        return resolved
            .distinctBy { ep ->
                if (ep.episode != null && ep.episode != 0) {
                    "ep:${ep.season ?: 1}:${ep.episode}"
                } else {
                    "data:${ep.data.ifBlank { ep.name ?: ep.hashCode().toString() }}"
                }
            }
            .let { list ->
                val hasEpisodeNumbers = list.any { it.episode != null && it.episode != 0 }
                if (hasEpisodeNumbers) {
                    if (isSortAscending) {
                        list.sortedBy { it.episode ?: Int.MAX_VALUE }
                    } else {
                        list.sortedByDescending { it.episode ?: Int.MIN_VALUE }
                    }
                } else {
                    if (isSortAscending) list else list.reversed()
                }
            }
    }

    fun calculateTargetEpisodeIndex(episodes: List<Episode>, latestHistory: WatchHistory?): Int {
        if (latestHistory == null || episodes.isEmpty()) return 0
        val isLatestCompleted = latestHistory.duration > 0 &&
            PlayerLinkHandler.isCompleted(latestHistory.position, latestHistory.duration)
        val currentIdx = episodes.indexOfFirst { it.matchesHistory(latestHistory) }
        return if (currentIdx != -1) {
            if (isLatestCompleted && currentIdx + 1 < episodes.size) {
                currentIdx + 1
            } else {
                currentIdx
            }
        } else {
            0
        }
    }

    fun filterReleasedEpisodes(
        episodes: List<Episode>,
        providerName: String,
        aniListCeilingEpisode: Int? = null,
        episodeOffset: Int = 0,
    ): List<Episode> {
        return episodes.filter { ep ->
            val localStatus = parseEpisodeReleaseStatus(ep, providerName)
            if (localStatus.isUnreleased || localStatus.isMissingFromProvider) return@filter false
            if (aniListCeilingEpisode != null) {
                val epNum = ep.episode ?: return@filter false
                if (epNum <= 0) return@filter false
                if (epNum + episodeOffset > aniListCeilingEpisode) return@filter false
            }
            true
        }
    }

    fun isSeasonCompleted(currentSeasonEpisodes: List<Episode>, historyLookup: EpisodeHistoryLookup): Boolean {
        return currentSeasonEpisodes.isNotEmpty() && currentSeasonEpisodes.all { ep ->
            val hist = historyLookup.find(ep)
            hist != null && PlayerLinkHandler.isCompleted(hist.position, hist.duration)
        }
    }
}
