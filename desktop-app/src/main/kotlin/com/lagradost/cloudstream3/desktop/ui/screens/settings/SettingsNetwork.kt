package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.settings.network.DnsSettingsSection
import com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManageCookiesDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManageIgnoredDomainsDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManualClearanceDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.network.TorrentEngineSection
import com.lagradost.common.storage.DesktopDataStore

@Composable
fun SettingsNetworkScreen(viewModel: SettingsViewModel) {
    val scrollState = rememberScrollState()
    var containerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is SettingsUiEffect.ShowToast -> {
                    if (effect.isError) {
                        AppToastManager.showError(effect.message)
                    } else {
                        AppToastManager.showInfo(effect.message)
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalSettingsScrollState provides scrollState,
        LocalScrollContainerCoordinates provides containerCoordinates,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { containerCoordinates = it }
                .verticalScroll(scrollState)
                .padding(top = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsNetwork(viewModel = viewModel)
        }
    }
}

@Composable
fun SettingsNetwork(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var containerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var showCookiesDialog by remember { mutableStateOf(false) }
    var showManualSolverDialog by remember { mutableStateOf(false) }
    var showIgnoredDomainsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerCoordinates = it },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DnsSettingsSection(
            uiState = uiState,
            onEvent = viewModel::onEvent,
        )

        SettingsGroupCard(title = "Experimental & Scraper Engine") {
            Text(
                text = "Advanced network resolution options for providers and scraping.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            MviSettingsToggle(
                key = DesktopDataStore.PREF_ALLOW_CF_BYPASS,
                label = "Experimental Cloudflare Solver",
                subtitle = "Resolves Cloudflare Turnstile challenges using the embedded in-app WebView2 engine when required by a source.",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )

            val cfEnabled = uiState.booleanSettings[DesktopDataStore.PREF_ALLOW_CF_BYPASS] ?: false

            if (cfEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Saved Cookies & Clearance",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Inspect active provider domains, clear stale tokens, or solve Cloudflare challenges manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showManualSolverDialog = true },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Solve Manually")
                        }

                        Button(
                            onClick = { showCookiesDialog = true },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Manage Cookies")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ignored Verification Sites",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Sites configured with 'Don't ask again'. Challenge popups are permanently suppressed for these domains.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    OutlinedButton(
                        onClick = { showIgnoredDomainsDialog = true },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Box { Text("Manage Ignored Sites") }
                    }
                }
            }
        }

        TorrentEngineSection(
            uiState = uiState,
            onEvent = viewModel::onEvent,
        )
    }

    ManageCookiesDialog(
        show = showCookiesDialog,
        onDismiss = { showCookiesDialog = false },
        viewModel = viewModel,
    )

    ManageIgnoredDomainsDialog(
        show = showIgnoredDomainsDialog,
        onDismiss = { showIgnoredDomainsDialog = false },
        viewModel = viewModel,
    )

    ManualClearanceDialog(
        show = showManualSolverDialog,
        onDismiss = { showManualSolverDialog = false },
        viewModel = viewModel,
    )
}

@Composable
fun DnsCustomAndTestSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) = com.lagradost.cloudstream3.desktop.ui.screens.settings.network.DnsCustomAndTestSection(
    uiState = uiState,
    onEvent = viewModel::onEvent,
)

@Composable
fun ManageCookiesDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) = com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManageCookiesDialog(
    show = show,
    onDismiss = onDismiss,
    viewModel = viewModel,
)

@Composable
fun ManualClearanceDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) = com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManualClearanceDialog(
    show = show,
    onDismiss = onDismiss,
    viewModel = viewModel,
)

@Composable
fun ManageIgnoredDomainsDialog(
    viewModel: SettingsViewModel,
    show: Boolean,
    onDismiss: () -> Unit,
) = com.lagradost.cloudstream3.desktop.ui.screens.settings.network.ManageIgnoredDomainsDialog(
    show = show,
    onDismiss = onDismiss,
    viewModel = viewModel,
)
