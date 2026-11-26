package com.lagradost.cloudstream3.desktop.domain.tracking.repository

import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncAuditEntry
import com.lagradost.cloudstream3.syncproviders.AuthData
import kotlinx.coroutines.flow.StateFlow

interface TrackingRepository {
    val accounts: StateFlow<Map<String, Array<AuthData>>>
    val bindingsVersion: StateFlow<Long>
    val auditVersion: StateFlow<Long>
    val settingsVersion: StateFlow<Long>

    fun getAccountsForProvider(idPrefix: String): Array<AuthData>?
    fun updateAccounts(idPrefix: String, accounts: Array<AuthData>)

    fun isAutoSyncEnabled(trackerId: String = TrackerIds.ANILIST): Boolean
    suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String = TrackerIds.ANILIST)

    fun getBindPolicy(trackerId: String = TrackerIds.ANILIST): TrackerBindPolicy
    suspend fun setBindPolicy(policy: TrackerBindPolicy, trackerId: String = TrackerIds.ANILIST)

    fun isConfirmBeforeSync(trackerId: String = TrackerIds.ANILIST): Boolean
    suspend fun setConfirmBeforeSync(enabled: Boolean, trackerId: String = TrackerIds.ANILIST)

    fun getAutoSyncThresholdPercent(): Int = 80
    suspend fun setAutoSyncThresholdPercent(percent: Int) {}

    suspend fun getBinding(
        showKey: String,
        mappedSeason: Int,
        trackerId: String = TrackerIds.ANILIST,
    ): TrackerBinding?

    suspend fun getBindingsForShow(
        showKey: String,
        trackerId: String = TrackerIds.ANILIST,
    ): List<TrackerBinding>

    suspend fun getAllBindingsForProfile(
        trackerId: String = TrackerIds.ANILIST,
    ): List<TrackerBinding>

    suspend fun saveBinding(binding: TrackerBinding)

    suspend fun deleteBinding(
        showKey: String,
        mappedSeason: Int,
        trackerId: String = TrackerIds.ANILIST,
    )

    suspend fun recordAudit(
        profileId: Long,
        trackerId: String,
        showName: String,
        remoteMediaId: String,
        episodeNum: Long,
        effectiveRemoteEpisode: Long,
        oldProgress: Long,
        newProgress: Long,
        statusApplied: String?,
        state: SyncState,
        reason: String?,
        timestamp: Long = System.currentTimeMillis(),
    ): Long

    suspend fun updateAuditState(rowId: Long, state: SyncState, reason: String? = null)

    suspend fun getAuditLog(trackerId: String = TrackerIds.ANILIST): List<TrackerSyncAuditEntry>
}
