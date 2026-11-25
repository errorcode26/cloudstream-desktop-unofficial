package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType

@Composable
fun DetailsLibraryStatusDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    currentBookmark: DesktopBookmark?,
    onSetWatchType: (DesktopWatchType) -> Unit,
    onRemoveBookmark: () -> Unit,
) {
    if (!show) return

    CloudstreamAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = if (currentBookmark != null) "Library Status" else "Add to Library",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            val statusItems = listOf(
                DesktopWatchType.WATCHING to Icons.Default.PlayArrow,
                DesktopWatchType.COMPLETED to Icons.Default.CheckCircle,
                DesktopWatchType.PLANTOWATCH to Icons.Default.Bookmark,
                DesktopWatchType.ONHOLD to Icons.Default.PauseCircle,
                DesktopWatchType.REWATCHING to Icons.AutoMirrored.Filled.RotateRight,
                DesktopWatchType.DROPPED to Icons.Default.Cancel,
            )
            val primaryColor = MaterialTheme.colorScheme.primary

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                statusItems.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { (type, icon) ->
                            val isSelected = currentBookmark?.watchType == type.id
                            val itemInteraction = remember { MutableInteractionSource() }
                            val isHovered by itemInteraction.collectIsHoveredAsState()

                            Surface(
                                onClick = { onSetWatchType(type) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) {
                                    primaryColor.copy(alpha = 0.18f)
                                } else if (isHovered) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    Color.White.copy(alpha = 0.04f)
                                },
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) {
                                        primaryColor
                                    } else if (isHovered) {
                                        Color.White.copy(alpha = 0.25f)
                                    } else {
                                        Color.White.copy(alpha = 0.10f)
                                    },
                                ),
                                interactionSource = itemInteraction,
                                modifier = Modifier.weight(1f).height(84.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (isSelected) primaryColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = if (isSelected) primaryColor else Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = type.stringRes,
                                            color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.85f),
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = primaryColor,
                                            modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentBookmark != null) {
                com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(
                    onClick = onRemoveBookmark,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove from Library", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(onClick = onDismissRequest) {
                Text("Cancel", fontSize = 13.sp)
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsHeroLibraryButton(
    modifier: Modifier = Modifier,
    currentBookmark: DesktopBookmark?,
    activeWatchType: DesktopWatchType,
    isButtonsNarrow: Boolean,
    onEditStatus: () -> Unit,
) {
    val isInLibrary = currentBookmark != null
    val libraryIcon = if (isInLibrary) {
        when (activeWatchType) {
            DesktopWatchType.WATCHING -> Icons.Outlined.PlayArrow
            DesktopWatchType.COMPLETED -> Icons.Outlined.Check
            DesktopWatchType.ONHOLD -> Icons.Outlined.Pause
            DesktopWatchType.DROPPED -> Icons.Outlined.Close
            DesktopWatchType.PLANTOWATCH -> Icons.Outlined.BookmarkAdded
            DesktopWatchType.REWATCHING -> Icons.AutoMirrored.Filled.RotateRight
        }
    } else {
        Icons.Outlined.BookmarkAdd
    }
    val tooltipText = if (isInLibrary) "Change Library Status" else "Add to Library"
    val theme = LocalDesktopTheme.current
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = theme.SurfaceElevated,
                border = BorderStroke(1.dp, theme.Divider),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = tooltipText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
            onClick = onEditStatus,
            modifier = if (isInLibrary) {
                modifier.height(if (isButtonsNarrow) 44.dp else 52.dp).animateContentSize()
            } else {
                modifier.size(if (isButtonsNarrow) 44.dp else 52.dp)
            },
            isActive = isInLibrary,
        ) {
            if (isInLibrary) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = if (isButtonsNarrow) 12.dp else 16.dp),
                ) {
                    Icon(
                        imageVector = libraryIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
                    )
                    Spacer(Modifier.width(if (isButtonsNarrow) 6.dp else 8.dp))
                    Text(
                        text = activeWatchType.stringRes,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isButtonsNarrow) 13.sp else 14.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Change Status",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(if (isButtonsNarrow) 16.dp else 18.dp),
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = libraryIcon,
                        contentDescription = "Add to Library",
                        tint = Color.White,
                        modifier = Modifier.size(if (isButtonsNarrow) 22.dp else 26.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsHeroWebButton(
    modifier: Modifier = Modifier,
    isButtonsNarrow: Boolean,
    provider: MainAPI,
    url: String?,
) {
    val fullWebUrl = remember(provider, url) {
        if (url.isNullOrBlank()) null
        else {
            val fixed = provider.fixUrl(url)
            if (fixed.startsWith("http://") || fixed.startsWith("https://")) fixed else null
        }
    } ?: return

    val theme = LocalDesktopTheme.current

    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = theme.SurfaceElevated,
                border = BorderStroke(1.dp, theme.Divider),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = "Open source page in browser",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
            onClick = { ExternalLinkHandler.launchSystemBrowser(fullWebUrl) },
            modifier = modifier.size(if (isButtonsNarrow) 44.dp else 52.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Open in Browser (${provider.name})",
                    tint = Color.White,
                    modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
                )
            }
        }
    }
}

@Composable
fun DetailsHeroTrailerButton(
    modifier: Modifier = Modifier,
    isButtonsNarrow: Boolean,
    onClick: () -> Unit,
) {
    com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
        onClick = onClick,
        modifier = modifier.height(if (isButtonsNarrow) 44.dp else 52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (isButtonsNarrow) 12.dp else 18.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Trailer",
                tint = Color.White,
                modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
            )
            Spacer(Modifier.width(if (isButtonsNarrow) 6.dp else 8.dp))
            Text(
                text = "Trailer",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isButtonsNarrow) 13.sp else 14.sp,
            )
        }
    }
}
