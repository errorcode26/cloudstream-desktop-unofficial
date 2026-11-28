package com.lagradost.cloudstream3.desktop.ui.badges

import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class TechTagType { FORMAT, CODEC, HDR, AUDIO }

data class TechTag(
    val text: String,
    val type: TechTagType,
    val badgeFile: String? = null,
)

/**
 * Extracts structured technical stream tags (debrid source, container/rip, editions,
 * HDR format, video codec, audio codec/channels, and languages) from raw stream titles
 * so Main App cards and HTML player surfaces stay consistent.
 */
object StreamTechTagExtractor {
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
    private val REGEX_HDR_WORD = Regex("""\bHDR\b""")

    private val LANGUAGE_RULES = listOf(
        Triple(REGEX_HIN, "HIN", "lang-hi.png"),
        Triple(REGEX_ENG, "ENG", "lang-en.png"),
        Triple(REGEX_TAM, "TAM", "lang-ta.png"),
        Triple(REGEX_TEL, "TEL", "lang-te.png"),
        Triple(REGEX_MAL, "MAL", "lang-ml.png"),
        Triple(REGEX_KAN, "KAN", "lang-kn.png"),
        Triple(REGEX_SPA, "SPA", "lang-es.png"),
        Triple(REGEX_FRE, "FRE", "lang-fr.png"),
        Triple(REGEX_GER, "GER", "lang-de.png"),
        Triple(REGEX_ITA, "ITA", "lang-it.png"),
        Triple(REGEX_POR, "POR", "lang-pt.png"),
        Triple(REGEX_RUS, "RUS", "lang-ru.png"),
        Triple(REGEX_JPN, "JPN", "lang-ja.png"),
        Triple(REGEX_KOR, "KOR", "lang-ko.png"),
        Triple(REGEX_CHI, "CHI", "lang-zh.png"),
        Triple(REGEX_ARA, "ARA", "lang-ar.png"),
        Triple(REGEX_TUR, "TUR", "lang-tr.png"),
    )

    fun extract(rawName: String): List<TechTag> {
        if (rawName.isBlank()) return emptyList()
        return try {
            val tags = mutableListOf<TechTag>()
            val upper = rawName.uppercase()

            // Debrid providers
            when {
                upper.contains("[RD") || upper.contains("RD+") || upper.contains("REAL-DEBRID") ->
                    tags.add(TechTag("Real-Debrid", TechTagType.FORMAT, "rd.png"))
                upper.contains("[TB") || upper.contains("TB+") || upper.contains("TORBOX") ->
                    tags.add(TechTag("TorBox", TechTagType.FORMAT, "torbox.png"))
                upper.contains("[AD") || upper.contains("AD+") || upper.contains("ALLDEBRID") ->
                    tags.add(TechTag("AllDebrid", TechTagType.FORMAT, "alldebrid.png"))
                upper.contains("[PM") || upper.contains("PM+") || upper.contains("PREMIUMIZE") ->
                    tags.add(TechTag("Premiumize", TechTagType.FORMAT, "premiumize.png"))
                upper.contains("[DL") || upper.contains("DL+") || upper.contains("DEBRID-LINK") || upper.contains("DEBRIDLINK") ->
                    tags.add(TechTag("Debrid-Link", TechTagType.FORMAT, "debridlink.png"))
            }

            // Format / Rip
            when {
                upper.contains("REMUX") -> tags.add(TechTag("REMUX", TechTagType.FORMAT, "source-remux.png"))
                upper.contains("BLURAY") || upper.contains("BLU-RAY") || upper.contains("BDRIP") || upper.contains("BRRIP") ->
                    tags.add(TechTag("BluRay", TechTagType.FORMAT, "source-bluray.png"))
                upper.contains("WEB-DL") || upper.contains("WEBDL") ->
                    tags.add(TechTag("WEB-DL", TechTagType.FORMAT, "source-webdl.png"))
                upper.contains("WEBRIP") || upper.contains("WEB-RIP") ->
                    tags.add(TechTag("WEBRip", TechTagType.FORMAT, "source-webrip.png"))
                REGEX_CAM.containsMatchIn(rawName) -> tags.add(TechTag("CAM", TechTagType.FORMAT, "cam.png"))
                REGEX_TS.containsMatchIn(rawName) -> tags.add(TechTag("TeleSync", TechTagType.FORMAT, "telesync.png"))
                REGEX_TC.containsMatchIn(rawName) -> tags.add(TechTag("TeleCine", TechTagType.FORMAT, "tc.png"))
                REGEX_SCR.containsMatchIn(rawName) -> tags.add(TechTag("SCR", TechTagType.FORMAT, "scr.png"))
                upper.contains("HDTV") || upper.contains("PDTV") -> tags.add(TechTag("HDTV", TechTagType.FORMAT, "source-hdtv.png"))
                REGEX_DVD.containsMatchIn(rawName) -> tags.add(TechTag("DVD", TechTagType.FORMAT, "source-dvd.png"))
            }

            // Editions
            when {
                upper.contains("IMAX ENHANCED") -> tags.add(TechTag("IMAX Enhanced", TechTagType.FORMAT, "imax-enhanced.png"))
                upper.contains("IMAX") -> tags.add(TechTag("IMAX", TechTagType.FORMAT, "imax.png"))
            }
            when {
                REGEX_DC.containsMatchIn(rawName) -> tags.add(TechTag("Director's Cut", TechTagType.FORMAT, "directors-cut.png"))
                upper.contains("EXTENDED") -> tags.add(TechTag("Extended", TechTagType.FORMAT, "extended.png"))
                upper.contains("REMASTERED") || upper.contains("REMASTER") -> tags.add(TechTag("Remastered", TechTagType.FORMAT, "remastered.png"))
                REGEX_PROPER.containsMatchIn(rawName) -> tags.add(TechTag("PROPER", TechTagType.FORMAT, "proper.png"))
            }

            // Dynamic Range / HDR
            when {
                upper.contains("DOLBY VISION") || upper.contains("DOVI") || upper.contains("DV ") || upper.contains(".DV.") || upper.contains("-DV-") || upper.contains("[DV]") ->
                    tags.add(TechTag("Dolby Vision", TechTagType.HDR, "dolby-vision.png"))
                upper.contains("HDR10+") -> tags.add(TechTag("HDR10+", TechTagType.HDR, "hdr10plus.png"))
                upper.contains("HDR10") -> tags.add(TechTag("HDR10", TechTagType.HDR, "hdr10.png"))
                REGEX_HDR_WORD.containsMatchIn(upper) || upper.contains("[HDR]") -> tags.add(TechTag("HDR", TechTagType.HDR, "hdr.png"))
            }

            // Video Codec
            when {
                upper.contains("AV1") -> tags.add(TechTag("AV1", TechTagType.CODEC, "av1.png"))
                upper.contains("HEVC") || upper.contains("X265") || upper.contains("H.265") || upper.contains("H265") ->
                    tags.add(TechTag("HEVC", TechTagType.CODEC, "hevc.png"))
                upper.contains("X264") || upper.contains("H.264") || upper.contains("H264") || upper.contains("AVC") ->
                    tags.add(TechTag("AVC", TechTagType.CODEC, "avc.png"))
                upper.contains("PRORES") -> tags.add(TechTag("ProRes", TechTagType.CODEC, "prores.png"))
                upper.contains("VP9") -> tags.add(TechTag("VP9", TechTagType.CODEC, "vp9.png"))
            }
            if (upper.contains("10BIT") || upper.contains("10-BIT") || upper.contains("10 BIT")) {
                tags.add(TechTag("10-bit", TechTagType.CODEC, "10bit.png"))
            }

            // Audio Codec
            if (upper.contains("ATMOS")) {
                tags.add(TechTag("Atmos", TechTagType.AUDIO, "atmos.png"))
            }
            when {
                upper.contains("TRUEHD") -> tags.add(TechTag("TrueHD", TechTagType.AUDIO, "truehd.png"))
                upper.contains("DTS:X") || upper.contains("DTS-X") -> tags.add(TechTag("DTS:X", TechTagType.AUDIO, "dtsx.png"))
                upper.contains("DTS-HD") || upper.contains("DTSHD") -> tags.add(TechTag("DTS-HD", TechTagType.AUDIO, "dtshd-ma.png"))
                upper.contains("DTS") -> tags.add(TechTag("DTS", TechTagType.AUDIO, "dts.png"))
                upper.contains("DDP5.1") || upper.contains("DDP 5.1") || upper.contains("EAC3 5.1") || upper.contains("E-AC-3 5.1") || upper.contains("DDP5") ->
                    tags.add(TechTag("DDP 5.1", TechTagType.AUDIO, "ddplus.png"))
                upper.contains("DDP7.1") || upper.contains("DDP 7.1") || upper.contains("EAC3 7.1") ->
                    tags.add(TechTag("DDP 7.1", TechTagType.AUDIO, "ddplus.png"))
                upper.contains("DDP2") || upper.contains("DDP 2.0") || upper.contains("DDP") || upper.contains("DD+") || upper.contains("EAC3") || upper.contains("E-AC-3") ->
                    tags.add(TechTag("DD+", TechTagType.AUDIO, "ddplus.png"))
                upper.contains("DD5.1") || upper.contains("DD 5.1") || upper.contains("AC3 5.1") || upper.contains("AC-3 5.1") ->
                    tags.add(TechTag("DD 5.1", TechTagType.AUDIO, "dd.png"))
                upper.contains("FLAC") -> tags.add(TechTag("FLAC", TechTagType.AUDIO, "flac.png"))
                upper.contains("AAC 5.1") || upper.contains("AAC5.1") -> tags.add(TechTag("AAC 5.1", TechTagType.AUDIO, "aac.png"))
                upper.contains("AAC") -> tags.add(TechTag("AAC", TechTagType.AUDIO, "aac.png"))
                upper.contains("OPUS") -> tags.add(TechTag("Opus", TechTagType.AUDIO, "opus.png"))
            }

            // Audio Channels
            when {
                upper.contains("7.1") || upper.contains("8CH") || upper.contains("8 CH") ->
                    tags.add(TechTag("7.1", TechTagType.AUDIO, "7-1.png"))
                upper.contains("5.1") || upper.contains("6CH") || upper.contains("6 CH") ->
                    tags.add(TechTag("5.1", TechTagType.AUDIO, "5-1.png"))
                upper.contains("2.0") || upper.contains("2CH") || upper.contains("STEREO") ->
                    tags.add(TechTag("2.0", TechTagType.AUDIO, "2-0.png"))
            }

            // Audio & Subtitle Features
            when {
                upper.contains("DUAL AUDIO") || upper.contains("DUAL-AUDIO") || upper.contains("DUALAUDIO") ->
                    tags.add(TechTag("Dual Audio", TechTagType.AUDIO, "dual-audio.png"))
                upper.contains("MULTI AUDIO") || upper.contains("MULTI-AUDIO") || upper.contains("MULTIAUDIO") ->
                    tags.add(TechTag("Multi Audio", TechTagType.AUDIO, "multi-audio.png"))
                REGEX_DUB.containsMatchIn(rawName) ->
                    tags.add(TechTag("Dubbed", TechTagType.AUDIO, "dubbed.png"))
            }
            if (upper.contains("MULTI SUB") || upper.contains("MULTI-SUB") || upper.contains("MULTISUB")) {
                tags.add(TechTag("Multi Sub", TechTagType.FORMAT, "multi-sub.png"))
            }

            // Languages
            for ((regex, label, asset) in LANGUAGE_RULES) {
                if (regex.containsMatchIn(rawName)) {
                    tags.add(TechTag(label, TechTagType.FORMAT, asset))
                }
            }

            tags.distinctBy { it.text }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Materializes classpath badge resource into appData cache when packaged resources live in JAR. */
    internal fun extractClasspathResourceIfNeeded(resourcePath: String, destFile: File): File? {
        if (destFile.exists() && destFile.length() > 0L) return destFile
        val normalized = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        val stream = StreamTechTagExtractor::class.java.getResourceAsStream(normalized) ?: return null
        return try {
            destFile.parentFile?.mkdirs()
            val tmpFile = File(destFile.parentFile, "${destFile.name}.${Thread.currentThread().threadId()}.tmp")
            stream.use { input ->
                Files.copy(input, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                runCatching {
                    Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.recoverCatching {
                    Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                destFile.takeIf { it.exists() && it.length() > 0L }
            } else {
                tmpFile.delete()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
