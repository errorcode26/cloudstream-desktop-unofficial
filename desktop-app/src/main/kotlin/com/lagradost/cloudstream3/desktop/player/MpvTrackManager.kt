package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.impl.proxy.LocalStreamProxyState
import com.sun.jna.Pointer

/**
 * Manages extraction, disambiguation, language matching, and state updates
 * for native MPV audio, video, and subtitle tracks and chapters.
 */
internal class MpvTrackManager(
    private val playerState: PlayerState?,
    private val lib: MpvLibrary = MpvLibrary.INSTANCE,
) {
    var hasAutoSwitchedAudio = false
    var hasAutoSwitchedSub = false

    fun reset() {
        hasAutoSwitchedAudio = false
        hasAutoSwitchedSub = false
    }

    fun pollTracksAndChapters(h: Pointer) {
        val trackCountStr = MpvLibrary.getPropertyString(h, "track-list/count")
        val trackCount = trackCountStr?.toIntOrNull() ?: 0

        val audioTracks = mutableListOf<PlayerState.VideoTrack>()
        val subTracks = mutableListOf<PlayerState.VideoTrack>()
        val videoTracks = mutableListOf<PlayerState.VideoTrack>()
        val audioSearchInfo = mutableListOf<Triple<Int, Boolean, String>>()
        val subSearchInfo = mutableListOf<Triple<Int, Boolean, String>>()

        for (i in 0 until trackCount) {
            val id = MpvLibrary.getPropertyString(h, "track-list/$i/id")?.toIntOrNull() ?: continue
            val type = MpvLibrary.getPropertyString(h, "track-list/$i/type") ?: continue
            val lang = MpvLibrary.getPropertyString(h, "track-list/$i/lang")
            val title = MpvLibrary.getPropertyString(h, "track-list/$i/title")
            val codec = MpvLibrary.getPropertyString(h, "track-list/$i/codec")
            val isForced = MpvLibrary.getPropertyString(h, "track-list/$i/forced") == "yes"
            val isDefault = MpvLibrary.getPropertyString(h, "track-list/$i/default") == "yes"
            val isOriginal = MpvLibrary.getPropertyString(h, "track-list/$i/original") == "yes"
            val isExternal = MpvLibrary.getPropertyString(h, "track-list/$i/external") == "yes"
            val channels = MpvLibrary.getPropertyString(h, "track-list/$i/audio-channels")
                ?: MpvLibrary.getPropertyString(h, "track-list/$i/demux-channel-count")
            val selected = MpvLibrary.getPropertyString(h, "track-list/$i/selected") == "yes"

            val name = cleanTrackDisplayName(
                type = type,
                id = id,
                rawTitle = title,
                rawLang = lang,
                codec = codec,
                isForced = isForced,
                isDefault = isDefault,
                isExternal = isExternal,
                channels = channels,
            )
            if (type == "audio") {
                audioTracks.add(PlayerState.VideoTrack(id, name, selected))
                audioSearchInfo.add(Triple(id, selected, "${lang.orEmpty()} ${title.orEmpty()} $name ${if (isOriginal) "original" else ""}"))
            } else if (type == "sub") {
                subTracks.add(PlayerState.VideoTrack(id, name, selected))
                subSearchInfo.add(Triple(id, selected, "${lang.orEmpty()} ${title.orEmpty()} $name"))
            } else if (type == "video") {
                val res = MpvLibrary.getPropertyString(h, "track-list/$i/demux-h") ?: ""
                val fpsVal = MpvLibrary.getPropertyString(h, "track-list/$i/demux-fps")?.toDoubleOrNull() ?: 0.0
                val finalName = if (res.isNotEmpty()) {
                    if (fpsVal > 30.0) "${res}p ${fpsVal.toInt()}fps" else "${res}p"
                } else {
                    name
                }
                videoTracks.add(PlayerState.VideoTrack(id, finalName, selected))
            }
        }

        // Disambiguate duplicate names
        val subNameCounts = subTracks.groupingBy { it.name }.eachCount()
        val subDupTracker = mutableMapOf<String, Int>()
        val disambiguatedSubTracks = subTracks.map { track ->
            if ((subNameCounts[track.name] ?: 0) > 1) {
                val idx = (subDupTracker[track.name] ?: 0) + 1
                subDupTracker[track.name] = idx
                track.copy(name = "${track.name} #$idx")
            } else {
                track
            }
        }

        val audioNameCounts = audioTracks.groupingBy { it.name }.eachCount()
        val audioDupTracker = mutableMapOf<String, Int>()
        val disambiguatedAudioTracks = audioTracks.map { track ->
            if ((audioNameCounts[track.name] ?: 0) > 1) {
                val idx = (audioDupTracker[track.name] ?: 0) + 1
                audioDupTracker[track.name] = idx
                track.copy(name = "${track.name} #$idx")
            } else {
                track
            }
        }

        playerState?._audioTracks?.value = disambiguatedAudioTracks
        playerState?._subtitleTracks?.value = disambiguatedSubTracks
        playerState?._videoTracks?.value = videoTracks

        val vFormat = MpvLibrary.getPropertyString(h, "video-format")
        val hasVideo = videoTracks.isNotEmpty() || (vFormat?.isNotBlank() == true && !vFormat.equals("none", ignoreCase = true))
        val isAudioOnly = trackCount > 0 && !hasVideo
        if (isAudioOnly) {
            playerState?._isAudioOnlyStream?.value = true
        } else if (trackCount > 0 && hasVideo) {
            playerState?._isAudioOnlyStream?.value = false
        }

        // Auto audio track selection based on priority
        if (!hasAutoSwitchedAudio && audioSearchInfo.isNotEmpty()) {
            val candidateLangs = LanguagePriorityHelper.getOrderedAudioLanguages()
            for (langCode in candidateLangs) {
                val target = audioSearchInfo.firstOrNull { (_, _, meta) ->
                    LanguageMatcher.matchesAudioTrack(null, null, meta, langCode)
                }
                if (target != null) {
                    if (!target.second) {
                        AppLogger.i("DesktopMpvEngine", "Auto-switching audio track to id=${target.first} for priority lang=$langCode")
                        lib.mpv_set_property_string(h, "aid", target.first.toString())
                    }
                    hasAutoSwitchedAudio = true
                    break
                }
            }
        }

        // Auto subtitle track selection based on priority
        val subEnabled = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_ENABLED)
            ?: (DesktopDataStore.getKey<String>(PlayerConfig.PREF_PREFERRED_SUB_LANG) != "off")

        if (!subEnabled) {
            if (!hasAutoSwitchedSub) {
                val currentSid = MpvLibrary.getPropertyString(h, "sid")
                if (currentSid != null && currentSid != "no") {
                    lib.mpv_set_property_string(h, "sid", "no")
                }
                hasAutoSwitchedSub = true
            }
        } else if (!hasAutoSwitchedSub && !(playerState?.hasUserManuallyChangedSub ?: false) && subSearchInfo.isNotEmpty()) {
            val candidateLangs = LanguagePriorityHelper.getOrderedSubtitleLanguages()
            for (langCode in candidateLangs) {
                val target = subSearchInfo.firstOrNull { (_, _, meta) ->
                    LanguageMatcher.matchesSubtitleTrack(null, null, meta, langCode)
                }
                if (target != null) {
                    if (!target.second) {
                        AppLogger.i("DesktopMpvEngine", "Auto-switching subtitle track to id=${target.first} for priority lang=$langCode")
                        lib.mpv_set_property_string(h, "sid", target.first.toString())
                        lib.mpv_set_property_string(h, "sub-visibility", "yes")
                    }
                    hasAutoSwitchedSub = true
                    break
                }
            }
        }

        // Lazy audio track fallback
        val lazyAudios = LocalStreamProxyState.lazyAudioTracks.value
        if (trackCount > 0 && audioTracks.isEmpty() && lazyAudios.isNotEmpty()) {
            val currentActiveUrl = playerState?.activeLazyAudioTrackUrl?.value
            val targetTrack = if (currentActiveUrl != null) {
                lazyAudios.find { it.url == currentActiveUrl } ?: lazyAudios.first()
            } else {
                val candidateLangs = LanguagePriorityHelper.getOrderedAudioLanguages()
                val matchedAudio = candidateLangs.firstNotNullOfOrNull { candidateCode ->
                    val keywords = LanguageMatcher.getKeywordsForCode(candidateCode)
                    lazyAudios.firstOrNull { audio ->
                        val combined = "${audio.language} ${audio.name}".lowercase()
                        keywords.any { kw -> combined.contains(kw) }
                    }
                }
                matchedAudio ?: lazyAudios.first()
            }
            AppLogger.i("DesktopMpvEngine", "Auto-attaching audio track: ${targetTrack.name}")
            playerState?.loadLazyAudioTrack(PlayerState.LazyTrack(targetTrack.url, targetTrack.name, targetTrack.language, targetTrack.bitrate))
        }

        val w = MpvLibrary.getPropertyString(h, "width") ?: "0"
        val hw = MpvLibrary.getPropertyString(h, "height") ?: "0"
        if (w != "0" && hw != "0") {
            playerState?._resolution?.value = "${w}x$hw"
        }

        val fs = MpvLibrary.getPropertyString(h, "file-size")?.toLongOrNull() ?: 0L
        if (fs > 0L) {
            playerState?._fileSize?.value = fs
        }

        // Poll Chapters
        val chapterCount = MpvLibrary.getPropertyString(h, "chapter-list/count")?.toIntOrNull() ?: 0
        if (chapterCount > 0) {
            val chapters = mutableListOf<PlayerState.Chapter>()
            for (c in 0 until chapterCount) {
                val chTitle = MpvLibrary.getPropertyString(h, "chapter-list/$c/title") ?: "Chapter ${c + 1}"
                val chTime = MpvLibrary.getPropertyString(h, "chapter-list/$c/time")?.toDoubleOrNull() ?: 0.0
                chapters.add(PlayerState.Chapter(index = c, title = chTitle, timeMs = (chTime * 1000).toLong()))
            }
            playerState?.updateChaptersFromPlayer(chapters)
            val currentChapter = MpvLibrary.getPropertyString(h, "chapter")?.toIntOrNull() ?: -1
            playerState?._currentChapterIndex?.value = currentChapter
        } else {
            playerState?.updateChaptersFromPlayer(emptyList())
            playerState?._currentChapterIndex?.value = -1
        }
    }

    fun pollVideoStats(h: Pointer, ps: PlayerState) {
        if (!ps._showStats.value) return
        ps._videoCodec.value = MpvLibrary.getPropertyString(h, "video-codec") ?: "Unknown"
        ps._audioCodec.value = MpvLibrary.getPropertyString(h, "audio-codec") ?: "Unknown"
        ps._hwdecCurrent.value = MpvLibrary.getPropertyString(h, "hwdec-current") ?: "Unknown"
        ps._droppedFrames.value = MpvLibrary.getPropertyString(h, "vo-drop-frame-count")?.toLongOrNull() ?: 0L
        ps._fps.value = MpvLibrary.getPropertyString(h, "container-fps")?.toDoubleOrNull() ?: 0.0
        val w = MpvLibrary.getPropertyString(h, "width") ?: "0"
        val hw = MpvLibrary.getPropertyString(h, "height") ?: "0"
        ps._resolution.value = "${w}x$hw"
        ps._videoBitrate.value = MpvLibrary.getPropertyString(h, "video-bitrate")?.toLongOrNull() ?: 0L
        ps._audioBitrate.value = MpvLibrary.getPropertyString(h, "audio-bitrate")?.toLongOrNull() ?: 0L
    }
}
