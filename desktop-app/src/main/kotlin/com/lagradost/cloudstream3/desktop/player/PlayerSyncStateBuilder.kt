package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.player.skip.SkipInterval
import com.lagradost.cloudstream3.desktop.ui.screens.details.EpisodeHistoryLookup
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.player.impl.proxy.ProxyTrack
import java.util.Locale

object PlayerSyncStateBuilder {
    private val LINK_SIZE_REGEX = Regex("""(?i)\[\s*(\d+(?:\.\d+)?\s*(?:GB|MB|GiB|MiB))\s*\]|\((\d+(?:\.\d+)?\s*(?:GB|MB|GiB|MiB))\)|\b(\d+(?:\.\d+)?\s*(?:GB|MB|GiB|MiB))\b""")

    fun formatByteSize(bytes: Long): String? {
        if (bytes < 1024L * 1024L) return null
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    fun buildPlayerUiSyncState(
        plot: String?,
        year: Int?,
        tags: List<String>?,
        contentRating: String?,
        rating: Double?,
        isProbing: Boolean,
        isScraping: Boolean,
        backdropUrl: String?,
        logoUrl: String?,
        currentLinkIndex: Int,
        failedLinks: Map<Int, String>,
        links: List<ExtractorLink>,
        liveFileSize: Long,
        providerName: String?,
        episodes: List<Episode>,
        currentEpisodeId: String?,
        currentEpisodeNumber: Int?,
        currentSeasonNumber: Int?,
        seriesPosterUrl: String?,
        episodeHistoryLookup: EpisodeHistoryLookup,
        audioTracks: List<PlayerState.VideoTrack>,
        subtitleTracks: List<PlayerState.VideoTrack>,
        videoTracks: List<PlayerState.VideoTrack>,
        proxyAudioTracks: List<ProxyTrack>,
        proxySubtitleTracks: List<ProxyTrack>,
        proxyVideoTracks: List<ProxyTrack>,
        autoFetchedSubtitleTracks: List<LazyTrackPayload>,
        startPositionMs: Long,
        title: String?,
        activeShader: String?,
        activeLazyVideoTrackUrl: String?,
        activeLazyAudioTrackUrl: String?,
        resolution: String?,
        activeSubtitleOverrideEnabled: Boolean,
        isLive: Boolean,
        isAudioOnlyStream: Boolean,
        isAudioMode: Boolean,
        chapters: List<PlayerState.Chapter>,
        currentChapterIndex: Int,
        activeSkipInterval: SkipInterval?,
        skipIntervals: List<SkipInterval>,
        actors: List<ActorData>,
        isAnime: Boolean,
        isExhausted: Boolean,
        exhaustionReason: String?,
        exhaustionDiagnostics: String?,
        resolvedImdbId: String?,
    ): PlayerUiSyncState {
        val rawBackdrop = backdropUrl?.let { raw -> if (raw.startsWith("//")) "https:$raw" else raw }
        val rawLogo = logoUrl?.let { raw -> if (raw.startsWith("//")) "https:$raw" else raw }
        val safeBackdrop = com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(rawBackdrop, isBackdrop = true) ?: rawBackdrop
        val safeLogo = com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(rawLogo, isBackdrop = false) ?: rawLogo

        val starringCast = actors.filter { actorData ->
            val role = actorData.roleString ?: actorData.role?.name
            role?.equals("Director", ignoreCase = true) != true &&
                role?.equals("Creator", ignoreCase = true) != true &&
                role?.equals("Writer", ignoreCase = true) != true &&
                role?.equals("Producer", ignoreCase = true) != true &&
                role?.equals("Executive Producer", ignoreCase = true) != true
        }

        val isAnimationTitle = isAnime ||
            tags?.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) } == true

        val safeActors = starringCast.take(8).map { actorData ->
            val rawImg = actorData.actor.image?.let { raw -> if (raw.startsWith("//")) "https:$raw" else raw }
            val enhancedImg = com.lagradost.cloudstream3.desktop.utils.ImageUtils.enhanceProfileUrl(rawImg) ?: rawImg
            val safeImg = enhancedImg?.let { com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(it) ?: it }

            val rawVaImg = actorData.voiceActor?.image?.let { raw -> if (raw.startsWith("//")) "https:$raw" else raw }
            val enhancedVaImg = com.lagradost.cloudstream3.desktop.utils.ImageUtils.enhanceProfileUrl(rawVaImg) ?: rawVaImg
            val safeVaImg = enhancedVaImg?.let { com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(it) ?: it }

            ActorPayload(
                name = actorData.actor.name,
                role = actorData.roleString ?: actorData.role?.name,
                image = safeImg,
                voiceActorName = actorData.voiceActor?.name,
                voiceActorImage = safeVaImg,
                isAnime = actorData.voiceActor != null || (isAnimationTitle && isAnime),
            )
        }

        val availableShaders = ShaderManager.getAvailableShaders()
        val availableFonts = com.lagradost.cloudstream3.desktop.ui.theme.CustomFontManager.getAvailableFonts()
        val activeSubtitleFont = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_FONT)?.takeIf { it.isNotBlank() } ?: "Inter"
        val activeSubtitleBackground = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BG)
        val activeSubtitleBorderColor = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BORDER_COLOR)
        val activeSubtitleBorderSize = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BORDER_SIZE)
        val activeSubtitleShadowColor = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SHADOW_COLOR)
        val activeSubtitleShadowOffset = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SHADOW_OFFSET)
        val activeSubtitleBlur = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BLUR)
        val activeSubtitleBold = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BOLD)
        val activeSubtitleItalic = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_ITALIC)
        val activeSubtitleColor = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_COLOR) ?: "#FFFFFF"
        val activeSubtitleSize = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SIZE) ?: "45"
        val activeSubtitlePos = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_POS) ?: "100"
        val activeSubtitleRemoveCaptions = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_REMOVE_CAPTIONS) ?: false
        val activeSubtitleRemoveBloat = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_REMOVE_BLOAT) ?: true
        val activeSubtitleUppercase = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_UPPERCASE) ?: false
        val audioNormalization = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUDIO_NORMALIZATION) ?: false
        val audioNormStrength = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_AUDIO_NORM_STRENGTH) ?: "Medium"
        val audioSpatial = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUDIO_SPATIAL) ?: false
        val audioEqPreset = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_AUDIO_EQ_PRESET) ?: "Flat"
        val audioVolumeMax = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUDIO_VOLUME_MAX) ?: false
        val audioDelay = com.lagradost.common.storage.DesktopDataStore.getKey<Float>(PlayerConfig.PREF_AUDIO_DELAY) ?: 0f

        return PlayerUiSyncState(
            plot = plot,
            year = year,
            tags = tags,
            contentRating = contentRating,
            rating = rating,
            isProbing = isProbing,
            isScraping = isScraping,
            backdropUrl = safeBackdrop,
            logoUrl = safeLogo,
            currentLinkIndex = currentLinkIndex,
            failedLinks = failedLinks.map { FailedLinkPayload(it.key, it.value, links.getOrNull(it.key)?.url) },
            links = links.mapIndexed { index, l ->
                val isTorrent = com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine.isTorrentLink(l)
                val nameSize = LINK_SIZE_REGEX.find(l.name)?.let { mr ->
                    val matched = mr.groupValues[1].ifBlank { mr.groupValues[2].ifBlank { mr.groupValues[3] } }.trim()
                    val num = matched.replace(Regex("""(?i)[^\d.]"""), "").toDoubleOrNull() ?: 0.0
                    if (num > 0.0) matched else null
                }
                val isAdaptiveStream = l.isM3u8 || l.isDash || l.url.contains(".m3u8", ignoreCase = true) || l.url.contains(".mpd", ignoreCase = true)
                val currentLiveSize = if (index == currentLinkIndex && liveFileSize > 0L && !isAdaptiveStream) formatByteSize(liveFileSize) else null
                val effectiveSize = currentLiveSize ?: nameSize

                val addonSource = l.source.trim().ifBlank { null }
                val hostSource = if (l.source.isNotBlank() && l.source != l.name) l.source else com.lagradost.cloudstream3.desktop.ui.screens.links.extractCleanServer(l.name, "")
                val targetName = addonSource ?: hostSource.takeIf { it.isNotBlank() } ?: providerName
                val resolvedLogo = if (!targetName.isNullOrBlank()) {
                    com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.getPluginIcon(targetName)
                        ?: com.lagradost.cloudstream3.desktop.stremio.StremioAddonManager.addons.value.firstOrNull {
                            it.name.equals(targetName, ignoreCase = true) ||
                                targetName.contains(it.name, ignoreCase = true) ||
                                it.name.contains(targetName, ignoreCase = true)
                        }?.logoUrl
                        ?: providerName?.let { com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.getPluginIcon(it) }
                } else null
                val safeStreamLogo = resolvedLogo?.let { com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(it) ?: it }

                LinkPayload(
                    index = index,
                    name = l.name,
                    quality = l.quality,
                    isActive = (index == currentLinkIndex),
                    isM3u8 = l.isM3u8,
                    isDash = l.isDash,
                    url = l.url,
                    isTorrent = isTorrent,
                    source = l.source,
                    size = effectiveSize,
                    logoUrl = safeStreamLogo,
                )
            },
            episodes = episodes.map {
                val epRawPoster = it.posterUrl?.takeIf { p -> p.isNotBlank() }
                val resolvedEpPoster = if (epRawPoster != null) {
                    if (epRawPoster.startsWith("//")) "https:$epRawPoster" else epRawPoster
                } else {
                    seriesPosterUrl
                }
                val isCurrent = (currentEpisodeId != null && it.data == currentEpisodeId) ||
                    (currentEpisodeNumber != null && it.episode == currentEpisodeNumber && (currentSeasonNumber == null || it.season == null || it.season == currentSeasonNumber))
                val hist = episodeHistoryLookup.find(it)
                val progress = if (hist != null && hist.duration > 0) {
                    if (com.lagradost.player.impl.PlayerLinkHandler.isCompleted(hist.position, hist.duration)) {
                        1.0
                    } else {
                        (hist.position.toDouble() / hist.duration.toDouble()).coerceIn(0.0, 1.0)
                    }
                } else {
                    null
                }
                val isWatched = progress != null && (progress >= 0.9 || (hist != null && com.lagradost.player.impl.PlayerLinkHandler.isCompleted(hist.position, hist.duration)))

                val normalizedPoster = resolvedEpPoster?.let { url -> if (url.startsWith("//")) "https:$url" else url }
                val safePoster = com.lagradost.cloudstream3.desktop.utils.ImageUtils.getCachedDiskFileUri(normalizedPoster) ?: normalizedPoster

                val lockUnreleased = com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.lockUnreleasedEpisodes.value
                val releaseStatus = com.lagradost.cloudstream3.desktop.ui.screens.details.parseEpisodeReleaseStatus(it, providerName)
                val hasPlayableData = it.data.isNotBlank() &&
                    !it.data.startsWith("unreleased_") &&
                    !it.data.startsWith("synthetic_") &&
                    it.data != "null" && it.data != "{}" && it.data != "[]"
                val hasCachedLinks = com.lagradost.cloudstream3.desktop.ui.screens.player.LinkCache.get(it.data) != null
                val isLocked = !hasCachedLinks && (
                    (releaseStatus.isUnreleased && lockUnreleased) || releaseStatus.isMissingFromProvider || !hasPlayableData
                )
                val releaseText = releaseStatus.statusBadgeText ?: releaseStatus.formattedDate

                EpisodePayload(
                    id = it.data,
                    title = it.name ?: "Episode ${it.episode}",
                    season = it.season,
                    episode = it.episode,
                    isActive = isCurrent,
                    posterUrl = safePoster,
                    description = it.description,
                    runTime = it.runTime,
                    score = it.score?.toFloat(10)?.toDouble(),
                    watchedPercentage = progress?.let { p -> p * 100.0 },
                    isSeen = isWatched,
                    isLocked = isLocked,
                    releaseDateText = releaseText,
                )
            },
            audioTracks = audioTracks.map {
                SubtitleTrackPayload(it.id, it.name, it.isSelected)
            },
            subTracks = subtitleTracks.map {
                val safeName = it.name.replace(Regex("""[\r\n]+"""), " | ").trim().let { name -> if (name.length > 150) name.take(147) + "..." else name }
                SubtitleTrackPayload(it.id, safeName, it.isSelected)
            },
            videoTracks = videoTracks.map {
                SubtitleTrackPayload(it.id, it.name, it.isSelected)
            },
            lazyAudioTracks = proxyAudioTracks.map {
                LazyTrackPayload(it.url, it.name, it.language)
            },
            lazySubTracks = (
                proxySubtitleTracks.map {
                    val safeName = it.name.replace(Regex("""[\r\n]+"""), " | ").trim().let { name -> if (name.length > 150) name.take(147) + "..." else name }
                    LazyTrackPayload(it.url, safeName, it.language)
                } + autoFetchedSubtitleTracks
                ).distinctBy { it.url },
            lazyVideoTracks = proxyVideoTracks.map {
                LazyTrackPayload(it.url, it.name, it.language)
            },
            startPositionMs = startPositionMs,
            title = title ?: "",
            shaders = availableShaders,
            activeShader = activeShader,
            activeSubtitleFont = activeSubtitleFont,
            availableSubtitleFonts = availableFonts,
            activeSubtitleBackground = activeSubtitleBackground,
            activeSubtitleBorderColor = activeSubtitleBorderColor,
            activeSubtitleBorderSize = activeSubtitleBorderSize,
            activeSubtitleShadowColor = activeSubtitleShadowColor,
            activeSubtitleShadowOffset = activeSubtitleShadowOffset,
            activeSubtitleBlur = activeSubtitleBlur,
            activeSubtitleBold = activeSubtitleBold,
            activeSubtitleItalic = activeSubtitleItalic,
            activeLazyVideoTrackUrl = activeLazyVideoTrackUrl,
            activeLazyAudioTrackUrl = activeLazyAudioTrackUrl,
            resolution = resolution,
            activeSubtitleOverrideEnabled = activeSubtitleOverrideEnabled,
            activeSubtitleColor = activeSubtitleColor,
            activeSubtitleSize = activeSubtitleSize,
            activeSubtitlePos = activeSubtitlePos,
            activeSubtitleRemoveCaptions = activeSubtitleRemoveCaptions,
            activeSubtitleRemoveBloat = activeSubtitleRemoveBloat,
            activeSubtitleUppercase = activeSubtitleUppercase,
            isLive = isLive,
            isAudioOnlyStream = isAudioOnlyStream,
            isAudioMode = isAudioMode,
            chapters = chapters.map { ChapterPayload(it.index, it.title, it.timeMs) },
            currentChapterIndex = currentChapterIndex,
            activeSkipInterval = activeSkipInterval?.let {
                SkipIntervalPayload(it.startMs, it.endMs, it.type.name, it.label, it.providerId)
            },
            skipIntervals = skipIntervals.map {
                SkipIntervalPayload(it.startMs, it.endMs, it.type.name, it.label, it.providerId)
            },
            actors = safeActors,
            isExhausted = isExhausted,
            exhaustionReason = exhaustionReason,
            exhaustionDiagnostics = exhaustionDiagnostics,
            audioNormalization = audioNormalization,
            audioNormStrength = audioNormStrength,
            audioSpatial = audioSpatial,
            audioEqPreset = audioEqPreset,
            audioVolumeMax = audioVolumeMax,
            audioDelay = audioDelay,
            imdbId = resolvedImdbId,
        )
    }

    fun buildAppStateUpdatePayload(
        volume: Float,
        isMuted: Boolean,
        isBuffering: Boolean,
        isLoading: Boolean,
        loadingStatusText: String?,
        fileSizeBytes: Long,
    ): AppStateUpdatePayload {
        val interpolationEnabled = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_INTERPOLATION) ?: false
        val autoPlayEnabled = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY) ?: true
        val showEndTime = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SHOW_END_TIME) ?: false
        val showClock = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SHOW_CLOCK) ?: false
        val showServerQuality = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SHOW_SERVER_QUALITY) ?: false
        val pauseInfoMode = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_PAUSE_INFO_MODE) ?: "delay_5s"
        val showPauseCast = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_PAUSE_SHOW_CAST) ?: true
        val seekDurationMs = (PlayerConfig.getSeekDurationSeconds() * 1000).toLong()
        val hdrToneMapping = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_HDR_TONE_MAPPING) ?: "spline"
        val hdrComputePeak = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_HDR_COMPUTE_PEAK) ?: true
        val debandQuality = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_DEBAND_QUALITY) ?: "off"
        val dithering = com.lagradost.common.storage.DesktopDataStore.getKey<String>(PlayerConfig.PREF_DITHERING) ?: "fruit"

        return AppStateUpdatePayload(
            volume = volume,
            isMuted = isMuted,
            isAppLoading = isLoading,
            loadingStatusText = loadingStatusText,
            debugWait = false,
            debugHasEver = true,
            debugPos = 0.0,
            interpolationEnabled = interpolationEnabled,
            autoPlayEnabled = autoPlayEnabled,
            showEndTime = showEndTime,
            showClock = showClock,
            showServerQuality = showServerQuality,
            pauseInfoMode = pauseInfoMode,
            showPauseCast = showPauseCast,
            seekDurationMs = seekDurationMs,
            fileSize = fileSizeBytes.takeIf { it > 0L }?.let { formatByteSize(it) },
            hdrToneMapping = hdrToneMapping,
            hdrComputePeak = hdrComputePeak,
            debandQuality = debandQuality,
            dithering = dithering,
        )
    }
}
