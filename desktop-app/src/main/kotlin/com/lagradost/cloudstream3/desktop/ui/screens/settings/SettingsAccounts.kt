package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxApiClient
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxDeviceAuthData
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxUserData
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamCustomDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.dialogs.TorboxAuthDialog
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsAccounts(
    viewModel: SettingsViewModel,
    onNavigate: (Config) -> Unit = {},
    onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedApiForLogin by remember { mutableStateOf<AuthAPI?>(null) }
    val cachedAccounts by AccountManager.accountsFlow.collectAsState()

    val torboxApiKey = uiState.stringSettings[DesktopDataStore.PREF_TORBOX_API_KEY]
        ?: (DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY) ?: "")
    val torboxEnabled = uiState.booleanSettings[DesktopDataStore.PREF_TORBOX_ENABLED]
        ?: (DesktopDataStore.getKey<Boolean>(DesktopDataStore.PREF_TORBOX_ENABLED) ?: true)

    val torboxState = uiState.torboxAccountState
    var torboxKeyInput by remember(torboxApiKey) { mutableStateOf(torboxApiKey) }
    var showTorboxPassword by remember { mutableStateOf(false) }
    // isValidatingTorboxKey in ViewModel
    // isStartingTorboxAuth in ViewModel
    // activeTorboxAuth in ViewModel
    // torboxFeedbackMessage in ViewModel
    // torboxFeedbackIsError in ViewModel

    /* LaunchedEffect removed - torbox state is managed by SettingsViewModel
        if (torboxApiKey.isNotBlank()) {
            val user = TorboxApiClient.getUser(torboxApiKey)
            torboxUser = user
        } else {
            torboxUser = null
        }
    }

    */ val scrollState = rememberScrollState()
    var containerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    CompositionLocalProvider(
        LocalSettingsScrollState provides scrollState,
        LocalScrollContainerCoordinates provides containerCoordinates,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerCoordinates = it }
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val profiles by com.lagradost.cloudstream3.desktop.profile.ProfileManager.profiles.collectAsState()
            val activeProfile by com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfile.collectAsState()
            val isPickerOnStartup by com.lagradost.cloudstream3.desktop.profile.ProfileManager.isPickerOnStartup.collectAsState()

            SettingsGroupCard(title = "User Profiles") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Profile List Items
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                com.lagradost.cloudstream3.desktop.profile.ProfileAvatar(
                                    profile = profile,
                                    size = 36.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    fontSize = 16.sp,
                                )

                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (profile.id == activeProfile.id) FontWeight.Bold else FontWeight.Medium,
                                            color = Color.White,
                                        )
                                        if (profile.id == activeProfile.id) {
                                            Surface(
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            ) {
                                                Text(
                                                    "Active",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    val details = buildList {
                                        if (profile.isKids) add("Kids Profile")
                                        if (profile.hasPin) add("PIN Protected")
                                    }.joinToString(" • ")
                                    if (details.isNotEmpty()) {
                                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (profile.id != activeProfile.id) {
                                    DesktopOutlinedButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                com.lagradost.cloudstream3.desktop.profile.ProfileManager.switchProfile(profile.id)
                                            }
                                        },
                                        shape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.compact,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text("Switch", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                DesktopIconButton(
                                    onClick = { onNavigate(Config.ProfileEdit(profileId = profile.id, switchOnCreate = false)) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = "Edit Profile", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Actions & Preferences
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DesktopButton(onClick = { onNavigate(Config.ProfileEdit(profileId = null, switchOnCreate = false)) }) {
                            Text("+ Create New Profile")
                        }

                        val autoSignIn by com.lagradost.cloudstream3.desktop.profile.ProfileManager.autoSignIn.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auto sign-in on launch", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = autoSignIn,
                                onCheckedChange = { checked ->
                                    scope.launch(Dispatchers.IO) {
                                        com.lagradost.cloudstream3.desktop.profile.ProfileManager.setAutoSignIn(checked)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            SettingsGroupCard(title = "Subtitle Providers") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountManager.subtitleProviders.forEachIndexed { index, provider ->
                        val accounts = cachedAccounts[provider.idPrefix]
                        val isLoggedIn = !accounts.isNullOrEmpty() && accounts.firstOrNull()?.token?.accessToken?.isNotBlank() == true

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = when {
                                        !provider.hasInApp -> "Active (Built-in integration)"
                                        isLoggedIn -> "API Key configured"
                                        else -> "API Key required for external subtitle search"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isLoggedIn) MaterialTheme.colorScheme.primary else Color.Gray,
                                )
                            }

                            if (provider.hasInApp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isLoggedIn) {
                                        DesktopOutlinedButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    AccountManager.updateAccounts(provider.idPrefix, emptyArray())
                                                    if (provider.idPrefix == "subsource") {
                                                        DesktopDataStore.removeKey(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_SUBSOURCE_API_KEY)
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                    ),

        
                                        ) {
                                            Text("Remove")
                                        }
                                        DesktopButton(
                                            onClick = { selectedApiForLogin = provider },
                                        ) {
                                            Text("Edit Key")
                                        }
                                    } else {
                                        DesktopButton(
                                            onClick = { selectedApiForLogin = provider },
                                        ) {
                                            Text("Add API Key")
                                        }
                                    }
                                }
                            }
                        }

                        if (index < AccountManager.subtitleProviders.size - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            SettingsGroupCard(title = "Trackers & Integrations") {
                val registeredTrackers = remember { com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry.all() }
                val plannedTrackers = remember { com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry.plannedTrackers }
                val trackingRepo = remember { com.lagradost.cloudstream3.desktop.di.AppContainerHolder.container.trackingRepository }
                val settingsVersion by trackingRepo.settingsVersion.collectAsState()
                val thresholdPct = remember(settingsVersion) { trackingRepo.getAutoSyncThresholdPercent() }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Connect tracker accounts and choose individually which ones auto-record completed episodes ($thresholdPct% watched).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    registeredTrackers.forEachIndexed { index, tracker ->
                        val auth = cachedAccounts[tracker.id]?.firstOrNull()
                        val isConnected = auth?.token?.accessToken?.isNotBlank() == true
                        val autoSyncOn = remember(settingsVersion, tracker.id) { trackingRepo.isAutoSyncEnabled(tracker.id) }
                        val bindPolicy = remember(settingsVersion, tracker.id) { trackingRepo.getBindPolicy(tracker.id) }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DesktopBadgeComponents.TrackerBrandIcon(
                                        trackerId = tracker.id,
                                        size = 18.dp,
                                    )
                                    Text(
                                        text = tracker.descriptor.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = if (isConnected) {
                                        val syncMode = if (autoSyncOn) "Auto-Track" else "Manual Sync"
                                        "Connected as ${auth.user.name ?: "${tracker.descriptor.displayName} User"} • $syncMode • ${bindPolicy.displayName}"
                                    } else {
                                        "Not connected for '${activeProfile.name}' • Default: ${bindPolicy.displayName}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray,
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "Auto-Track",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (autoSyncOn) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Switch(
                                        checked = autoSyncOn,
                                        onCheckedChange = { enabled ->
                                            scope.launch(Dispatchers.IO) {
                                                trackingRepo.setAutoSyncEnabled(enabled, tracker.id)
                                            }
                                        },
                                    )
                                }
                                if (isConnected) {
                                    DesktopOutlinedButton(
                                        onClick = {
                                            com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryViewModel.requestSection(
                                                section = com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection.Tracker(tracker.id),
                                            )
                                            onNavigate(Config.Library)
                                        },
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Text("View in Library")
                                    }
                                }
                                DesktopButton(
                                    onClick = {
                                        when (tracker.id) {
                                            com.lagradost.cloudstream3.desktop.tracking.TrackerIds.ANILIST ->
                                                onNavigateToSubScreen(SettingsSubScreen.ACCOUNTS_ANILIST)
                                            com.lagradost.cloudstream3.desktop.tracking.TrackerIds.MAL ->
                                                onNavigateToSubScreen(SettingsSubScreen.ACCOUNTS_MAL)
                                            com.lagradost.cloudstream3.desktop.tracking.TrackerIds.SIMKL ->
                                                onNavigateToSubScreen(SettingsSubScreen.ACCOUNTS_SIMKL)
                                        }
                                    },
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Text(if (isConnected) "Manage Account" else "Sign In")
                                }
                            }
                        }

                        if (index < registeredTrackers.lastIndex || plannedTrackers.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }

                    plannedTrackers.forEachIndexed { index, planned ->
                        val plannedColor = Color(planned.accentColorHex)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = plannedColor.copy(alpha = 0.14f),
                                    ) {
                                        Text(
                                            text = planned.shortTag,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = plannedColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = planned.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = planned.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            ) {
                                Text(
                                    text = "Queued",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (index < plannedTrackers.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            }

            SettingsGroupCard(title = "Discord Rich Presence") {
                MviSettingsToggle(
                    key = DesktopDataStore.PREF_DISCORD_RPC_ENABLED,
                    label = "Enable Discord Rich Presence",
                    subtitle = "Display your current playback and browsing status on your Discord profile.",
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    defaultValue = false,
                )

                val discordRpcEnabled = uiState.booleanSettings[DesktopDataStore.PREF_DISCORD_RPC_ENABLED] ?: (DesktopDataStore.getKey<Boolean>(DesktopDataStore.PREF_DISCORD_RPC_ENABLED) ?: false)

                if (discordRpcEnabled) {
                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_TITLE,
                        label = "Show Media & Episode Titles",
                        subtitle = "Display the specific movie, series name, and episode number.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )

                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_PROGRESS,
                        label = "Show Playback Progress Bar",
                        subtitle = "Display a live countdown progress bar on Discord while playing video.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )

                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_DISCORD_RPC_SHOW_BROWSING,
                        label = "Show Browsing Activity",
                        subtitle = "Display when browsing menus and catalogs when video is not playing.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )
                }
            }

            SettingsGroupCard(title = "Torbox Debrid") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Status Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Torbox Cloud Engine",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (torboxApiKey.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = "Connected • ${torboxState.user?.planName ?: "Active"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    ) {
                                        Text(
                                            text = "Not Connected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            val subDetails = buildList {
                                torboxState.user?.email?.let { add(it) }
                                torboxState.user?.expiresAt?.take(10)?.let { add("Expires $it") }
                                if (isEmpty()) {
                                    if (torboxApiKey.isNotBlank()) {
                                        add("API Key Configured")
                                    } else {
                                        add("Connect your account for instant cloud torrent streaming")
                                    }
                                }
                            }.joinToString(" • ")

                            Text(
                                text = subDetails,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (torboxApiKey.isNotBlank()) {
                                DesktopOutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            viewModel.onEvent(SettingsUiEvent.DisconnectTorbox)
                                            torboxKeyInput = ""
                                            // cleared
                                            torboxKeyInput = ""
                                            // disconnected
                                            // no error
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),

                                ) {
                                    Text("Disconnect")
                                }
                            }

                            DesktopButton(
                                onClick = {
                                    viewModel.onEvent(SettingsUiEvent.StartTorboxAuth)
                                    return@DesktopButton
                                    scope.launch(Dispatchers.IO) {
                                        val auth = TorboxApiClient.startDeviceAuthorization()
                                        // isStartingTorboxAuth = false
                                        if (auth != null) {
                                            // activeTorboxAuth = auth
                                        } else {
                                            // torboxFeedbackMessage = ...
                                            // error */
                                        }
                                    }
                                },
                                enabled = !torboxState.isStartingAuth,
                            ) {
                                if (torboxState.isStartingAuth) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Connecting...")
                                } else {
                                    Text(if (torboxApiKey.isNotBlank()) "Re-Login (WebView)" else "Log In with Torbox")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Direct API Key Input
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Or enter Torbox API Key manually:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = torboxKeyInput,
                                onValueChange = { torboxKeyInput = it },
                                label = { Text("Torbox API Key / Token") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                visualTransformation = if (showTorboxPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    DesktopIconButton(onClick = { showTorboxPassword = !showTorboxPassword }) {
                                        Icon(
                                            imageVector = if (showTorboxPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Visibility",
                                        )
                                    }
                                },
                            )

                            DesktopButton(
                                onClick = {
                                    viewModel.onEvent(SettingsUiEvent.ValidateTorboxKey(torboxKeyInput)); return@DesktopButton; val clean = torboxKeyInput.trim()
                                    if (clean.isBlank()) {
                                        // torboxFeedbackMessage = "API Key cannot be blank"
                                        // error */
                                        return@DesktopButton
                                    }
                                    // isValidatingTorboxKey = true
                                    return@DesktopButton
                                    scope.launch(Dispatchers.IO) {
                                        val user = TorboxApiClient.getUser(clean)
                                        // isValidatingTorboxKey = false
                                        if (user != null) {
                                            DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, clean)
                                            DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_ENABLED, true)
                                            viewModel.onEvent(SettingsUiEvent.OnUpdateString(DesktopDataStore.PREF_TORBOX_API_KEY, clean))
                                            viewModel.onEvent(SettingsUiEvent.OnUpdateBoolean(DesktopDataStore.PREF_TORBOX_ENABLED, true))
                                            // torboxUser = user
                                            // torboxFeedbackMessage = "Connected successfully as ${user.email ?: "user"} (${user.planName})"
                                            // no error
                                        } else {
                                            // torboxFeedbackMessage = "Invalid API Key or server unreachable"
                                            // error */
                                        }
                                    }
                                },
                                enabled = !torboxState.isValidatingKey,
                                modifier = Modifier.height(56.dp),
                            ) {
                                if (torboxState.isValidatingKey) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Validating...")
                                } else {
                                    Text("Validate & Save")
                                }
                            }
                        }

                        torboxState.feedbackMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (torboxState.feedbackIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Auto-Resolve Toggle
                    MviSettingsToggle(
                        key = DesktopDataStore.PREF_TORBOX_AUTO_RESOLVE,
                        label = "Auto-Resolve Torrents via Torbox",
                        subtitle = "Automatically stream cached torrents and magnets through Torbox high-speed HTTPS cloud CDN.",
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        defaultValue = true,
                    )
                }
            }
        }
    }

    selectedApiForLogin?.let { targetApi ->
        InAppLoginDialog(
            api = targetApi, isTestingApi = uiState.isTestingAccountApi, testApiStatus = uiState.testAccountApiStatus, onTestApi = { key -> viewModel.onEvent(SettingsUiEvent.TestAccountApiKey(targetApi.idPrefix, key)) },
            onDismiss = { viewModel.onEvent(SettingsUiEvent.ResetAccountApiTestStatus); selectedApiForLogin = null },
            onSuccess = { authData ->
                viewModel.onEvent(SettingsUiEvent.ResetAccountApiTestStatus)
                val apiPrefix = targetApi.idPrefix
                scope.launch(Dispatchers.IO) {
                    AccountManager.updateAccounts(apiPrefix, arrayOf(authData))
                    if (apiPrefix == "subsource") {
                        authData.token.accessToken?.let {
                            DesktopDataStore.setKey(com.lagradost.cloudstream3.desktop.player.PlayerConfig.PREF_SUBSOURCE_API_KEY, it)
                        }
                    }
                }
                selectedApiForLogin = null
            },
        )
    }

    torboxState.activeAuth?.let { auth ->
        TorboxAuthDialog(
            auth = auth,
            onDismiss = { viewModel.onEvent(SettingsUiEvent.CancelTorboxAuth) },
            onAuthorized = { token -> viewModel.onEvent(SettingsUiEvent.CompleteTorboxAuth(token)) /*
                scope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, token)
                    DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_ENABLED, true)
                    viewModel.onEvent(SettingsUiEvent.OnUpdateString(DesktopDataStore.PREF_TORBOX_API_KEY, token))
                    viewModel.onEvent(SettingsUiEvent.OnUpdateBoolean(DesktopDataStore.PREF_TORBOX_ENABLED, true))
                    torboxUser = TorboxApiClient.getUser(token)
                }
                // activeTorboxAuth = null
                // torboxFeedbackMessage = "Successfully connected to Torbox via WebView!"
                torboxFeedbackIsError = false */
            },
            onError = { err -> viewModel.onEvent(SettingsUiEvent.SetTorboxAuthError(err)) /*
                torboxFeedbackMessage = err
                // error */
                // activeTorboxAuth = null
            },
        )
    }
}

@Composable
fun InAppLoginDialog(api: AuthAPI, onDismiss: () -> Unit, onSuccess: (AuthData) -> Unit, isTestingApi: Boolean = false, testApiStatus: Pair<Boolean, String>? = null, onTestApi: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    val initialKey = AccountManager.cachedAccounts[api.idPrefix]?.firstOrNull()?.token?.accessToken ?: ""
    var apiKeyStr by remember { mutableStateOf(initialKey) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // var isTestingApi in ViewModel
    // var testApiStatus in ViewModel

    val req = api.inAppLoginRequirement
    val isApiKeyOnly = req != null && req.apiKey && !req.username && !req.password && !req.email && !req.server

    CloudstreamCustomDialog(
        show = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(460.dp).wrapContentHeight(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(28.dp)) {
            Text(if (isApiKeyOnly) "Enter API Key for ${api.name}" else "Login to ${api.name}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            if (req?.username == true) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (req?.email == true) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (req?.password == true) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (req?.server == true) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (req?.apiKey == true) {
                OutlinedTextField(
                    value = apiKeyStr,
                    onValueChange = {
                        apiKeyStr = it
                        if (errorMsg != null) errorMsg = null

                    },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            val curTestStatus = testApiStatus
            val curErrorMsg = errorMsg
            if (curTestStatus != null) {
                val (isSuccess, msg) = curTestStatus
                Text(
                    text = if (isSuccess) "✓ $msg" else "✕ $msg",
                    color = if (isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(14.dp))
            } else if (curErrorMsg != null) {
                Text(curErrorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isApiKeyOnly) Arrangement.SpaceBetween else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isApiKeyOnly) {
                    DesktopOutlinedButton(
                        onClick = {
                            onTestApi(apiKeyStr); if (true) return@DesktopOutlinedButton; val cleanKey = apiKeyStr.trim()
                            if (cleanKey.isBlank()) {
                                val _dTestStatus = false to "API Key cannot be empty"
                                return@DesktopOutlinedButton
                            }
                            val _dTesting = true
                            val _dTestStatusNull: Pair<Boolean, String>? = null
                            scope.launch(Dispatchers.IO) {
                                val res = runCatching {
                                    if (api.idPrefix == "subdl") {
                                        val r = com.lagradost.cloudstream3.app.get("https://api.subdl.com/api/v1/subtitles?api_key=$cleanKey&film_name=matrix", timeout = 8000L).okhttpResponse
                                        when (r.code) {
                                            200 -> true to "Valid API Key (Connected successfully)"
                                            400, 401, 403 -> false to "Invalid API Key (HTTP ${r.code})"
                                            else -> false to "HTTP ${r.code}: Unexpected response"
                                        }
                                    } else if (api.idPrefix == "subsource") {
                                        com.lagradost.cloudstream3.desktop.subtitles.SubsourceSubtitleProvider.testApiKey(cleanKey)
                                    } else {
                                        true to "API Key format verified"
                                    }
                                }.getOrElse { false to "Connection error: ${it.message}" }


                            }
                        },
                        enabled = !isTestingApi && apiKeyStr.isNotBlank(),
                    ) {
                        if (isTestingApi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...")
                        } else {
                            Text("Test API Key")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DesktopTextButton(onClick = onDismiss) { Text("Cancel") }
                    DesktopButton(onClick = {
                        val cleanKey = apiKeyStr.trim()
                        if (isApiKeyOnly && cleanKey.isBlank()) {
                            errorMsg = "API Key cannot be empty"
                            return@DesktopButton
                        }
                        val authData = AuthData(
                            user = com.lagradost.cloudstream3.syncproviders.AuthUser(name = if (username.isNotBlank()) username.trim() else "User", id = 0, profilePicture = ""),
                            token = com.lagradost.cloudstream3.syncproviders.AuthToken(accessToken = cleanKey),
                        )
                        onSuccess(authData)
                    }) { Text(if (isApiKeyOnly) "Save Key" else "Login") }
                }
            }
        }
    }
}
