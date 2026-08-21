package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.input.GamepadButton
import com.lagradost.cloudstream3.desktop.input.GamepadManager

/**
 * Sleek visual controller button badge (e.g., A, B, X, Y, LB, RB).
 */
@Composable
fun GamepadButtonBadge(
    button: GamepadButton,
    modifier: Modifier = Modifier,
) {
    val (label, bgGradient, textColor) = when (button) {
        GamepadButton.BUTTON_A -> Triple("A", Color(0xFF1B5E20), Color(0xFF81C784)) // Green
        GamepadButton.BUTTON_B -> Triple("B", Color(0xFFB71C1C), Color(0xFFEF9A9A)) // Red
        GamepadButton.BUTTON_X -> Triple("X", Color(0xFF0D47A1), Color(0xFF90CAF9)) // Blue
        GamepadButton.BUTTON_Y -> Triple("Y", Color(0xFFF57F17), Color(0xFFFFF59D)) // Yellow
        GamepadButton.SHOULDER_LEFT -> Triple("LB", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.SHOULDER_RIGHT -> Triple("RB", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.TRIGGER_LEFT -> Triple("LT", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.TRIGGER_RIGHT -> Triple("RT", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.THUMB_LEFT -> Triple("L3", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.THUMB_RIGHT -> Triple("R3", Color(0xFF37474F), Color(0xFFCFD8DC))
        GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN,
        GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT -> Triple("D-Pad", Color(0xFF263238), Color(0xFFB0BEC5))
        GamepadButton.START -> Triple("Menu", Color(0xFF263238), Color(0xFFECEFF1))
        GamepadButton.BACK_SELECT -> Triple("View", Color(0xFF263238), Color(0xFFECEFF1))
        GamepadButton.RIGHT_STICK_UP, GamepadButton.RIGHT_STICK_DOWN,
        GamepadButton.RIGHT_STICK_LEFT, GamepadButton.RIGHT_STICK_RIGHT -> Triple("RS", Color(0xFF263238), Color(0xFFB0BEC5))
    }

    val isPill = label.length > 1
    val shape = if (isPill) RoundedCornerShape(6.dp) else CircleShape

    Box(
        modifier = modifier
            .then(if (isPill) Modifier.height(18.dp).padding(horizontal = 2.dp) else Modifier.size(18.dp))
            .background(bgGradient, shape)
            .border(1.dp, textColor.copy(alpha = 0.6f), shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (isPill) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = if (isPill) Modifier.padding(horizontal = 4.dp) else Modifier,
        )
    }
}

/**
 * Single hint item combining a button badge with an action label.
 */
@Composable
fun GamepadHintItem(
    button: GamepadButton,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GamepadButtonBadge(button = button)
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Contextual controller hint bar displayed at the bottom edge when game controller is active.
 */
@Composable
fun GamepadHintsOverlay(
    currentTitle: String,
    modifier: Modifier = Modifier,
) {
    val isControllerActive by GamepadManager.isControllerActive.collectAsState()

    AnimatedVisibility(
        visible = isControllerActive,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF121216).copy(alpha = 0.88f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(bottom = 12.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    currentTitle == "Home" -> {
                        GamepadHintItem(GamepadButton.BUTTON_A, "Open")
                        GamepadHintItem(GamepadButton.BUTTON_Y, "Providers")
                        GamepadHintItem(GamepadButton.SHOULDER_LEFT, "")
                        GamepadHintItem(GamepadButton.SHOULDER_RIGHT, "Tabs")
                        GamepadHintItem(GamepadButton.RIGHT_STICK_DOWN, "Scroll")
                        GamepadHintItem(GamepadButton.BACK_SELECT, "History")
                        GamepadHintItem(GamepadButton.BUTTON_X, "Search")
                    }
                    currentTitle.startsWith("Details") -> {
                        GamepadHintItem(GamepadButton.BUTTON_A, "Play / Select")
                        GamepadHintItem(GamepadButton.BUTTON_B, "Back")
                        GamepadHintItem(GamepadButton.BUTTON_Y, "Bookmark")
                        GamepadHintItem(GamepadButton.RIGHT_STICK_DOWN, "Scroll")
                        GamepadHintItem(GamepadButton.DPAD_UP, "Episodes")
                    }
                    currentTitle == "Search" -> {
                        GamepadHintItem(GamepadButton.BUTTON_A, "Select")
                        GamepadHintItem(GamepadButton.BUTTON_B, "Back")
                        GamepadHintItem(GamepadButton.SHOULDER_LEFT, "")
                        GamepadHintItem(GamepadButton.SHOULDER_RIGHT, "Tabs")
                    }
                    else -> {
                        GamepadHintItem(GamepadButton.BUTTON_A, "Select")
                        GamepadHintItem(GamepadButton.BUTTON_B, "Back")
                        GamepadHintItem(GamepadButton.SHOULDER_LEFT, "")
                        GamepadHintItem(GamepadButton.SHOULDER_RIGHT, "Tabs")
                    }
                }
            }
        }
    }
}
