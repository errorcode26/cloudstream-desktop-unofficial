package com.lagradost.cloudstream3.desktop.data.tracking

import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncAuditEntry
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopTrackerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class TrackingRepositoryImpl : TrackingRepository {
    override val accounts: StateFlow<Map<String, Array<AuthData>>>
        get() = AccountManager.accountsFlow

    private val _bindingsVersion = MutableStateFlow(0L)
    override val bindingsVersion: StateFlow<Long> = _bindingsVersion.asStateFlow()

    private val _auditVersion = MutableStateFlow(0L)
    override val auditVersion: StateFlow<Long> = _auditVersion.asStateFlow()

    private val _settingsVersion = MutableStateFlow(0L)
    override val settingsVersion: StateFlow<Long> = _settingsVersion.asStateFlow()

    override fun getAccountsForProvider(idPrefix: String): Array<AuthData>? {
        return AccountManager.cachedAccounts[idPrefix] ?: AccountManager.accounts(idPrefix)
    }

    override fun updateAccounts(idPrefix: String, accounts: Array<AuthData>) {
        AccountManager.updateAccounts(idPrefix, accounts)
    }

    override fun isAutoSyncEnabled(trackerId: String): Boolean {
        val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC)
        return DesktopDataStore.getProfileKey<Boolean>(key) ?: false
    }

    override suspend fun setAutoSyncEnabled(enabled: Boolean, trackerId: String) {
        withContext(Dispatchers.IO) {
            val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_AUTO_SYNC)
            DesktopDataStore.setProfileKey(key, enabled)
            _settingsVersion.update { it + 1L }
        }
    }

    override fun getBindPolicy(trackerId: String): TrackerBindPolicy {
        val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_BIND_POLICY)
        return TrackerBindPolicy.fromString(DesktopDataStore.getProfileKey<String>(key))
    }

    override suspend fun setBindPolicy(policy: TrackerBindPolicy, trackerId: String) {
        withContext(Dispatchers.IO) {
            val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_BIND_POLICY)
            DesktopDataStore.setProfileKey(key, policy.name)
            _settingsVersion.update { it + 1L }
        }
    }

    override fun isConfirmBeforeSync(trackerId: String): Boolean {
        val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_CONFIRM_BEFORE_SYNC)
        return DesktopDataStore.getProfileKey<Boolean>(key) ?: true
    }

    override suspend fun setConfirmBeforeSync(enabled: Boolean, trackerId: String) {
        withContext(Dispatchers.IO) {
            val key = settingsKey(trackerId, DesktopTrackerStore.KEY_TRACKER_ANILIST_CONFIRM_BEFORE_SYNC)
            DesktopDataStore.setProfileKey(key, enabled)
            _settingsVersion.update { it + 1L }
        }
    }

    override fun getAutoSyncThresholdPercent(): Int {
        val stored = DesktopDataStore.getProfileKey<Int>(DesktopTrackerStore.KEY_TRACKER_AUTO_SYNC_THRESHOLD)
        return when (stored) {
            80, 85, 90 -> stored
            else -> 80
        }
    }

    override suspend fun setAutoSyncThresholdPercent(percent: Int) {
        withContext(Dispatchers.IO) {
            val sanitized = when {
                percent <= 82 -> 80
                percent <= 87 -> 85
                else -> 90
            }
            DesktopDataStore.setProfileKey(DesktopTrackerStore.KEY_TRACKER_AUTO_SYNC_THRESHOLD, sanitized)
            _settingsVersion.update { it + 1L }
        }
    }

    override suspend fun getBinding(
        showKey: String,
        mappedSeason: Int,
        trackerId: String,
    ): TrackerBinding? {
        val profileId = DesktopDataStore.activeProfileId.toLong()
        val row = DesktopTrackerStore.getBinding(profileId, trackerId, showKey, mappedSeason.toLong())
            ?: return null
        return row.toModel()
    }

    override suspend fun getBindingsForShow(
        showKey: String,
        trackerId: String,
    ): List<TrackerBinding> {
        val profileId = DesktopDataStore.activeProfileId.toLong()
        return DesktopTrackerStore.getBindingsForShow(profileId, trackerId, showKey).map { it.toModel() }
    }

    override suspend fun getAllBindingsForProfile(
        trackerId: String,
    ): List<TrackerBinding> {
        val profileId = DesktopDataStore.activeProfileId.toLong()
        return DesktopTrackerStore.getAllBindingsForProfile(profileId, trackerId).map { it.toModel() }
    }

    override suspend fun saveBinding(binding: TrackerBinding) {
        DesktopTrackerStore.upsertBinding(
            profileId = binding.profileId,
            trackerId = binding.trackerId,
            showKey = binding.showKey,
            mappedSeason = binding.mappedSeason,
            remoteMediaId = binding.remoteMediaId,
            remoteTitle = binding.remoteTitle,
            bindMode = binding.bindMode.name,
            episodeOffset = binding.episodeOffset,
            expectedTotalEpisodes = binding.expectedTotalEpisodes,
            updatedAt = binding.updatedAt,
        )
        _bindingsVersion.update { it + 1L }
    }

    override suspend fun deleteBinding(
        showKey: String,
        mappedSeason: Int,
        trackerId: String,
    ) {
        val profileId = DesktopDataStore.activeProfileId.toLong()
        DesktopTrackerStore.deleteBinding(profileId, trackerId, showKey, mappedSeason.toLong())
        _bindingsVersion.update { it + 1L }
    }

    override suspend fun recordAudit(
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
        timestamp: Long,
    ): Long {
        val id = DesktopTrackerStore.insertAuditRow(
            profileId = profileId,
            trackerId = trackerId,
            showName = showName,
            remoteMediaId = remoteMediaId,
            episodeNum = episodeNum,
            effectiveRemoteEpisode = effectiveRemoteEpisode,
            oldProgress = oldProgress,
            newProgress = newProgress,
            statusApplied = statusApplied,
            state = state.name,
            reason = reason,
            timestamp = timestamp,
        )
        DesktopTrackerStore.pruneAudit(trackerId)
        if (state != SyncState.PENDING) {
            _auditVersion.update { it + 1L }
        }
        return id
    }

    override suspend fun updateAuditState(rowId: Long, state: SyncState, reason: String?) {
        if (rowId <= 0L) return
        DesktopTrackerStore.updateAuditState(rowId, state.name, reason)
        if (state != SyncState.PENDING) {
            _auditVersion.update { it + 1L }
        }
    }

    override suspend fun getAuditLog(trackerId: String): List<TrackerSyncAuditEntry> {
        val profileId = DesktopDataStore.activeProfileId.toLong()
        return DesktopTrackerStore.getAuditLog(profileId, trackerId).map { row ->
            TrackerSyncAuditEntry(
                id = row.id,
                profileId = row.profileId,
                trackerId = row.trackerId,
                showName = row.showName,
                remoteMediaId = row.remoteMediaId,
                episodeNum = row.episodeNum,
                effectiveRemoteEpisode = row.effectiveRemoteEpisode,
                oldProgress = row.oldProgress,
                newProgress = row.newProgress,
                statusApplied = row.statusApplied,
                state = SyncState.fromString(row.state),
                reason = row.reason,
                timestamp = row.timestamp,
            )
        }
    }

    private fun settingsKey(trackerId: String, defaultAnilistKey: String): String =
        if (trackerId == TrackerIds.ANILIST) defaultAnilistKey else "tracker_${trackerId}_${defaultAnilistKey.removePrefix("tracker_anilist_")}"

    private fun com.lagradost.cloudstream3.desktop.db.TrackerBindings.toModel(): TrackerBinding =
        TrackerBinding(
            profileId = profileId,
            trackerId = trackerId,
            showKey = showKey,
            mappedSeason = mappedSeason,
            remoteMediaId = remoteMediaId,
            remoteTitle = remoteTitle,
            bindMode = BindMode.fromString(bindMode),
            episodeOffset = episodeOffset,
            expectedTotalEpisodes = expectedTotalEpisodes,
            updatedAt = updatedAt,
        )
}
