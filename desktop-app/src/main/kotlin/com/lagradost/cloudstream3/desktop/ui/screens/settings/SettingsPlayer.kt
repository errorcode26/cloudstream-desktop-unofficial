package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// 1. PLAYBACK & MEDIA CATEGORY HUB
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsPlayerHubScreen(
    viewModel: SettingsViewModel,
    onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Live state computations for dynamic badges
    val hwdec = uiState.stringSettings[PlayerConfig.PREF_HWDEC] ?: "auto-safe"
    val gpuApi = uiState.stringSettings[PlayerConfig.PREF_GPU_API] ?: "d3d11"
    val voDriver = uiState.stringSettings[PlayerConfig.PREF_VO_DRIVER] ?: "gpu"
    val ambientMode = uiState.booleanSettings[PlayerConfig.PREF_AMBIENT_MODE]
        ?: remember { DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AMBIENT_MODE) ?: false }
    val hwdecBadge = when {
        ambientMode || voDriver == "gpu-next" -> "libplacebo • GPU"
        gpuApi == "opengl" -> "OpenGL • GPU"
        hwdec == "auto-safe" -> "Auto-Safe GPU"
        hwdec == "auto-copy" -> "Auto-Copy GPU"
        hwdec == "no" -> "Software CPU"
        else -> "Hardware Accel"
    }

    val audioNorm = uiState.booleanSettings[PlayerConfig.PREF_AUDIO_NORMALIZATION] ?: false
    val audioBadge = if (audioNorm) "Normalization Active" else "Direct Audio"

    val subFont = uiState.stringSettings[PlayerConfig.PREF_SUB_FONT] ?: "Inter"
    val subSize = uiState.stringSettings[PlayerConfig.PREF_SUB_SIZE] ?: "45"
    val subtitleBadge = "$subFont • ${subSize}px"

    val autoPlay = uiState.booleanSettings[PlayerConfig.PREF_AUTO_PLAY] ?: true
    val skipEnabled = uiState.booleanSettings[PlayerConfig.PREF_ENABLE_SKIP_INTERVALS] ?: true
    val autoSkipIntro = uiState.booleanSettings[PlayerConfig.PREF_AUTO_SKIP_INTRO] ?: false
    val autoPlayBadge = when {
        autoPlay && autoSkipIntro -> "Auto-Play & Skip"
        autoPlay -> "Auto-Play Active"
        skipEnabled -> "AniSkip Ready"
        else -> "Manual Selection"
    }

    val downloadThreads = (uiState.floatSettings[DesktopDataStore.PREF_DOWNLOAD_THREADS] ?: 8f).toInt()
    val downloadBadge = "$downloadThreads Threads"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Card 1: Video & Hardware Engine
        SettingsHubCard(
            icon = Icons.Outlined.PlayCircle,
            title = "Video & Hardware Engine",
            subtitle = "GPU hardware decoding acceleration, smooth display resample interpolation, pause metadata overlays, and yt-dlp format streams.",
            badge = hwdecBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.PLAYER_RENDERING_ENGINE) },
        )

        // Card 2: Audio Processing & Equalizer
        SettingsHubCard(
            icon = Icons.Outlined.GraphicEq,
            title = "Audio Processing & Equalizer",
            subtitle = "Volume normalization (dynamic range compression), dialogue boost, multi-channel downmixing, audio sync offset delay, and EQ sound presets.",
            badge = audioBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.PLAYER_AUDIO_EQ) },
        )

        // Card 3: Subtitle Styling Studio
        SettingsHubCard(
            icon = Icons.Outlined.Subtitles,
            title = "Subtitle Styling Studio",
            subtitle = "Interactive preview studio for font typography, font size, text colors, border outlines, drop shadow blur, and background opacity.",
            badge = subtitleBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.SUBTITLES) },
        )

        // Card 4: Stream Scraping & Priorities
        SettingsHubCard(
            icon = Icons.Outlined.Tune,
            title = "Stream Scraping & Priorities",
            subtitle = "Video quality resolution priority order (4K/1080p/720p), preferred audio language stack, and fallback subtitle language stack.",
            badge = "Priority Stack",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.STREAM_PRIORITIES) },
        )

        // Card 5: Auto-Play & Skip Automation
        SettingsHubCard(
            icon = Icons.Default.FastForward,
            title = "Auto-Play & Skip Automation",
            subtitle = "Automatic next episode stream connection, provider connection timeout, AniSkip opening/ending intro and outro skipping.",
            badge = autoPlayBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.PLAYER_AUTOPLAY_SKIP) },
        )

        // Card 6: Downloads & Offline Storage
        SettingsHubCard(
            icon = Icons.Outlined.Download,
            title = "Downloads & Storage Engine",
            subtitle = "Offline downloads directory, multi-threaded parallel download chunks, concurrent task limits, and screenshot export path.",
            badge = downloadBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.PLAYER_DOWNLOADS) },
        )

        // Card 7: Keyboard Shortcuts & Hotkeys
        SettingsHubCard(
            icon = Icons.Outlined.Keyboard,
            title = "Keyboard Shortcuts & Hotkeys",
            subtitle = "Complete desktop keyboard reference for cinematic clean mode, borderless fullscreen, volume leveling, seek intervals, and subtitle sync hotkeys.",
            badge = "30+ Shortcuts",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.KEYBOARD_SHORTCUTS) },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. VIDEO & HARDWARE RENDERING SUB-SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsPlayerRenderingScreen(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsGroupCard(title = "Hardware Acceleration & Frame Pacing") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_HWDEC,
                label = "Hardware Acceleration",
                subtitle = "Choose how video decoding is handled by your GPU hardware",
                options = listOf(
                    "auto-safe" to "Auto Safe (Recommended)",
                    "auto-copy" to "Auto Copy (Fallback for older GPUs)",
                    "no" to "Software Decoding (CPU Only)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "auto-safe",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_GPU_API,
                label = "Graphics Rendering API",
                subtitle = "Low-level graphics pipeline (switch to OpenGL if you experience black screens on fullscreen)",
                options = listOf(
                    "d3d11" to "Direct3D 11 (Recommended / Fast)",
                    "opengl" to "OpenGL (Fallback for older GPUs)",
                    "auto" to "Auto (System Negotiated)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "d3d11",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_VO_DRIVER,
                label = "Video Output Driver",
                subtitle = "Underlying video presentation engine (switch to gpu-next for advanced gamut mapping and HDR)",
                options = listOf(
                    "gpu" to "Standard GPU (Direct3D 11 / OpenGL - Stable Default)",
                    "gpu-next" to "Next-Gen libplacebo (Advanced Gamut & Native HDR Passthrough)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "gpu",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_INTERPOLATION,
                label = "Smooth Video (Display Resample)",
                subtitle = "Eliminates frame pacing judder on high-refresh-rate desktop displays",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_AMBIENT_MODE,
                label = "Ambient Mode (Blurred Borders)",
                subtitle = "Fills letterbox and pillarbox bars with blurred video edge colors via MPV (forces gpu-next; reopen player if active)",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_AMBIENT_BLUR_RADIUS,
                label = "Ambient Blur Intensity",
                subtitle = "Controls how softly blurred video colors spread across letterbox bars",
                options = listOf(
                    "10" to "Subtle (10px)",
                    "20" to "Balanced (20px — Default)",
                    "35" to "Soft (35px)",
                    "60" to "Heavy (60px)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = PlayerConfig.DEFAULT_AMBIENT_BLUR_RADIUS,
            )
        }

        SettingsGroupCard(title = "Image Scalers & Color Fidelity") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_VIDEO_QUALITY_PROFILE,
                label = "Rendering Quality Profile",
                subtitle = "Balancing upscaling algorithms between performance and image sharpness",
                options = listOf(
                    "balanced" to "Balanced (Spline36 - Clean Sharp Upscaling)",
                    "high_quality" to "High Quality (EWA-Lanczos / GPU-HQ - Max Sharpness)",
                    "fast" to "Fast (Bilinear - Low Resource Usage)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "balanced",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_DITHERING,
                label = "Dithering Engine",
                subtitle = "Smooths out 10-bit to 8-bit color gradients to eliminate step-banding artifacts",
                options = listOf(
                    "fruit" to "Fruit (Error Diffusion - Recommended for 8-bit SDR)",
                    "ordered" to "Ordered Dithering",
                    "no" to "Off (Direct 10-bit / Native Output)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "fruit",
            )
        }

        SettingsGroupCard(title = "HDR Tone-Mapping & Banding Correction") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_HDR_TONE_MAPPING,
                label = "HDR Tone-Mapping Curve",
                subtitle = "Algorithm used to compress high-dynamic-range luminance into SDR color gamut",
                options = listOf(
                    "spline" to "Spline (Natural Contrast & Color Balance - Recommended)",
                    "bt.2446a" to "BT.2446 Method A (Broad Dynamic Range)",
                    "mobius" to "Mobius (Smooth Highlight Roll-off)",
                    "clip" to "Direct Clip (Hard Highlight Cutoff)",
                    "auto" to "Auto (MPV Engine Default)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "spline",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_HDR_COMPUTE_PEAK,
                label = "Compute Dynamic Scene Peak",
                subtitle = "Measures per-frame peak brightness to avoid washed-out grey tones on SDR desktop monitors",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = true,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_DEBAND_QUALITY,
                label = "Debanding Filter",
                subtitle = "Smoothes compression macroblocks and color banding across sky and gradient scenes",
                options = listOf(
                    "off" to "Disabled (Raw Stream Bitstream)",
                    "light" to "Light (Fast - removes subtle banding)",
                    "medium" to "Medium (Balanced - recommended for 1080p web streams)",
                    "strong" to "Strong (Heavy smoothing for low-bitrate banding)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "off",
            )
        }

        SettingsGroupCard(title = "Buffer Engine & Seeking") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_VIDEO_BUFFER_SIZE,
                label = "Video Cache Buffer RAM",
                subtitle = "Maximum memory allocated to demuxer stream cache (enforced 100 MB safe minimum)",
                options = listOf(
                    "100000000" to "100 MB (Default / Safe Minimum)",
                    "250000000" to "250 MB (Recommended for 1080p)",
                    "500000000" to "500 MB (Recommended for 4K)",
                    "1000000000" to "1000 MB (1 GB - High Bandwidth)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "100000000",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_VIDEO_BUFFER_LENGTH,
                label = "Forward Buffer Duration",
                subtitle = "Seconds of video stream demuxer reads ahead into RAM (enforced 30s minimum)",
                options = listOf(
                    "30" to "30 Seconds (Default / Safe Minimum)",
                    "60" to "60 Seconds (1 Minute)",
                    "120" to "120 Seconds (2 Minutes)",
                    "300" to "300 Seconds (5 Minutes)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "30",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsDropdown(
                key = PlayerConfig.PREF_SEEK_DURATION,
                label = "Seek Step Duration",
                subtitle = "Time jumped when pressing Left/Right arrow keys, J/L keys, or mouse side buttons",
                options = listOf(
                    "5" to "5 Seconds",
                    "10" to "10 Seconds (Default)",
                    "15" to "15 Seconds",
                    "30" to "30 Seconds",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "10",
            )
        }

        SettingsGroupCard(title = "On-Screen Display & Overlays") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_PAUSE_INFO_MODE,
                label = "Pause Metadata Overlay",
                subtitle = "Control if and when title, episode synopsis, and age rating badges appear on pause",
                options = listOf(
                    "delay_5s" to "After 5 Seconds Idle (Recommended)",
                    "delay_10s" to "After 10 Seconds Idle",
                    "delay_20s" to "After 20 Seconds Idle",
                    "immediate" to "Immediately on Pause",
                    "off" to "Disabled (Always Clean Frame)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "delay_5s",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_PAUSE_SHOW_CAST,
                label = "Show Starring Cast on Pause",
                subtitle = "Displays lead actors and character roles alongside synopsis when paused",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = true,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_SHOW_CLOCK,
                label = "Show Clock in Player",
                subtitle = "Displays current real-world time in the top bar during playback",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_SHOW_END_TIME,
                label = "Show Estimated End Time",
                subtitle = "Displays when the current video will finish playing",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MviSettingsToggle(
                key = PlayerConfig.PREF_SHOW_SERVER_QUALITY,
                label = "Show Server & Quality",
                subtitle = "Displays the active streaming server and video quality in the player top bar",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )
        }

        SettingsGroupCard(title = "Advanced Engine") {
            MviSettingsDropdown(
                key = PlayerConfig.PREF_YTDL_FORMAT,
                label = "yt-dlp Format / Quality",
                subtitle = "Applies only to external YouTube and yt-dlp extracted stream links",
                options = listOf(
                    "bestvideo[height<=?1080]+bestaudio/best" to "1080p (Full HD)",
                    "bestvideo[height<=?720]+bestaudio/best" to "720p (HD)",
                    "bestvideo[height<=?480]+bestaudio/best" to "480p (SD)",
                    "best" to "Highest Available",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "bestvideo[height<=?1080]+bestaudio/best",
            )
        }

        SettingsGroupCard(title = "Custom MPV Configuration") {
            val mpvConfPath = remember { File(PlatformPaths.appDataDir, "mpv.conf").absolutePath }

            MviSettingsToggle(
                key = PlayerConfig.PREF_ENABLE_CUSTOM_MPV_CONF,
                label = "Load Custom mpv.conf",
                subtitle = "Applies advanced options from $mpvConfPath if the file exists",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = false,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. AUTO-PLAY & SKIP AUTOMATION SUB-SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsPlayerAutoPlayScreen(
    viewModel: SettingsViewModel,
    trackingRepo: TrackingRepository = com.lagradost.cloudstream3.desktop.di.AppContainerHolder.container.trackingRepository,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val autoPlay = uiState.booleanSettings[PlayerConfig.PREF_AUTO_PLAY] ?: true
    val skipEnabled = uiState.booleanSettings[PlayerConfig.PREF_ENABLE_SKIP_INTERVALS] ?: true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsGroupCard(title = "Stream Auto-Play & Timeout") {
            MviSettingsToggle(
                key = PlayerConfig.PREF_AUTO_PLAY,
                label = "Auto-Play Streams",
                subtitle = "Automatically select and stream the highest scoring seekable source when clicking an episode or movie",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = true,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            MviSettingsDropdown(
                key = PlayerConfig.PREF_AUTO_PLAY_TIMEOUT,
                label = "Stream Probe & Failover Timeout",
                subtitle = "Maximum duration to wait for a candidate stream connection before failing over to the next mirror",
                options = listOf(
                    "12000" to "12 Seconds (Aggressive)",
                    "15000" to "15 Seconds (Quick)",
                    "20000" to "20 Seconds (Balanced Default)",
                    "30000" to "30 Seconds (Patient)",
                    "45000" to "45 Seconds (High Latency)",
                    "60000" to "60 Seconds (Slow Network)",
                    "0" to "Disabled (Wait Indefinitely)",
                ),
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = "20000",
            )
        }

        SettingsGroupCard(title = "Intro & Outro Skipping (AniSkip)") {
            MviSettingsToggle(
                key = PlayerConfig.PREF_ENABLE_SKIP_INTERVALS,
                label = "Enable Intro & Outro Discovery",
                subtitle = "Discovers openings, endings, and recaps using AniSkip and embedded chapter markers",
                uiState = uiState,
                onEvent = viewModel::onEvent,
                defaultValue = true,
            )

            if (skipEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                MviSettingsToggle(
                    key = PlayerConfig.PREF_AUTO_SKIP_INTRO,
                    label = "Auto-Skip Openings & Intros",
                    subtitle = "Automatically skips intros without needing to press the on-screen skip button",
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    defaultValue = false,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                MviSettingsToggle(
                    key = PlayerConfig.PREF_AUTO_SKIP_OUTRO,
                    label = "Auto-Skip Endings & Outros",
                    subtitle = "Automatically jumps past ending theme songs and credits",
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    defaultValue = false,
                )
            }
        }

        SettingsGroupCard(title = "Episode Protection & Spoilers") {
            val lockUnreleasedEpisodes by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.lockUnreleasedEpisodes.collectAsState()
            val antiSpoilerEnabled by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.antiSpoilerEnabled.collectAsState()

            SettingsToggleItem(
                label = "Lock Unreleased Episodes",
                subtitle = "Prevent clicking and playing future/unreleased episodes and display countdown/air date badges",
                checked = lockUnreleasedEpisodes,
                onCheckedChange = { com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.setLockUnreleasedEpisodes(it) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            SettingsToggleItem(
                label = "Anti-Spoiler Mode",
                subtitle = "Hide episode thumbnails, titles, and descriptions until watched",
                checked = antiSpoilerEnabled,
                onCheckedChange = { com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.setAntiSpoilerEnabled(it) },
            )
        }

        val trackerSettingsVersion by trackingRepo.settingsVersion.collectAsState()
        val trackerAccounts by com.lagradost.cloudstream3.syncproviders.AccountManager.accountsFlow.collectAsState()
        val trackerScope = rememberCoroutineScope()
        val registeredTrackers = remember { com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry.all() }
        val anyAutoTrackEnabled = remember(trackerSettingsVersion) {
            registeredTrackers.any { trackingRepo.isAutoSyncEnabled(it.id) }
        }
        val thresholdPct = remember(trackerSettingsVersion) {
            trackingRepo.getAutoSyncThresholdPercent()
        }

        SettingsGroupCard(title = "Tracker Progress Auto-Sync") {
            Text(
                text = "Choose which trackers automatically record watched episodes & movies once continuous playback passes the completion threshold.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            registeredTrackers.forEach { tracker ->
                val isConnected = trackerAccounts[tracker.id]?.firstOrNull()?.token?.accessToken?.isNotBlank() == true
                val isEnabled = remember(trackerSettingsVersion, tracker.id) {
                    trackingRepo.isAutoSyncEnabled(tracker.id)
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    ) {
                        com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents.TrackerBrandIcon(
                            trackerId = tracker.id,
                            size = 18.dp,
                        )
                        Column {
                            Text(
                                text = "Auto-Track ${tracker.descriptor.displayName}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when {
                                    isConnected && isEnabled -> "Connected • Auto-syncing at $thresholdPct%"
                                    isConnected -> "Connected • Manual sync only"
                                    else -> "Not signed in (Manage in Settings -> Accounts)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnected && isEnabled) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            trackerScope.launch(Dispatchers.IO) {
                                trackingRepo.setAutoSyncEnabled(enabled, tracker.id)
                            }
                        },
                        modifier = Modifier.testTag("settings_autoplay_auto_track_${tracker.id}"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        ),
                    )
                }
            }

            if (anyAutoTrackEnabled) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                SettingsDropdownItem(
                    label = "Auto-Sync Completion Threshold",
                    subtitle = "Minimum watch percentage before recording remote tracker progress",
                    options = listOf(
                        "80" to "80% Watched (Recommended)",
                        "85" to "85% Watched (Balanced)",
                        "90" to "90% Watched (Near Credits)",
                    ),
                    currentValue = thresholdPct.toString(),
                    onSelectionChanged = { valStr ->
                        valStr.toIntOrNull()?.let { pct ->
                            trackerScope.launch(Dispatchers.IO) {
                                trackingRepo.setAutoSyncThresholdPercent(pct)
                            }
                        }
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. COMPATIBILITY ALIASES
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsPlayer(viewModel: SettingsViewModel, onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    SettingsPlayerHubScreen(viewModel, onNavigateToSubScreen)

@Composable
fun SettingsPlayerPlaybackScreen(
    viewModel: SettingsViewModel,
    onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {},
) = SettingsPlayerHubScreen(viewModel, onNavigateToSubScreen)
