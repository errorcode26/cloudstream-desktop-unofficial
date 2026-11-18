package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.badges.CardMetadataConfig
import com.lagradost.cloudstream3.desktop.ui.components.CategoryRowWithHeader
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCard
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCardDetailed
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCardWide
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.ContinueWatchingStyle
import com.lagradost.cloudstream3.desktop.ui.theme.PosterMetadataPosition
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTextAlignment
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTitlePosition
import com.lagradost.cloudstream3.desktop.ui.theme.ProviderBadgeDisplayMode
import com.lagradost.common.storage.WatchHistory
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

    val cinematicPresetSelected = posterTextAlignment == PosterTextAlignment.LEFT &&
        posterMetadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        posterTitlePosition == PosterTitlePosition.BELOW &&
        posterTitleMaxLines == 2 &&
        posterRoundingDp == 10 &&
        autoCleanTitles
    val centeredPresetSelected = posterTextAlignment == PosterTextAlignment.CENTER &&
        posterMetadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        posterTitlePosition == PosterTitlePosition.BELOW
    val onPosterPresetSelected = posterMetadataPosition == PosterMetadataPosition.ON_POSTER
    val compactPresetSelected = posterTextAlignment == PosterTextAlignment.LEFT &&
        posterMetadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        posterTitlePosition == PosterTitlePosition.BELOW &&
        posterTitleMaxLines == 1 &&
        posterWidthDp == 135

    fun applyCinematicPreset() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterTitleMaxLines(2)
        AppearanceConfig.setPosterRoundingDp(10)
        CardMetadataConfig.setAutoCleanTitles(true)
    }

    fun applyCenteredPreset() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.CENTER)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
    }

    fun applyOnPosterPreset() {
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.ON_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
    }

    fun applyCompactPreset() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterTitleMaxLines(1)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterWidthDp(135)
    }

    fun applyDefaults() {
        AppearanceConfig.setPosterWidthDp(160)
        AppearanceConfig.setHomeSpacingDp(12)
        AppearanceConfig.setHomeVerticalSpacingDp(16)
        AppearanceConfig.setPosterRoundingDp(12)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterTitleMaxLines(2)
        AppearanceConfig.setContinueWatchingStyle(ContinueWatchingStyle.PREMIUM)
        AppearanceConfig.setProviderBadgeDisplayMode(ProviderBadgeDisplayMode.HIDDEN)
        AppearanceConfig.setPosterHoverGlowEnabled(true)
        CardMetadataConfig.setAutoCleanTitles(true)
        CardMetadataConfig.setAutoDetectSubDub(true)
        CardMetadataConfig.setAutoDetectQuality(true)
        CardMetadataConfig.setShowRatingBadges(true)
    }

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
                                StudioPresetChip("Cinematic", cinematicPresetSelected) { applyCinematicPreset() }
                                StudioPresetChip("Centered", centeredPresetSelected) { applyCenteredPreset() }
                                StudioPresetChip("Badges on Card", onPosterPresetSelected) { applyOnPosterPreset() }
                                StudioPresetChip("Ultra Compact", compactPresetSelected) { applyCompactPreset() }
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
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
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
                        applyDefaults()
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

@Composable
private fun PosterStudioTopBar(
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Surface(color = theme.SurfaceElevated.copy(alpha = 0.9f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to appearance settings",
                    tint = theme.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Poster Studio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextPrimary,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = "Live",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "Design poster cards, badges, and home shelves with a real-time preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.TextMuted,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Live Preview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.TextPrimary,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = "Updates live",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "Adjust any control and watch the shelves respond instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reload preview content",
                tint = theme.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PosterStudioPreview(
    movies: List<SearchResponse>,
    series: List<SearchResponse>,
    history: List<WatchHistory>,
    provider: MainAPI?,
    posterWidth: Dp,
    cardSpacing: Dp,
    rowSpacing: Dp,
    continueWatchingStyle: ContinueWatchingStyle,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val isCompact = maxWidth < 600.dp
        val horizontalPadding = if (isCompact) 8.dp else 16.dp
        val spacingDp = if (isCompact) 8.dp else cardSpacing
        val effectiveRowSpacing = if (isCompact) 8.dp else rowSpacing

        val optimalItemWidth = if (isCompact) {
            115.dp
        } else {
            val netWidth = maxWidth - horizontalPadding * 2 - 20.dp
            val exactColumns = (netWidth + spacingDp) / (posterWidth + spacingDp)
            val columns = exactColumns.toInt().coerceAtLeast(1)
            ((netWidth + spacingDp) / columns) - spacingDp
        }

        if (history.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No preview content available",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Install a content provider or retry loading the preview.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(effectiveRowSpacing),
        ) {
            if (history.isNotEmpty()) {
                CategoryRowWithHeader(
                    title = "Continue Watching",
                    itemCount = history.size,
                    rowContentPadding = PaddingValues(
                        horizontal = 10.dp,
                        vertical = if (isCompact) 4.dp else (4.dp + (rowSpacing * 0.25f)),
                    ),
                    headerPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp + (rowSpacing * 0.35f),
                        bottom = 4.dp,
                    ),
                    itemSpacing = spacingDp,
                ) {
                    items(history.size) { index ->
                        val entry = history[index]
                        when (continueWatchingStyle) {
                            ContinueWatchingStyle.PREMIUM -> {
                                val cardWidth = if (isCompact) 280.dp else 340.dp
                                val cardHeight = if (isCompact) 125.dp else 140.dp
                                WatchHistoryCardWide(
                                    modifier = Modifier.width(cardWidth).height(cardHeight),
                                    history = entry,
                                    provider = provider,
                                    onRemove = {},
                                    onClick = {},
                                    onPlayClick = {},
                                )
                            }
                            ContinueWatchingStyle.DETAILED -> {
                                val cardWidth = if (isCompact) 280.dp else 350.dp
                                val cardHeight = if (isCompact) 105.dp else 115.dp
                                WatchHistoryCardDetailed(
                                    modifier = Modifier.width(cardWidth).height(cardHeight),
                                    history = entry,
                                    provider = provider,
                                    onRemove = {},
                                    onClick = {},
                                    onPlayClick = {},
                                )
                            }
                            ContinueWatchingStyle.THUMBNAIL -> {
                                val cardWidth = if (isCompact) 230.dp else 380.dp
                                val cardHeight = cardWidth * 9f / 16f
                                WatchHistoryCard(
                                    modifier = Modifier.width(cardWidth).height(cardHeight),
                                    history = entry,
                                    provider = provider,
                                    onRemove = {},
                                    onClick = {},
                                    onPlayClick = {},
                                )
                            }
                        }
                    }
                }
            }

            if (movies.isNotEmpty()) {
                CategoryRowWithHeader(
                    modifier = Modifier.fillMaxWidth(),
                    title = if (provider != null) "Featured from ${provider.name}" else "Trending Content",
                    itemCount = movies.size,
                    rowContentPadding = PaddingValues(
                        horizontal = 10.dp,
                        vertical = if (isCompact) 4.dp else (4.dp + (rowSpacing * 0.25f)),
                    ),
                    headerPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp + (rowSpacing * 0.35f),
                        bottom = 4.dp,
                    ),
                    itemSpacing = spacingDp,
                ) {
                    items(movies.size) { index ->
                        PosterCard(
                            item = movies[index],
                            provider = provider,
                            itemWidth = optimalItemWidth,
                            onClick = {},
                            onPlayClick = {},
                        )
                    }
                }
            }

            if (series.isNotEmpty()) {
                CategoryRowWithHeader(
                    modifier = Modifier.fillMaxWidth(),
                    title = "Popular Series & Shows",
                    itemCount = series.size,
                    rowContentPadding = PaddingValues(
                        horizontal = 10.dp,
                        vertical = if (isCompact) 4.dp else (4.dp + (rowSpacing * 0.25f)),
                    ),
                    headerPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp + (rowSpacing * 0.35f),
                        bottom = 4.dp,
                    ),
                    itemSpacing = spacingDp,
                ) {
                    items(series.size) { index ->
                        PosterCard(
                            item = series[index],
                            provider = provider,
                            itemWidth = optimalItemWidth,
                            onClick = {},
                            onPlayClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.TextMuted,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = theme.SurfaceCard.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun StudioSliderControl(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val intervalCount = ((valueRange.endInclusive - valueRange.start) / step).roundToInt()
    val sliderSteps = (intervalCount - 1).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = theme.TextPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StudioStepperButton(
                    icon = Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    enabled = value > valueRange.start,
                    onClick = { onValueChange((value - step).coerceIn(valueRange)) },
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 58.dp),
                )
                StudioStepperButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Increase $label",
                    enabled = value < valueRange.endInclusive,
                    onClick = { onValueChange((value + step).coerceIn(valueRange)) },
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = sliderSteps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
        )
    }
}

@Composable
private fun StudioStepperButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Surface(
        modifier = Modifier
            .size(28.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) theme.TextPrimary else theme.TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> StudioChoiceControl(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = theme.TextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StudioToggleControl(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val theme = LocalDesktopTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = theme.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun StudioPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun StudioDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}
