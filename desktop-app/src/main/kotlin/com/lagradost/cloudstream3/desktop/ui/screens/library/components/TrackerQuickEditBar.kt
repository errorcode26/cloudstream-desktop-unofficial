package com.lagradost.cloudstream3.desktop.ui.screens.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryTrackerQuickEdit
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

@Composable
fun TrackerQuickEditBar(
    quickEdit: LibraryTrackerQuickEdit,
    descriptorsById: Map<String, TrackerDescriptor>,
    onEvent: (LibraryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val entry = quickEdit.entry
    val descriptor = quickEdit.descriptor
    val brandColor = Color(descriptor.accentColorHex)
    val bounds = entry.releaseBounds
    val ceiling = bounds.ceilingEpisode

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = theme.SurfaceCard.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, brandColor.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = buildList {
                        add("${descriptor.displayName} #${entry.remoteMediaId}")
                        bounds.statusBadgeText?.let { add(it) }
                    }.joinToString("  •  ")
                    Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = theme.TextMuted)
                }

                if (quickEdit.availableEntries.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        quickEdit.availableEntries.forEach { candidate ->
                            val candidateDesc = descriptorsById[candidate.providerId]
                            val isSelected = candidate.providerId == entry.providerId
                            val accent = candidateDesc?.let { Color(it.accentColorHex) } ?: theme.Accent
                            FilterChip(
                                selected = isSelected,
                                enabled = !quickEdit.isSaving,
                                onClick = { onEvent(LibraryUiEvent.OnSwitchQuickEditTracker(candidate.providerId)) },
                                shape = chipShape,
                                label = { Text(candidateDesc?.shortTag ?: candidate.providerId) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }

                DesktopIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close Quick Edit",
                    onClick = { onEvent(LibraryUiEvent.OnDismissQuickEdit) },
                )
            }

            quickEdit.errorMessage?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnifiedWatchStatus.supportedStatusesFor(entry.providerId, entry.isMovie).forEach { statusEnum ->
                    val selected = quickEdit.status == statusEnum
                    val lockedCompleted = statusEnum == UnifiedWatchStatus.COMPLETED && !bounds.canMarkCompleted
                    Surface(
                        onClick = { if (!lockedCompleted) onEvent(LibraryUiEvent.OnEditQuickStatus(statusEnum)) },
                        enabled = !lockedCompleted && !quickEdit.isSaving,
                        shape = chipShape,
                        color = if (selected) theme.Accent.copy(alpha = 0.16f) else theme.SurfaceElevated.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, if (selected) theme.Accent else theme.Divider.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            if (lockedCompleted) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = theme.TextMuted.copy(alpha = 0.45f), modifier = Modifier.size(12.dp))
                            }
                            Text(
                                text = statusEnum.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (lockedCompleted) theme.TextMuted.copy(alpha = 0.45f) else if (selected) theme.Accent else theme.TextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.isMovie) {
                    val isMovieWatched = quickEdit.progress >= 1
                    Surface(
                        onClick = {
                            val nextWatched = !isMovieWatched
                            if (nextWatched) {
                                if (quickEdit.status == UnifiedWatchStatus.PLAN_TO_WATCH) {
                                    onEvent(LibraryUiEvent.OnEditQuickStatus(UnifiedWatchStatus.COMPLETED))
                                } else {
                                    onEvent(LibraryUiEvent.OnEditQuickProgress(1))
                                }
                            } else {
                                if (quickEdit.status == UnifiedWatchStatus.COMPLETED) {
                                    onEvent(LibraryUiEvent.OnEditQuickStatus(UnifiedWatchStatus.PLAN_TO_WATCH))
                                } else {
                                    onEvent(LibraryUiEvent.OnEditQuickProgress(0))
                                }
                            }
                        },
                        enabled = !quickEdit.isSaving && (!bounds.isNotYetReleased || isMovieWatched),
                        shape = chipShape,
                        color = if (isMovieWatched) brandColor.copy(alpha = 0.18f) else theme.SurfaceElevated.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, if (isMovieWatched) brandColor else theme.Divider.copy(alpha = 0.45f)),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = if (isMovieWatched) brandColor else theme.TextMuted, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (isMovieWatched) "Watched" else "Unwatched",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMovieWatched) brandColor else theme.TextPrimary,
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DesktopOutlinedButton(
                            onClick = { onEvent(LibraryUiEvent.OnEditQuickProgress(quickEdit.progress - 1)) },
                            enabled = quickEdit.progress > 0 && !quickEdit.isSaving,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        val totalTxt = entry.totalEpisodes?.let { " / $it" } ?: ""
                        Text(
                            text = "Ep ${quickEdit.progress}$totalTxt",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = theme.TextPrimary,
                        )
                        DesktopOutlinedButton(
                            onClick = { onEvent(LibraryUiEvent.OnEditQuickProgress(quickEdit.progress + 1)) },
                            enabled = !bounds.isNotYetReleased && (ceiling == null || quickEdit.progress < ceiling) && !quickEdit.isSaving,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(15.dp))
                    Surface(
                        onClick = { onEvent(LibraryUiEvent.OnEditQuickScore(0)) },
                        enabled = !quickEdit.isSaving,
                        shape = chipShape,
                        color = if (quickEdit.score100 == 0) theme.SurfaceElevated else theme.SurfaceCard,
                        border = BorderStroke(1.dp, if (quickEdit.score100 == 0) theme.Accent else theme.Divider.copy(alpha = 0.4f)),
                    ) {
                        Text("0", style = MaterialTheme.typography.labelSmall, color = theme.TextMuted, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                    (1..10).forEach { s ->
                        val active = quickEdit.score100 == s * 10
                        Surface(
                            onClick = { onEvent(LibraryUiEvent.OnEditQuickScore(s * 10)) },
                            enabled = !quickEdit.isSaving,
                            shape = chipShape,
                            color = if (active) Color(0xFFFBBF24).copy(alpha = 0.22f) else theme.SurfaceElevated.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, if (active) Color(0xFFFBBF24) else theme.Divider.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                "$s",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) Color(0xFFFBBF24) else theme.TextPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                if (descriptor.supportsFavorites) {
                    DesktopOutlinedButton(
                        onClick = { onEvent(LibraryUiEvent.OnToggleQuickFavorite) },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(
                            imageVector = if (quickEdit.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (quickEdit.isFavourite) Color(0xFFFB7185) else theme.TextPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (descriptor.supportsLibraryRemoval) {
                    DesktopOutlinedButton(
                        onClick = { onEvent(LibraryUiEvent.OnRequestRemoveTrackerEntry(entry)) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove")
                    }
                }

                DesktopButton(
                    onClick = { onEvent(LibraryUiEvent.OnSaveQuickEdit) },
                    enabled = !quickEdit.isSaving,
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (quickEdit.isSaving) "Saving..." else "Save")
                }
            }
        }
    }
}
