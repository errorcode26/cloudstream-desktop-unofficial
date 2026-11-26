package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.metadata.CanonicalMediaSeed
import com.lagradost.common.storage.DesktopBookmarkStore

fun buildTrackerShowKey(apiName: String, showUrl: String): String =
    DesktopBookmarkStore.bookmarkId(apiName, showUrl)

enum class TrackerBindPolicy(
    val displayName: String,
) {
    STRICT_AUTO_AND_PINNED(displayName = "Automatic (Auto-Match & Pinned)"),
    EXPLORE_OR_PINNED_ONLY(displayName = "Verified IDs Only"),
    MANUAL_PIN_ONLY(displayName = "Manual Pin Only");

    companion object {
        fun fromString(value: String?): TrackerBindPolicy = when (value) {
            STRICT_AUTO_AND_PINNED.name -> STRICT_AUTO_AND_PINNED
            EXPLORE_OR_PINNED_ONLY.name -> EXPLORE_OR_PINNED_ONLY
            MANUAL_PIN_ONLY.name -> MANUAL_PIN_ONLY
            else -> STRICT_AUTO_AND_PINNED
        }
    }
}

enum class BindMode {
    /** Resolved automatically and verified against canonical seed or strict matcher. */
    AUTO_VERIFIED,

    /** User explicitly selected or confirmed this binding. Treated as ground truth. */
    USER_PINNED,

    /** User blocked tracking for this entry. Engine writes nothing and skips silently. */
    BLOCKED;

    companion object {
        fun fromString(value: String): BindMode =
            entries.firstOrNull { it.name == value } ?: AUTO_VERIFIED
    }
}

enum class SyncState {
    PENDING,
    SENT,
    CANCELLED,
    FAILED,

    /** Hard-blocked by engine rules (guard failed, ep overflow, etc.). */
    BLOCKED;

    companion object {
        fun fromString(value: String): SyncState =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}

data class TrackerBinding(
    val profileId: Long,
    val trackerId: String,
    val showKey: String,
    val mappedSeason: Long,
    val remoteMediaId: String,
    val remoteTitle: String,
    val bindMode: BindMode,
    val episodeOffset: Long,
    val expectedTotalEpisodes: Long?,
    val updatedAt: Long,
)

data class TrackerSyncAuditEntry(
    val id: Long,
    val profileId: Long,
    val trackerId: String,
    val showName: String,
    val remoteMediaId: String,
    val episodeNum: Long,
    val effectiveRemoteEpisode: Long,
    val oldProgress: Long,
    val newProgress: Long,
    val statusApplied: String?,
    val state: SyncState,
    val reason: String?,
    val timestamp: Long,
)

data class AniListCandidate(
    val anilistId: Int,
    val malId: Int? = null,
    val title: String,
    val format: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val posterUrl: String? = null,
    val matchScore: Double? = null,
)

data class MatchRequest(
    val profileId: Long,
    val showKey: String,
    val showTitle: String,
    val mappedSeason: Int = 1,
    val year: Int? = null,
    val tvType: TvType = TvType.Anime,
    val canonicalSeed: CanonicalMediaSeed? = null,
    val resolvedAnilistId: Int? = null,
    val resolvedMalId: Int? = null,
    val resolvedTmdbId: Int? = null,
    val resolvedImdbId: String? = null,
    val bindPolicy: TrackerBindPolicy = TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY,
    val hasMultipleSeasons: Boolean = false,
)

sealed interface MatchResolution {
    data class Bound(val binding: TrackerBinding) : MatchResolution
    data object BlockedByUser : MatchResolution
    data class NeedsConfirmation(
        val reason: String,
        val candidates: List<AniListCandidate> = emptyList(),
        val mediaCandidates: List<TrackerMediaCandidate> = emptyList(),
    ) : MatchResolution
    data class Skipped(val reason: String) : MatchResolution
}
