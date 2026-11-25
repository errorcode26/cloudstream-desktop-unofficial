package com.lagradost.cloudstream3.desktop.ui.screens.links.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
import com.lagradost.cloudstream3.desktop.ui.components.desktopDragScroll
import com.lagradost.cloudstream3.desktop.ui.screens.StreamFormatFilter
import com.lagradost.cloudstream3.desktop.ui.screens.links.contract.StreamSpecFilter

data class SourceOption(
    val name: String,
    val count: Int,
    val isAddon: Boolean = false,
)

data class QualityOption(
    val qualityValue: Int,
    val label: String,
    val count: Int,
)

data class FormatOption(
    val format: StreamFormatFilter,
    val count: Int,
)

@Composable
fun DesktopFilterPill(
    label: String,
    count: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    accentColor: Color = DesktopUi.Accent,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val containerColor = when {
        selected -> accentColor.copy(alpha = 0.22f)
        isHovered -> Color.White.copy(alpha = 0.08f)
        else -> Color(0xFF1E222D).copy(alpha = 0.65f)
    }

    val borderColor = when {
        selected -> accentColor.copy(alpha = 0.75f)
        isHovered -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.10f)
    }

    val textColor = when {
        selected -> Color.White
        isHovered -> Color.White
        else -> Color.White.copy(alpha = 0.85f)
    }

    val pillShape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.compact

    Surface(
        shape = pillShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(32.dp)
            .hoverable(interactionSource)
            .clip(pillShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                leadingIcon()
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = textColor,
            )

            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) accentColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LinksSourceFilterRow(
    selectedPlayer: String,
    onPlayerSelected: (String) -> Unit,
    sourceTabs: List<SourceOption>,
    selectedSource: String?,
    onSourceSelected: (String?) -> Unit,
    providerName: String,
    onOpenPriorityDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Player Selector Toggle (MPV / VLC)
        val switcherShapes = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current
        Surface(
            shape = switcherShapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, DesktopUi.Divider.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("mpv" to "MPV", "vlc" to "VLC").forEach { (id, label) ->
                    val isSel = selectedPlayer == id
                    Surface(
                        shape = switcherShapes.compact,
                        color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .clip(switcherShapes.compact)
                            .clickable { onPlayerSelected(id) },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) MaterialTheme.colorScheme.onPrimary else DesktopUi.TextMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Plugin vs Addons Source Tabs
        val sourceScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(sourceScrollState)
                .desktopDragScroll(sourceScrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sourceTabs.forEach { tab ->
                val isSel = (tab.name == "All Sources" && selectedSource == null) ||
                    (selectedSource != null && selectedSource.equals(tab.name, ignoreCase = true))
                val isPluginTab = !tab.isAddon && tab.name != "All Sources"
                val sourceLogoUrl = remember(tab.name) {
                    if (isPluginTab) {
                        DesktopRepositoryManager.getPluginIcon(providerName)
                    } else if (tab.isAddon) {
                        DesktopRepositoryManager.getPluginIcon(tab.name)
                            ?: StremioAddonManager.addons.value.firstOrNull {
                                it.name.equals(tab.name, ignoreCase = true) ||
                                    tab.name.contains(it.name, ignoreCase = true) ||
                                    it.name.contains(tab.name, ignoreCase = true)
                            }?.logoUrl
                    } else {
                        null
                    }
                }

                DesktopFilterPill(
                    label = tab.name,
                    count = tab.count,
                    selected = isSel,
                    onClick = {
                        onSourceSelected(if (tab.name == "All Sources" || isSel) null else tab.name)
                    },
                    leadingIcon = {
                        if (sourceLogoUrl != null) {
                            AsyncImage(
                                model = sourceLogoUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (tab.isAddon) {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (isSel) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.7f),
                            )
                        } else if (isPluginTab) {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            )
                        }
                    },
                    accentColor = if (tab.isAddon) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Priority Settings Icon Button
        com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton(
            onClick = onOpenPriorityDialog,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "Source Priorities",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun LinksSpecQualityFilterRow(
    totalLinkCount: Int,
    availableQualities: List<QualityOption>,
    selectedQuality: Int?,
    onQualitySelected: (Int?) -> Unit,
    availableSpecs: List<Pair<StreamSpecFilter, Int>>,
    selectedSpecFilter: StreamSpecFilter,
    onSpecSelected: (StreamSpecFilter) -> Unit,
    availableFormats: List<FormatOption>,
    selectedFormat: StreamFormatFilter,
    onFormatSelected: (StreamFormatFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableQualities.isEmpty() && availableSpecs.size <= 1 && availableFormats.size <= 1) return

    val filterScrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .horizontalScroll(filterScrollState)
            .desktopDragScroll(filterScrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (availableQualities.isNotEmpty()) {
            DesktopFilterPill(
                label = "All Qualities",
                count = totalLinkCount,
                selected = selectedQuality == null,
                onClick = { onQualitySelected(null) },
                accentColor = DesktopUi.Accent,
            )

            availableQualities.forEach { option ->
                val isSel = selectedQuality == option.qualityValue
                val pillAccent = when {
                    option.qualityValue >= 2160 -> Color(0xFFF59E0B)
                    option.qualityValue in 1080..2159 -> Color(0xFF3B82F6)
                    option.qualityValue in 720..1079 -> Color(0xFF06B6D4)
                    option.qualityValue in 480..719 -> Color(0xFF94A3B8)
                    else -> DesktopUi.Accent
                }
                DesktopFilterPill(
                    label = option.label,
                    count = option.count,
                    selected = isSel,
                    onClick = { onQualitySelected(if (isSel) null else option.qualityValue) },
                    accentColor = pillAccent,
                )
            }
        }

        if (availableSpecs.size > 1) {
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
                    .background(Color.White.copy(alpha = 0.15f)),
            )

            availableSpecs.filter { it.first != StreamSpecFilter.ALL }.forEach { (spec, count) ->
                val isSel = selectedSpecFilter == spec
                val pillAccent = when (spec) {
                    StreamSpecFilter.ALL -> MaterialTheme.colorScheme.primary
                    StreamSpecFilter.DEBRID -> Color(0xFFF59E0B)
                    StreamSpecFilter.REMUX -> Color(0xFF8B5CF6)
                    StreamSpecFilter.BLURAY -> Color(0xFF3B82F6)
                    StreamSpecFilter.WEBDL -> Color(0xFF10B981)
                }
                DesktopFilterPill(
                    label = spec.label,
                    count = count,
                    selected = isSel,
                    onClick = { onSpecSelected(if (isSel) StreamSpecFilter.ALL else spec) },
                    accentColor = pillAccent,
                )
            }
        }

        if (availableFormats.size > 1) {
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
                    .background(Color.White.copy(alpha = 0.15f)),
            )

            availableFormats.forEach { formatOpt ->
                val isSel = selectedFormat == formatOpt.format
                DesktopFilterPill(
                    label = formatOpt.format.label,
                    count = formatOpt.count,
                    selected = isSel,
                    onClick = { onFormatSelected(formatOpt.format) },
                    accentColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
