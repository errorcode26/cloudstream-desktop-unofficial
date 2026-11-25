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
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
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

enum class TechTagType { FORMAT, CODEC, HDR, AUDIO }
data class TechTag(val text: String, val type: TechTagType, val badgeFile: String? = null)

private val REGEX_HIN = Regex("""\b(HIN|HINDI)\b""", RegexOption.IGNORE_CASE)
private val REGEX_ENG = Regex("""\b(ENG|ENGLISH)\b""", RegexOption.IGNORE_CASE)
private val REGEX_TAM = Regex("""\b(TAM|TAMIL)\b""", RegexOption.IGNORE_CASE)
private val REGEX_TEL = Regex("""\b(TEL|TELUGU)\b""", RegexOption.IGNORE_CASE)
private val REGEX_MAL = Regex("""\b(MAL|MALAYALAM)\b""", RegexOption.IGNORE_CASE)
private val REGEX_KAN = Regex("""\b(KAN|KANNADA)\b""", RegexOption.IGNORE_CASE)
private val REGEX_SPA = Regex("""\b(SPA|SPANISH|CASTELLANO)\b""", RegexOption.IGNORE_CASE)
private val REGEX_FRE = Regex("""\b(FRE|FRA|FRENCH|VFF|VFQ)\b""", RegexOption.IGNORE_CASE)
private val REGEX_GER = Regex("""\b(GER|DEU|GERMAN|DEUTSCH)\b""", RegexOption.IGNORE_CASE)
private val REGEX_ITA = Regex("""\b(ITA|ITALIAN)\b""", RegexOption.IGNORE_CASE)
private val REGEX_POR = Regex("""\b(POR|PORTUGUESE)\b""", RegexOption.IGNORE_CASE)
private val REGEX_RUS = Regex("""\b(RUS|RUSSIAN)\b""", RegexOption.IGNORE_CASE)
private val REGEX_JPN = Regex("""\b(JPN|JAPANESE)\b""", RegexOption.IGNORE_CASE)
private val REGEX_KOR = Regex("""\b(KOR|KOREAN)\b""", RegexOption.IGNORE_CASE)
private val REGEX_CHI = Regex("""\b(CHI|CHINESE|MANDARIN|CANTONESE)\b""", RegexOption.IGNORE_CASE)
private val REGEX_ARA = Regex("""\b(ARA|ARABIC)\b""", RegexOption.IGNORE_CASE)
private val REGEX_TUR = Regex("""\b(TUR|TURKISH)\b""", RegexOption.IGNORE_CASE)
private val REGEX_CAM = Regex("""\b(CAM|HDCAM|CAMRIP|CAM-RIP)\b""", RegexOption.IGNORE_CASE)
private val REGEX_TS = Regex("""\b(TS|HDTS|HD-TS|TELESYNC)\b""", RegexOption.IGNORE_CASE)
private val REGEX_TC = Regex("""\b(TC|HDTC|TELECINE)\b""", RegexOption.IGNORE_CASE)
private val REGEX_SCR = Regex("""\b(SCR|SCREENER|DVDSCR)\b""", RegexOption.IGNORE_CASE)
private val REGEX_DVD = Regex("""\b(DVD|DVDRIP|DVD-RIP)\b""", RegexOption.IGNORE_CASE)
private val REGEX_PROPER = Regex("""\bPROPER\b""", RegexOption.IGNORE_CASE)
private val REGEX_DC = Regex("""\b(DIRECTORS CUT|DIRECTOR'S CUT|DC)\b""", RegexOption.IGNORE_CASE)
private val REGEX_DUB = Regex("""\b(DUBBED|DUB)\b""", RegexOption.IGNORE_CASE)

internal fun extractStructuredTechTags(rawName: String): List<TechTag> {
    if (rawName.isBlank()) return emptyList()
    return try {
        val tags = mutableListOf<TechTag>()
        val upper = rawName.uppercase()

    // 0. Debrid providers
    if (upper.contains("[RD") || upper.contains("RD+") || upper.contains("REAL-DEBRID")) {
        tags.add(TechTag("Real-Debrid", TechTagType.FORMAT, "rd.png"))
    } else if (upper.contains("[TB") || upper.contains("TB+") || upper.contains("TORBOX")) {
        tags.add(TechTag("TorBox", TechTagType.FORMAT, "torbox.png"))
    } else if (upper.contains("[AD") || upper.contains("AD+") || upper.contains("ALLDEBRID")) {
        tags.add(TechTag("AllDebrid", TechTagType.FORMAT, "alldebrid.png"))
    } else if (upper.contains("[PM") || upper.contains("PM+") || upper.contains("PREMIUMIZE")) {
        tags.add(TechTag("Premiumize", TechTagType.FORMAT, "premiumize.png"))
    } else if (upper.contains("[DL") || upper.contains("DL+") || upper.contains("DEBRID-LINK") || upper.contains("DEBRIDLINK")) {
        tags.add(TechTag("Debrid-Link", TechTagType.FORMAT, "debridlink.png"))
    }

    // 1. Format / Rip
    if (upper.contains("REMUX")) {
        tags.add(TechTag("REMUX", TechTagType.FORMAT, "source-remux.png"))
    } else if (upper.contains("BLURAY") || upper.contains("BLU-RAY") || upper.contains("BDRIP") || upper.contains("BRRIP")) {
        tags.add(TechTag("BluRay", TechTagType.FORMAT, "source-bluray.png"))
    } else if (upper.contains("WEB-DL") || upper.contains("WEBDL")) {
        tags.add(TechTag("WEB-DL", TechTagType.FORMAT, "source-webdl.png"))
    } else if (upper.contains("WEBRIP") || upper.contains("WEB-RIP")) {
        tags.add(TechTag("WEBRip", TechTagType.FORMAT, "source-webrip.png"))
    } else if (REGEX_CAM.containsMatchIn(rawName)) {
        tags.add(TechTag("CAM", TechTagType.FORMAT, "cam.png"))
    } else if (REGEX_TS.containsMatchIn(rawName)) {
        tags.add(TechTag("TeleSync", TechTagType.FORMAT, "telesync.png"))
    } else if (REGEX_TC.containsMatchIn(rawName)) {
        tags.add(TechTag("TeleCine", TechTagType.FORMAT, "tc.png"))
    } else if (REGEX_SCR.containsMatchIn(rawName)) {
        tags.add(TechTag("SCR", TechTagType.FORMAT, "scr.png"))
    } else if (upper.contains("HDTV") || upper.contains("PDTV")) {
        tags.add(TechTag("HDTV", TechTagType.FORMAT, "source-hdtv.png"))
    } else if (REGEX_DVD.containsMatchIn(rawName)) {
        tags.add(TechTag("DVD", TechTagType.FORMAT, "source-dvd.png"))
    }

    // Editions
    if (upper.contains("IMAX ENHANCED")) {
        tags.add(TechTag("IMAX Enhanced", TechTagType.FORMAT, "imax-enhanced.png"))
    } else if (upper.contains("IMAX")) {
        tags.add(TechTag("IMAX", TechTagType.FORMAT, "imax.png"))
    }
    if (REGEX_DC.containsMatchIn(rawName)) {
        tags.add(TechTag("Director's Cut", TechTagType.FORMAT, "directors-cut.png"))
    } else if (upper.contains("EXTENDED")) {
        tags.add(TechTag("Extended", TechTagType.FORMAT, "extended.png"))
    } else if (upper.contains("REMASTERED") || upper.contains("REMASTER")) {
        tags.add(TechTag("Remastered", TechTagType.FORMAT, "remastered.png"))
    } else if (REGEX_PROPER.containsMatchIn(rawName)) {
        tags.add(TechTag("PROPER", TechTagType.FORMAT, "proper.png"))
    }

    // 2. Dynamic Range / HDR
    if (upper.contains("DOLBY VISION") || upper.contains("DOVI") || upper.contains("DV ") || upper.contains(".DV.") || upper.contains("-DV-") || upper.contains("[DV]")) {
        tags.add(TechTag("Dolby Vision", TechTagType.HDR, "dolby-vision.png"))
    } else if (upper.contains("HDR10+")) {
        tags.add(TechTag("HDR10+", TechTagType.HDR, "hdr10plus.png"))
    } else if (upper.contains("HDR10")) {
        tags.add(TechTag("HDR10", TechTagType.HDR, "hdr10.png"))
    } else if (Regex("""\bHDR\b""").containsMatchIn(upper) || upper.contains("[HDR]")) {
        tags.add(TechTag("HDR", TechTagType.HDR, "hdr.png"))
    }

    // 3. Video Codec
    if (upper.contains("AV1")) {
        tags.add(TechTag("AV1", TechTagType.CODEC, "av1.png"))
    } else if (upper.contains("HEVC") || upper.contains("X265") || upper.contains("H.265") || upper.contains("H265")) {
        tags.add(TechTag("HEVC", TechTagType.CODEC, "hevc.png"))
    } else if (upper.contains("X264") || upper.contains("H.264") || upper.contains("H264") || upper.contains("AVC")) {
        tags.add(TechTag("AVC", TechTagType.CODEC, "avc.png"))
    } else if (upper.contains("PRORES")) {
        tags.add(TechTag("ProRes", TechTagType.CODEC, "prores.png"))
    } else if (upper.contains("VP9")) {
        tags.add(TechTag("VP9", TechTagType.CODEC, "vp9.png"))
    }
    if (upper.contains("10BIT") || upper.contains("10-BIT") || upper.contains("10 BIT")) {
        tags.add(TechTag("10-bit", TechTagType.CODEC, "10bit.png"))
    }

    // 4. Audio Codec
    if (upper.contains("ATMOS")) {
        tags.add(TechTag("Atmos", TechTagType.AUDIO, "atmos.png"))
    }
    if (upper.contains("TRUEHD")) {
        tags.add(TechTag("TrueHD", TechTagType.AUDIO, "truehd.png"))
    } else if (upper.contains("DTS:X") || upper.contains("DTS-X")) {
        tags.add(TechTag("DTS:X", TechTagType.AUDIO, "dtsx.png"))
    } else if (upper.contains("DTS-HD") || upper.contains("DTSHD")) {
        tags.add(TechTag("DTS-HD", TechTagType.AUDIO, "dtshd-ma.png"))
    } else if (upper.contains("DTS")) {
        tags.add(TechTag("DTS", TechTagType.AUDIO, "dts.png"))
    } else if (upper.contains("DDP5.1") || upper.contains("DDP 5.1") || upper.contains("EAC3 5.1") || upper.contains("E-AC-3 5.1") || upper.contains("DDP5")) {
        tags.add(TechTag("DDP 5.1", TechTagType.AUDIO, "ddplus.png"))
    } else if (upper.contains("DDP7.1") || upper.contains("DDP 7.1") || upper.contains("EAC3 7.1")) {
        tags.add(TechTag("DDP 7.1", TechTagType.AUDIO, "ddplus.png"))
    } else if (upper.contains("DDP2") || upper.contains("DDP 2.0") || upper.contains("DDP") || upper.contains("DD+") || upper.contains("EAC3") || upper.contains("E-AC-3")) {
        tags.add(TechTag("DD+", TechTagType.AUDIO, "ddplus.png"))
    } else if (upper.contains("DD5.1") || upper.contains("DD 5.1") || upper.contains("AC3 5.1") || upper.contains("AC-3 5.1")) {
        tags.add(TechTag("DD 5.1", TechTagType.AUDIO, "dd.png"))
    } else if (upper.contains("FLAC")) {
        tags.add(TechTag("FLAC", TechTagType.AUDIO, "flac.png"))
    } else if (upper.contains("AAC 5.1") || upper.contains("AAC5.1")) {
        tags.add(TechTag("AAC 5.1", TechTagType.AUDIO, "aac.png"))
    } else if (upper.contains("AAC")) {
        tags.add(TechTag("AAC", TechTagType.AUDIO, "aac.png"))
    } else if (upper.contains("OPUS")) {
        tags.add(TechTag("Opus", TechTagType.AUDIO, "opus.png"))
    }

    // Audio Channels
    if (upper.contains("7.1") || upper.contains("8CH") || upper.contains("8 CH")) {
        tags.add(TechTag("7.1", TechTagType.AUDIO, "7-1.png"))
    } else if (upper.contains("5.1") || upper.contains("6CH") || upper.contains("6 CH")) {
        tags.add(TechTag("5.1", TechTagType.AUDIO, "5-1.png"))
    } else if (upper.contains("2.0") || upper.contains("2CH") || upper.contains("STEREO")) {
        tags.add(TechTag("2.0", TechTagType.AUDIO, "2-0.png"))
    }

    // Audio & Subtitle Features
    if (upper.contains("DUAL AUDIO") || upper.contains("DUAL-AUDIO") || upper.contains("DUALAUDIO")) {
        tags.add(TechTag("Dual Audio", TechTagType.AUDIO, "dual-audio.png"))
    } else if (upper.contains("MULTI AUDIO") || upper.contains("MULTI-AUDIO") || upper.contains("MULTIAUDIO")) {
        tags.add(TechTag("Multi Audio", TechTagType.AUDIO, "multi-audio.png"))
    } else if (REGEX_DUB.containsMatchIn(rawName)) {
        tags.add(TechTag("Dubbed", TechTagType.AUDIO, "dubbed.png"))
    }
    if (upper.contains("MULTI SUB") || upper.contains("MULTI-SUB") || upper.contains("MULTISUB")) {
        tags.add(TechTag("Multi Sub", TechTagType.FORMAT, "multi-sub.png"))
    }

    // 5. Languages
    if (REGEX_HIN.containsMatchIn(rawName)) {
        tags.add(TechTag("HIN", TechTagType.FORMAT, "lang-hi.png"))
    }
    if (REGEX_ENG.containsMatchIn(rawName)) {
        tags.add(TechTag("ENG", TechTagType.FORMAT, "lang-en.png"))
    }
    if (REGEX_TAM.containsMatchIn(rawName)) {
        tags.add(TechTag("TAM", TechTagType.FORMAT, "lang-ta.png"))
    }
    if (REGEX_TEL.containsMatchIn(rawName)) {
        tags.add(TechTag("TEL", TechTagType.FORMAT, "lang-te.png"))
    }
    if (REGEX_MAL.containsMatchIn(rawName)) {
        tags.add(TechTag("MAL", TechTagType.FORMAT, "lang-ml.png"))
    }
    if (REGEX_KAN.containsMatchIn(rawName)) {
        tags.add(TechTag("KAN", TechTagType.FORMAT, "lang-kn.png"))
    }
    if (REGEX_SPA.containsMatchIn(rawName)) {
        tags.add(TechTag("SPA", TechTagType.FORMAT, "lang-es.png"))
    }
    if (REGEX_FRE.containsMatchIn(rawName)) {
        tags.add(TechTag("FRE", TechTagType.FORMAT, "lang-fr.png"))
    }
    if (REGEX_GER.containsMatchIn(rawName)) {
        tags.add(TechTag("GER", TechTagType.FORMAT, "lang-de.png"))
    }
    if (REGEX_ITA.containsMatchIn(rawName)) {
        tags.add(TechTag("ITA", TechTagType.FORMAT, "lang-it.png"))
    }
    if (REGEX_POR.containsMatchIn(rawName)) {
        tags.add(TechTag("POR", TechTagType.FORMAT, "lang-pt.png"))
    }
    if (REGEX_RUS.containsMatchIn(rawName)) {
        tags.add(TechTag("RUS", TechTagType.FORMAT, "lang-ru.png"))
    }
    if (REGEX_JPN.containsMatchIn(rawName)) {
        tags.add(TechTag("JPN", TechTagType.FORMAT, "lang-ja.png"))
    }
    if (REGEX_KOR.containsMatchIn(rawName)) {
        tags.add(TechTag("KOR", TechTagType.FORMAT, "lang-ko.png"))
    }
    if (REGEX_CHI.containsMatchIn(rawName)) {
        tags.add(TechTag("CHI", TechTagType.FORMAT, "lang-zh.png"))
    }
    if (REGEX_ARA.containsMatchIn(rawName)) {
        tags.add(TechTag("ARA", TechTagType.FORMAT, "lang-ar.png"))
    }
    if (REGEX_TUR.containsMatchIn(rawName)) {
        tags.add(TechTag("TUR", TechTagType.FORMAT, "lang-tr.png"))
    }

    tags.distinctBy { it.text }
    } catch (_: Exception) {
        emptyList()
    }
}

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

    val containerBg = when {
        isPrimary && isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        isPrimary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        isHovered -> Color.White.copy(alpha = 0.15f)
        else -> Color.White.copy(alpha = 0.05f)
    }

    val borderColor = when {
        isPrimary && isHovered -> MaterialTheme.colorScheme.primary
        isPrimary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
        isHovered -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    val iconTint = when {
        isPrimary && isHovered -> MaterialTheme.colorScheme.onPrimary
        isPrimary -> MaterialTheme.colorScheme.primary
        isHovered -> Color.White
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
                .clickable(enabled = enabled, onClick = onClick),
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
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.005f else 1f,
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
        hovered -> DesktopUi.SurfaceElevated.copy(alpha = 0.85f)
        else -> DesktopUi.SurfaceCard.copy(alpha = 0.55f)
    }
    val cardBorder = when {
        isPlaying -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        hovered -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .hoverable(interaction)
            .clip(cardShape)
            .clickable(enabled = !isBusy, onClick = onPlay),
        shape = cardShape,
        color = cardBackground,
        tonalElevation = if (hovered) 4.dp else 1.dp,
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
