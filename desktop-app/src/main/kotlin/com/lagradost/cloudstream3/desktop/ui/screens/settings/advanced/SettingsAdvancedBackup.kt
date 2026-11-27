package com.lagradost.cloudstream3.desktop.ui.screens.settings.advanced

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.backup.*
import com.lagradost.cloudstream3.desktop.ui.components.*
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsAdvancedBackup(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val backupState = uiState.backupState
    val scope = rememberCoroutineScope()
    val isBusy = backupState.isCreatingBackup || backupState.isInspectingBackup || backupState.isRestoringBackup

    SettingsGroupCard(title = "Backup & Restore", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Create portable .cs3backup archives containing your database (profiles, watch history progress, bookmarks, settings, cloned sites, tracker bindings), repository URLs (repos.json), installed extensions, avatars, and cookies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopButton(
                    onClick = { viewModel.onEvent(SettingsUiEvent.OpenExportBackupDialog) },
                    enabled = !isBusy,
                    modifier = Modifier.height(40.dp),
                ) {
                    if (backupState.isCreatingBackup) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Export Backup")
                }

                DesktopOutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val chosen = showNativeBackupFilePicker(
                                title = "Select CloudStream Backup (.cs3backup)",
                                mode = FileDialog.LOAD,
                                defaultFileName = null,
                            )
                            if (chosen != null) {
                                viewModel.onEvent(SettingsUiEvent.InspectBackupFile(chosen.absolutePath))
                            }
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.height(40.dp),
                ) {
                    if (backupState.isInspectingBackup || backupState.isRestoringBackup) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from File...")
                }

                DesktopFilledTonalButton(
                    onClick = { viewModel.onEvent(SettingsUiEvent.CreateBackup()) },
                    enabled = !isBusy,
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Quick Snapshot")
                }

                DesktopIconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                java.awt.Desktop.getDesktop().open(DesktopBackupEngine.getBackupsRootDir())
                            } catch (e: Exception) {
                                AppToastManager.showError("Failed to open backups folder: ${e.message}")
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Open Backups Folder", modifier = Modifier.size(20.dp))
                }
            }

            val statusMsg = backupState.lastStatusMessage
            if (!statusMsg.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (backupState.lastStatusIsError) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (backupState.lastStatusIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        backupState.lastRestoreWarnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (backupState.availableBackups.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Saved & Automatic Snapshots (${backupState.availableBackups.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DesktopIconButton(
                        onClick = { viewModel.onEvent(SettingsUiEvent.LoadBackupsList) },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Backups", modifier = Modifier.size(18.dp))
                    }
                }

                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(backupState.availableBackups, key = { it.filePath }) { entry ->
                            BackupArchiveRow(
                                entry = entry,
                                enabled = !isBusy,
                                onInspect = { viewModel.onEvent(SettingsUiEvent.InspectBackupFile(entry.filePath)) },
                            )
                        }
                    }
                    DesktopVerticalScrollbar(
                        listState = listState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    if (backupState.showExportDialog) {
        BackupExportOptionsDialog(
            options = backupState.exportOptions,
            isCreating = backupState.isCreatingBackup,
            onOptionsChange = { viewModel.onEvent(SettingsUiEvent.UpdateExportOptions(it)) },
            onSaveDefault = { viewModel.onEvent(SettingsUiEvent.CreateBackup(targetPath = null, options = backupState.exportOptions)) },
            onSaveAs = {
                scope.launch(Dispatchers.IO) {
                    val chosen = showNativeBackupFilePicker(
                        title = "Export CloudStream Backup",
                        mode = FileDialog.SAVE,
                        defaultFileName = DesktopBackupEngine.defaultBackupFileName(),
                    )
                    if (chosen != null) {
                        viewModel.onEvent(SettingsUiEvent.CreateBackup(targetPath = chosen.absolutePath, options = backupState.exportOptions))
                    }
                }
            },
            onDismiss = { viewModel.onEvent(SettingsUiEvent.DismissExportBackupDialog) },
        )
    }

    val pendingInspection = backupState.pendingRestoreInspection
    if (pendingInspection != null) {
        BackupRestoreConfirmDialog(
            inspection = pendingInspection,
            options = backupState.restoreOptions,
            isRestoring = backupState.isRestoringBackup,
            onOptionsChange = { viewModel.onEvent(SettingsUiEvent.UpdateRestoreOptions(it)) },
            onConfirm = { viewModel.onEvent(SettingsUiEvent.ConfirmRestoreBackup) },
            onDismiss = { viewModel.onEvent(SettingsUiEvent.DismissRestoreDialog) },
        )
    }
}

@Composable
private fun BackupArchiveRow(
    entry: BackupArchiveEntry,
    enabled: Boolean,
    onInspect: () -> Unit,
) {
    val kindLabel = when (entry.kind) {
        BackupKind.MANUAL -> "Manual"
        BackupKind.AUTO_STARTUP -> "Auto Daily"
        BackupKind.PRE_UPDATE -> "Pre-Update"
        BackupKind.PRE_RESTORE -> "Pre-Restore"
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    ) {
                        Text(
                            text = kindLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = entry.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val countsSummary = entry.counts?.let { c ->
                    "${c.profiles} profiles • ${c.bookmarks} bookmarks • ${c.watchHistoryEntries} progress • ${c.repositories} repos • ${c.extensions} extensions"
                } ?: formatBackupBytes(entry.sizeBytes)
                Text(
                    text = "${formatTimestamp(entry.lastModifiedMs)} • ${formatBackupBytes(entry.sizeBytes)} • $countsSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            DesktopOutlinedButton(
                onClick = onInspect,
                enabled = enabled,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Restore", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BackupExportOptionsDialog(
    options: BackupOptions,
    isCreating: Boolean,
    onOptionsChange: (BackupOptions) -> Unit,
    onSaveDefault: () -> Unit,
    onSaveAs: () -> Unit,
    onDismiss: () -> Unit,
) {
    CloudstreamCustomDialog(
        show = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f),
    ) {
        val scrollState = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Export Full Backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "Choose what to include in the portable .cs3backup archive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BackupOptionRow(
                        title = "Database (Profiles, Watch Progress, Bookmarks, Settings & Cloned Sites)",
                        subtitle = "Exports a consistent SQLite snapshot plus portable JSON covering profiles, history, tracker bindings, downloads metadata, and custom provider links.",
                        checked = options.includeDatabase,
                        onCheckedChange = { onOptionsChange(options.copy(includeDatabase = it)) },
                    )
                    BackupOptionRow(
                        title = "Include Saved Accounts & API Tokens",
                        subtitle = "Includes tracker login tokens and third-party API keys inside this archive. Disable before sharing a backup file with someone else.",
                        checked = options.includeCredentials,
                        enabled = options.includeDatabase,
                        onCheckedChange = { onOptionsChange(options.copy(includeCredentials = it)) },
                    )
                    BackupOptionRow(
                        title = "Extension Repositories (repos.json & Catalog Caches)",
                        subtitle = "Preserves repository JSON URLs and cached plugin catalogs.",
                        checked = options.includeRepositories,
                        onCheckedChange = { onOptionsChange(options.copy(includeRepositories = it)) },
                    )
                    BackupOptionRow(
                        title = "Installed Extensions (.jar / .cs3 Binaries)",
                        subtitle = "Bundles exact installed plugin files for offline-safe recovery even if an upstream repository goes offline.",
                        checked = options.includeExtensionBinaries,
                        onCheckedChange = { onOptionsChange(options.copy(includeExtensionBinaries = it)) },
                    )
                    BackupOptionRow(
                        title = "Profile Avatars, Custom Fonts & Cookies",
                        subtitle = "Includes profile avatar images, user-added fonts, and saved site clearance cookies.",
                        checked = options.includeAssetsAndCookies,
                        onCheckedChange = { onOptionsChange(options.copy(includeAssetsAndCookies = it)) },
                    )
                }
                DesktopVerticalScrollbar(scrollState = scrollState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopTextButton(onClick = onDismiss, enabled = !isCreating, modifier = Modifier.height(40.dp)) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(10.dp))
                DesktopOutlinedButton(onClick = onSaveDefault, enabled = !isCreating, modifier = Modifier.height(40.dp)) {
                    Text("Save in Backups Folder")
                }
                Spacer(Modifier.width(10.dp))
                DesktopButton(onClick = onSaveAs, enabled = !isCreating, modifier = Modifier.height(40.dp)) {
                    Text("Choose Save Location...")
                }
            }
        }
    }
}

@Composable
private fun BackupRestoreConfirmDialog(
    inspection: BackupInspection,
    options: RestoreOptions,
    isRestoring: Boolean,
    onOptionsChange: (RestoreOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = inspection.manifest.counts
    val scrollState = rememberScrollState()

    CloudstreamCustomDialog(
        show = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Inspect & Restore Backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "File: ${File(inspection.filePath).name} • App v${inspection.manifest.appVersion} • ${formatTimestamp(inspection.manifest.createdAtEpochMs)} • SHA-256 verified",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Verified Archive Contents", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Profiles: ${c.profiles} • Bookmarks: ${c.bookmarks} • Watch Progress: ${c.watchHistoryEntries} • Tracker Bindings: ${c.trackerBindings} • Cloned Sites: ${c.clonedSites}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Repositories: ${c.repositories} • Extensions Listed: ${c.extensions} (binaries bundled: ${inspection.extensions.count { it.binaryBundled }}) • Avatars: ${c.avatars} • Custom Fonts: ${c.customFonts}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    BackupOptionRow(
                        title = "Restore Database (Profiles, Watch Progress, Bookmarks, Settings & Cloned Sites)",
                        subtitle = if (inspection.hasSqliteDatabase || inspection.hasPortableJson) "Available in archive" else "Not included in this archive",
                        checked = options.restoreDatabase && (inspection.hasSqliteDatabase || inspection.hasPortableJson),
                        enabled = inspection.hasSqliteDatabase || inspection.hasPortableJson,
                        onCheckedChange = { onOptionsChange(options.copy(restoreDatabase = it)) },
                    )
                    BackupOptionRow(
                        title = "Restore Saved Accounts & API Keys",
                        subtitle = if (inspection.hasCredentialsInBackup) "Archive includes saved accounts/keys" else "No saved tokens stored in this archive",
                        checked = options.restoreCredentials && inspection.hasCredentialsInBackup,
                        enabled = options.restoreDatabase && inspection.hasCredentialsInBackup,
                        onCheckedChange = { onOptionsChange(options.copy(restoreCredentials = it)) },
                    )
                    BackupOptionRow(
                        title = "Restore Extension Repositories (repos.json URLs)",
                        subtitle = if (inspection.repositoryUrls.isNotEmpty()) {
                            inspection.repositoryUrls.joinToString(", ")
                        } else {
                            "No repositories stored in archive"
                        },
                        checked = options.restoreRepositories && inspection.hasRepositories,
                        enabled = inspection.hasRepositories,
                        onCheckedChange = { onOptionsChange(options.copy(restoreRepositories = it)) },
                    )
                    val hasBundledBinaries = inspection.extensions.any { it.binaryBundled }
                    BackupOptionRow(
                        title = "Restore Installed Extensions (.jar / .cs3 Binaries)",
                        subtitle = if (hasBundledBinaries) {
                            "${inspection.extensions.count { it.binaryBundled }} plugin package(s) bundled offline"
                        } else {
                            "Fast snapshot without plugin binaries (sync from restored repository URLs after restore)"
                        },
                        checked = options.restoreExtensionBinaries && hasBundledBinaries,
                        enabled = hasBundledBinaries,
                        onCheckedChange = { onOptionsChange(options.copy(restoreExtensionBinaries = it)) },
                    )
                    BackupOptionRow(
                        title = "Restore Profile Avatars, Custom Fonts & Cookies",
                        subtitle = "Avatars will be remapped automatically to your active data folder.",
                        checked = options.restoreAssetsAndCookies,
                        onCheckedChange = { onOptionsChange(options.copy(restoreAssetsAndCookies = it)) },
                    )

                    Text(
                        text = "Safety guarantee: an automatic pre_restore snapshot is created before any live data is replaced, and any error triggers automatic rollback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DesktopVerticalScrollbar(scrollState = scrollState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopTextButton(onClick = onDismiss, enabled = !isRestoring, modifier = Modifier.height(40.dp)) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(12.dp))
                DesktopButton(onClick = onConfirm, enabled = !isRestoring, modifier = Modifier.height(40.dp)) {
                    if (isRestoring) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Restore Selected")
                }
            }
        }
    }
}

@Composable
private fun BackupOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun showNativeBackupFilePicker(
    title: String,
    mode: Int,
    defaultFileName: String?,
): File? {
    var result: File? = null
    val runner = {
        val dialog = FileDialog(null as Frame?, title, mode).apply {
            directory = DesktopBackupEngine.getBackupsRootDir().absolutePath
            if (defaultFileName != null) {
                file = defaultFileName
            }
            filenameFilter = FilenameFilter { _, name ->
                name.endsWith(".${DesktopBackupEngine.BACKUP_EXTENSION}", ignoreCase = true)
            }
            isVisible = true
        }
        val selectedFile = dialog.file
        val selectedDir = dialog.directory
        if (!selectedFile.isNullOrBlank() && !selectedDir.isNullOrBlank()) {
            result = File(selectedDir, selectedFile)
        }
        dialog.dispose()
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        runner()
    } else {
        javax.swing.SwingUtilities.invokeAndWait(runner)
    }
    return result
}

private fun formatBackupBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
