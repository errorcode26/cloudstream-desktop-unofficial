package com.lagradost.cloudstream3.desktop.ui.screens.settings.network

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.download.AppDownloadManager
import com.lagradost.cloudstream3.desktop.download.TaskStatus
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.P2pTorrentDisclaimerDialog
import com.lagradost.cloudstream3.desktop.ui.screens.settings.MviSettingsToggle
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsToggleItem
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiState
import com.lagradost.cloudstream3.desktop.updates.UnifiedUpdateManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.util.Locale

@Composable
fun TorrentEngineSection(
    uiState: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    SettingsGroupCard(
        title = "Peer-to-Peer (Torrent) Streaming",
        modifier = modifier,
    ) {
        Text(
            text = "Stream high-bitrate torrents and magnet links directly using the embedded P2P engine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        val p2pEnabled = uiState.booleanSettings[DesktopDataStore.PREF_P2P_ENABLED] ?: false
        var showP2pDisclaimer by remember { mutableStateOf(false) }

        SettingsToggleItem(
            label = "Enable P2P Torrent Streaming",
            subtitle = "Allows playing torrent and magnet streams. When active, your public IP address is visible to other peers in the swarm.",
            checked = p2pEnabled,
            onCheckedChange = { nextVal ->
                if (nextVal) {
                    showP2pDisclaimer = true
                } else {
                    onEvent(SettingsUiEvent.OnUpdateBoolean(DesktopDataStore.PREF_P2P_ENABLED, false))
                }
            },
        )

        P2pTorrentDisclaimerDialog(
            show = showP2pDisclaimer,
            isSettingsContext = true,
            onDismiss = { showP2pDisclaimer = false },
            onConfirm = {
                showP2pDisclaimer = false
                onEvent(SettingsUiEvent.OnUpdateBoolean(DesktopDataStore.PREF_P2P_ENABLED, true))
            },
        )

        if (p2pEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

            MviSettingsToggle(
                key = DesktopDataStore.PREF_P2P_SHOW_HUD,
                label = "Show Live P2P Swarm HUD",
                subtitle = "Display real-time download speed, active seeders, and connected peers on the player overlay.",
                uiState = uiState,
                onEvent = onEvent,
                defaultValue = true,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            val tasks by AppDownloadManager.tasks.collectAsState()
            val torrTask = tasks.firstOrNull { it.id == "torrserver" }
            val engineState = uiState.engineState
            var showDeleteConfirmDialog by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TorrServer Streaming Engine",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                torrTask?.status == TaskStatus.RUNNING -> "Downloading engine... ${torrTask.downloadedMB} / ${torrTask.totalMB} (${torrTask.speedFormatted})"
                                engineState.isInstalled -> "Installed (${UnifiedUpdateManager.getTorrServerInstalledVersion()} • ${String.format(Locale.ROOT, "%.1f", engineState.fileSizeMB)} MB) • Ready to stream"
                                else -> "Not Installed (0 MB) • Downloads on first torrent play or install now"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (engineState.updateFeedback != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = engineState.updateFeedback,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                if (torrTask?.status == TaskStatus.RUNNING) {
                    if (torrTask.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { torrTask.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DesktopTextButton(
                            onClick = { AppDownloadManager.cancelDownload("torrserver") },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Cancel Download")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!engineState.isInstalled) {
                            DesktopButton(
                                onClick = {
                                    DesktopTorrentEngine.binary.downloadWithManager()
                                },
                            ) {
                                Text("Download Engine Now (~28 MB)")
                            }
                        } else {
                            DesktopOutlinedButton(
                                onClick = {
                                    onEvent(SettingsUiEvent.CheckTorrServerUpdates)
                                },
                                enabled = !engineState.isCheckingUpdates,
                            ) {
                                Text(if (engineState.isCheckingUpdates) "Checking..." else "Check for Updates")
                            }

                            DesktopTextButton(
                                onClick = {
                                    DesktopTorrentEngine.binary.downloadWithManager()
                                },
                            ) {
                                Text("Re-download")
                            }

                            DesktopTextButton(
                                onClick = { showDeleteConfirmDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Uninstall")
                            }
                        }

                        DesktopTextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val parent = DesktopTorrentEngine.binary.getBinaryFile().parentFile
                                    parent.mkdirs()
                                    try {
                                        Desktop.getDesktop().open(parent)
                                    } catch (e: Exception) {
                                        AppLogger.e("Failed to open torrent engine folder", e)
                                        AppToastManager.showError("Unable to open folder in system explorer")
                                    }
                                }
                            },
                        ) {
                            Text("Open Folder")
                        }
                    }
                }
            }

            if (showDeleteConfirmDialog) {
                CloudstreamAlertDialog(
                    show = true,
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    title = { Text("Uninstall Torrent Engine?") },
                    text = { Text("This will delete the 28 MB TorrServer executable from your computer to free up space. You can re-download it at any time.") },
                    confirmButton = {
                        DesktopButton(
                            onClick = {
                                showDeleteConfirmDialog = false
                                onEvent(SettingsUiEvent.DeleteTorrServerBinary)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Uninstall")
                        }
                    },
                    dismissButton = {
                        DesktopTextButton(onClick = { showDeleteConfirmDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}
