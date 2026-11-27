package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

/**
 * Standardized floating back button for child and drilldown screens.
 */
@Composable
fun DesktopBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Back",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isHovered || isFocused -> 1.05f
            else -> 1.0f
        },
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "backButtonScale",
    )

    val shape = LocalDesktopButtonShapes.current.icon
    val bgColor = if (isHovered || isFocused) Color(0xFF14141A).copy(alpha = 0.80f) else Color(0xFF0F0F12).copy(alpha = 0.55f)
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else if (isHovered) {
        Color.White.copy(alpha = 0.32f)
    } else {
        Color.White.copy(alpha = 0.15f)
    }

    Surface(
        shape = shape,
        color = bgColor,
        border = BorderStroke(if (isFocused) 1.5.dp else 1.dp, borderColor),
        shadowElevation = 0.dp,
        modifier = modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Compact header back button shared by grid and settings sub-screens.
 */
@Composable
fun DesktopInlineBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val buttonShape = LocalDesktopButtonShapes.current.medium
    Surface(
        shape = buttonShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            if (isFocused) 1.5.dp else 1.dp,
            if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        ),
        modifier = modifier
            .clip(buttonShape)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = buttonShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
