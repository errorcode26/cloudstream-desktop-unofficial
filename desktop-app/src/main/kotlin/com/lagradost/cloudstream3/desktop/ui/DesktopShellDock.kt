package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.desktop.ui.components.DockItemsList
import com.lagradost.cloudstream3.desktop.ui.components.DockProfileAvatar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopAppearance
import com.lagradost.cloudstream3.desktop.ui.theme.NavigationStyle
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Calculates window padding based on dock position, breakpoint constraints, and topbar visibility.
 */
fun calculateShellContentPadding(
    showDock: Boolean,
    effectiveDockPosition: DockPosition,
    isCompact: Boolean,
    showTopBar: Boolean,
): PaddingValues {
    val safeTop = if (showTopBar) (if (isCompact) 54.dp else 64.dp) else 0.dp
    val basePadding = if (isCompact) 8.dp else 16.dp

    return if (showDock) {
        when (effectiveDockPosition) {
            DockPosition.LEFT -> PaddingValues(
                start = 82.dp + basePadding,
                top = safeTop + basePadding,
                end = basePadding,
                bottom = basePadding,
            )
            DockPosition.RIGHT -> PaddingValues(
                start = basePadding,
                top = safeTop + basePadding,
                end = 82.dp + basePadding,
                bottom = basePadding,
            )
            DockPosition.TOP -> PaddingValues(
                start = basePadding,
                top = (if (showTopBar) 68.dp else 54.dp) + basePadding,
                end = basePadding,
                bottom = basePadding,
            )
            DockPosition.BOTTOM -> PaddingValues(
                start = basePadding,
                top = safeTop + basePadding,
                end = basePadding,
                bottom = (if (isCompact) 64.dp else 82.dp) + basePadding,
            )
        }
    } else {
        PaddingValues(
            start = basePadding,
            top = safeTop + basePadding,
            end = basePadding,
            bottom = basePadding,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NavigationDock(
    modifier: Modifier = Modifier,
    currentTitle: String,
    dockPosition: DockPosition,
    onNavigate: (Config) -> Unit,
    onSearchClick: () -> Unit,
    onOpenProfileManager: (() -> Unit)? = null,
) {
    val isBottom = dockPosition == DockPosition.BOTTOM
    val isRight = dockPosition == DockPosition.RIGHT
    val isTop = dockPosition == DockPosition.TOP
    val isHorizontal = isBottom || isTop

    val appearance = LocalDesktopAppearance.current
    val navStyle = appearance.navigationStyle
    val isSeamless = navStyle == NavigationStyle.SEAMLESS_BAR
    val isLightMode = appearance.isLightMode
    val amoledMode = appearance.amoledMode
    val desktopTheme = LocalDesktopTheme.current
    val dockHazeState = LocalHazeState.current
    val isTopOrBottom = isTop || isBottom
    val isSideSeamless = isSeamless && !isHorizontal
    val performanceMode by AppearanceConfig.performanceModeEnabled.collectAsState()

    val railInteraction = remember { MutableInteractionSource() }
    val isRailHoveredByInteraction by railInteraction.collectIsHoveredAsState()
    var isRailPointerHovered by remember { mutableStateOf(false) }
    val isExpanded = isSideSeamless && (isRailPointerHovered || isRailHoveredByInteraction)

    val animatedRailWidth by animateDpAsState(
        targetValue = if (isExpanded) 220.dp else 64.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "seamlessSideRailWidth",
    )

    val vignetteAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "sideVignetteAlpha",
    )

    val dockItems = @Composable { expanded: Boolean ->
        DockItemsList(
            currentTitle = currentTitle,
            isHorizontal = isHorizontal,
            indicatorAtTop = isTop,
            isRight = isRight,
            isExpanded = expanded,
            onNavigate = onNavigate,
            onSearchClick = onSearchClick,
        )
    }

    val barBase = when {
        isLightMode -> desktopTheme.SurfaceCard
        amoledMode -> Color.Black
        else -> Color(0xFF14141A)
    }

    if (isSeamless) {
        val barModifier = when {
            isBottom -> Modifier.fillMaxWidth().height(56.dp)
            isTop -> Modifier.fillMaxWidth().height(56.dp)
            isRight -> Modifier.fillMaxHeight().width(if (isSideSeamless) animatedRailWidth else 64.dp)
            else -> Modifier.fillMaxHeight().width(if (isSideSeamless) animatedRailWidth else 64.dp)
        }

        val seamlessHazeModifier = if (dockHazeState != null && isTopOrBottom && !amoledMode && !performanceMode) {
            Modifier.hazeEffect(
                state = dockHazeState,
                style = HazeStyle(
                    backgroundColor = barBase.copy(alpha = 0.65f),
                    tint = HazeTint(barBase.copy(alpha = 0.65f)),
                    blurRadius = 24.dp,
                ),
            )
        } else {
            Modifier
        }

        val borderModifier = when {
            isBottom -> Modifier.drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawLine(
                        color = if (isLightMode) {
                            desktopTheme.Divider
                        } else if (amoledMode) {
                            Color.White.copy(0.12f)
                        } else {
                            Color.White.copy(0.12f)
                        },
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            isTop -> Modifier.drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawLine(
                        color = if (isLightMode) {
                            desktopTheme.Divider
                        } else if (amoledMode) {
                            Color.White.copy(0.12f)
                        } else {
                            Color.White.copy(0.12f)
                        },
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            else -> Modifier
        }

        val hoverModifier = if (isSideSeamless) {
            Modifier
                .hoverable(railInteraction)
                .onPointerEvent(PointerEventType.Enter) { isRailPointerHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isRailPointerHovered = false }
        } else {
            Modifier
        }

        val baseDark = if (amoledMode) {
            Color.Black
        } else if (isLightMode) {
            desktopTheme.SurfaceCard
        } else {
            Color.Black
        }
        val vignetteColors = if (isRight) {
            listOf(
                Color.Transparent,
                baseDark.copy(alpha = 0.06f),
                baseDark.copy(alpha = 0.20f),
                baseDark.copy(alpha = 0.45f),
                baseDark.copy(alpha = 0.72f),
                baseDark.copy(alpha = 0.90f),
                baseDark.copy(alpha = 0.96f),
            )
        } else {
            listOf(
                baseDark.copy(alpha = 0.96f),
                baseDark.copy(alpha = 0.90f),
                baseDark.copy(alpha = 0.72f),
                baseDark.copy(alpha = 0.45f),
                baseDark.copy(alpha = 0.20f),
                baseDark.copy(alpha = 0.06f),
                Color.Transparent,
            )
        }

        Box(
            modifier = modifier
                .then(if (isSideSeamless && isExpanded) Modifier.zIndex(100f) else Modifier.zIndex(40f)),
            contentAlignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (isSideSeamless) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.52f)
                        .alpha(vignetteAlpha)
                        .background(Brush.horizontalGradient(vignetteColors)),
                )
            }

            Box(
                modifier = Modifier
                    .then(barModifier)
                    .then(if (isTopOrBottom && (dockHazeState == null || amoledMode)) Modifier.background(barBase) else Modifier)
                    .then(seamlessHazeModifier)
                    .then(borderModifier)
                    .then(hoverModifier)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
            ) {
                if (isHorizontal) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        dockItems(false)
                        Box(
                            modifier = Modifier
                                .height(20.dp)
                                .width(1.dp)
                                .background(if (isLightMode) desktopTheme.Divider else Color.White.copy(alpha = 0.2f)),
                        )
                        DockProfileAvatar(
                            isExpanded = false,
                            isHorizontal = true,
                            onOpenProfileManager = onOpenProfileManager,
                            onOpenCreateProfile = { onNavigate(Config.ProfileEdit(profileId = null, switchOnCreate = true)) },
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 14.dp),
                        horizontalAlignment = if (isRight) Alignment.End else Alignment.Start,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = if (isRight) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        ) {
                            dockItems(isExpanded)
                        }

                        DockProfileAvatar(
                            isExpanded = isExpanded,
                            isRight = isRight,
                            onOpenProfileManager = onOpenProfileManager,
                            onOpenCreateProfile = { onNavigate(Config.ProfileEdit(profileId = null, switchOnCreate = true)) },
                        )
                    }
                }
            }
        }
    } else {
        val pillShape = RoundedCornerShape(26.dp)
        val surfaceModifier = when {
            isBottom -> Modifier.padding(bottom = 16.dp).height(52.dp).wrapContentWidth()
            isTop -> Modifier.padding(top = 16.dp).height(52.dp).wrapContentWidth()
            isRight -> Modifier.padding(end = 16.dp).width(52.dp).wrapContentHeight()
            else -> Modifier.padding(start = 16.dp).width(52.dp).wrapContentHeight()
        }

        val paddingInsideSurface = if (isHorizontal) {
            Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        } else {
            Modifier.padding(vertical = 14.dp, horizontal = 5.dp)
        }

        val glassBase = when {
            isLightMode -> desktopTheme.SurfaceElevated
            amoledMode -> Color.Black
            else -> Color(0xFF14141A)
        }
        val glassGradient = Brush.linearGradient(
            colors = listOf(
                glassBase.copy(alpha = if (amoledMode) 0.85f else 0.70f),
                glassBase.copy(alpha = if (amoledMode) 0.70f else 0.55f),
            ),
        )
        val borderGradient = Brush.linearGradient(
            colors = listOf(
                if (isLightMode) desktopTheme.Divider.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.45f),
                if (isLightMode) desktopTheme.Divider.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.20f),
            ),
        )

        val dockHazeModifier = if (dockHazeState != null && !amoledMode && !performanceMode) {
            Modifier.hazeEffect(
                state = dockHazeState,
                style = HazeStyle(
                    backgroundColor = if (isLightMode) Color(0xFFF0F0F4).copy(alpha = 0.50f) else Color(0xFF14141A).copy(alpha = 0.35f),
                    tint = HazeTint(if (isLightMode) Color.White.copy(alpha = 0.35f) else Color(0xFF0F0F14).copy(alpha = 0.40f)),
                    blurRadius = 24.dp,
                    noiseFactor = 0.05f,
                ),
            )
        } else {
            Modifier
        }

        Box(modifier = modifier) {
            Box(
                modifier = surfaceModifier
                    .shadow(
                        elevation = 16.dp,
                        shape = pillShape,
                        clip = false,
                        ambientColor = Color.Transparent,
                        spotColor = Color.Black.copy(alpha = 0.35f),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .then(dockHazeModifier)
                        .then(
                            if (dockHazeState != null && !amoledMode) {
                                Modifier.background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = if (isLightMode) 0.15f else 0.06f),
                                            Color.White.copy(alpha = if (isLightMode) 0.05f else 0.02f),
                                        ),
                                    ),
                                )
                            } else {
                                Modifier.background(glassGradient)
                            },
                        )
                        .border(1.2.dp, borderGradient, pillShape)
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        },
                ) {
                    if (isHorizontal) {
                        Row(
                            modifier = paddingInsideSurface,
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            dockItems(false)
                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(if (isLightMode) desktopTheme.Divider else Color.White.copy(alpha = 0.2f)),
                            )
                            DockProfileAvatar(
                                isExpanded = false,
                                isHorizontal = true,
                                onOpenProfileManager = onOpenProfileManager,
                                onOpenCreateProfile = { onNavigate(Config.ProfileEdit(profileId = null, switchOnCreate = true)) },
                            )
                        }
                    } else {
                        Column(
                            modifier = paddingInsideSurface,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            dockItems(false)
                            Spacer(modifier = Modifier.height(2.dp))
                            DockProfileAvatar(
                                isExpanded = false,
                                isRight = false,
                                onOpenProfileManager = onOpenProfileManager,
                                onOpenCreateProfile = { onNavigate(Config.ProfileEdit(profileId = null, switchOnCreate = true)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobileBottomNavBar(
    modifier: Modifier = Modifier,
    currentTitle: String,
    onNavigate: (Config) -> Unit,
    onSearchClick: () -> Unit,
) {
    val isLightMode = LocalDesktopAppearance.current.isLightMode
    val bg = if (isLightMode) Color(0xFFF7F7F9) else Color(0xFF14141A)
    val border = if (isLightMode) Color(0xFFE5E5EA) else Color(0xFF282834)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp),
        color = bg.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        shadowElevation = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            MobileTabItem(
                icon = PremiumIcons.Home,
                label = "Home",
                selected = currentTitle == "Home",
                onClick = { onNavigate(Config.Home) },
            )

            MobileTabItem(
                icon = PremiumIcons.Search,
                label = "Search",
                selected = currentTitle == "Search" || currentTitle == "Explore & Catalogs",
                onClick = onSearchClick,
            )

            MobileTabItem(
                icon = PremiumIcons.Library,
                label = "Library",
                selected = currentTitle == "Library" || currentTitle == "Watch History",
                onClick = { onNavigate(Config.Library) },
            )

            MobileTabItem(
                icon = PremiumIcons.Settings,
                label = "Settings",
                selected = currentTitle == "Settings" || currentTitle == "Extensions",
                onClick = { onNavigate(Config.Settings) },
            )
        }
    }
}

@Composable
internal fun MobileTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isHighlighted = selected || isHovered || isFocused
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .clip(shape)
            .hoverable(interactionSource)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isHighlighted) primaryColor else unselectedColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (isHighlighted) primaryColor else unselectedColor,
        )
    }
}
