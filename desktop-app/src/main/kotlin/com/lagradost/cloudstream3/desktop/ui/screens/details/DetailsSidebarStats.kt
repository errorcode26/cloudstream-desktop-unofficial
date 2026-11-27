package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.components.getTextShadow
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.desktop.utils.appScope
import com.lagradost.cloudstream3.fixUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun InfoPanelRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoRowItem(
    label: String,
    value: String,
    textShadow: TextStyle,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    isHovered: Boolean = interactionSource.collectIsHoveredAsState().value,
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val itemShape = RoundedCornerShape(6.dp)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = if (onClick != null) {
            Modifier
                .clip(itemShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .hoverable(interactionSource)
                .desktopInteractiveFocus(interactionSource = interactionSource, shape = itemShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
        } else {
            Modifier
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.merge(textShadow),
            color = Color.White.copy(alpha = 0.55f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontSize = 11.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(textShadow),
            color = if (onClick != null && isActiveVisual) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.95f),
            textDecoration = if (onClick != null && isActiveVisual) TextDecoration.Underline else null,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DetailsSidebarStats(
    provider: MainAPI,
    data: LoadResponse,
    uiState: DetailsUiState?,
    isNarrow: Boolean,
    isRightColumnPinned: Boolean,
    rightColumnAlpha: Float,
    isRightColumnHovered: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .then(if (isNarrow) Modifier.fillMaxWidth() else Modifier.widthIn(max = 420.dp))
            .padding(bottom = 12.dp)
            .graphicsLayer { alpha = rightColumnAlpha }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Enter) {
                            onHoverChange(true)
                        } else if (event.type == PointerEventType.Exit) {
                            onHoverChange(false)
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = if (isNarrow) Alignment.CenterHorizontally else Alignment.End,
    ) {
        val textShadow = TextStyle(
            shadow = getTextShadow(),
        )

        if (!isNarrow) {
            val actionAlpha by animateFloatAsState(
                targetValue = if (isRightColumnHovered) 1f else 0f,
                label = "actionAlpha",
            )
            val screensaverEnabled by AppearanceConfig.screensaverEnabled.collectAsState()
            val screenshots = uiState?.screenshots ?: emptyList()

            Row(
                modifier = Modifier.graphicsLayer { alpha = actionAlpha },
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (screenshots.isNotEmpty()) {
                    com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton(
                        onClick = {
                            appScope.launch(Dispatchers.IO) {
                                AppearanceConfig.setScreensaverEnabled(!screensaverEnabled)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (screensaverEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (screensaverEnabled) "Pause backdrop screensaver" else "Resume backdrop screensaver",
                            tint = if (screensaverEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isRightColumnPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin sidebar",
                        tint = if (isRightColumnPinned) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        val hideDetailsSource by AppearanceConfig.hideDetailsSource.collectAsState()

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = if (isNarrow) Alignment.CenterHorizontally else Alignment.End,
        ) {
            val stats = buildList {
                if (!hideDetailsSource) {
                    add("Source" to provider.name)
                }

                val relDate = uiState?.enrichedReleaseDate ?: data.year?.toString()
                if (!relDate.isNullOrBlank()) add("Release Date" to relDate)

                val status = uiState?.enrichedStatus
                if (!status.isNullOrBlank()) add("Status" to status)
            }

            stats.forEach { stat ->
                InfoRowItem(
                    label = stat.first,
                    value = stat.second,
                    textShadow = textShadow,
                    onClick = if (stat.first == "Source") {
                        val fixed = provider.fixUrl(data.url)
                        if (fixed.startsWith("http://") || fixed.startsWith("https://")) {
                            { ExternalLinkHandler.launchSystemBrowser(fixed) }
                        } else null
                    } else null,
                )
            }
        }
    }
}
