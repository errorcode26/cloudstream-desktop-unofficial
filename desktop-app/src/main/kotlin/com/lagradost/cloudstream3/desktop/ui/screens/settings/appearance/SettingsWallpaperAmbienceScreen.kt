package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.crossfade
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsSliderItem
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsToggleItem
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig

@Composable
fun SettingsWallpaperAmbienceScreen() {
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    val ambientGlowEnabled by AppearanceConfig.ambientGlowEnabled.collectAsState()
    val ambientGlowIntensity by AppearanceConfig.ambientGlowIntensity.collectAsState()
    val ambientGlowPositions by AppearanceConfig.ambientGlowPositions.collectAsState()
    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val bgImagePath by AppearanceConfig.backgroundImagePath.collectAsState()
    val bgImageBlur by AppearanceConfig.backgroundImageBlur.collectAsState()
    val bgImageBrightness by AppearanceConfig.backgroundImageBrightness.collectAsState()
    val bgImageOpacity by AppearanceConfig.backgroundImageOpacity.collectAsState()
    val bgImageVignetteEnabled by AppearanceConfig.backgroundImageVignetteEnabled.collectAsState()
    val bgImageVignetteIntensity by AppearanceConfig.backgroundImageVignetteIntensity.collectAsState()
    val bgImageTintEnabled by AppearanceConfig.backgroundImageTintEnabled.collectAsState()
    val bgImageTintAlpha by AppearanceConfig.backgroundImageTintAlpha.collectAsState()
    val uiCardOpacity by AppearanceConfig.uiCardOpacity.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── Performance ───────────────────────────────────────────────────────
        SettingsGroupCard(title = "Performance") {
            SettingsToggleItem(
                label = "Performance Mode",
                subtitle = "Disables GPU blur effects (backdrop blur, glassmorphism, ambient glow). Recommended for integrated graphics and low-RAM systems.",
                checked = performanceModeEnabled,
                onCheckedChange = { AppearanceConfig.setPerformanceModeEnabled(it) },
            )
        }

        // ── Ambient Glow ──────────────────────────────────────────────────────
        SettingsGroupCard(title = "Ambient Glow") {
            SettingsToggleItem(
                label = "Ambient Glow",
                subtitle = "Soft adaptive backlight projected from active hero content onto the canvas",
                checked = ambientGlowEnabled,
                onCheckedChange = { AppearanceConfig.setAmbientGlowEnabled(it) },
            )

            if (isLightMode || amoledMode) {
                Text(
                    text = if (isLightMode) "Not available in Light mode." else "Not available in AMOLED mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }

            if (ambientGlowEnabled && !isLightMode && !amoledMode) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsSliderItem(
                    label = "Intensity",
                    subtitle = "${(ambientGlowIntensity * 100).toInt()}%",
                    value = ambientGlowIntensity,
                    onValueChange = { AppearanceConfig.setAmbientGlowIntensity(it) },
                    valueRange = 0.05f..0.60f,
                    steps = 11,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Projection Positions",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Select one or more screen corners to project ambient glow",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val positions = listOf(
                        "Top Left", "Top Right", "Center", "Bottom Left", "Bottom Right",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        positions.forEach { posKey ->
                            val isSelected = ambientGlowPositions.contains(posKey)
                            FilterChip(
                                selected = isSelected,
                                onClick = { AppearanceConfig.toggleAmbientGlowPosition(posKey) },
                                label = { Text(posKey, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null,
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Custom Wallpaper ──────────────────────────────────────────────────
        SettingsGroupCard(title = "Custom Background Wallpaper") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Wallpaper Image", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Set a custom local image as the app background with blur and tint controls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bgImagePath.isNotBlank()) {
                        OutlinedButton(
                            onClick = { AppearanceConfig.clearBackgroundImage() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (bgImagePath.isNotBlank() && java.io.File(bgImagePath).exists()) {
                    val wallpaperFile = remember(bgImagePath) { java.io.File(bgImagePath) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            coil3.compose.AsyncImage(
                                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                                    .data(wallpaperFile)
                                    .size(coil3.size.Size(1280, 720))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Active Wallpaper",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                                            startY = 50f,
                                        ),
                                    ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = wallpaperFile.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = wallpaperFile.parent ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Button(
                                    onClick = {
                                        val chosen = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.open(
                                            title = "Choose Wallpaper Image",
                                            allowedExtensions = listOf("jpg", "jpeg", "png", "webp", "bmp"),
                                            category = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.Category.WALLPAPER,
                                        )
                                        if (chosen != null) AppearanceConfig.setBackgroundImagePath(chosen.absolutePath)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Change Image")
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Wallpaper,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = "No Custom Wallpaper Active",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Select a high-resolution image to use as your desktop app canvas backdrop",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    val chosen = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.open(
                                        title = "Choose Wallpaper Image",
                                        allowedExtensions = listOf("jpg", "jpeg", "png", "webp", "bmp"),
                                        category = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.Category.WALLPAPER,
                                    )
                                    if (chosen != null) AppearanceConfig.setBackgroundImagePath(chosen.absolutePath)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Choose Wallpaper (.png, .jpg, .webp)")
                            }
                        }
                    }
                }

                if (bgImagePath.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsSliderItem(
                        label = "Blur",
                        subtitle = "${bgImageBlur.toInt()} dp",
                        value = bgImageBlur,
                        onValueChange = { AppearanceConfig.setBackgroundImageBlur(it) },
                        valueRange = 0f..50f,
                        steps = 10,
                    )
                    SettingsSliderItem(
                        label = "Brightness",
                        subtitle = "${(bgImageBrightness * 100).toInt()}%",
                        value = bgImageBrightness,
                        onValueChange = { AppearanceConfig.setBackgroundImageBrightness(it) },
                        valueRange = 0.05f..1.0f,
                        steps = 19,
                    )
                    SettingsSliderItem(
                        label = "Opacity",
                        subtitle = "${(bgImageOpacity * 100).toInt()}%",
                        value = bgImageOpacity,
                        onValueChange = { AppearanceConfig.setBackgroundImageOpacity(it) },
                        valueRange = 0.1f..1.0f,
                        steps = 9,
                    )
                    SettingsToggleItem(
                        label = "Vignette",
                        subtitle = "Darkens image edges for a focused cinema look",
                        checked = bgImageVignetteEnabled,
                        onCheckedChange = { AppearanceConfig.setBackgroundImageVignetteEnabled(it) },
                    )
                    if (bgImageVignetteEnabled) {
                        SettingsSliderItem(
                            label = "Vignette Intensity",
                            subtitle = "${(bgImageVignetteIntensity * 100).toInt()}%",
                            value = bgImageVignetteIntensity,
                            onValueChange = { AppearanceConfig.setBackgroundImageVignetteIntensity(it) },
                            valueRange = 0.1f..1.0f,
                            steps = 9,
                        )
                    }
                    SettingsToggleItem(
                        label = "Color Tint",
                        subtitle = "Blends accent color overlay onto wallpaper",
                        checked = bgImageTintEnabled,
                        onCheckedChange = { AppearanceConfig.setBackgroundImageTintEnabled(it) },
                    )
                    if (bgImageTintEnabled) {
                        SettingsSliderItem(
                            label = "Tint Opacity",
                            subtitle = "${(bgImageTintAlpha * 100).toInt()}%",
                            value = bgImageTintAlpha,
                            onValueChange = { AppearanceConfig.setBackgroundImageTintAlpha(it) },
                            valueRange = 0.05f..0.80f,
                            steps = 15,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsSliderItem(
                    label = "UI Card & Glass Opacity",
                    subtitle = "${(uiCardOpacity * 100).toInt()}%",
                    value = uiCardOpacity,
                    onValueChange = { AppearanceConfig.setUiCardOpacity(it) },
                    valueRange = 0.15f..1.0f,
                    steps = 17,
                )
            }
        }
    }
}
