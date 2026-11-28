package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.tracking.EpisodeSyncCheck
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackersUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler

@Immutable
data class EpisodeTrackerBadgeState(
    val providerId: String,
    val shortTag: String,
    val accentColorHex: Long,
    val syncedColorHex: Long,
    val effectiveEpisode: Int,
    val isSynced: Boolean,
    val isSyncing: Boolean,
)

@Composable
fun rememberEpisodeTrackerBadges(
    ep: Episode,
    releaseStatus: EpisodeReleaseStatus,
    trackersState: DetailsTrackersUiState?,
): List<EpisodeTrackerBadgeState> {
    return remember(
        ep.episode,
        releaseStatus.isUnreleased,
        releaseStatus.isMissingFromProvider,
        trackersState?.entries,
    ) {
        if (trackersState == null) return@remember emptyList()
        trackersState.entries.values.mapNotNull { entry ->
            if (!entry.isConnected || entry.boundEntry == null) return@mapNotNull null
            if (!entry.descriptor.supportsPerEpisodeSync) return@mapNotNull null
            val provider = TrackerRegistry.get(entry.descriptor.id) ?: return@mapNotNull null
            val check = provider.canSyncEpisode(
                bounds = entry.releaseBounds,
                rawEpisodeNumber = ep.episode,
                episodeOffset = entry.editOffset,
                localReleaseBlocked = releaseStatus.isUnreleased || releaseStatus.isMissingFromProvider,
            )
            when (check) {
                is EpisodeSyncCheck.Blocked -> null
                is EpisodeSyncCheck.Allowed -> {
                    val remoteProg = entry.remoteStatus?.progress ?: 0
                    EpisodeTrackerBadgeState(
                        providerId = entry.descriptor.id,
                        shortTag = entry.descriptor.shortTag,
                        accentColorHex = entry.descriptor.accentColorHex,
                        syncedColorHex = entry.descriptor.syncedColorHex,
                        effectiveEpisode = check.effectiveEpisode,
                        isSynced = check.effectiveEpisode <= remoteProg,
                        isSyncing = entry.syncingEpisodeNum == ep.episode,
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeTrackerSyncPillsRow(
    badges: List<EpisodeTrackerBadgeState>,
    isCompact: Boolean = false,
    compactLabels: Boolean = false,
    onSyncClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return
    val logoHeight = if (isCompact) 11.dp else 13.dp
    val hPad = if (isCompact) 5.5.dp else 6.5.dp
    val vPad = if (isCompact) 2.5.dp else 3.dp
    val pillShape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        badges.forEach { badge ->
            val syncedColor = Color(badge.syncedColorHex)
            val pendingColor = Color(badge.accentColorHex)
            val glassBg = Color.Black.copy(alpha = 0.74f)

            if (badge.isSynced || onSyncClick == null) {
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(glassBg)
                        .border(0.6.dp, syncedColor.copy(alpha = 0.45f), pillShape)
                        .padding(horizontal = hPad, vertical = vPad),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DesktopBadgeComponents.TrackerBrandIcon(
                            trackerId = badge.providerId,
                            size = logoHeight,
                        )
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Synced to ${badge.shortTag}",
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(if (isCompact) 10.dp else 11.5.dp),
                        )
                    }
                }
            } else {
                val pillInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(glassBg)
                        .border(0.6.dp, pendingColor.copy(alpha = 0.65f), pillShape)
                        .then(
                            if (!badge.isSyncing) {
                                Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .desktopInteractiveFocus(interactionSource = pillInteraction, shape = pillShape)
                                    .hoverable(pillInteraction)
                                    .clickable(interactionSource = pillInteraction, indication = null) {
                                        onSyncClick(badge.providerId)
                                    }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = hPad, vertical = vPad),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                    ) {
                        if (badge.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(if (isCompact) 10.dp else 11.dp),
                                strokeWidth = 1.6.dp,
                                color = pendingColor,
                            )
                        } else {
                            DesktopBadgeComponents.TrackerBrandIcon(
                                trackerId = badge.providerId,
                                size = logoHeight,
                            )
                        }
                        val label = when {
                            badge.isSyncing && compactLabels -> "..."
                            badge.isSyncing -> "Syncing..."
                            compactLabels -> "+E${badge.effectiveEpisode}"
                            else -> "Track E${badge.effectiveEpisode}"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (isCompact) 9.5.sp else 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White.copy(alpha = 0.94f),
                        )
                    }
                }
            }
        }
    }
}

data class EpisodePresentation(
    val releaseStatus: EpisodeReleaseStatus,
    val isEpisodeLocked: Boolean,
    val targetUrl: String?,
    val fallbackUrl: String?,
    val progress: Float,
    val isWatched: Boolean,
    val hasStartedPlayback: Boolean,
    val shouldHideSpoilers: Boolean,
    val finalTitle: String,
    val runTimeStr: String?,
    val cleanDesc: String,
    val hasDesc: Boolean,
    val formattedDate: String?,
    val durationText: String?,
)

object EpisodePresentationHelper {
    fun compute(
        ep: Episode,
        history: WatchHistory?,
        provider: MainAPI,
        data: LoadResponse,
        uiState: DetailsUiState?,
        isAntiSpoiler: Boolean,
        lockUnreleasedEpisodes: Boolean,
    ): EpisodePresentation {
        val releaseStatus = parseEpisodeReleaseStatus(ep, provider.name)
        val hasCachedLinks = com.lagradost.cloudstream3.desktop.ui.screens.player.LinkCache.get(ep.data) != null
        val isEpisodeLocked = !hasCachedLinks && ((releaseStatus.isUnreleased && lockUnreleasedEpisodes) || releaseStatus.isMissingFromProvider)

        val epImg = (provider.fixUrlNull(ep.posterUrl) ?: ep.posterUrl)
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.isNotBlank() && !it.contains("imgbb") }

        val screenshots = uiState?.screenshots?.filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.map { it.replace("original//", "original/").replace("w1280//", "w1280/") }.orEmpty()
        val fallbackScreenshot = if (screenshots.isNotEmpty()) {
            val idx = ((ep.episode ?: 1) - 1).coerceAtLeast(0) % screenshots.size
            val s = screenshots[idx]
            if (s.startsWith("//")) "https:$s" else s
        } else {
            null
        }

        val fallbackBackdrop = (uiState?.enrichedBackdropUrl ?: provider.fixUrlNull(data.backgroundPosterUrl) ?: data.backgroundPosterUrl)
            ?.replace("original//", "original/")?.replace("w1280//", "w1280/")
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.isNotBlank() }

        val fallbackPoster = (provider.fixUrlNull(data.posterUrl) ?: data.posterUrl)
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.isNotBlank() && !it.contains("imgbb") }

        val targetUrl = epImg ?: fallbackScreenshot ?: fallbackBackdrop ?: fallbackPoster
        val fallbackUrl = if (epImg != null) (fallbackScreenshot ?: fallbackBackdrop ?: fallbackPoster) else if (fallbackScreenshot != null) (fallbackBackdrop ?: fallbackPoster) else fallbackPoster

        val progress = if (history != null && history.duration > 0) {
            if (PlayerLinkHandler.isCompleted(history.position, history.duration)) {
                1f
            } else {
                (history.position.toFloat() / history.duration.toFloat()).coerceIn(0f, 1f)
            }
        } else {
            0f
        }

        val isWatched = progress >= com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory.WATCHED_THRESHOLD
        val hasStartedPlayback = progress > 0f || (history != null && history.position > 5)
        val shouldHideSpoilers = isAntiSpoiler && !hasStartedPlayback && !isWatched

        val rawTitle = ep.name ?: "Episode ${ep.episode ?: "?"}"
        val titleCleaned = rawTitle
            .replace(EPISODE_E_PREFIX_REGEX, "")
            .replace(EPISODE_WORD_PREFIX_REGEX, "")
            .trim()
        val finalTitle = if (titleCleaned.isBlank()) "Episode ${ep.episode ?: "?"}" else titleCleaned

        val epRunTime = ep.runTime ?: data.duration
        val runTimeStr = epRunTime?.let { dur ->
            val mins = if (dur > 1000) dur / 60 else dur
            if (mins >= 60) {
                val h = mins / 60
                val m = mins % 60
                if (m > 0) "${h}h ${m}m" else "${h}h"
            } else {
                "${mins}m"
            }
        }

        val rawDesc = ep.description ?: ""
        val formattedDate = releaseStatus.formattedDate
        val cleanDesc = TitleUtils.cleanHtml(rawDesc.replace(EPISODE_DATE_REGEX, "").trim()) ?: ""
        val hasDesc = cleanDesc.isNotBlank()

        val durationText = if (history != null && history.duration > 0) {
            if (progress > 0f && progress < 1f) {
                val leftSeconds = history.duration - history.position
                val leftMins = leftSeconds / 60L
                if (leftMins >= 60) {
                    "${leftMins / 60}h ${leftMins % 60}m left"
                } else if (leftMins > 0) {
                    "${leftMins}m left"
                } else {
                    "<1m left"
                }
            } else {
                val totalMins = history.duration / 60L
                if (totalMins >= 60) "${totalMins / 60}h ${totalMins % 60}m" else "${totalMins}m"
            }
        } else if (epRunTime != null) {
            val totalMins = if (epRunTime > 1000) epRunTime / 60 else epRunTime
            if (totalMins >= 60) "${totalMins / 60}h ${totalMins % 60}m" else "${totalMins}m"
        } else {
            null
        }

        return EpisodePresentation(
            releaseStatus = releaseStatus,
            isEpisodeLocked = isEpisodeLocked,
            targetUrl = targetUrl,
            fallbackUrl = fallbackUrl,
            progress = progress,
            isWatched = isWatched,
            hasStartedPlayback = hasStartedPlayback,
            shouldHideSpoilers = shouldHideSpoilers,
            finalTitle = finalTitle,
            runTimeStr = runTimeStr,
            cleanDesc = cleanDesc,
            hasDesc = hasDesc,
            formattedDate = formattedDate,
            durationText = durationText,
        )
    }
}

@Composable
fun rememberEpisodePresentation(
    ep: Episode,
    history: WatchHistory?,
    provider: MainAPI,
    data: LoadResponse,
    uiState: DetailsUiState?,
    isAntiSpoiler: Boolean,
    lockUnreleasedEpisodes: Boolean,
    thumbnailVersion: Int = 0,
): EpisodePresentation {
    return remember(ep, history, data, uiState?.enrichedBackdropUrl, uiState?.screenshots, uiState?.isEnriching, isAntiSpoiler, lockUnreleasedEpisodes, thumbnailVersion) {
        EpisodePresentationHelper.compute(
            ep = ep,
            history = history,
            provider = provider,
            data = data,
            uiState = uiState,
            isAntiSpoiler = isAntiSpoiler,
            lockUnreleasedEpisodes = lockUnreleasedEpisodes,
        )
    }
}

fun Episode.matchesHistory(history: WatchHistory?): Boolean {
    if (history == null) return false
    if (history.episodeId == this.data) return true
    if (this.season != null && this.episode != null && history.season == this.season && history.episode == this.episode) return true
    if (this.episode != null && history.episode != null && this.episode == history.episode && (this.season ?: 1) == (history.season ?: 1)) return true
    return false
}

class EpisodeHistoryLookup(historyMap: Map<String, WatchHistory>) {
    private val byData = historyMap
    private val bySeasonEp: Map<Pair<Int, Int>, WatchHistory> = historyMap.values
        .mapNotNull { hist -> hist.episode?.let { ep -> Pair(Pair(hist.season ?: 1, ep), hist) } }
        .toMap()

    fun find(ep: Episode): WatchHistory? {
        return byData[ep.data] ?: ep.episode?.let { epNum ->
            bySeasonEp[Pair(ep.season ?: 1, epNum)]
        }
    }
}
