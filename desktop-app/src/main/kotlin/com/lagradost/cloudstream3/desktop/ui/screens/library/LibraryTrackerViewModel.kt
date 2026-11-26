package com.lagradost.cloudstream3.desktop.ui.screens.library

import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.tracking.AniListOAuthFlow
import com.lagradost.cloudstream3.desktop.tracking.LoopbackOAuthMode
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerOAuthLoopback
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.AniListWindowTab
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.AuditFilterOption
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiState
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import com.lagradost.cloudstream3.syncproviders.providers.MalAuthRequest
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklAuthRequest
import com.lagradost.cloudstream3.syncproviders.providers.SimklPinPollResult
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LibraryTrackerViewModel(
    private val trackerId: String = TrackerIds.ANILIST,
    private val repository: TrackingRepository = AppContainerHolder.container.trackingRepository,
    private val aniListApi: AniListApi = AccountManager.aniListApi,
    private val malApi: MalApi = AccountManager.malApi,
    private val simklApi: SimklApi = AccountManager.simklApi,
) : BaseMviViewModel<LibraryTrackerUiState, LibraryTrackerUiEvent, LibraryTrackerUiEffect>(
    initialState = LibraryTrackerUiState(),
) {
    private var browserAuthJob: Job? = null
    private var devicePinJob: Job? = null
    private var activeLoopbackSession: TrackerOAuthLoopback.LoopbackSession? = null
    private var pendingMalAuth: MalAuthRequest? = null
    private var pendingSimklAuth: SimklAuthRequest? = null

    init {
        loadSnapshot()

        viewModelScope.launch {
            repository.accounts.collect { accounts ->
                val activeAccount = accounts[trackerId]?.firstOrNull()
                updateState {
                    copy(
                        anilistUser = activeAccount?.user,
                        isAnilistConnected = activeAccount?.token?.accessToken?.isNotBlank() == true,
                    )
                }
            }
        }

        viewModelScope.launch {
            ProfileManager.activeProfile.collect { profile ->
                updateState { copy(activeProfileName = profile.name) }
                refreshAuditLog()
            }
        }

        viewModelScope.launch {
            repository.auditVersion.collect {
                refreshAuditLog()
            }
        }

        viewModelScope.launch {
            repository.settingsVersion.collect {
                updateState {
                    copy(
                        autoSyncEnabled = repository.isAutoSyncEnabled(trackerId),
                        bindPolicy = repository.getBindPolicy(trackerId),
                        confirmBeforeSync = repository.isConfirmBeforeSync(trackerId),
                        autoSyncThresholdPercent = repository.getAutoSyncThresholdPercent(),
                    )
                }
            }
        }
    }

    override fun handleEvent(event: LibraryTrackerUiEvent) {
        when (event) {
            is LibraryTrackerUiEvent.OnSelectAnilistTab -> {
                updateState { copy(selectedAnilistTab = event.tab) }
                if (event.tab == AniListWindowTab.HISTORY) {
                    refreshAuditLog()
                }
            }
            is LibraryTrackerUiEvent.OnStartBrowserAuth -> startBrowserLoopbackAuth()
            is LibraryTrackerUiEvent.OnStartDefaultAuthPage -> startDefaultAuthPage()
            is LibraryTrackerUiEvent.OnCancelBrowserAuth -> cancelBrowserAuth()
            is LibraryTrackerUiEvent.OnStartDevicePin -> startDevicePinFlow()
            is LibraryTrackerUiEvent.OnCancelDevicePin -> cancelDevicePinFlow()
            is LibraryTrackerUiEvent.OnClientIdInputChange -> updateState {
                copy(clientIdInput = event.value, verificationError = null)
            }
            is LibraryTrackerUiEvent.OnSaveClientId -> saveClientId()
            is LibraryTrackerUiEvent.OnClientSecretInputChange -> updateState {
                copy(clientSecretInput = event.value, verificationError = null)
            }
            is LibraryTrackerUiEvent.OnSaveClientSecret -> saveClientSecret()
            is LibraryTrackerUiEvent.OnOpenDeveloperPortal ->
                ExternalLinkHandler.launchSystemBrowser(developerPortalUrl())
            is LibraryTrackerUiEvent.OnTokenInputChange -> updateState {
                copy(tokenInput = event.value, verificationError = null, verificationSuccessMessage = null)
            }
            is LibraryTrackerUiEvent.OnVerifyAndConnect -> verifyAndConnectManual(uiState.value.tokenInput)
            is LibraryTrackerUiEvent.OnRequestDisconnect -> updateState { copy(showDisconnectConfirm = true) }
            is LibraryTrackerUiEvent.OnDismissDisconnectConfirm -> updateState { copy(showDisconnectConfirm = false) }
            is LibraryTrackerUiEvent.OnConfirmDisconnect -> confirmDisconnect()
            is LibraryTrackerUiEvent.OnSelectAuditFilter -> updateState {
                copy(auditFilter = event.filter).applyAuditFilter()
            }
            is LibraryTrackerUiEvent.OnRefreshAudit -> refreshAuditLog()
            is LibraryTrackerUiEvent.OnSetAutoSync -> {
                updateState { copy(autoSyncEnabled = event.enabled) }
                viewModelScope.launch(Dispatchers.IO) {
                    repository.setAutoSyncEnabled(event.enabled, trackerId)
                }
            }
            is LibraryTrackerUiEvent.OnSetBindPolicy -> {
                updateState { copy(bindPolicy = event.policy) }
                viewModelScope.launch(Dispatchers.IO) {
                    repository.setBindPolicy(event.policy, trackerId)
                }
            }
            is LibraryTrackerUiEvent.OnSetConfirmBeforeSync -> {
                updateState { copy(confirmBeforeSync = event.enabled) }
                viewModelScope.launch(Dispatchers.IO) {
                    repository.setConfirmBeforeSync(event.enabled, trackerId)
                }
            }
            is LibraryTrackerUiEvent.OnSetAutoSyncThreshold -> {
                updateState { copy(autoSyncThresholdPercent = event.percent) }
                viewModelScope.launch(Dispatchers.IO) {
                    repository.setAutoSyncThresholdPercent(event.percent)
                }
            }
        }
    }

    private fun trackerDisplayName(): String = when (trackerId) {
        TrackerIds.MAL -> "MyAnimeList"
        TrackerIds.SIMKL -> "SIMKL"
        else -> "AniList"
    }

    private fun trackerBrandColorHex(): Long = when (trackerId) {
        TrackerIds.MAL -> 0xFF2E51A2L
        TrackerIds.SIMKL -> 0xFF00B7FFL
        else -> 0xFF02A9FFL
    }

    private fun developerPortalUrl(): String = when (trackerId) {
        TrackerIds.MAL -> "https://myanimelist.net/apiconfig"
        TrackerIds.SIMKL -> "https://simkl.com/settings/developer/"
        else -> "https://anilist.co/settings/developer"
    }

    private fun developerPortalLabel(): String = when (trackerId) {
        TrackerIds.MAL -> "MyAnimeList API Portal"
        TrackerIds.SIMKL -> "SIMKL Developer Portal"
        else -> "AniList Developer Portal"
    }

    private fun loopbackRedirectUri(): String = when (trackerId) {
        TrackerIds.MAL -> AniListOAuthFlow.MAL_LOOPBACK_REDIRECT_URI
        TrackerIds.SIMKL -> AniListOAuthFlow.SIMKL_LOOPBACK_REDIRECT_URI
        else -> AniListOAuthFlow.LOOPBACK_REDIRECT_URI
    }

    private fun missingKeyHint(): String = when (trackerId) {
        TrackerIds.MAL -> "mal.key"
        TrackerIds.SIMKL -> "simkl.key"
        else -> "anilist.key"
    }

    private fun currentCustomClientId(): String = when (trackerId) {
        TrackerIds.MAL -> malApi.customClientId()
        TrackerIds.SIMKL -> simklApi.customClientId()
        else -> aniListApi.customClientId()
    }

    private fun persistCustomClientId(value: String) {
        when (trackerId) {
            TrackerIds.MAL -> malApi.setCustomClientId(value)
            TrackerIds.SIMKL -> simklApi.setCustomClientId(value)
            else -> aniListApi.setCustomClientId(value)
        }
    }

    private fun supportsClientSecret(): Boolean =
        trackerId == TrackerIds.MAL || trackerId == TrackerIds.ANILIST

    private fun currentCustomClientSecret(): String = when (trackerId) {
        TrackerIds.MAL -> malApi.customClientSecret()
        TrackerIds.ANILIST -> aniListApi.customClientSecret()
        else -> ""
    }

    private fun persistCustomClientSecret(value: String) {
        when (trackerId) {
            TrackerIds.MAL -> malApi.setCustomClientSecret(value)
            TrackerIds.ANILIST -> aniListApi.setCustomClientSecret(value)
        }
    }

    private fun clientSecretLabel(): String = when (trackerId) {
        TrackerIds.MAL -> "Client Secret (only if App Type is 'Web')"
        else -> "Client Secret (required for OAuth code exchange)"
    }

    private fun hasBakedClientId(): Boolean = when (trackerId) {
        TrackerIds.MAL -> malApi.hasBakedClientId()
        TrackerIds.SIMKL -> simklApi.hasBakedClientId()
        else -> aniListApi.hasBakedClientId()
    }

    private fun hasClientId(): Boolean = when (trackerId) {
        TrackerIds.MAL -> malApi.hasClientId()
        TrackerIds.SIMKL -> simklApi.hasClientId()
        else -> aniListApi.hasClientId()
    }

    private fun loadSnapshot() {
        val currentClientId = currentCustomClientId()
        val currentClientSecret = currentCustomClientSecret()
        val activeAccount = repository.getAccountsForProvider(trackerId)?.firstOrNull()
        updateState {
            copy(
                trackerId = this@LibraryTrackerViewModel.trackerId,
                trackerDisplayName = trackerDisplayName(),
                brandColorHex = trackerBrandColorHex(),
                developerPortalLabel = developerPortalLabel(),
                loopbackRedirectUri = loopbackRedirectUri(),
                missingKeyPropertyHint = missingKeyHint(),
                supportsDevicePin = this@LibraryTrackerViewModel.trackerId == TrackerIds.SIMKL,
                supportsPinFallbackAuth = this@LibraryTrackerViewModel.trackerId == TrackerIds.ANILIST,
                supportsClientSecret = supportsClientSecret(),
                clientSecretLabel = clientSecretLabel(),
                activeProfileName = ProfileManager.activeProfile.value.name,
                anilistUser = activeAccount?.user,
                isAnilistConnected = activeAccount?.token?.accessToken?.isNotBlank() == true,
                hasBakedClientId = hasBakedClientId(),
                hasEffectiveClientId = hasClientId(),
                savedClientId = currentClientId,
                clientIdInput = currentClientId,
                savedClientSecret = currentClientSecret,
                clientSecretInput = currentClientSecret,
                autoSyncEnabled = repository.isAutoSyncEnabled(trackerId),
                bindPolicy = repository.getBindPolicy(trackerId),
                confirmBeforeSync = repository.isConfirmBeforeSync(trackerId),
                autoSyncThresholdPercent = repository.getAutoSyncThresholdPercent(),
            )
        }
        refreshAuditLog()
    }

    private fun startDefaultAuthPage() {
        if (trackerId != TrackerIds.ANILIST) return
        val label = trackerDisplayName()
        cancelBrowserAuth()
        cancelDevicePinFlow()
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = uiState.value.clientIdInput.trim()
            val trimmedSecret = uiState.value.clientSecretInput.trim()
            if (trimmed != uiState.value.savedClientId) {
                persistCustomClientId(trimmed)
                updateState { copy(savedClientId = trimmed, hasEffectiveClientId = hasClientId()) }
            }
            if (trimmedSecret != uiState.value.savedClientSecret) {
                persistCustomClientSecret(trimmedSecret)
                updateState { copy(savedClientSecret = trimmedSecret) }
            }
            if (!hasClientId()) {
                updateState {
                    copy(
                        verificationError = "No $label Client ID configured. Expand Advanced below to set a Client ID.",
                    )
                }
                return@launch
            }
            AppLogger.i(
                TAG,
                "Launching $label default auth page (customId=${uiState.value.savedClientId.isNotBlank()})",
            )
            ExternalLinkHandler.launchSystemBrowser(aniListApi.loginRequest().url)
            updateState {
                copy(
                    verificationError = null,
                    verificationSuccessMessage = "Browser opened on AniList's default redirect. Approve access, then paste the displayed code or redirect URL under Advanced.",
                )
            }
        }
    }

    private fun startDevicePinFlow() {
        if (trackerId != TrackerIds.SIMKL) return
        val trimmed = uiState.value.clientIdInput.trim()
        cancelBrowserAuth()
        cancelDevicePinFlow()

        devicePinJob = viewModelScope.launch(Dispatchers.IO) {
            if (trimmed != uiState.value.savedClientId) {
                persistCustomClientId(trimmed)
                updateState { copy(savedClientId = trimmed, hasEffectiveClientId = hasClientId()) }
            }
            if (!hasClientId()) {
                updateState {
                    copy(
                        isPollingPin = false,
                        pinCode = null,
                        pinVerificationUrl = null,
                        verificationError = "No SIMKL Client ID configured. Expand Advanced below to set your Client ID or paste a token.",
                    )
                }
                return@launch
            }

            updateState {
                copy(
                    isPollingPin = true,
                    pinCode = null,
                    pinVerificationUrl = null,
                    verificationError = null,
                    verificationSuccessMessage = "Requesting SIMKL PIN code...",
                )
            }

            val session = simklApi.requestDevicePin().getOrElse { err ->
                updateState {
                    copy(
                        isPollingPin = false,
                        verificationError = err.message ?: "Unable to request SIMKL PIN.",
                        verificationSuccessMessage = null,
                    )
                }
                return@launch
            }

            updateState {
                copy(
                    pinCode = session.pin.userCode,
                    pinVerificationUrl = session.pin.verificationUrl,
                    isPollingPin = true,
                    verificationError = null,
                    verificationSuccessMessage = "Enter code ${session.pin.userCode} at ${session.pin.verificationUrl}",
                )
            }
            ExternalLinkHandler.launchSystemBrowser(session.pin.verificationUrl)

            val deadlineMs = System.currentTimeMillis() + (session.pin.expiresIn.coerceAtLeast(60) * 1000L)
            val intervalMs = session.pin.interval.coerceAtLeast(4) * 1000L

            while (isActive && System.currentTimeMillis() < deadlineMs) {
                delay(intervalMs)
                when (val poll = simklApi.pollDevicePin(session)) {
                    SimklPinPollResult.Pending -> Unit
                    is SimklPinPollResult.Authorized -> {
                        onAuthorizationSuccess(poll.authData)
                        return@launch
                    }
                    is SimklPinPollResult.Failed -> {
                        updateState {
                            copy(
                                isPollingPin = false,
                                pinCode = null,
                                pinVerificationUrl = null,
                                verificationError = poll.message,
                                verificationSuccessMessage = null,
                            )
                        }
                        return@launch
                    }
                }
            }

            if (isActive) {
                updateState {
                    copy(
                        isPollingPin = false,
                        pinCode = null,
                        pinVerificationUrl = null,
                        verificationError = "SIMKL PIN expired before approval. Start a new PIN code to try again.",
                        verificationSuccessMessage = null,
                    )
                }
            }
        }
    }

    private fun cancelDevicePinFlow() {
        devicePinJob?.cancel()
        devicePinJob = null
        updateState {
            copy(
                isPollingPin = false,
                pinCode = null,
                pinVerificationUrl = null,
            )
        }
    }

    private fun startBrowserLoopbackAuth() {
        val label = trackerDisplayName()
        val trimmed = uiState.value.clientIdInput.trim()
        val trimmedSecret = uiState.value.clientSecretInput.trim()
        browserAuthJob?.cancel()
        cancelDevicePinFlow()
        activeLoopbackSession?.close()
        activeLoopbackSession = null

        browserAuthJob = viewModelScope.launch(Dispatchers.IO) {
            if (trimmed != uiState.value.savedClientId) {
                persistCustomClientId(trimmed)
                updateState { copy(savedClientId = trimmed, hasEffectiveClientId = hasClientId()) }
            }
            if (supportsClientSecret() && trimmedSecret != uiState.value.savedClientSecret) {
                persistCustomClientSecret(trimmedSecret)
                updateState { copy(savedClientSecret = trimmedSecret) }
            }
            if (!hasClientId()) {
                updateState {
                    copy(
                        isAwaitingBrowserAuth = false,
                        verificationError = "No $label Client ID configured. Expand Advanced below to set a Client ID or paste an access token.",
                    )
                }
                return@launch
            }

            val loopback = when (trackerId) {
                TrackerIds.MAL -> TrackerOAuthLoopback.startSession(
                    port = AniListOAuthFlow.MAL_LOOPBACK_PORT,
                    path = AniListOAuthFlow.MAL_LOOPBACK_PATH,
                    serviceName = "MyAnimeList",
                    mode = LoopbackOAuthMode.AUTHORIZATION_CODE,
                )
                TrackerIds.SIMKL -> TrackerOAuthLoopback.startSession(
                    port = AniListOAuthFlow.SIMKL_LOOPBACK_PORT,
                    path = AniListOAuthFlow.SIMKL_LOOPBACK_PATH,
                    serviceName = "SIMKL",
                    mode = LoopbackOAuthMode.AUTHORIZATION_CODE,
                )
                else -> AniListOAuthFlow.startLoopbackSession()
            }
            activeLoopbackSession = loopback

            val authUrl = when (trackerId) {
                TrackerIds.MAL -> {
                    val req = malApi.buildAuthorizeRequest(AniListOAuthFlow.MAL_LOOPBACK_REDIRECT_URI)
                    pendingMalAuth = req
                    req.url
                }
                TrackerIds.SIMKL -> {
                    val req = simklApi.buildAuthorizeRequest(AniListOAuthFlow.SIMKL_LOOPBACK_REDIRECT_URI)
                    pendingSimklAuth = req
                    req.url
                }
                else -> if (loopback != null) {
                    aniListApi.buildAuthorizeUrl(AniListOAuthFlow.LOOPBACK_REDIRECT_URI)
                } else {
                    aniListApi.loginRequest().url
                }
            }

            updateState {
                copy(
                    isAwaitingBrowserAuth = loopback != null,
                    verificationError = null,
                    verificationSuccessMessage = if (loopback != null) {
                        "Waiting for $label authorization in your browser (Redirect URI: ${loopbackRedirectUri()})..."
                    } else {
                        "Browser opened. If $label redirects with a code or token, paste it under Advanced."
                    },
                )
            }
            AppLogger.i(
                TAG,
                "Launching $label browser auth (mode=${if (loopback != null) "loopback" else "default"}, customId=${uiState.value.savedClientId.isNotBlank()})",
            )
            ExternalLinkHandler.launchSystemBrowser(authUrl)

            if (loopback != null) {
                var captured: String? = null
                try {
                    captured = loopback.awaitRedirectInput(timeoutMs = 180_000L)
                } finally {
                    loopback.close()
                    if (activeLoopbackSession === loopback) activeLoopbackSession = null
                }
                if (!captured.isNullOrBlank()) {
                    updateState {
                        copy(
                            isAwaitingBrowserAuth = false,
                            isVerifyingToken = true,
                            verificationError = null,
                            verificationSuccessMessage = null,
                        )
                    }
                    executeAuthorize(captured)
                } else if (uiState.value.isAwaitingBrowserAuth) {
                    updateState {
                        copy(
                            isAwaitingBrowserAuth = false,
                            verificationError = "Browser login timed out or returned no authorization. Verify Redirect URI '${loopbackRedirectUri()}' on $label, or paste token under Advanced.",
                        )
                    }
                }
            }
        }
    }

    private fun cancelBrowserAuth() {
        browserAuthJob?.cancel()
        browserAuthJob = null
        activeLoopbackSession?.close()
        activeLoopbackSession = null
        updateState { copy(isAwaitingBrowserAuth = false) }
    }

    private fun saveClientId() {
        val trimmed = uiState.value.clientIdInput.trim()
        viewModelScope.launch(Dispatchers.IO) {
            persistCustomClientId(trimmed)
            updateState {
                copy(
                    savedClientId = trimmed,
                    clientIdInput = trimmed,
                    hasEffectiveClientId = hasClientId(),
                    verificationSuccessMessage = if (trimmed.isNotEmpty()) {
                        "Custom Client ID saved."
                    } else {
                        "Custom Client ID cleared."
                    },
                    verificationError = null,
                )
            }
        }
    }

    private fun saveClientSecret() {
        if (!supportsClientSecret()) return
        val trimmed = uiState.value.clientSecretInput.trim()
        viewModelScope.launch(Dispatchers.IO) {
            persistCustomClientSecret(trimmed)
            updateState {
                copy(
                    savedClientSecret = trimmed,
                    clientSecretInput = trimmed,
                    verificationSuccessMessage = if (trimmed.isNotEmpty()) {
                        "Custom Client Secret saved."
                    } else {
                        "Custom Client Secret cleared."
                    },
                    verificationError = null,
                )
            }
        }
    }

    private fun verifyAndConnectManual(rawInput: String) {
        val cleaned = rawInput.trim()
        if (cleaned.isBlank()) {
            updateState { copy(verificationError = "Paste an access token, authorization code, or redirect URL.") }
            return
        }
        if (uiState.value.isVerifyingToken) return

        cancelBrowserAuth()
        cancelDevicePinFlow()
        updateState {
            copy(
                isVerifyingToken = true,
                verificationError = null,
                verificationSuccessMessage = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedId = uiState.value.clientIdInput.trim()
            if (trimmedId != uiState.value.savedClientId) {
                persistCustomClientId(trimmedId)
                updateState { copy(savedClientId = trimmedId, hasEffectiveClientId = hasClientId()) }
            }
            val trimmedSecret = uiState.value.clientSecretInput.trim()
            if (supportsClientSecret() && trimmedSecret != uiState.value.savedClientSecret) {
                persistCustomClientSecret(trimmedSecret)
                updateState { copy(savedClientSecret = trimmedSecret) }
            }
            executeAuthorize(cleaned)
        }
    }

    private suspend fun executeAuthorize(cleaned: String) {
        val result: Result<AuthData> = when (trackerId) {
            TrackerIds.MAL -> {
                val pending = pendingMalAuth
                if (cleaned.contains("code=") && pending != null) {
                    malApi.exchangeCodeAndAuthorize(
                        redirectOrCode = cleaned,
                        expectedState = pending.state,
                        codeVerifier = pending.codeVerifier,
                        redirectUri = pending.redirectUri,
                    )
                } else if (!cleaned.contains("://") && !cleaned.contains("access_token=") && pending != null && cleaned.length < 400 && !cleaned.contains('.')) {
                    // Raw authorization code paste fallback if a browser flow was started.
                    malApi.exchangeCodeAndAuthorize(
                        redirectOrCode = cleaned,
                        expectedState = pending.state,
                        codeVerifier = pending.codeVerifier,
                        redirectUri = pending.redirectUri,
                    )
                } else {
                    malApi.authorizeWithToken(cleaned)
                }
            }
            TrackerIds.SIMKL -> {
                val pending = pendingSimklAuth
                if (cleaned.contains("code=") && pending != null) {
                    simklApi.exchangeCodeAndAuthorize(
                        redirectOrCode = cleaned,
                        expectedState = pending.state,
                        codeVerifier = pending.codeVerifier,
                        redirectUri = pending.redirectUri,
                    )
                } else {
                    simklApi.authorizeWithToken(cleaned)
                }
            }
            else -> {
                val looksLikeAccessToken = cleaned.contains("access_token=") ||
                    cleaned.startsWith("Bearer ", ignoreCase = true) ||
                    (!cleaned.contains("code=") && !cleaned.contains("error=") && !cleaned.contains("://") && cleaned.contains('.'))
                if (!looksLikeAccessToken) {
                    aniListApi.exchangeCodeAndAuthorize(cleaned, AniListOAuthFlow.LOOPBACK_REDIRECT_URI)
                } else {
                    aniListApi.authorizeWithToken(cleaned)
                }
            }
        }

        result.fold(
            onSuccess = { authData -> onAuthorizationSuccess(authData) },
            onFailure = { err ->
                val label = trackerDisplayName()
                AppLogger.w(TAG, "$label authorization failed: ${err.message ?: err.javaClass.simpleName}")
                updateState {
                    copy(
                        isVerifyingToken = false,
                        verificationError = err.message ?: "Failed to verify $label authorization",
                    )
                }
            },
        )
    }

    private fun onAuthorizationSuccess(authData: AuthData) {
        repository.updateAccounts(trackerId, arrayOf(authData))
        val displayName = authData.user.name ?: trackerDisplayName()
        val label = trackerDisplayName()
        pendingMalAuth = null
        pendingSimklAuth = null
        updateState {
            copy(
                isVerifyingToken = false,
                isAwaitingBrowserAuth = false,
                isPollingPin = false,
                pinCode = null,
                pinVerificationUrl = null,
                tokenInput = "",
                verificationError = null,
                verificationSuccessMessage = "Connected as $displayName",
            )
        }
        sendEffect(LibraryTrackerUiEffect.ShowToast("Connected $label account: $displayName"))
    }

    private fun confirmDisconnect() {
        val label = trackerDisplayName()
        cancelBrowserAuth()
        cancelDevicePinFlow()
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAccounts(trackerId, emptyArray())
            updateState { copy(showDisconnectConfirm = false) }
            sendEffect(LibraryTrackerUiEffect.ShowToast("Disconnected $label for profile '${uiState.value.activeProfileName}'"))
        }
    }

    private fun refreshAuditLog() {
        updateState { copy(isLoadingAudit = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val entries = repository.getAuditLog(trackerId)
            updateState {
                copy(
                    auditEntries = entries,
                    isLoadingAudit = false,
                ).applyAuditFilter()
            }
        }
    }

    private fun LibraryTrackerUiState.applyAuditFilter(): LibraryTrackerUiState {
        val targetState = auditFilter.filterState
        val filtered = if (targetState == null) auditEntries else auditEntries.filter { it.state == targetState }
        return copy(filteredAuditEntries = filtered)
    }

    override fun dispose() {
        cancelBrowserAuth()
        cancelDevicePinFlow()
        super.dispose()
    }

    private companion object {
        private const val TAG = "LibraryTrackerViewModel"
    }
}
