package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

@Composable
fun DesktopFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 80.dp,
    height: Dp = 40.dp,
    shape: Shape = LocalDesktopButtonShapes.current.compact,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()

    Box(
        modifier = modifier
            .height(height)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(
                if (isSelected) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
            )
            .border(
                width = if (isSelected || isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) {
                    MaterialTheme.colorScheme.primary
                } else if (isSelected) {
                    Color.White.copy(alpha = 0.65f)
                } else {
                    Color.White.copy(alpha = 0.1f)
                },
                shape = shape,
            )
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

@Composable
fun DesktopIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    isActive: Boolean = false,
    shape: Shape = LocalDesktopButtonShapes.current.icon,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    val alpha = if (enabled) (if (isActive) 0.8f else 0.6f) else 0.25f
    val borderColor = if (enabled) {
        if (isFocused) MaterialTheme.colorScheme.primary
        else if (isActive) Color.White.copy(alpha = 0.65f)
        else Color.White.copy(alpha = 0.1f)
    } else {
        Color.White.copy(alpha = 0.04f)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                if (isActive) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
                },
            )
            .border(
                width = if (isActive || isFocused) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                if (isActive) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun DesktopActionBadge(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    activeColor: Color = Color.White,
    height: Dp = 40.dp,
    shape: Shape = LocalDesktopButtonShapes.current.compact,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isActive) activeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            if (isFocused) 1.5.dp else 1.dp,
            if (isFocused) MaterialTheme.colorScheme.primary else if (isActive) activeColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
        ),
        interactionSource = interactionSource,
        modifier = modifier
            .height(height)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isActive) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Primary hero CTA button (Play / Resume / Watch Now) with desktop hover boundary glow,
 * one-shot diagonal sheen sweep on hover entry, spring press/hover scale, and icon nudge.
 */
@Composable
fun HeroPrimaryCtaButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = LocalDesktopButtonShapes.current.primary,
    contentDescription: String? = null,
    isUnavailable: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    iconSize: Dp = 22.dp,
    fontSize: TextUnit = 15.sp,
    iconSpacing: Dp = 8.dp,
    iconNudgeOnHover: Boolean = !isUnavailable,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val isPressed by interactionSource.collectIsPressedAsState()
    val accentColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current.density

    val containerScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.97f
            isActiveVisual -> 1.028f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "heroCtaScale",
    )

    val glowElevation by animateDpAsState(
        targetValue = if (isActiveVisual) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "heroCtaGlow",
    )

    val borderWidth by animateDpAsState(
        targetValue = when {
            isUnavailable && isActiveVisual -> 1.8.dp
            isUnavailable -> 1.2.dp
            isPressed -> 2.4.dp
            isActiveVisual -> 2.dp
            else -> 0.dp
        },
        animationSpec = tween(durationMillis = 160),
        label = "heroCtaBorderWidth",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isUnavailable && isActiveVisual -> Color(0xFFFF6B6B).copy(alpha = 0.85f)
            isUnavailable -> Color(0xFFE50914).copy(alpha = 0.5f)
            isActiveVisual || isPressed -> accentColor.copy(alpha = 0.95f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "heroCtaBorderColor",
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isUnavailable && isActiveVisual -> Color(0xFFE50914).copy(alpha = 0.24f)
            isUnavailable -> Color(0xFFE50914).copy(alpha = 0.15f)
            isPressed -> Color(0xFFE5E5E5)
            isActiveVisual -> Color.White
            else -> Color(0xFFF5F5F5)
        },
        animationSpec = tween(durationMillis = 150),
        label = "heroCtaBgColor",
    )

    val contentTint = if (isUnavailable) Color(0xFFFF6B6B) else Color(0xFF0F0F0F)
    val glowColor = if (isUnavailable) Color(0xFFE50914) else accentColor

    val iconScale by animateFloatAsState(
        targetValue = if (isActiveVisual && iconNudgeOnHover) 1.18f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "heroCtaIconScale",
    )
    val iconShiftDp by animateFloatAsState(
        targetValue = if (isActiveVisual && iconNudgeOnHover) 2.5f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "heroCtaIconShift",
    )

    val sheenProgress = remember { Animatable(-0.35f) }
    LaunchedEffect(isActiveVisual, isUnavailable) {
        if (isActiveVisual && !isUnavailable) {
            sheenProgress.snapTo(-0.35f)
            sheenProgress.animateTo(
                targetValue = 1.35f,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            )
        } else {
            sheenProgress.snapTo(-0.35f)
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = containerScale
                scaleY = containerScale
            }
            .shadow(
                elevation = glowElevation,
                shape = shape,
                ambientColor = glowColor.copy(alpha = if (isActiveVisual) 0.55f else 0f),
                spotColor = glowColor.copy(alpha = if (isActiveVisual) 0.75f else 0f),
            )
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                drawContent()
                val progress = sheenProgress.value
                if (!isUnavailable && progress > -0.34f && progress < 1.34f) {
                    val width = size.width
                    val height = size.height
                    val startX = progress * width
                    val bandWidth = (width * 0.28f).coerceAtLeast(48f)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                accentColor.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.48f),
                                Color.Transparent,
                            ),
                            start = Offset(startX - bandWidth, 0f),
                            end = Offset(startX + bandWidth, height),
                        ),
                    )
                }
            }
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(BorderStroke(borderWidth, borderColor), shape)
                } else {
                    Modifier
                },
            )
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentTint,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        translationX = iconShiftDp * density
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            Spacer(Modifier.width(iconSpacing))
            Text(
                text = text,
                color = contentTint,
                fontWeight = FontWeight.ExtraBold,
                fontSize = fontSize,
                maxLines = 1,
            )
        }
    }
}

/**
 * Translucent glass hero action button with cohesive hover border brightening and spring scale feedback.
 */
@Composable
fun HeroSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = LocalDesktopButtonShapes.current.primary,
    isActive: Boolean = false,
    content: @Composable (isHovered: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.93f
            isActiveVisual -> 1.05f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "heroSecondaryScale",
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White.copy(alpha = 0.26f)
            isActiveVisual -> Color.White.copy(alpha = 0.22f)
            isActive -> Color.White.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.12f)
        },
        animationSpec = tween(durationMillis = 150),
        label = "heroSecondaryBg",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.primary
            isHovered || isActive -> Color.White.copy(alpha = 0.60f)
            else -> Color.White.copy(alpha = 0.28f)
        },
        animationSpec = tween(durationMillis = 150),
        label = "heroSecondaryBorder",
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isActiveVisual || isActive) 1.5.dp else 1.2.dp,
        animationSpec = tween(durationMillis = 150),
        label = "heroSecondaryBorderWidth",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = shape,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
        interactionSource = interactionSource,
    ) {
        content(isActiveVisual)
    }
}
