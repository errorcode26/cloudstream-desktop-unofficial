package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.ui.components.DesktopActionBadge
import com.lagradost.cloudstream3.desktop.ui.components.DesktopFilterChip
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.desktopDragScroll
import com.lagradost.cloudstream3.desktop.ui.components.shimmerBackground
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig

@Composable
fun DetailsEpisodeEmptyState(providerName: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1414).copy(alpha = 0.7f),
        border = BorderStroke(1.2.dp, Color(0xFFE50914).copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFFE50914).copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "No Episodes Found on $providerName",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "The provider did not return any streaming links or episodes for this title. You may want to check another provider.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
fun DetailsEpisodeShimmer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .shimmerBackground(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(5) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(135.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .shimmerBackground(),
                    )
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerBackground(),
                    )
                }
            }
        }
    }
}

@Composable
fun DetailsEpisodeCompactHeader(
    hPadding: Dp,
    seasons: List<Int>,
    effectiveSeason: Int,
    seasonListState: LazyListState,
    uiState: DetailsUiState?,
    dubStatuses: List<DubStatus>,
    selectedDub: DubStatus?,
    currentSeasonEpisodes: List<Episode>,
    isSeasonWatched: Boolean,
    isAntiSpoiler: Boolean,
    isSortAscending: Boolean,
    currentMode: Int,
    onSeasonSelect: (Int) -> Unit,
    onShowSeasonModal: () -> Unit,
    onSelectDub: (DubStatus) -> Unit,
    onToggleSeasonWatched: (List<Episode>, Boolean) -> Unit,
    onToggleSort: () -> Unit,
    onSetEpisodeViewMode: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (seasons.size <= 1) {
                    val singleSeason = seasons.firstOrNull() ?: 1
                    Text(
                        text = if (singleSeason == 0) "Specials" else "Season $singleSeason",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (seasons.size <= 4) {
                    LazyRow(
                        state = seasonListState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(seasons, key = { it }) { season ->
                            val isSelected = effectiveSeason == season
                            val seasonName = if (season == 0) "Specials" else "Season $season"

                            DesktopFilterChip(
                                text = seasonName,
                                isSelected = isSelected,
                                onClick = { onSeasonSelect(season) },
                                minWidth = 75.dp,
                            )
                        }
                    }
                } else {
                    val currentSeasonName = if (effectiveSeason == 0) "Specials" else "Season $effectiveSeason"
                    SeasonSelectorButton(
                        seasonName = currentSeasonName,
                        onClick = onShowSeasonModal,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            DesktopFilterChip(
                text = if (isSortAscending) "▼" else "▲",
                isSelected = false,
                onClick = onToggleSort,
                minWidth = 36.dp,
            )

            Spacer(Modifier.width(6.dp))

            DesktopIconButton(
                icon = when (currentMode) {
                    0 -> Icons.Default.ViewCarousel
                    1 -> Icons.Default.GridView
                    2 -> Icons.Default.TableRows
                    else -> Icons.Default.ViewCarousel
                },
                contentDescription = when (currentMode) {
                    0 -> "Current: Carousel (Click for Grid)"
                    1 -> "Current: Grid (Click for List)"
                    2 -> "Current: List (Click for Carousel)"
                    else -> "Toggle Episode View"
                },
                onClick = {
                    val nextMode = (currentMode + 1) % 3
                    onSetEpisodeViewMode(nextMode)
                },
                isActive = false,
            )
        }

        if (dubStatuses.size > 1) {
            SubDubSegmentedSwitch(
                dubStatuses = dubStatuses,
                selectedDub = selectedDub,
                onSelectDub = onSelectDub,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (currentSeasonEpisodes.isNotEmpty()) {
                DesktopActionBadge(
                    text = if (isSeasonWatched) "✓ Watched" else "Mark Watched",
                    onClick = { onToggleSeasonWatched(currentSeasonEpisodes, !isSeasonWatched) },
                    isActive = isSeasonWatched,
                    activeColor = Color(0xFF4ADE80),
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }

            AntiSpoilerToggleButton(
                isAntiSpoiler = isAntiSpoiler,
                isCompact = true,
            )
        }
    }
}

@Composable
private fun AntiSpoilerToggleButton(
    isAntiSpoiler: Boolean,
    isCompact: Boolean,
) {
    val toggleShape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.compact
    Surface(
        onClick = { AppearanceConfig.setAntiSpoilerEnabled(!isAntiSpoiler) },
        shape = toggleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.height(if (isCompact) 34.dp else 40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (isCompact) 8.dp else 12.dp),
        ) {
            Text(
                "Anti-spoiler",
                style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                fontWeight = if (isAntiSpoiler) FontWeight.Bold else FontWeight.Medium,
                color = if (isAntiSpoiler) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.width(if (isCompact) 4.dp else 8.dp))
            Switch(
                checked = isAntiSpoiler,
                onCheckedChange = { AppearanceConfig.setAntiSpoilerEnabled(it) },
                modifier = Modifier.scale(if (isCompact) 0.7f else 0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.White.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

@Composable
fun DetailsEpisodeDesktopHeader(
    hPadding: Dp,
    seasons: List<Int>,
    effectiveSeason: Int,
    seasonListState: LazyListState,
    uiState: DetailsUiState?,
    dubStatuses: List<DubStatus>,
    selectedDub: DubStatus?,
    currentSeasonEpisodes: List<Episode>,
    isSeasonWatched: Boolean,
    isAntiSpoiler: Boolean,
    isSortAscending: Boolean,
    currentMode: Int,
    onSeasonSelect: (Int) -> Unit,
    onShowSeasonModal: () -> Unit,
    onSelectDub: (DubStatus) -> Unit,
    onToggleSeasonWatched: (List<Episode>, Boolean) -> Unit,
    onToggleSort: () -> Unit,
    onSetEpisodeViewMode: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (seasons.isNotEmpty()) {
                if (seasons.size == 1) {
                    val singleSeason = seasons.first()
                    Text(
                        text = if (singleSeason == 0) "Specials" else "Season $singleSeason",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (seasons.size <= 4) {
                    LazyRow(
                        state = seasonListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .desktopDragScroll(seasonListState),
                    ) {
                        items(seasons, key = { it }) { season ->
                            val isSelected = effectiveSeason == season
                            val seasonName = if (season == 0) "Specials" else "Season $season"

                            DesktopFilterChip(
                                text = seasonName,
                                isSelected = isSelected,
                                onClick = { onSeasonSelect(season) },
                                minWidth = 90.dp,
                            )
                        }
                    }
                } else {
                    val currentSeasonName = if (effectiveSeason == 0) "Specials" else "Season $effectiveSeason"
                    SeasonSelectorButton(
                        seasonName = currentSeasonName,
                        onClick = onShowSeasonModal,
                    )
                }

                if (currentSeasonEpisodes.isNotEmpty()) {
                    Text(
                        text = "•   ${currentSeasonEpisodes.size} ${if (currentSeasonEpisodes.size == 1) "Episode" else "Episodes"}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (dubStatuses.size > 1) {
                SubDubSegmentedSwitch(
                    dubStatuses = dubStatuses,
                    selectedDub = selectedDub,
                    onSelectDub = onSelectDub,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (currentSeasonEpisodes.isNotEmpty()) {
                DesktopActionBadge(
                    text = if (isSeasonWatched) "✓ Season Watched" else "Mark Season Watched",
                    onClick = { onToggleSeasonWatched(currentSeasonEpisodes, !isSeasonWatched) },
                    isActive = isSeasonWatched,
                    activeColor = Color(0xFF4ADE80),
                )
            }

            AntiSpoilerToggleButton(
                isAntiSpoiler = isAntiSpoiler,
                isCompact = false,
            )

            DesktopFilterChip(
                text = if (isSortAscending) "Sort ▼" else "Sort ▲",
                isSelected = false,
                onClick = onToggleSort,
                minWidth = 70.dp,
            )

            DesktopIconButton(
                icon = when (currentMode) {
                    0 -> Icons.Default.ViewCarousel
                    1 -> Icons.Default.GridView
                    2 -> Icons.Default.TableRows
                    else -> Icons.Default.ViewCarousel
                },
                contentDescription = when (currentMode) {
                    0 -> "Current: Carousel (Click for Grid)"
                    1 -> "Current: Grid (Click for List)"
                    2 -> "Current: List (Click for Carousel)"
                    else -> "Toggle Episode View"
                },
                onClick = {
                    val nextMode = (currentMode + 1) % 3
                    onSetEpisodeViewMode(nextMode)
                },
                isActive = false,
            )
        }
    }
}
