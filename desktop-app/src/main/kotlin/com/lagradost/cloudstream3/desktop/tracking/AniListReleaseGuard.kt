package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListTitleHolder
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonMedia
import com.lagradost.cloudstream3.syncproviders.providers.anilist.SeasonNextAiringEpisode

data class ReleaseBounds(
    val mediaStatus: String? = null,
    val format: String? = null,
    val totalEpisodes: Int? = null,
    val nextAiringEpisode: Int? = null,
    val nextAiringTimeUntilSec: Int? = null,
    val isNotYetReleased: Boolean = false,
    val isAiringOrHiatus: Boolean = false,
    val canMarkCompleted: Boolean = true,
    val maxAiredEpisode: Int? = null,
    val ceilingEpisode: Int? = null,
) {
    val statusBadgeText: String?
        get() = when {
            isNotYetReleased -> "Not Yet Released"
            mediaStatus == "HIATUS" -> if (maxAiredEpisode != null && maxAiredEpisode > 0) {
                "Hiatus · Ep 1-$maxAiredEpisode aired"
            } else {
                "Hiatus"
            }
            mediaStatus == "RELEASING" -> when {
                maxAiredEpisode != null && nextAiringEpisode != null ->
                    "Airing · $maxAiredEpisode aired (next Ep $nextAiringEpisode)"
                maxAiredEpisode != null ->
                    "Airing · $maxAiredEpisode aired"
                else -> "Airing"
            }
            mediaStatus == "FINISHED" && totalEpisodes != null && totalEpisodes > 0 ->
                "Finished · $totalEpisodes eps"
            mediaStatus == "CANCELLED" -> "Cancelled"
            else -> null
        }
}

sealed interface GuardDecision {
    data object Allowed : GuardDecision
    data class Blocked(val reason: String) : GuardDecision
}

sealed interface EpisodeSyncCheck {
    data class Allowed(val effectiveEpisode: Int) : EpisodeSyncCheck
    data class Blocked(val reason: String) : EpisodeSyncCheck
}

object AniListReleaseGuard {
    fun evaluate(holder: AniListTitleHolder?, fallbackTotalEpisodes: Int? = null): ReleaseBounds =
        evaluate(
            mediaStatus = holder?.mediaStatus,
            format = holder?.format,
            totalEpisodes = holder?.episodes ?: fallbackTotalEpisodes,
            nextAiring = holder?.nextAiringEpisode,
        )

    fun evaluate(season: SeasonMedia?, fallbackTotalEpisodes: Int? = null): ReleaseBounds =
        evaluate(
            mediaStatus = season?.status,
            format = season?.format,
            totalEpisodes = season?.episodes ?: fallbackTotalEpisodes,
            nextAiring = season?.nextAiringEpisode,
        )

    fun evaluate(
        mediaStatus: String?,
        format: String?,
        totalEpisodes: Int?,
        nextAiring: SeasonNextAiringEpisode?,
    ): ReleaseBounds {
        val normalizedStatus = mediaStatus?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val normalizedFormat = format?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val isNotYetReleased = normalizedStatus == "NOT_YET_RELEASED"
        val isAiringOrHiatus = normalizedStatus == "RELEASING" || normalizedStatus == "HIATUS"
        val canMarkCompleted = !isNotYetReleased && !isAiringOrHiatus
        val knownTotal = totalEpisodes?.takeIf { it > 0 }
        val nextEpNum = nextAiring?.episode?.takeIf { it > 0 }
        val timeUntil = nextAiring?.timeUntilAiring

        val maxAiredEpisode: Int? = when {
            isNotYetReleased -> 0
            isAiringOrHiatus && nextEpNum != null -> {
                if (timeUntil != null && timeUntil <= 0) {
                    nextEpNum
                } else {
                    (nextEpNum - 1).coerceAtLeast(0)
                }
            }
            isAiringOrHiatus -> knownTotal
            normalizedFormat == "MOVIE" -> 1
            else -> knownTotal
        }

        val ceilingEpisode: Int? = when {
            isNotYetReleased -> 0
            normalizedFormat == "MOVIE" -> 1
            else -> listOfNotNull(maxAiredEpisode, knownTotal).minOrNull()
        }

        return ReleaseBounds(
            mediaStatus = normalizedStatus,
            format = normalizedFormat,
            totalEpisodes = knownTotal,
            nextAiringEpisode = nextEpNum,
            nextAiringTimeUntilSec = timeUntil,
            isNotYetReleased = isNotYetReleased,
            isAiringOrHiatus = isAiringOrHiatus,
            canMarkCompleted = canMarkCompleted,
            maxAiredEpisode = maxAiredEpisode,
            ceilingEpisode = ceilingEpisode,
        )
    }

    fun validateStatusAndProgress(
        bounds: ReleaseBounds,
        requestedStatus: AniListStatusType,
        requestedProgress: Int?,
    ): GuardDecision {
        if (requestedStatus == AniListStatusType.None) {
            return GuardDecision.Allowed
        }
        if (requestedStatus == AniListStatusType.Completed && !bounds.canMarkCompleted) {
            val stateLabel = when (bounds.mediaStatus) {
                "NOT_YET_RELEASED" -> "not yet released"
                "HIATUS" -> "on hiatus"
                else -> "still airing"
            }
            return GuardDecision.Blocked("Cannot mark a title as Completed while it is $stateLabel.")
        }

        val progress = requestedProgress ?: return GuardDecision.Allowed
        if (progress < 0) {
            return GuardDecision.Blocked("Progress cannot be negative.")
        }
        if (progress > 0 && bounds.isNotYetReleased) {
            return GuardDecision.Blocked("Cannot track watched episodes for a title that has not released yet.")
        }
        if (bounds.format == "MOVIE" && progress > 1) {
            return GuardDecision.Blocked("Movie progress cannot exceed 1.")
        }
        val ceiling = bounds.ceilingEpisode
        if (progress > 0 && ceiling != null && progress > ceiling) {
            return GuardDecision.Blocked("Episode $progress has not released yet (max available: Ep $ceiling).")
        }
        return GuardDecision.Allowed
    }

    fun canSyncEpisode(
        bounds: ReleaseBounds,
        rawEpisodeNumber: Int?,
        episodeOffset: Int = 0,
        localReleaseBlocked: Boolean = false,
    ): EpisodeSyncCheck {
        if (localReleaseBlocked) {
            return EpisodeSyncCheck.Blocked("Episode is not released yet.")
        }
        if (rawEpisodeNumber == null || rawEpisodeNumber <= 0) {
            return EpisodeSyncCheck.Blocked("Special / Ep 0 entries cannot be synced.")
        }
        val effectiveEp = rawEpisodeNumber + episodeOffset
        if (effectiveEp <= 0) {
            return EpisodeSyncCheck.Blocked("Mapped episode ($effectiveEp) is below 1.")
        }
        if (bounds.format == "MOVIE" && effectiveEp != 1) {
            return EpisodeSyncCheck.Blocked("Movie entries only allow episode 1.")
        }
        return when (val decision = validateStatusAndProgress(bounds, AniListStatusType.Watching, effectiveEp)) {
            is GuardDecision.Allowed -> EpisodeSyncCheck.Allowed(effectiveEp)
            is GuardDecision.Blocked -> EpisodeSyncCheck.Blocked(decision.reason)
        }
    }

    fun nextStatusForManualEpisodeSync(
        bounds: ReleaseBounds,
        currentStatus: AniListStatusType,
        effectiveEpisode: Int,
    ): AniListStatusType {
        val total = bounds.totalEpisodes
        val reachedFinishedFinale = total != null &&
            total > 0 &&
            effectiveEpisode >= total &&
            bounds.canMarkCompleted &&
            bounds.mediaStatus == "FINISHED"
        return when {
            reachedFinishedFinale && currentStatus != AniListStatusType.ReWatching -> AniListStatusType.Completed
            currentStatus == AniListStatusType.ReWatching -> AniListStatusType.ReWatching
            currentStatus == AniListStatusType.Completed -> AniListStatusType.ReWatching
            else -> AniListStatusType.Watching
        }
    }
}
