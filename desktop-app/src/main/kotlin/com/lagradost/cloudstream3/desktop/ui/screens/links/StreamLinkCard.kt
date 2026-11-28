package com.lagradost.cloudstream3.desktop.ui.screens.links

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager
import com.lagradost.cloudstream3.desktop.ui.badges.BadgeManager
import com.lagradost.cloudstream3.desktop.ui.badges.StreamTechTagExtractor
import com.lagradost.cloudstream3.desktop.ui.badges.TechTag
import com.lagradost.cloudstream3.desktop.ui.badges.TechTagType
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
import com.lagradost.cloudstream3.desktop.ui.components.collectIsKeyboardFocusedAsState
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

private val SERVER_BRACKET_REGEX = Regex("\\[(.*?)\\]")
private val SIZE_BRACKET_REGEX = Regex("\\[([0-9.]+\\s*(?:MB|GB|KB|TB|GiB|MiB))\\]", RegexOption.IGNORE_CASE)
private val SIZE_FALLBACK_REGEX = Regex("([0-9.]+\\s*(?:MB|GB|KB|TB|GiB|MiB))", RegexOption.IGNORE_CASE)

internal fun extractCleanServer(rawName: String, fallback: String): String {
    return try {
        if (rawName.isBlank()) return fallback
        val bracketMatch = SERVER_BRACKET_REGEX.find(rawName)
        if (bracketMatch != null) {
            val candidate = bracketMatch.groupValues[1].trim()
            if (!candidate.contains("MB", ignoreCase = true) && !candidate.contains("GB", ignoreCase = true)) {
                return candidate
            }
        }
        val parts = rawName.split(" ", "-", ".").filter { it.isNotBlank() }
        parts.firstOrNull { it.length > 2 && !it.contains("720") && !it.contains("1080") && !it.contains("x264") } ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

internal fun extractCleanSize(rawName: String): String? {
    return try {
        if (rawName.isBlank()) return null
        val sizeMatch = SIZE_BRACKET_REGEX.find(rawName) ?: SIZE_FALLBACK_REGEX.find(rawName)
        sizeMatch?.groupValues?.get(1)?.replace("GiB", "GB", ignoreCase = true)?.replace("MiB", "MB", ignoreCase = true)
    } catch (_: Exception) {
        null
    }
}

internal fun extractStructuredTechTags(rawName: String): List<TechTag> =
    StreamTechTagExtractor.extract(rawName)

internal fun extractTechTags(rawName: String): List<String> = extractStructuredTechTags(rawName).map { it.text }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tooltipText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isFocused by interaction.collectIsKeyboardFocusedAsState()
    val isHighlighted = isHovered || isFocused

    val containerBg = when {
        isPrimary && isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        isPrimary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        isHighlighted -> Color.White.copy(alpha = 0.15f)
        else -> Color.White.copy(alpha = 0.05f)
    }

    val borderColor = when {
        isPrimary && isHighlighted -> MaterialTheme.colorScheme.primary
        isPrimary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
        isFocused -> MaterialTheme.colorScheme.primary
        isHovered -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    val iconTint = when {
        isPrimary && isHighlighted -> MaterialTheme.colorScheme.onPrimary
        isPrimary -> MaterialTheme.colorScheme.primary
        isHighlighted -> Color.White
        else -> DesktopUi.TextPrimary.copy(alpha = 0.70f)
    }

    val buttonShape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.icon
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1E1E28),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = tooltipText,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        delayMillis = 250,
    ) {
        Surface(
            modifier = modifier
                .size(36.dp)
                .hoverable(interaction)
                .clip(buttonShape)
                .desktopInteractiveFocus(
                    interactionSource = interaction,
                    shape = buttonShape,
                    enabled = enabled,
                )
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
            shape = buttonShape,
            color = containerBg,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun StreamLinkCard(
    link: ExtractorLink,
    isP2pEnabled: Boolean = false,
    isPlaying: Boolean = false,
    isBusy: Boolean,
    providerName: String? = null,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsKeyboardFocusedAsState()
    val isHighlighted = hovered || focused
    val scale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.005f else 1f,
        animationSpec = tween(150),
        label = "cardScale",
    )

    val effectiveQuality = remember(link) { com.lagradost.cloudstream3.desktop.player.QualityDataHelper.extractEffectiveQuality(link) }
    val formattedQuality = com.lagradost.cloudstream3.desktop.player.QualityDataHelper.formatQuality(effectiveQuality)
    val is4k = effectiveQuality >= 2160
    val is1080 = effectiveQuality in 1080..2159
    val is720 = effectiveQuality in 720..1079
    val is480 = effectiveQuality in 480..719

    val qualityContainerColor = when {
        is4k -> Color(0xFFF59E0B).copy(alpha = 0.18f)
        is1080 -> Color(0xFF3B82F6).copy(alpha = 0.18f)
        is720 -> Color(0xFF06B6D4).copy(alpha = 0.18f)
        is480 -> Color(0xFF94A3B8).copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    val qualityBorderColor = when {
        is4k -> Color(0xFFF59E0B).copy(alpha = 0.50f)
        is1080 -> Color(0xFF3B82F6).copy(alpha = 0.50f)
        is720 -> Color(0xFF06B6D4).copy(alpha = 0.50f)
        is480 -> Color(0xFF94A3B8).copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.15f)
    }

    val qualityTextColor = when {
        is4k -> Color(0xFFF59E0B)
        is1080 -> Color(0xFF60A5FA)
        is720 -> Color(0xFF22D3EE)
        is480 -> Color(0xFFCBD5E1)
        else -> DesktopUi.TextMuted
    }

    val qualityBadgeText = when {
        is4k -> "4K UHD"
        is1080 -> "1080p FHD"
        is720 -> "720p HD"
        is480 -> "480p SD"
        effectiveQuality > 0 -> "${effectiveQuality}p"
        else -> formattedQuality
    }

    val formatTag = when {
        link.isM3u8 || link.name.contains("HLS", ignoreCase = true) || link.url.contains(".m3u8") -> "HLS"
        link.isDash || link.name.contains("DASH", ignoreCase = true) || link.url.contains(".mpd") -> "DASH"
        link.type == ExtractorLinkType.TORRENT ||
            link.type == ExtractorLinkType.MAGNET ||
            link.url.startsWith("magnet:") -> "TORRENT"
        link.name.contains("MKV", ignoreCase = true) || link.url.contains(".mkv", ignoreCase = true) -> "MKV"
        else -> "MP4"
    }

    val isAdaptive = formatTag == "HLS" || formatTag == "DASH" || link.isM3u8 || link.isDash ||
        link.url.contains(".m3u8", ignoreCase = true) || link.url.contains(".mpd", ignoreCase = true)

    val cleanSize = remember(link.name) { extractCleanSize(link.name) }
    val addonSource = remember(link.source) { link.source.trim().ifBlank { null } }
    val hostSource = remember(link.name, link.source) {
        if (link.source.isNotBlank() && link.source != link.name) link.source else extractCleanServer(link.name, "")
    }
    val structuredTags = remember(link.name) { extractStructuredTechTags(link.name) }

    val sourceLogoUrl = remember(addonSource, hostSource, providerName) {
        val targetName = addonSource ?: hostSource.takeIf { it.isNotBlank() } ?: providerName
        if (!targetName.isNullOrBlank()) {
            DesktopRepositoryManager.getPluginIcon(targetName)
                ?: StremioAddonManager.addons.value.firstOrNull {
                    it.name.equals(targetName, ignoreCase = true) ||
                        targetName.contains(it.name, ignoreCase = true) ||
                        it.name.contains(targetName, ignoreCase = true)
                }?.logoUrl
                ?: DesktopRepositoryManager.getPluginIcon(providerName)
        } else {
            null
        }
    }

    val rawLines = remember(link.name) {
        link.name.lines().map { it.trim() }.filter { it.isNotBlank() }
    }
    val firstLine = rawLines.firstOrNull() ?: ""
    val detailLines = remember(rawLines, structuredTags) {
        if (rawLines.size <= 1) {
            emptyList()
        } else {
            rawLines.drop(1).filter { line ->
                val isSpecLine = line.startsWith("🎦") ||
                    (structuredTags.isNotEmpty() && (line.contains("10bit", ignoreCase = true) || line.contains("HDR", ignoreCase = true) || line.contains("DD", ignoreCase = true) || line.contains("DTS", ignoreCase = true)))
                !isSpecLine
            }
        }
    }
    val hasSizeInDetails = remember(detailLines, cleanSize) {
        cleanSize != null && detailLines.any { it.contains("💾") || it.contains(cleanSize, ignoreCase = true) }
    }

    val cleanTitle = remember(firstLine, addonSource, hostSource, cleanSize) {
        var t = firstLine.removePrefix("⚡").trim()
        if (addonSource != null) {
            t = t.replace("[$addonSource]", "").replace(addonSource, "").trim()
        }
        if (hostSource.isNotBlank()) {
            t = t.replace("[$hostSource]", "").trim()
        }
        if (cleanSize != null) {
            t = t.replace("[$cleanSize]", "").replace("($cleanSize)", "").replace(cleanSize, "").trim()
        }
        t = t.replace(Regex("""\[\s*\]|\(\s*\)"""), "")
            .replace(Regex("""^[\s\-–|•]+|[\s\-–|•]+$"""), "")
            .trim()
        t.ifBlank { firstLine }
    }

    val cardShape = RoundedCornerShape(12.dp)
    val cardBackground = when {
        isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        isHighlighted -> DesktopUi.SurfaceElevated.copy(alpha = 0.85f)
        else -> DesktopUi.SurfaceCard.copy(alpha = 0.55f)
    }
    val cardBorder = when {
        isPlaying -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        focused -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        hovered -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .hoverable(interaction)
            .clip(cardShape)
            .desktopInteractiveFocus(
                interactionSource = interaction,
                shape = cardShape,
                enabled = !isBusy,
            )
            .clickable(enabled = !isBusy, interactionSource = interaction, indication = null, onClick = onPlay),
        shape = cardShape,
        color = cardBackground,
        tonalElevation = if (isHighlighted) 4.dp else 1.dp,
        border = cardBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // Prominent Brand Logo Box on Left
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    if (sourceLogoUrl != null) {
                        AsyncImage(
                            model = sourceLogoUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            if (addonSource != null) Icons.Default.Extension else Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1: Badges Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    // Quality Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = qualityContainerColor,
                        border = BorderStroke(1.dp, qualityBorderColor),
                    ) {
                        Text(
                            text = qualityBadgeText,
                            color = qualityTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp),
                        )
                    }

                    // Addon / Source Badge
                    val effectiveSource = addonSource ?: providerName
                    if (effectiveSource != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                if (sourceLogoUrl != null) {
                                    AsyncImage(
                                        model = sourceLogoUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Icon(
                                        if (addonSource != null) Icons.Default.Extension else Icons.Default.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = effectiveSource,
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Format Badge
                    val isTorrentBadge = formatTag == "TORRENT"
                    val isP2pOff = isTorrentBadge && !isP2pEnabled
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isP2pOff) Color(0xFFF59E0B).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isP2pOff) Color(0xFFF59E0B).copy(alpha = 0.4f) else DesktopUi.Divider.copy(alpha = 0.3f)),
                    ) {
                        Text(
                            text = if (isP2pOff) "TORRENT • P2P OFF" else formatTag,
                            color = if (isP2pOff) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isP2pOff) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }

                    // Host / Server Tag (if different from addon source)
                    if (hostSource.isNotBlank() && !hostSource.equals(addonSource, ignoreCase = true)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ) {
                            Text(
                                text = hostSource,
                                color = DesktopUi.TextMuted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                // If currently playing, show animated/styled PLAYING indicator
                if (isPlaying) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "PLAYING",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Row 2: Title / Release Name
            Text(
                text = cleanTitle,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = if (isPlaying) MaterialTheme.colorScheme.primary else DesktopUi.TextPrimary,
                softWrap = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Row 3: Detail Lines (e.g. from Stremio) AND/OR Tech Badges
            if (detailLines.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    detailLines.take(3).forEach { dLine ->
                        Text(
                            text = dLine,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = DesktopUi.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (structuredTags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    structuredTags.take(6).forEach { tag ->
                        val badgeFile = tag.badgeFile?.let { BadgeManager.getBadgeFile(it) }
                        if (badgeFile != null && badgeFile.exists()) {
                            AsyncImage(
                                model = badgeFile,
                                contentDescription = tag.text,
                                modifier = Modifier
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Fit,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            )
                        } else {
                            val (bgColor, borderColor, textColor) = when (tag.type) {
                                TechTagType.FORMAT -> Triple(Color(0xFF262833).copy(alpha = 0.85f), Color(0xFF4A4E63).copy(alpha = 0.50f), Color(0xFFCBD5E1))
                                TechTagType.CODEC -> Triple(Color(0xFF1E2648).copy(alpha = 0.85f), Color(0xFF4C66B2).copy(alpha = 0.50f), Color(0xFF93C5FD))
                                TechTagType.HDR -> Triple(Color(0xFF332614).copy(alpha = 0.85f), Color(0xFFB47D1E).copy(alpha = 0.50f), Color(0xFFFBBF24))
                                TechTagType.AUDIO -> Triple(Color(0xFF142E35).copy(alpha = 0.85f), Color(0xFF1E7582).copy(alpha = 0.50f), Color(0xFF22D3EE))
                            }
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = bgColor,
                                border = BorderStroke(1.dp, borderColor),
                            ) {
                                Text(
                                    text = tag.text,
                                    color = textColor,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.3.sp,
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Row 4: Size Badge on Left, Action Buttons on Right
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Size Badge (only shown if not already present in details line)
                if (cleanSize != null && !hasSizeInDetails) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E1E1E),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    ) {
                        Text(
                            text = "SIZE $cleanSize",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            ),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Copy Stream URL
                    StreamActionIconButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Copy Stream URL",
                        tooltipText = "Copy Stream URL",
                        enabled = !isBusy,
                        onClick = onCopy,
                    )

                    // Download Stream (Direct links only)
                    if (!isAdaptive) {
                        StreamActionIconButton(
                            icon = Icons.Default.Download,
                            contentDescription = "Download Stream",
                            tooltipText = "Download Stream",
                            enabled = !isBusy,
                            onClick = onDownload,
                        )
                    }

                    // Play Button
                    StreamActionIconButton(
                        icon = Icons.Default.PlayArrow,
                        contentDescription = "Play Stream",
                        tooltipText = "Play Stream",
                        enabled = !isBusy,
                        onClick = onPlay,
                        isPrimary = true,
                    )
                }
            }
        }
    }
    }
}
