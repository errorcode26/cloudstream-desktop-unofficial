package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.backup.DesktopBackupEngine
import com.lagradost.cloudstream3.desktop.init.CrashRecoveryManager
import com.lagradost.common.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Startup dialog displayed when an abnormal termination or crash is detected from the prior session.
 * Uses shared CloudstreamAlertDialog wrapper to ensure strict Amoled/Dark consistency.
 */
@Composable
fun CrashRecoveryDialog() {
    val quarantinedFile = DatabaseFactory.lastQuarantinedBackupFile
    if (!CrashRecoveryManager.hasCrashDetected && quarantinedFile == null) return
    val scope = rememberCoroutineScope()

    CloudstreamAlertDialog(
        show = true,
        onDismissRequest = {
            CrashRecoveryManager.dismiss()
            DatabaseFactory.clearLastQuarantinedBackupNotice()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = if (quarantinedFile != null) "Database Recovery Triggered" else "Previous Session Ended Unexpectedly",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (quarantinedFile != null) {
                        "CloudStream Desktop detected SQLite corruption on startup and quarantined your previous database to ${quarantinedFile.name}. You can restore from an automatic safety backup under Settings -> Advanced -> Backup & Restore."
                    } else {
                        "CloudStream Desktop detected that your last session closed unexpectedly or crashed. You can copy the full diagnostics to your clipboard, open the logs folder, or check your saved backups under Settings -> Advanced -> Backup & Restore."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopOutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                java.awt.Desktop.getDesktop().open(DesktopBackupEngine.getBackupsRootDir())
                            }.onFailure {
                                AppToastManager.showError("Could not open backups folder")
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Backup,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Backups")
                }

                DesktopOutlinedButton(
                    onClick = {
                        if (openLogsFolder()) {
                            AppToastManager.showInfo("Opened logs folder in Explorer")
                        } else {
                            AppToastManager.showError("Could not open logs folder")
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Open Logs")
                }

                DesktopButton(
                    onClick = {
                        if (copyDiagnosticLogsToClipboard()) {
                            AppToastManager.showSuccess("Full diagnostic logs copied to clipboard")
                        } else {
                            AppToastManager.showError("Failed to copy crash logs")
                        }
                        CrashRecoveryManager.dismiss()
                        DatabaseFactory.clearLastQuarantinedBackupNotice()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Full Logs")
                }
            }
        },
        dismissButton = {
            DesktopTextButton(
                onClick = {
                    CrashRecoveryManager.dismiss()
                    DatabaseFactory.clearLastQuarantinedBackupNotice()
                },
            ) {
                Text("Dismiss")
            }
        },
    )
}
