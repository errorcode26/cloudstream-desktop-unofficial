package com.lagradost.cloudstream3.desktop.ui.screens.library.contract

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream3.desktop.tracking.AniListOAuthFlow
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncAuditEntry
import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import com.lagradost.cloudstream3.syncproviders.AuthUser

enum class AniListWindowTab(val label: String) {
    ACCOUNT("Account & Sign-In"),
    HISTORY("Sync History"),
}

enum class AuditFilterOption(val label: String, val filterState: SyncState?) {
    ALL("All", null),
    SENT("Synced", SyncState.SENT),
    BLOCKED("Blocked", SyncState.BLOCKED),
    CANCELLED("Cancelled", SyncState.CANCELLED),
    FAILED("Failed", SyncState.FAILED),
}

@Immutable
data class LibraryTrackerUiState(
    val trackerId: String = TrackerIds.ANILIST,
    val trackerDisplayName: String = "AniList",
    val brandColorHex: Long = 0xFF02A9FFL,
    val developerPortalLabel: String = "AniList Developer Portal",
    val loopbackRedirectUri: String = AniListOAuthFlow.LOOPBACK_REDIRECT_URI,
    val missingKeyPropertyHint: String = "anilist.key",
    val supportsDevicePin: Boolean = false,
    val supportsPinFallbackAuth: Boolean = false,
    val supportsClientSecret: Boolean = false,
    val clientSecretLabel: String = "Client Secret",
    val activeProfileName: String = "Default",
    val anilistUser: AuthUser? = null,
    val isAnilistConnected: Boolean = false,
    val hasBakedClientId: Boolean = false,
    val hasEffectiveClientId: Boolean = false,
    val isAwaitingBrowserAuth: Boolean = false,
    val pinCode: String? = null,
    val pinVerificationUrl: String? = null,
    val isPollingPin: Boolean = false,
    val savedClientId: String = "",
    val clientIdInput: String = "",
    val savedClientSecret: String = "",
    val clientSecretInput: String = "",
    val tokenInput: String = "",
    val isVerifyingToken: Boolean = false,
    val verificationError: String? = null,
    val verificationSuccessMessage: String? = null,
    val selectedAnilistTab: AniListWindowTab = AniListWindowTab.ACCOUNT,
    val showDisconnectConfirm: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val bindPolicy: TrackerBindPolicy = TrackerBindPolicy.STRICT_AUTO_AND_PINNED,
    val confirmBeforeSync: Boolean = true,
    val autoSyncThresholdPercent: Int = 80,
    val auditEntries: List<TrackerSyncAuditEntry> = emptyList(),
    val filteredAuditEntries: List<TrackerSyncAuditEntry> = emptyList(),
    val auditFilter: AuditFilterOption = AuditFilterOption.ALL,
    val isLoadingAudit: Boolean = false,
) : UiState

sealed interface LibraryTrackerUiEvent : UiEvent {
    data class OnSelectAnilistTab(val tab: AniListWindowTab) : LibraryTrackerUiEvent
    data object OnStartBrowserAuth : LibraryTrackerUiEvent
    data object OnStartDefaultAuthPage : LibraryTrackerUiEvent
    data object OnCancelBrowserAuth : LibraryTrackerUiEvent
    data object OnStartDevicePin : LibraryTrackerUiEvent
    data object OnCancelDevicePin : LibraryTrackerUiEvent
    data class OnClientIdInputChange(val value: String) : LibraryTrackerUiEvent
    data object OnSaveClientId : LibraryTrackerUiEvent
    data class OnClientSecretInputChange(val value: String) : LibraryTrackerUiEvent
    data object OnSaveClientSecret : LibraryTrackerUiEvent
    data object OnOpenDeveloperPortal : LibraryTrackerUiEvent
    data class OnTokenInputChange(val value: String) : LibraryTrackerUiEvent
    data object OnVerifyAndConnect : LibraryTrackerUiEvent
    data object OnRequestDisconnect : LibraryTrackerUiEvent
    data object OnDismissDisconnectConfirm : LibraryTrackerUiEvent
    data object OnConfirmDisconnect : LibraryTrackerUiEvent
    data class OnSelectAuditFilter(val filter: AuditFilterOption) : LibraryTrackerUiEvent
    data object OnRefreshAudit : LibraryTrackerUiEvent
    data class OnSetAutoSync(val enabled: Boolean) : LibraryTrackerUiEvent
    data class OnSetBindPolicy(val policy: TrackerBindPolicy) : LibraryTrackerUiEvent
    data class OnSetConfirmBeforeSync(val enabled: Boolean) : LibraryTrackerUiEvent
    data class OnSetAutoSyncThreshold(val percent: Int) : LibraryTrackerUiEvent
}

sealed interface LibraryTrackerUiEffect : UiEffect {
    data class ShowToast(val message: String, val isError: Boolean = false) : LibraryTrackerUiEffect
}
