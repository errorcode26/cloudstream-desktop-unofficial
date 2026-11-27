package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All SQLite access for tracker tables runs here — dispatched on IO, serialized via withDbLock.
 * This object is the single gateway; no other layer touches TrackerBindings or TrackerSyncAudit directly.
 */
object DesktopTrackerStore {

    // ── Tracker settings keys (read/written via DesktopDataStore profile-scoped helpers) ──────

    /** Auto-sync enabled per tracker. Default false (explicit opt-in per tracker). */
    const val KEY_TRACKER_ANILIST_AUTO_SYNC = "tracker_anilist_auto_sync"

    /**
     * Bind policy: controls how media entries match for automatic binding.
     * Values: "STRICT_AUTO_AND_PINNED" (default), "EXPLORE_OR_PINNED_ONLY", "MANUAL_PIN_ONLY"
     */
    const val KEY_TRACKER_ANILIST_BIND_POLICY = "tracker_anilist_bind_policy"

    /** Whether to show an 8-second grace/cancel toast before writing to remote. Default true. */
    const val KEY_TRACKER_ANILIST_CONFIRM_BEFORE_SYNC = "tracker_anilist_confirm_before_sync"

    /** Playback completion percentage threshold (80, 85, or 90) before auto-tracking triggers. */
    const val KEY_TRACKER_AUTO_SYNC_THRESHOLD = "tracker_auto_sync_threshold"

    /** Optional user-supplied AniList OAuth Client ID (global setting). */
    const val KEY_TRACKER_ANILIST_CUSTOM_CLIENT_ID = "tracker_anilist_custom_client_id"

    // ── TrackerBindings ───────────────────────────────────────────────────────────────────────

    suspend fun upsertBinding(
        profileId: Long,
        trackerId: String,
        showKey: String,
        mappedSeason: Long,
        remoteMediaId: String,
        remoteTitle: String,
        bindMode: String,
        episodeOffset: Long,
        expectedTotalEpisodes: Long?,
        updatedAt: Long,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries.upsertTrackerBinding(
                    profileId = profileId,
                    trackerId = trackerId,
                    showKey = showKey,
                    mappedSeason = mappedSeason,
                    remoteMediaId = remoteMediaId,
                    remoteTitle = remoteTitle,
                    bindMode = bindMode,
                    episodeOffset = episodeOffset,
                    expectedTotalEpisodes = expectedTotalEpisodes,
                    updatedAt = updatedAt,
                )
            }.onFailure {
                AppLogger.e("DesktopTrackerStore: upsertBinding failed for $showKey/$mappedSeason", it)
            }
        }
    }

    suspend fun getBinding(
        profileId: Long,
        trackerId: String,
        showKey: String,
        mappedSeason: Long,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectTrackerBinding(profileId, trackerId, showKey, mappedSeason)
                    .executeAsOneOrNull()
            }.getOrNull()
        }
    }

    suspend fun getBindingsForShow(
        profileId: Long,
        trackerId: String,
        showKey: String,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectTrackerBindingsForShow(profileId, trackerId, showKey)
                    .executeAsList()
            }.getOrElse { emptyList() }
        }
    }

    suspend fun getAllBindingsForProfile(
        profileId: Long,
        trackerId: String,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectAllTrackerBindingsForProfile(profileId, trackerId)
                    .executeAsList()
            }.getOrElse { emptyList() }
        }
    }

    suspend fun deleteBinding(
        profileId: Long,
        trackerId: String,
        showKey: String,
        mappedSeason: Long,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .deleteTrackerBinding(profileId, trackerId, showKey, mappedSeason)
            }.onFailure {
                AppLogger.e("DesktopTrackerStore: deleteBinding failed for $showKey/$mappedSeason", it)
            }
        }
    }

    suspend fun deleteAllBindingsForProfile(profileId: Long) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .deleteTrackerBindingsForProfile(profileId)
            }.onFailure {
                AppLogger.e("DesktopTrackerStore: deleteAllBindingsForProfile failed for profile $profileId", it)
            }
        }
    }

    // ── TrackerSyncAudit ──────────────────────────────────────────────────────────────────────

    /**
     * Inserts a new audit row and returns its auto-generated row id, or -1 on failure.
     * The returned id is used by [updateAuditState] to transition PENDING → final state.
     */
    suspend fun insertAuditRow(
        profileId: Long,
        trackerId: String,
        showName: String,
        remoteMediaId: String,
        episodeNum: Long,
        effectiveRemoteEpisode: Long,
        oldProgress: Long,
        newProgress: Long,
        statusApplied: String?,
        state: String,
        reason: String?,
        timestamp: Long,
    ): Long = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                val queries = DatabaseFactory.database.cloudstreamDBQueries
                queries.transactionWithResult<Long> {
                    queries.insertTrackerSyncAudit(
                        profileId = profileId,
                        trackerId = trackerId,
                        showName = showName,
                        remoteMediaId = remoteMediaId,
                        episodeNum = episodeNum,
                        effectiveRemoteEpisode = effectiveRemoteEpisode,
                        oldProgress = oldProgress,
                        newProgress = newProgress,
                        statusApplied = statusApplied,
                        state = state,
                        reason = reason,
                        timestamp = timestamp,
                    )
                    queries.selectLastInsertRowId().executeAsOne()
                }
            }.getOrElse {
                AppLogger.e("DesktopTrackerStore: insertAuditRow failed", it)
                -1L
            }
        }
    }

    suspend fun updateAuditState(
        rowId: Long,
        state: String,
        reason: String?,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .updateTrackerSyncAuditState(state = state, reason = reason, id = rowId)
            }.onFailure {
                AppLogger.e("DesktopTrackerStore: updateAuditState failed for row $rowId", it)
            }
        }
    }

    suspend fun getAuditLog(
        profileId: Long,
        trackerId: String,
    ) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectTrackerSyncAudit(profileId, trackerId)
                    .executeAsList()
            }.getOrElse { emptyList() }
        }
    }

    suspend fun pruneAudit(trackerId: String) = withContext(Dispatchers.IO) {
        DesktopDataStore.withDbLock {
            runCatching {
                DatabaseFactory.database.cloudstreamDBQueries
                    .pruneTrackerSyncAudit(trackerId, trackerId)
            }.onFailure {
                AppLogger.w("DesktopTrackerStore: pruneAudit failed for $trackerId: ${it.message}")
            }
        }
    }
}
