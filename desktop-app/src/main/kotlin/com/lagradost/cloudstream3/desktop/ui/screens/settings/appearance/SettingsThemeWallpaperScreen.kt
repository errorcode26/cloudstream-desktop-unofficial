package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.HeroPrimaryCtaButton
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.BuiltInPresets
import com.lagradost.cloudstream3.desktop.ui.theme.ButtonShapeStyle
import com.lagradost.cloudstream3.desktop.ui.theme.CastProfileShapeStyle
import com.lagradost.cloudstream3.desktop.ui.theme.DesktopButtonShapes
import com.lagradost.cloudstream3.desktop.ui.theme.DesktopProfileShapes
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopProfileShapes
import com.lagradost.cloudstream3.desktop.ui.theme.ThemeMode
import com.lagradost.cloudstream3.desktop.ui.theme.ThemePreset
import com.lagradost.cloudstream3.desktop.ui.theme.accentColorFromName
import com.lagradost.cloudstream3.desktop.ui.theme.parseHexColor

@Composable
fun SettingsThemeWallpaperScreen() {
    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val buttonShapeStyle by AppearanceConfig.buttonShapeStyle.collectAsState()
    val castProfileShapeStyle by AppearanceConfig.castProfileShapeStyle.collectAsState()
    val appThemeBackground by AppearanceConfig.appThemeBackground.collectAsState()
    val customThemeAccent by AppearanceConfig.customThemeAccent.collectAsState()
    val customAppThemeBackground by AppearanceConfig.customAppThemeBackground.collectAsState()
    val activePresetId by AppearanceConfig.appPresetTheme.collectAsState()
    var showAccentPickerDialog by remember { mutableStateOf(false) }
    var showBackgroundPickerDialog by remember { mutableStateOf(false) }

    val curatedAccents = remember {
        listOf(
            "Blue" to Color(0xFF3B82F6),
            "Cyan" to Color(0xFF06B6D4),
            "Purple" to Color(0xFF7C6BFF),
            "Green" to Color(0xFF10B981),
            "Amber" to Color(0xFFF59E0B),
            "Orange" to Color(0xFFF97316),
            "Red" to Color(0xFFEF4444),
            "Rose" to Color(0xFFEC4899),
            "Ice" to Color(0xFF94A3B8),
        )
    }

    val curatedBackgrounds = remember {
        listOf(
            "Navy" to ("Deep Navy" to Color(0xFF0C0C16)),
            "Midnight" to ("Midnight" to Color(0xFF0B1120)),
            "Slate" to ("Dark Slate" to Color(0xFF18181B)),
            "Mocha" to ("Warm Mocha" to Color(0xFF1E1815)),
            "Forest" to ("Deep Forest" to Color(0xFF0F1714)),
        )
    }

    val curatedLightBackgrounds = remember {
        listOf(
            "Frost" to ("Clean Frost" to Color(0xFFF1F5F9)),
            "Alabaster" to ("Warm Alabaster" to Color(0xFFF7F4EE)),
            "Nordic" to ("Nordic Snow" to Color(0xFFECEFF4)),
            "Latte" to ("Warm Latte" to Color(0xFFF3EDE5)),
            "Matcha" to ("Matcha Tea" to Color(0xFFEDF4EF)),
            "Sakura" to ("Sakura Blush" to Color(0xFFFAF0F4)),
            "Solar" to ("Solarized" to Color(0xFFFDF6E3)),
        )
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val currentThemeMode = when {
            isLightMode -> ThemeMode.LIGHT
            amoledMode -> ThemeMode.AMOLED
            else -> ThemeMode.DARK
        }

        // Card 1: Appearance Mode
        SettingsGroupCard(title = "Appearance Mode") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val modes = listOf(
                    Triple(ThemeMode.DARK, "Dark Mode", "Comfortable dark canvas for everyday viewing"),
                    Triple(ThemeMode.AMOLED, "Pure AMOLED", "100% pitch black canvas for true OLED contrast"),
                    Triple(ThemeMode.LIGHT, "Light Mode", "Crisp, bright daytime contrast and palette"),
                )
                modes.forEach { (mode, title, desc) ->
                    val isSelected = currentThemeMode == mode
                    Surface(
                        onClick = { AppearanceConfig.setThemeMode(mode) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.weight(1f).height(104.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = when (mode) {
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                        ThemeMode.AMOLED -> Icons.Default.Contrast
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Card 1B: Shape & Corner Identity (Merged Buttons + Cast/Profile Shapes)
        SettingsGroupCard(title = "Shape & Corner Identity") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeSegmentedSettingRow(
                    title = "Buttons & Controls",
                    subtitle = "Action CTAs, filters, dialogs, and icon buttons",
                    options = ButtonShapeStyle.entries,
                    selectedOption = buttonShapeStyle,
                    labelFor = { it.label },
                    onSelect = { AppearanceConfig.setButtonShapeStyle(it) },
                    swatch = { preset, isSelected ->
                        val previewTokens = remember(preset) { DesktopButtonShapes.fromStyle(preset) }
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(11.dp)
                                .clip(previewTokens.primary)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                ),
                        )
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                ShapeSegmentedSettingRow(
                    title = "Cast & Profile Photos",
                    subtitle = "Cast carousels, full roster, dual-cast badges, and person pages",
                    options = CastProfileShapeStyle.entries,
                    selectedOption = castProfileShapeStyle,
                    labelFor = { it.label },
                    onSelect = { AppearanceConfig.setCastProfileShapeStyle(it) },
                    swatch = { preset, isSelected ->
                        val previewTokens = remember(preset) { DesktopProfileShapes.fromStyle(preset) }
                        Box(
                            modifier = Modifier
                                .height(14.dp)
                                .aspectRatio(previewTokens.portraitAspectRatio)
                                .clip(previewTokens.card)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                ),
                        )
                    },
                )

                val activeButtonShapes = LocalDesktopButtonShapes.current
                val activeProfileShapes = LocalDesktopProfileShapes.current

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Preview",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Box(
                            modifier = if (isLightMode) {
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF12141E))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            } else {
                                Modifier
                            },
                        ) {
                            HeroPrimaryCtaButton(
                                text = "Play",
                                icon = Icons.Default.PlayArrow,
                                onClick = {},
                                shape = activeButtonShapes.primary,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                iconSize = 16.dp,
                                fontSize = 12.5.sp,
                                iconSpacing = 5.dp,
                                modifier = Modifier.height(34.dp),
                            )
                        }

                        DesktopOutlinedButton(
                            onClick = {},
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text("Filter", style = MaterialTheme.typography.labelMedium)
                        }

                        DesktopIconButton(
                            onClick = {},
                            colors = IconButtonDefaults.filledTonalIconButtonColors(),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Icon button preview",
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(if (activeProfileShapes.isCircular) 38.dp else 28.dp)
                                    .aspectRatio(activeProfileShapes.portraitAspectRatio),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(activeProfileShapes.card)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), activeProfileShapes.card),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Cast preview",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 2.dp, y = 2.dp)
                                        .background(MaterialTheme.colorScheme.surface, activeProfileShapes.badge)
                                        .padding(1.5.dp)
                                        .clip(activeProfileShapes.badge)
                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)),
                                )
                            }
                            Text(
                                text = "Cast",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(if (activeProfileShapes.isCircular) 38.dp else 28.dp)
                                    .aspectRatio(activeProfileShapes.portraitAspectRatio)
                                    .clip(activeProfileShapes.hero)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), activeProfileShapes.hero),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Person preview",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = "Person",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Card 2: Accent Color & Highlights
        SettingsGroupCard(title = "Accent Color & Highlights") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Primary color used for focus rings, active buttons, slider tracks, and indicator badges.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    curatedAccents.forEach { (name, color) ->
                        val isSelected = themeAccent == name
                        Surface(
                            onClick = { AppearanceConfig.setThemeAccent(name) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(color),
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Custom Color Chip
                    val isCustom = themeAccent == "Custom"
                    val customColor = remember(customThemeAccent) { parseHexColor(customThemeAccent, Color(0xFF7C6BFF)) }
                    Surface(
                        onClick = { showAccentPickerDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCustom) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            width = if (isCustom) 2.dp else 1.dp,
                            color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.height(42.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(customColor)
                                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                            )
                            Text(
                                text = if (isCustom) "Custom (${customThemeAccent.uppercase()})" else "Custom Color...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCustom) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.Default.ColorLens,
                                contentDescription = "Pick custom accent",
                                tint = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Card 3: Canvas Background Tone
        SettingsGroupCard(title = "Canvas Background Tone") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (currentThemeMode == ThemeMode.AMOLED) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF101010),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column {
                                Text(
                                    text = "Pure Black Canvas Active",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    text = "In AMOLED mode, the background canvas is locked to true 100% black (#000000) for pixel turn-off on OLED displays.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Select canvas tone for main screens, sidebars, and dialog backdrops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val activeBackgrounds = if (currentThemeMode == ThemeMode.LIGHT) curatedLightBackgrounds else curatedBackgrounds

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        activeBackgrounds.forEach { (key, pair) ->
                            val (label, color) = pair
                            val isSelected = appThemeBackground == key
                            Surface(
                                onClick = { AppearanceConfig.setAppThemeBackground(key) },
                                shape = RoundedCornerShape(10.dp),
                                color = color,
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                                ),
                                modifier = Modifier.height(42.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (currentThemeMode == ThemeMode.LIGHT) Color(0xFF1E293B) else Color.White,
                                    )
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Custom Canvas Chip
                        val isCustomBg = appThemeBackground == "Custom"
                        val customBgColor = remember(customAppThemeBackground) { parseHexColor(customAppThemeBackground, Color(0xFF0C0C16)) }
                        Surface(
                            onClick = { showBackgroundPickerDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            color = customBgColor,
                            border = BorderStroke(
                                width = if (isCustomBg) 2.dp else 1.dp,
                                color = if (isCustomBg) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (isCustomBg) "Custom Canvas (${customAppThemeBackground.uppercase()})" else "Custom Canvas...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCustomBg) FontWeight.Bold else FontWeight.Medium,
                                    color = if (currentThemeMode == ThemeMode.LIGHT) Color(0xFF1E293B) else Color.White,
                                )
                                Icon(
                                    Icons.Default.ColorLens,
                                    contentDescription = "Pick custom canvas",
                                    tint = if (isCustomBg) MaterialTheme.colorScheme.primary else (if (currentThemeMode == ThemeMode.LIGHT) Color(0xFF1E293B) else Color.White.copy(alpha = 0.7f)),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Card 4: Curated 1-Click Theme Bundles
        SettingsGroupCard(title = "Curated 1-Click Theme Bundles") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Instantly configure mode, accent highlight, and canvas background in one click.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BuiltInPresets.presets.forEach { preset ->
                        val isMatchingPreset = remember(preset, isLightMode, amoledMode, themeAccent, appThemeBackground) {
                            val modeMatch = if (preset.isLightMode) isLightMode else (!isLightMode && !amoledMode)
                            val accentMatch = themeAccent == preset.themeAccent
                            val bgMatch = appThemeBackground == preset.appThemeBackground
                            modeMatch && accentMatch && bgMatch
                        }
                        ThemePresetCard(
                            preset = preset,
                            isSelected = isMatchingPreset,
                            onClick = { AppearanceConfig.applyPreset(preset) },
                            modifier = Modifier.width(172.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAccentPickerDialog) {
        CustomColorPickerDialog(
            show = showAccentPickerDialog,
            title = "Custom Accent Color",
            initialHex = customThemeAccent,
            onDismissRequest = { showAccentPickerDialog = false },
            onColorConfirmed = { hex ->
                AppearanceConfig.setCustomThemeAccent(hex)
                AppearanceConfig.setThemeAccent("Custom")
                AppearanceConfig.setAppPresetTheme("custom")
                showAccentPickerDialog = false
            },
        )
    }

    if (showBackgroundPickerDialog) {
        CustomColorPickerDialog(
            show = showBackgroundPickerDialog,
            title = "Custom Canvas Background",
            initialHex = customAppThemeBackground,
            onDismissRequest = { showBackgroundPickerDialog = false },
            onColorConfirmed = { hex ->
                AppearanceConfig.setCustomAppThemeBackground(hex)
                AppearanceConfig.setAppThemeBackground("Custom")
                AppearanceConfig.setAppPresetTheme("custom")
                showBackgroundPickerDialog = false
            },
        )
    }
}

@Composable
private fun <T> ShapeSegmentedSettingRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selectedOption: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    swatch: @Composable (option: T, isSelected: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 660.dp

        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShapePickerTray(
                    options = options,
                    selectedOption = selectedOption,
                    labelFor = labelFor,
                    onSelect = onSelect,
                    swatch = swatch,
                    modifier = Modifier.width(388.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShapePickerTray(
                    options = options,
                    selectedOption = selectedOption,
                    labelFor = labelFor,
                    onSelect = onSelect,
                    swatch = swatch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun <T> ShapePickerTray(
    options: List<T>,
    selectedOption: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    swatch: @Composable (option: T, isSelected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = selectedOption == option
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(7.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        swatch(option, isSelected)
                        Text(
                            text = labelFor(option),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = remember(preset) { accentColorFromName(preset.themeAccent, preset.customThemeAccent) }
    val bgColor = remember(preset) {
        if (preset.isLightMode) {
            parseHexColor(preset.customAppThemeBackground, Color(0xFFF1F5F9))
        } else {
            parseHexColor(preset.customAppThemeBackground, Color(0xFF0C0C16))
        }
    }
    val cardColor = remember(bgColor, preset.isLightMode) {
        if (preset.isLightMode) {
            Color(0xFFFFFFFF)
        } else {
            Color(
                (bgColor.red + 0.07f).coerceIn(0f, 1f),
                (bgColor.green + 0.07f).coerceIn(0f, 1f),
                (bgColor.blue + 0.08f).coerceIn(0f, 1f),
            )
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        ),
        modifier = modifier.height(112.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(accentColor),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(cardColor)
                            .padding(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accentColor),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (preset.isLightMode) "Light Mode" else "Dark Mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomColorPickerDialog(
    show: Boolean,
    title: String,
    initialHex: String,
    onDismissRequest: () -> Unit,
    onColorConfirmed: (String) -> Unit,
) {
    val cleanInitial = remember(initialHex) {
        val s = initialHex.trim().removePrefix("#")
        if (s.length == 6 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) "#${s.uppercase()}" else "#3B82F6"
    }
    var hexText by remember { mutableStateOf(cleanInitial) }

    val parsedColor = remember(hexText) {
        val stripped = hexText.trim().removePrefix("#")
        if (stripped.length == 6 && stripped.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            try {
                Color(stripped.toLong(16) or 0xFF000000L)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    val currentColor = parsedColor ?: Color(0xFF3B82F6)
    val red = (currentColor.red * 255f).toInt()
    val green = (currentColor.green * 255f).toInt()
    val blue = (currentColor.blue * 255f).toInt()

    fun updateRgb(newR: Int, newG: Int, newB: Int) {
        val hex = String.format("#%02X%02X%02X", newR.coerceIn(0, 255), newG.coerceIn(0, 255), newB.coerceIn(0, 255))
        hexText = hex
    }

    val quickSwatches = remember {
        listOf(
            "#7C6BFF", "#3B82F6", "#06B6D4", "#10B981",
            "#F59E0B", "#F97316", "#EF4444", "#EC4899",
            "#8B5CF6", "#6366F1", "#14B8A6", "#E11D48",
            "#0C0C16", "#0B1120", "#18181B", "#1E1815",
            "#0F1714", "#F1F5F9", "#F7F4EE", "#ECEFF4",
        )
    }

    CloudstreamAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        confirmButton = {
            DesktopButton(
                onClick = {
                    parsedColor?.let {
                        val formatted = hexText.trim().let { if (!it.startsWith("#")) "#$it" else it }.uppercase()
                        onColorConfirmed(formatted)
                    }
                },
                enabled = parsedColor != null,
                shape = LocalDesktopButtonShapes.current.medium,
            ) {
                Text("Apply Color")
            }
        },
        dismissButton = {
            DesktopOutlinedButton(
                onClick = onDismissRequest,
                shape = LocalDesktopButtonShapes.current.medium,
            ) {
                Text("Cancel")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = currentColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = hexText.uppercase(),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val filtered = input.take(7).filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                        hexText = if (!filtered.startsWith("#") && filtered.isNotEmpty()) "#$filtered" else filtered
                    },
                    label = { Text("Hex Code") },
                    singleLine = true,
                    isError = parsedColor == null && hexText.isNotEmpty(),
                    supportingText = {
                        if (parsedColor == null && hexText.isNotEmpty()) {
                            Text("Enter a valid 6-character hex (e.g. #7C6BFF)", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("RGB Sliders", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("R", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), modifier = Modifier.width(16.dp))
                        Slider(
                            value = red.toFloat(),
                            onValueChange = { updateRgb(it.toInt(), green, blue) },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$red", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("G", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), modifier = Modifier.width(16.dp))
                        Slider(
                            value = green.toFloat(),
                            onValueChange = { updateRgb(red, it.toInt(), blue) },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$green", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("B", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6), modifier = Modifier.width(16.dp))
                        Slider(
                            value = blue.toFloat(),
                            onValueChange = { updateRgb(red, green, it.toInt()) },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$blue", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Palette Swatches", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickSwatches.forEach { swatchHex ->
                            val swatchColor = parseHexColor(swatchHex, Color.Gray)
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (hexText.equals(swatchHex, ignoreCase = true)) 2.dp else 1.dp,
                                        color = if (hexText.equals(swatchHex, ignoreCase = true)) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                                        shape = CircleShape,
                                    )
                                    .clickable { hexText = swatchHex },
                            )
                        }
                    }
                }
            }
        },
    )
}
