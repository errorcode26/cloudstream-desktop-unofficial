package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.components.CategoryRowWithHeader
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCard
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCardDetailed
import com.lagradost.cloudstream3.desktop.ui.components.WatchHistoryCardWide
import com.lagradost.cloudstream3.desktop.ui.theme.ContinueWatchingStyle
import com.lagradost.common.storage.WatchHistory

@Composable
fun PreviewHeader(
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
        DesktopIconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
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
fun PosterStudioPreview(
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
