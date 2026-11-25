package com.lagradost.cloudstream3.desktop.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Unified button shape tokens shared across primary CTAs, standard buttons,
 * compact filter pills, and icon controls.
 */
@Immutable
data class DesktopButtonShapes(
    val style: ButtonShapeStyle = ButtonShapeStyle.ROUNDED,
    val primary: Shape = RoundedCornerShape(12.dp),
    val medium: Shape = RoundedCornerShape(10.dp),
    val compact: Shape = RoundedCornerShape(8.dp),
    val icon: Shape = RoundedCornerShape(10.dp),
) {
    companion object {
        fun fromStyle(style: ButtonShapeStyle): DesktopButtonShapes = when (style) {
            ButtonShapeStyle.ROUNDED -> DesktopButtonShapes(
                style = style,
                primary = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(10.dp),
                compact = RoundedCornerShape(8.dp),
                icon = RoundedCornerShape(10.dp),
            )
            ButtonShapeStyle.SOFT -> DesktopButtonShapes(
                style = style,
                primary = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(6.dp),
                compact = RoundedCornerShape(5.dp),
                icon = RoundedCornerShape(6.dp),
            )
            ButtonShapeStyle.PILL -> DesktopButtonShapes(
                style = style,
                primary = CircleShape,
                medium = CircleShape,
                compact = CircleShape,
                icon = CircleShape,
            )
            ButtonShapeStyle.SHARP -> DesktopButtonShapes(
                style = style,
                primary = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(3.dp),
                compact = RoundedCornerShape(2.dp),
                icon = RoundedCornerShape(4.dp),
            )
        }
    }
}

val LocalDesktopButtonShapes = staticCompositionLocalOf {
    DesktopButtonShapes.fromStyle(ButtonShapeStyle.ROUNDED)
}

@Composable
fun rememberDesktopButtonShapes(): DesktopButtonShapes {
    val style by AppearanceConfig.buttonShapeStyle.collectAsState()
    return remember(style) { DesktopButtonShapes.fromStyle(style) }
}
