package com.lagradost.cloudstream3.desktop.ui.screens.library.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.tracking.SyncState
import com.lagradost.cloudstream3.desktop.tracking.TrackerSyncAuditEntry
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.AuditFilterOption
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerUiState
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AniListHistoryContent(
    uiState: LibraryTrackerUiState,
    onEvent: (LibraryTrackerUiEvent) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val listState = rememberLazyListState()
    val chipShape = LocalDesktopButtonShapes.current.compact

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuditFilterOption.entries.forEach { option ->
                    FilterChip(
                        selected = uiState.auditFilter == option,
                        onClick = { onEvent(LibraryTrackerUiEvent.OnSelectAuditFilter(option)) },
                        shape = chipShape,
                        label = {
                            Text(
                                text = option.label,
                                fontWeight = if (uiState.auditFilter == option) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            DesktopOutlinedButton(
                onClick = { onEvent(LibraryTrackerUiEvent.OnRefreshAudit) },
                modifier = Modifier.height(40.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isLoadingAudit && uiState.filteredAuditEntries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.filteredAuditEntries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No sync history yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.TextMuted,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.filteredAuditEntries, key = { it.id }) { entry ->
                            AuditEntryRow(entry = entry)
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
}

@Composable
private fun AuditEntryRow(entry: TrackerSyncAuditEntry) {
    val theme = LocalDesktopTheme.current
    val formattedTime = remember(entry.timestamp) {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(entry.timestamp))
    }

    val (badgeLabel, badgeColor) = when (entry.state) {
        SyncState.SENT -> "SYNCED" to Color(0xFF10B981)
        SyncState.BLOCKED -> "BLOCKED" to Color(0xFFF59E0B)
        SyncState.CANCELLED -> "CANCELLED" to Color(0xFF94A3B8)
        SyncState.FAILED -> "FAILED" to Color(0xFFEF4444)
        SyncState.PENDING -> "PENDING" to Color(0xFF02A9FF)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = theme.SurfaceCard.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = entry.showName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progressText = buildString {
                    append("AniList #${entry.remoteMediaId}")
                    append("  •  Ep ${entry.episodeNum}")
                    if (entry.effectiveRemoteEpisode != entry.episodeNum) {
                        append(" -> Remote ${entry.effectiveRemoteEpisode}")
                    }
                    append("  •  Progress ${entry.oldProgress} -> ${entry.newProgress}")
                    entry.statusApplied?.let { append(" ($it)") }
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.reason.isNullOrBlank()) {
                    Text(
                        text = entry.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.state == SyncState.BLOCKED || entry.state == SyncState.FAILED) badgeColor else theme.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = theme.TextMuted,
            )
        }
    }
}
