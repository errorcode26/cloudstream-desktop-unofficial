package com.lagradost.cloudstream3.desktop.ui.screens.library.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryTrackerViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.AniListWindowTab
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiState
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler

@Composable
fun SettingsAniListScreen(
    viewModel: LibraryTrackerViewModel,
    modifier: Modifier = Modifier,
) {
    SettingsTrackerAccountScreen(viewModel = viewModel, modifier = modifier)
}

@Composable
fun SettingsTrackerAccountScreen(
    viewModel: LibraryTrackerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val brandColor = Color(uiState.brandColorHex)
    val connectedColor = Color(0xFF10B981)

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is LibraryTrackerUiEffect.ShowToast -> {
                    if (effect.isError) AppToastManager.showError(effect.message)
                    else AppToastManager.showSuccess(effect.message)
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(bottom = DesktopDimens.WatermarkBottomInset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = brandColor.copy(alpha = 0.16f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        DesktopBadgeComponents.TrackerBrandIcon(
                            trackerId = uiState.trackerId,
                            size = 22.dp,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${uiState.trackerDisplayName} Account & Sync Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = theme.TextPrimary,
                        )
                        val badgeColor = if (uiState.isAnilistConnected) connectedColor else theme.TextMuted
                        Surface(
                            shape = chipShape,
                            color = badgeColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = if (uiState.isAnilistConnected) "Connected" else "Disconnected",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    val syncSummary = if (uiState.autoSyncEnabled) {
                        "Auto-Track (${uiState.autoSyncThresholdPercent}% watched)"
                    } else {
                        "Manual Sync"
                    }
                    Text(
                        text = "Profile: ${uiState.activeProfileName}  •  $syncSummary  •  ${uiState.bindPolicy.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AniListWindowTab.entries.forEach { tab ->
                    FilterChip(
                        selected = uiState.selectedAnilistTab == tab,
                        onClick = { viewModel.onEvent(LibraryTrackerUiEvent.OnSelectAnilistTab(tab)) },
                        shape = chipShape,
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (uiState.selectedAnilistTab == tab) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (uiState.selectedAnilistTab) {
                AniListWindowTab.ACCOUNT -> TrackerAccountInlineContent(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                )
                AniListWindowTab.HISTORY -> AniListHistoryContent(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                )
            }
        }
    }

    TrackerDisconnectConfirmDialog(
        uiState = uiState,
        onEvent = viewModel::onEvent,
    )
}

@Composable
private fun TrackerAutomationSettingsCard(
    uiState: LibraryTrackerUiState,
    brandColor: Color,
    onEvent: (LibraryTrackerUiEvent) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = theme.SurfaceCard.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Auto-Track Watch Progress",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.TextPrimary,
                    )
                    Text(
                        text = "Automatically update ${uiState.trackerDisplayName} progress when playback finishes (${uiState.autoSyncThresholdPercent}% watched).",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                    )
                }
                Switch(
                    checked = uiState.autoSyncEnabled,
                    onCheckedChange = { onEvent(LibraryTrackerUiEvent.OnSetAutoSync(it)) },
                )
            }

            HorizontalDivider(color = theme.Divider.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Title Matching Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.TextPrimary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val policyDescriptions = mapOf(
                        TrackerBindPolicy.STRICT_AUTO_AND_PINNED to "Auto-bind verified IDs and clear matches",
                        TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY to "Auto-bind verified external IDs only",
                        TrackerBindPolicy.MANUAL_PIN_ONLY to "Always require manual confirmation",
                    )
                    TrackerBindPolicy.entries.forEach { policy ->
                        val selected = uiState.bindPolicy == policy
                        val shortLabel = when (policy) {
                            TrackerBindPolicy.STRICT_AUTO_AND_PINNED -> "Automatic"
                            TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY -> "Verified IDs"
                            TrackerBindPolicy.MANUAL_PIN_ONLY -> "Manual Only"
                        }
                        Surface(
                            onClick = { onEvent(LibraryTrackerUiEvent.OnSetBindPolicy(policy)) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) brandColor.copy(alpha = 0.16f) else theme.SurfaceElevated.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (selected) brandColor else theme.Divider.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = shortLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (selected) brandColor else theme.TextPrimary,
                                )
                                Text(
                                    text = policyDescriptions[policy].orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.TextMuted,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = theme.Divider.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Sync Threshold & Undo Grace Window",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.TextPrimary,
                    )
                    Text(
                        text = "Wait 8 seconds with an Undo option before committing auto-tracked episodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(80, 85, 90).forEach { pct ->
                        val active = uiState.autoSyncThresholdPercent == pct
                        Surface(
                            onClick = { onEvent(LibraryTrackerUiEvent.OnSetAutoSyncThreshold(pct)) },
                            shape = chipShape,
                            color = if (active) brandColor.copy(alpha = 0.18f) else theme.SurfaceElevated.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (active) brandColor else theme.Divider.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) brandColor else theme.TextPrimary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Switch(
                        checked = uiState.confirmBeforeSync,
                        onCheckedChange = { onEvent(LibraryTrackerUiEvent.OnSetConfirmBeforeSync(it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackerAccountInlineContent(
    uiState: LibraryTrackerUiState,
    onEvent: (LibraryTrackerUiEvent) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val brandColor = Color(uiState.brandColorHex)
    var showTokenText by remember { mutableStateOf(false) }
    var showSecretText by remember { mutableStateOf(false) }
    var showReconnectForm by remember(uiState.trackerId, uiState.isAnilistConnected) {
        mutableStateOf(!uiState.isAnilistConnected)
    }
    val hasCustomCredentialOverride = uiState.savedClientId.isNotBlank() || uiState.savedClientSecret.isNotBlank()
    var showAdvanced by remember(uiState.trackerId, uiState.hasEffectiveClientId) {
        mutableStateOf(!uiState.hasEffectiveClientId || hasCustomCredentialOverride)
    }
    LaunchedEffect(uiState.trackerId, hasCustomCredentialOverride) {
        if (hasCustomCredentialOverride) {
            showAdvanced = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(end = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.isAnilistConnected) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = theme.SurfaceCard.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            val avatarUrl = uiState.anilistUser?.profilePicture
                            if (!avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = uiState.anilistUser.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(CircleShape),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = uiState.anilistUser?.name ?: "${uiState.trackerDisplayName} User",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.TextPrimary,
                                )
                                Text(
                                    text = "${uiState.trackerDisplayName} ID ${uiState.anilistUser?.id ?: 0}  •  Profile '${uiState.activeProfileName}'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.TextMuted,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DesktopOutlinedButton(
                                onClick = { showReconnectForm = !showReconnectForm },
                                modifier = Modifier.height(40.dp),
                            ) {
                                Text(if (showReconnectForm) "Hide Switch Form" else "Switch Account")
                            }
                            DesktopOutlinedButton(
                                onClick = { onEvent(LibraryTrackerUiEvent.OnRequestDisconnect) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.height(40.dp),
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            TrackerAutomationSettingsCard(
                uiState = uiState,
                brandColor = brandColor,
                onEvent = onEvent,
            )

            if (showReconnectForm) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = theme.SurfaceCard.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "One-Click ${uiState.trackerDisplayName} Sign-In",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.TextPrimary,
                            )
                            Text(
                                text = "Authorize CloudStream Desktop without manually copying OAuth tokens. Auto-matching and playback sync behavior can be customized above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.TextMuted,
                            )
                        }

                        if (!uiState.hasEffectiveClientId) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "No embedded ${uiState.trackerDisplayName} Client ID is configured in this build. Expand Advanced below to enter your Client ID (or set ${uiState.missingKeyPropertyHint} in local.properties), or paste a token directly.",
                                    color = Color(0xFFF59E0B),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (uiState.supportsDevicePin) {
                                DesktopButton(
                                    onClick = { onEvent(LibraryTrackerUiEvent.OnStartDevicePin) },
                                    enabled = uiState.hasEffectiveClientId && !uiState.isVerifyingToken,
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (uiState.isPollingPin) "New PIN Code" else "Sign In with Device PIN")
                                }

                                DesktopOutlinedButton(
                                    onClick = { onEvent(LibraryTrackerUiEvent.OnStartBrowserAuth) },
                                    enabled = uiState.hasEffectiveClientId && !uiState.isVerifyingToken,
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (uiState.isAwaitingBrowserAuth) "Relaunch Browser" else "Sign In via Browser")
                                }
                            } else {
                                DesktopButton(
                                    onClick = { onEvent(LibraryTrackerUiEvent.OnStartBrowserAuth) },
                                    enabled = uiState.hasEffectiveClientId && !uiState.isVerifyingToken,
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (uiState.isAwaitingBrowserAuth) "Relaunch Browser" else "Sign In via Browser")
                                }

                                if (uiState.supportsPinFallbackAuth) {
                                    DesktopOutlinedButton(
                                        onClick = {
                                            showAdvanced = true
                                            onEvent(LibraryTrackerUiEvent.OnStartDefaultAuthPage)
                                        },
                                        enabled = uiState.hasEffectiveClientId && !uiState.isVerifyingToken,
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open PIN / Default Auth")
                                    }
                                }
                            }

                            if (uiState.isAwaitingBrowserAuth) {
                                DesktopOutlinedButton(
                                    onClick = { onEvent(LibraryTrackerUiEvent.OnCancelBrowserAuth) },
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Text("Cancel")
                                }
                            }

                            if (uiState.isPollingPin) {
                                DesktopOutlinedButton(
                                    onClick = { onEvent(LibraryTrackerUiEvent.OnCancelDevicePin) },
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Text("Cancel PIN")
                                }
                            }

                            if (uiState.isVerifyingToken || uiState.isAwaitingBrowserAuth || uiState.isPollingPin) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }

                        if (uiState.pinCode != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = brandColor.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, brandColor.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Device PIN Code",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = theme.TextMuted,
                                        )
                                        Text(
                                            text = uiState.pinCode,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.TextPrimary,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DesktopOutlinedButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(uiState.pinCode))
                                                AppToastManager.showSuccess("PIN code copied")
                                            },
                                            modifier = Modifier.height(40.dp),
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy Code")
                                        }
                                        val pinUrl = uiState.pinVerificationUrl ?: "https://simkl.com/pin"
                                        DesktopButton(
                                            onClick = { ExternalLinkHandler.launchSystemBrowser(pinUrl) },
                                            modifier = Modifier.height(40.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Open SIMKL PIN Page")
                                        }
                                    }
                                }
                            }
                        }

                        uiState.verificationError?.let { error ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        uiState.verificationSuccessMessage?.let { success ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = success,
                                    color = Color(0xFF10B981),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        HorizontalDivider(color = theme.Divider.copy(alpha = 0.45f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DesktopTextButton(
                                onClick = { showAdvanced = !showAdvanced },
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Advanced / Custom Client ID & Manual Token")
                            }

                            DesktopTextButton(
                                onClick = { onEvent(LibraryTrackerUiEvent.OnOpenDeveloperPortal) },
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(uiState.developerPortalLabel, fontSize = 12.sp)
                            }
                        }

                        AnimatedVisibility(visible = showAdvanced) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Loopback Redirect URI: ${uiState.loopbackRedirectUri}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.TextMuted,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = uiState.clientIdInput,
                                        onValueChange = { onEvent(LibraryTrackerUiEvent.OnClientIdInputChange(it)) },
                                        label = { Text("Custom Client ID (optional)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    DesktopOutlinedButton(
                                        onClick = { onEvent(LibraryTrackerUiEvent.OnSaveClientId) },
                                        enabled = uiState.clientIdInput.trim() != uiState.savedClientId,
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Text("Save ID")
                                    }
                                }

                                if (uiState.supportsClientSecret) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = uiState.clientSecretInput,
                                            onValueChange = { onEvent(LibraryTrackerUiEvent.OnClientSecretInputChange(it)) },
                                            label = { Text(uiState.clientSecretLabel) },
                                            singleLine = true,
                                            visualTransformation = if (showSecretText) VisualTransformation.None else PasswordVisualTransformation(),
                                            trailingIcon = {
                                                IconButton(onClick = { showSecretText = !showSecretText }) {
                                                    Icon(
                                                        imageVector = if (showSecretText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = "Toggle visibility",
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        DesktopOutlinedButton(
                                            onClick = { onEvent(LibraryTrackerUiEvent.OnSaveClientSecret) },
                                            enabled = uiState.clientSecretInput.trim() != uiState.savedClientSecret,
                                            modifier = Modifier.height(40.dp),
                                        ) {
                                            Text("Save Secret")
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = uiState.tokenInput,
                                        onValueChange = { onEvent(LibraryTrackerUiEvent.OnTokenInputChange(it)) },
                                        label = { Text("Access token or redirect URL (fallback)") },
                                        singleLine = true,
                                        visualTransformation = if (showTokenText) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { showTokenText = !showTokenText }) {
                                                Icon(
                                                    imageVector = if (showTokenText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = "Toggle visibility",
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    DesktopButton(
                                        onClick = { onEvent(LibraryTrackerUiEvent.OnVerifyAndConnect) },
                                        enabled = uiState.tokenInput.isNotBlank() && !uiState.isVerifyingToken,
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Verify Token")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        DesktopVerticalScrollbar(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
fun TrackerDisconnectConfirmDialog(
    uiState: LibraryTrackerUiState,
    onEvent: (LibraryTrackerUiEvent) -> Unit,
) {
    CloudstreamAlertDialog(
        show = uiState.showDisconnectConfirm,
        onDismissRequest = { onEvent(LibraryTrackerUiEvent.OnDismissDisconnectConfirm) },
        title = {
            Text(
                text = "Disconnect ${uiState.trackerDisplayName}?",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "Remove ${uiState.trackerDisplayName} account for '${uiState.activeProfileName}'?",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            DesktopButton(
                onClick = { onEvent(LibraryTrackerUiEvent.OnConfirmDisconnect) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                ),
                modifier = Modifier.height(40.dp),
            ) {
                Text("Disconnect")
            }
        },
        dismissButton = {
            DesktopOutlinedButton(
                onClick = { onEvent(LibraryTrackerUiEvent.OnDismissDisconnectConfirm) },
                modifier = Modifier.height(40.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}
