@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.ui.components.nonFocusableTap
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.common.storage.WatchHistory

/**
 * Centered Left-Zone Showcase displayed while the stream drawer is open.
 * Clean, cinematic layout without redundant thumbnail tiles:
 * - Large, prominent Title Logo above (height up to 130dp)
 * - Clean episode number badge & title (or movie release year & duration) below logo
 * - Formatted rating, duration, and progress indicator
 * - Legible, HTML-cleaned synopsis
 */
@Composable
fun FloatingEpisodeCard(
    provider: MainAPI,
    response: LoadResponse?,
    activeEpisode: Episode?,
    activeHistory: WatchHistory?,
    activeLinkData: Triple<MainAPI, String, WatchHistory>?,
    uiState: DetailsUiState?,
    isAmoled: Boolean,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    enrichedLogoUrl: String? = null,
) {
    if (response == null) return

    val logoUrl = enrichedLogoUrl ?: uiState?.enrichedLogoUrl ?: response.logoUrl
    val rawShowName = response.name.ifBlank { activeHistory?.showName ?: "" }
    val showName = remember(rawShowName) {
        com.lagradost.cloudstream3.desktop.utils.TitleUtils.cleanProviderTitle(rawShowName).first.ifBlank { rawShowName }
    }

    val leftSeconds = if (activeHistory != null && activeHistory.duration > 0) activeHistory.duration - activeHistory.position else 0L
    val leftMins = leftSeconds / 60000L
    val remainingText = if (leftMins > 0) "${leftMins}m left" else null

    val durationMins = activeEpisode?.runTime ?: response.duration
    val durationText = durationMins?.let { dur ->
        val mins = if (dur > 1000) dur / 60 else dur
        if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }

    val scoreVal = activeEpisode?.score ?: response.score
    val formattedScore = scoreVal?.toFloat(10)?.takeIf { it > 0.0f }?.let { String.format("%.1f", it) }

    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .nonFocusableTap { /* absorb clicks so clicking inside does not dismiss */ },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Large, Crisp Title LOGO above (or bold typography if no logo exists or fails to load)
        var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }

        if (!logoUrl.isNullOrBlank() && !logoLoadFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = showName,
                modifier = Modifier
                    .heightIn(min = 60.dp, max = 160.dp)
                    .fillMaxWidth(0.95f),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                onError = { logoLoadFailed = true },
            )
        } else {
            Text(
                text = showName,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        // 2. Clean Episode Number & Title or Movie Year & Duration
        if (activeEpisode != null) {
            val sNum = activeEpisode.season ?: activeHistory?.season ?: 1
            val epNum = activeEpisode.episode ?: activeHistory?.episode ?: 1
            val epTitle = activeEpisode.name?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val isSeries = response is TvSeriesLoadResponse || (activeEpisode.season != null)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                ) {
                    Text(
                        text = if (isSeries) "SEASON $sNum • EPISODE $epNum" else "SPECIAL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = epTitle,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (formattedScore != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = formattedScore,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }
                    }
                    if (durationText != null) {
                        if (formattedScore != null) Text(" • ", color = Color.White.copy(alpha = 0.40f))
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.70f),
                        )
                    }
                    if (remainingText != null) {
                        Text(" • ", color = Color.White.copy(alpha = 0.40f))
                        Text(
                            text = remainingText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            // Movie metadata line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                response.year?.let { y ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    ) {
                        Text(
                            text = y.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                if (durationText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                if (formattedScore != null) {
                    Text(" • ", color = Color.White.copy(alpha = 0.40f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = formattedScore,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
                if (remainingText != null) {
                    Text("•", color = Color.White.copy(alpha = 0.40f))
                    Text(
                        text = remainingText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
