package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.badges.CardMetadataConfig
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.ContinueWatchingStyle
import com.lagradost.cloudstream3.desktop.ui.theme.PosterMetadataPosition
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTextAlignment
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTitlePosition
import com.lagradost.cloudstream3.desktop.ui.theme.ProviderBadgeDisplayMode
import kotlin.math.roundToInt

/**
 * Poster Studio: a persistent split-pane editor with grouped controls on the left and a live
 * preview of the home shelves on the right. Every change is applied and persisted immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPosterEditorScreen(
    onBack: () -> Unit = {},
    viewModel: PosterEditorViewModel = remember { PosterEditorViewModel() },
) {
    val theme = LocalDesktopTheme.current

    val posterWidthDp by AppearanceConfig.posterWidthDp.collectAsState()
    val homeSpacingDp by AppearanceConfig.homeSpacingDp.collectAsState()
    val homeVerticalSpacingDp by AppearanceConfig.homeVerticalSpacingDp.collectAsState()
    val posterRoundingDp by AppearanceConfig.posterRoundingDp.collectAsState()
    val posterTitlePosition by AppearanceConfig.posterTitlePosition.collectAsState()
    val posterMetadataPosition by AppearanceConfig.posterMetadataPosition.collectAsState()
    val posterTextAlignment by AppearanceConfig.posterTextAlignment.collectAsState()
    val posterTitleMaxLines by AppearanceConfig.posterTitleMaxLines.collectAsState()
    val continueWatchingStyle by AppearanceConfig.continueWatchingStyle.collectAsState()
    val providerBadgeDisplayMode by AppearanceConfig.providerBadgeDisplayMode.collectAsState()
    val posterHoverGlowEnabled by AppearanceConfig.posterHoverGlowEnabled.collectAsState()

    val autoCleanTitles by CardMetadataConfig.autoCleanTitles.collectAsState()
    val autoDetectSubDub by CardMetadataConfig.autoDetectSubDub.collectAsState()
    val autoDetectQuality by CardMetadataConfig.autoDetectQuality.collectAsState()
    val showRatingBadges by CardMetadataConfig.showRatingBadges.collectAsState()

    val uiState by viewModel.uiState.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    val cinematicPresetSelected = PosterStudioPresets.isCinematicSelected(
        textAlignment = posterTextAlignment,
        metadataPosition = posterMetadataPosition,
        titlePosition = posterTitlePosition,
        titleMaxLines = posterTitleMaxLines,
        roundingDp = posterRoundingDp,
        autoCleanTitles = autoCleanTitles,
    )
    val centeredPresetSelected = PosterStudioPresets.isCenteredSelected(
        textAlignment = posterTextAlignment,
        metadataPosition = posterMetadataPosition,
        titlePosition = posterTitlePosition,
    )
    val onPosterPresetSelected = PosterStudioPresets.isOnPosterSelected(posterMetadataPosition)
    val compactPresetSelected = PosterStudioPresets.isCompactSelected(
        textAlignment = posterTextAlignment,
        metadataPosition = posterMetadataPosition,
        titlePosition = posterTitlePosition,
        titleMaxLines = posterTitleMaxLines,
        posterWidthDp = posterWidthDp,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = if (maxWidth < 900.dp) 320.dp else 380.dp

        Column(modifier = Modifier.fillMaxSize()) {
            PosterStudioTopBar(
                onBack = onBack,
                onReset = { showResetDialog = true },
            )
            HorizontalDivider(color = theme.Divider.copy(alpha = 0.5f))

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Surface(
                    modifier = Modifier.width(panelWidth).fillMaxHeight(),
                    color = theme.SurfaceElevated.copy(alpha = 0.35f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        StudioSection(
                            title = "Presets",
                            subtitle = "Curated starting points. Fine-tune anything below.",
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StudioPresetChip("Cinematic", cinematicPresetSelected) { PosterStudioPresets.applyCinematic() }
                                StudioPresetChip("Centered", centeredPresetSelected) { PosterStudioPresets.applyCentered() }
                                StudioPresetChip("Badges on Card", onPosterPresetSelected) { PosterStudioPresets.applyOnPoster() }
                                StudioPresetChip("Ultra Compact", compactPresetSelected) { PosterStudioPresets.applyCompact() }
                            }
                        }

                        StudioSection(
                            title = "Layout & Sizing",
                            subtitle = "Card dimensions and the gaps between them.",
                        ) {
                            StudioSliderControl(
                                label = "Poster Width",
                                valueText = "$posterWidthDp dp",
                                value = posterWidthDp.toFloat(),
                                valueRange = 100f..250f,
                                step = 5f,
                                onValueChange = { AppearanceConfig.setPosterWidthDp(it.roundToInt()) },
                            )
                            StudioDivider()
                            StudioSliderControl(
                                label = "Card Spacing",
                                valueText = "$homeSpacingDp dp",
                                value = homeSpacingDp.toFloat(),
                                valueRange = 0f..32f,
                                step = 2f,
                                onValueChange = { AppearanceConfig.setHomeSpacingDp(it.roundToInt()) },
                            )
                            StudioDivider()
                            StudioSliderControl(
                                label = "Row Spacing",
                                valueText = "$homeVerticalSpacingDp dp",
                                value = homeVerticalSpacingDp.toFloat(),
                                valueRange = 0f..64f,
                                step = 4f,
                                onValueChange = { AppearanceConfig.setHomeVerticalSpacingDp(it.roundToInt()) },
                            )
                            StudioDivider()
                            StudioSliderControl(
                                label = "Corner Radius",
                                valueText = "$posterRoundingDp dp",
                                value = posterRoundingDp.toFloat(),
                                valueRange = 0f..24f,
                                step = 2f,
                                onValueChange = { AppearanceConfig.setPosterRoundingDp(it.roundToInt()) },
                            )
                        }

                        StudioSection(
                            title = "Typography & Placement",
                            subtitle = "Where titles and metadata sit on each card.",
                        ) {
                            StudioChoiceControl(
                                label = "Title Position",
                                options = listOf(
                                    PosterTitlePosition.BELOW to "Below",
                                    PosterTitlePosition.INSIDE to "On hover",
                                    PosterTitlePosition.HIDDEN to "Hidden",
                                ),
                                selected = posterTitlePosition,
                                onSelect = { AppearanceConfig.setPosterTitlePosition(it) },
                            )
                            StudioDivider()
                            StudioChoiceControl(
                                label = "Metadata Position",
                                options = listOf(
                                    PosterMetadataPosition.BELOW_POSTER to "Below",
                                    PosterMetadataPosition.ON_POSTER to "On card",
                                    PosterMetadataPosition.HYBRID to "Hybrid",
                                    PosterMetadataPosition.STEALTH_HOVER to "Hover",
                                ),
                                selected = posterMetadataPosition,
                                onSelect = { AppearanceConfig.setPosterMetadataPosition(it) },
                            )
                            StudioDivider()
                            StudioChoiceControl(
                                label = "Alignment",
                                options = listOf(
                                    PosterTextAlignment.LEFT to "Left",
                                    PosterTextAlignment.CENTER to "Center",
                                ),
                                selected = posterTextAlignment,
                                onSelect = { AppearanceConfig.setPosterTextAlignment(it) },
                            )
                            StudioDivider()
                            StudioChoiceControl(
                                label = "Title Lines",
                                options = listOf(
                                    1 to "1 line",
                                    2 to "2 lines",
                                    3 to "3 lines",
                                ),
                                selected = posterTitleMaxLines,
                                onSelect = { AppearanceConfig.setPosterTitleMaxLines(it) },
                            )
                        }

                        StudioSection(
                            title = "Continue Watching",
                            subtitle = "Choose the shelf style used on the home screen.",
                        ) {
                            StudioChoiceControl(
                                label = "Shelf Style",
                                options = listOf(
                                    ContinueWatchingStyle.PREMIUM to "Wide",
                                    ContinueWatchingStyle.DETAILED to "Detailed",
                                    ContinueWatchingStyle.THUMBNAIL to "Classic",
                                ),
                                selected = continueWatchingStyle,
                                onSelect = { AppearanceConfig.setContinueWatchingStyle(it) },
                            )
                        }

                        StudioSection(
                            title = "Badges & Metadata",
                            subtitle = "Control which tags and labels appear on cards.",
                        ) {
                            StudioToggleControl(
                                label = "Clean Titles",
                                subtitle = "Strips release tags and codec noise from titles",
                                checked = autoCleanTitles,
                                onCheckedChange = { CardMetadataConfig.setAutoCleanTitles(it) },
                            )
                            StudioDivider()
                            StudioToggleControl(
                                label = "Rating Badges",
                                subtitle = "Shows the score pill when a rating is available",
                                checked = showRatingBadges,
                                onCheckedChange = { CardMetadataConfig.setShowRatingBadges(it) },
                            )
                            StudioDivider()
                            StudioToggleControl(
                                label = "Quality Badges",
                                subtitle = "Displays resolution tags such as 4K and 1080p",
                                checked = autoDetectQuality,
                                onCheckedChange = { CardMetadataConfig.setAutoDetectQuality(it) },
                            )
                            StudioDivider()
                            StudioToggleControl(
                                label = "SUB / DUB Badges",
                                subtitle = "Flags subtitle and dubbed audio tracks",
                                checked = autoDetectSubDub,
                                onCheckedChange = { CardMetadataConfig.setAutoDetectSubDub(it) },
                            )
                            StudioDivider()
                            StudioChoiceControl(
                                label = "Provider Badges",
                                options = listOf(
                                    ProviderBadgeDisplayMode.HIDDEN to "Hidden",
                                    ProviderBadgeDisplayMode.ICON_ONLY to "Icon only",
                                    ProviderBadgeDisplayMode.FULL_BADGE to "Full",
                                ),
                                selected = providerBadgeDisplayMode,
                                onSelect = { AppearanceConfig.setProviderBadgeDisplayMode(it) },
                            )
                        }

                        StudioSection(
                            title = "Card Effects",
                            subtitle = "Subtle depth and motion cues.",
                        ) {
                            StudioToggleControl(
                                label = "Hover Glow",
                                subtitle = "Ambient light beneath cards on hover",
                                checked = posterHoverGlowEnabled,
                                onCheckedChange = { AppearanceConfig.setPosterHoverGlowEnabled(it) },
                            )
                        }

                        Text(
                            text = "Changes apply instantly and are saved automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.TextMuted,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                    }
                }

                VerticalDivider(color = theme.Divider.copy(alpha = 0.5f))

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PreviewHeader(
                        isLoading = uiState.isLoading,
                        onRefresh = { viewModel.onEvent(PosterEditorUiEvent.RefreshPreviews) },
                    )
                    HorizontalDivider(color = theme.Divider.copy(alpha = 0.4f))
                    PosterStudioPreview(
                        movies = uiState.liveMovies,
                        series = uiState.liveSeries,
                        history = uiState.displayHistory,
                        provider = uiState.activeProvider,
                        posterWidth = posterWidthDp.dp,
                        cardSpacing = homeSpacingDp.dp,
                        rowSpacing = homeVerticalSpacingDp.dp,
                        continueWatchingStyle = continueWatchingStyle,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        CloudstreamAlertDialog(
            show = showResetDialog,
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Poster Studio?") },
            text = {
                Text(
                    "This restores poster size, spacing, typography, badge, and effect settings " +
                        "to their defaults. Your library and watch history are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PosterStudioPresets.applyDefaults()
                        showResetDialog = false
                    },
                ) {
                    Text("Reset defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
