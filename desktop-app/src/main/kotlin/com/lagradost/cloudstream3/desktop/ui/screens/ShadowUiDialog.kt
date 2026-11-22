package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.shadowui.ShadowDialog
import com.lagradost.cloudstream3.desktop.shadowui.ShadowUi
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.shadow.ShadowDialogFrame
import com.lagradost.cloudstream3.desktop.ui.screens.shadow.ShadowNode
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.accentColorFromName
import com.lagradost.cloudstream3.desktop.ui.theme.buildColorScheme
import com.lagradost.cloudstream3.desktop.ui.theme.buildDesktopColors
import kotlinx.coroutines.delay

/**
 * Root host for plugin-driven Android shadow dialogs on Desktop.
 *
 * Observes [ShadowUi.dialogs] and renders any dialog opened by plugins via
 * real Android APIs (AlertDialog, DialogFragment, LinearLayout, Switch, etc.)
 * using Cloudstream's native Material3 + Amoled CustomDialog system.
 */
@Composable
fun ShadowUiHost() {
    val dialogs by ShadowUi.dialogs.collectAsState()
    val version by ShadowUi.version.collectAsState()
    val toast by ShadowUi.toast.collectAsState()

    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val customThemeAccent by AppearanceConfig.customThemeAccent.collectAsState()
    val appThemeBackground by AppearanceConfig.appThemeBackground.collectAsState()
    val customAppThemeBackground by AppearanceConfig.customAppThemeBackground.collectAsState()
    val uiCardOpacity by AppearanceConfig.uiCardOpacity.collectAsState()

    val primaryColor = accentColorFromName(themeAccent, customThemeAccent)
    val desktopColors = buildDesktopColors(
        primaryColor = primaryColor,
        isLightMode = isLightMode,
        isAmoled = amoledMode,
        appThemeBackground = appThemeBackground,
        customBgHex = customAppThemeBackground,
    ).copy(cardOpacity = uiCardOpacity)
    val colorScheme = buildColorScheme(primaryColor, desktopColors, isLightMode)

    CompositionLocalProvider(LocalDesktopTheme provides desktopColors) {
        MaterialTheme(colorScheme = colorScheme) {
            // Render the topmost dialog in the stack
            dialogs.lastOrNull()?.let { dialog ->
                key(dialog.id) {
                    ShadowDialogFrame(dialog, version)
                }
            }

            // Transient toast notification overlay
            toast?.let { event ->
                val visible = remember(event.at) { mutableStateOf(true) }
                LaunchedEffect(event.at) {
                    delay(2200)
                    visible.value = false
                }
                if (visible.value) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.BottomCenter)
                            .padding(bottom = 32.dp),
                    ) {
                        Surface(
                            color = Color(0xE61A1A24),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            shadowElevation = 8.dp,
                        ) {
                            Text(
                                text = event.text,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Backward compatibility aliases
@Composable
fun ShadowDialogFrame(shadow: ShadowDialog, version: Int) =
    com.lagradost.cloudstream3.desktop.ui.screens.shadow.ShadowDialogFrame(shadow, version)

@Composable
fun ShadowNode(view: android.view.View, depth: Int = 1) =
    com.lagradost.cloudstream3.desktop.ui.screens.shadow.ShadowNode(view, depth)
