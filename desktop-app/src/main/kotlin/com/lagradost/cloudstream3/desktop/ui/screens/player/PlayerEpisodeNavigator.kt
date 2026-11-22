package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxApiClient
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxLibraryCache
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.screens.details.parseEpisodeReleaseStatus
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale

data class EpisodeLockStatus(
    val isLocked: Boolean,
    val message: String? = null,
)

data class TorboxResolvedEpisode(
    val link: ExtractorLink,
    val title: String,
)

object PlayerEpisodeNavigator {

    fun findNextEpisode(
        episodes: List<Episode>,
        currentEpId: String?,
        currentEpNum: Int?,
        currentSeasonNum: Int?,
    ): Episode? {
        var currentIndex = episodes.indexOfFirst { it.data == currentEpId }

        if (currentIndex == -1 && currentEpNum != null) {
            currentIndex = episodes.indexOfFirst { it.episode == currentEpNum && (currentSeasonNum == null || it.season == currentSeasonNum) }
        }

        return if (currentIndex != -1 && currentIndex + 1 < episodes.size) {
            episodes[currentIndex + 1]
        } else if (currentIndex == -1 && episodes.isNotEmpty() && currentEpNum != null) {
            episodes.find { it.episode == currentEpNum + 1 && (currentSeasonNum == null || it.season == currentSeasonNum) }
                ?: episodes.find { it.season == (currentSeasonNum ?: 1) + 1 && (it.episode == 1 || it.episode == 0) }
        } else {
            null
        }
    }

    fun findPrevEpisode(
        episodes: List<Episode>,
        currentEpId: String?,
        currentEpNum: Int?,
        currentSeasonNum: Int?,
    ): Episode? {
        var currentIndex = episodes.indexOfFirst { it.data == currentEpId }

        if (currentIndex == -1 && currentEpNum != null) {
            currentIndex = episodes.indexOfFirst { it.episode == currentEpNum && (currentSeasonNum == null || it.season == currentSeasonNum) }
        }

        return if (currentIndex > 0) episodes[currentIndex - 1] else null
    }

    fun checkEpisodeLock(
        episode: Episode,
        resolvedApiName: String,
        lockUnreleased: Boolean,
        hasCachedLinks: Boolean,
    ): EpisodeLockStatus {
        val hasPlayableData = episode.data.isNotBlank() &&
            !episode.data.startsWith("unreleased_") &&
            !episode.data.startsWith("synthetic_") &&
            episode.data != "null" && episode.data != "{}" && episode.data != "[]"

        val status = parseEpisodeReleaseStatus(episode, resolvedApiName)
        val isLocked = !hasCachedLinks && (
            (status.isUnreleased && lockUnreleased) || status.isMissingFromProvider || !hasPlayableData
        )

        return if (isLocked) {
            val toast = status.statusBadgeText ?: (if (status.isMissingFromProvider) "Episode is not available on $resolvedApiName." else "This episode is unreleased.")
            EpisodeLockStatus(isLocked = true, message = toast)
        } else {
            EpisodeLockStatus(isLocked = false, message = null)
        }
    }

    suspend fun resolveTorboxStream(
        apiKey: String,
        episode: Episode,
        currentData: VideoLaunchData,
    ): TorboxResolvedEpisode? {
        if (apiKey.isBlank()) return null

        val fileId = episode.data.toIntOrNull()
        val parentId = currentData.history.parentId.orEmpty()
        val parentParts = parentId.split("_")
        val itemId = parentParts.getOrNull(2)?.toIntOrNull()
        val item = if (itemId != null) TorboxLibraryCache.findItem(itemId) else null

        val cachedLinks = LinkCache.get(episode.data)
        if (cachedLinks != null && cachedLinks.links.isNotEmpty()) {
            val file = item?.files?.find { it.id == fileId }
            val videoTitle = file?.name?.takeIf { it.isNotBlank() } ?: episode.name ?: "Episode ${episode.episode ?: 1}"
            return TorboxResolvedEpisode(link = cachedLinks.links.first(), title = videoTitle)
        }

        val streamUrl = if (item != null) {
            TorboxApiClient.requestDownloadLink(apiKey, item, fileId)
        } else if (itemId != null) {
            TorboxApiClient.requestDownloadLink(apiKey, itemId, fileId)
        } else {
            null
        }

        if (!streamUrl.isNullOrBlank()) {
            val file = item?.files?.find { it.id == fileId }
            val videoTitle = file?.name?.takeIf { it.isNotBlank() } ?: episode.name ?: "Episode ${episode.episode ?: 1}"
            val effectiveBytes = file?.size ?: 0L
            val sizeStr = if (effectiveBytes > 0L) {
                val gb = effectiveBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                if (gb >= 1.0) {
                    String.format(Locale.US, "%.1f GB", gb)
                } else {
                    String.format(Locale.US, "%.0f MB", effectiveBytes.toDouble() / (1024.0 * 1024.0))
                }
            } else {
                null
            }
            val sizeTag = if (sizeStr != null) " [$sizeStr]" else ""
            val extLink = newExtractorLink(
                source = "Torbox ${item?.storageType?.label ?: "Cloud"}",
                name = videoTitle + sizeTag,
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = ""
            }
            LinkCache.set(episode.data, listOf(extLink), emptyList())
            return TorboxResolvedEpisode(link = extLink, title = videoTitle)
        }
        return null
    }
}
