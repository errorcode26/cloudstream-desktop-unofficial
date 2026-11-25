package com.lagradost.cloudstream3.desktop.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Unified portrait and avatar shape tokens for Cast & Crew cards, compact avatars,
 * dual-cast voice badges, and Person profile portraits.
 */
@Immutable
data class DesktopProfileShapes(
    val style: CastProfileShapeStyle = CastProfileShapeStyle.ROUNDED,
    val portraitAspectRatio: Float = 2f / 3f,
    val portraitAlignment: Alignment = Alignment.Center,
    val card: Shape = RoundedCornerShape(14.dp),
    val hero: Shape = RoundedCornerShape(18.dp),
    val compact: Shape = RoundedCornerShape(12.dp),
    val badge: Shape = RoundedCornerShape(10.dp),
) {
    val isCircular: Boolean
        get() = style == CastProfileShapeStyle.CIRCLE

    companion object {
        fun fromStyle(style: CastProfileShapeStyle): DesktopProfileShapes = when (style) {
            CastProfileShapeStyle.ROUNDED -> DesktopProfileShapes(
                style = style,
                portraitAspectRatio = 2f / 3f,
                portraitAlignment = Alignment.Center,
                card = RoundedCornerShape(14.dp),
                hero = RoundedCornerShape(18.dp),
                compact = RoundedCornerShape(12.dp),
                badge = RoundedCornerShape(10.dp),
            )
            CastProfileShapeStyle.SOFT -> DesktopProfileShapes(
                style = style,
                portraitAspectRatio = 2f / 3f,
                portraitAlignment = Alignment.Center,
                card = RoundedCornerShape(8.dp),
                hero = RoundedCornerShape(10.dp),
                compact = RoundedCornerShape(8.dp),
                badge = RoundedCornerShape(6.dp),
            )
            CastProfileShapeStyle.CIRCLE -> DesktopProfileShapes(
                style = style,
                portraitAspectRatio = 1f,
                // Upper-center crop keeps faces framed cleanly when converting 2:3 headshots to 1:1 circles.
                portraitAlignment = BiasAlignment(0f, -0.35f),
                card = CircleShape,
                hero = CircleShape,
                compact = CircleShape,
                badge = CircleShape,
            )
            CastProfileShapeStyle.SHARP -> DesktopProfileShapes(
                style = style,
                portraitAspectRatio = 2f / 3f,
                portraitAlignment = Alignment.Center,
                card = RoundedCornerShape(4.dp),
                hero = RoundedCornerShape(4.dp),
                compact = RoundedCornerShape(4.dp),
                badge = RoundedCornerShape(3.dp),
            )
        }
    }
}

val LocalDesktopProfileShapes = staticCompositionLocalOf {
    DesktopProfileShapes.fromStyle(CastProfileShapeStyle.ROUNDED)
}

@Composable
fun rememberDesktopProfileShapes(): DesktopProfileShapes {
    val style by AppearanceConfig.castProfileShapeStyle.collectAsState()
    return remember(style) { DesktopProfileShapes.fromStyle(style) }
}
