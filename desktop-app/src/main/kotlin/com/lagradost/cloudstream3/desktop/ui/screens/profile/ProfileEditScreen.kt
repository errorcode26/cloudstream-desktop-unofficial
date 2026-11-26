package com.lagradost.cloudstream3.desktop.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.profile.Profile
import com.lagradost.cloudstream3.desktop.profile.ProfileAvatar
import com.lagradost.cloudstream3.desktop.profile.ProfilePalette
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopBackButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopFilledTonalButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.DiceBearBackground
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.DiceBearCatalog
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.DiceBearPreset
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiState
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.desktop.utils.NativeFileDialog

private enum class TileLoadState { LOADING, READY, FAILED }

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = LocalDesktopTheme.current
    var showCreditsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.effectFlow) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is ProfileEditUiEffect.NavigateBack -> onBack()
            }
        }
    }

    if (uiState.showDeleteConfirm) {
        CloudstreamAlertDialog(
            show = true,
            onDismissRequest = { viewModel.onEvent(ProfileEditUiEvent.DismissDelete) },
            title = { Text("Delete Profile?") },
            text = {
                Text(
                    "Are you sure you want to delete profile '${uiState.originalName}'? Its watch history and bookmarks will be permanently removed.",
                )
            },
            confirmButton = {
                DesktopButton(
                    onClick = { viewModel.onEvent(ProfileEditUiEvent.ConfirmDelete) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                DesktopTextButton(onClick = { viewModel.onEvent(ProfileEditUiEvent.DismissDelete) }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showCreditsDialog) {
        CloudstreamAlertDialog(
            show = true,
            onDismissRequest = { showCreditsDialog = false },
            title = { Text("Avatar Credits & Licenses") },
            text = {
                Text(
                    text = "Live avatars via DiceBear v9 (MIT).\n\n" +
                        "CC BY 4.0 (Attribution): Adventurer (Lisa Wischofsky), Big Ears (The Visual Team), " +
                        "Big Smile (Ashley Seo), Croodles (vijay verma), Dylan (Natalia Spivak), " +
                        "Fun Emoji (Davis Uche), Micah (Micah Lanier), Miniavs (Webpixels), Personas (Draftbit).\n\n" +
                        "Free Personal & Commercial: Avataaars & Bottts (Pablo Stanley).\n\n" +
                        "CC0 1.0 (Public Domain): Lorelei (Lisa Wischofsky), Notionists (Zoish), Open Peeps (Pablo Stanley), " +
                        "Pixel Art, Thumbs, Glass, Shapes, Rings, and Identicon (DiceBear).\n\n" +
                        "Images may be cropped when cached locally. Full terms at dicebear.com/licenses.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                DesktopButton(onClick = { showCreditsDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    uiState.cropperTargetPath?.let { cropperPath ->
        AvatarCropperDialog(
            imagePathOrUrl = cropperPath,
            onDismiss = { viewModel.onEvent(ProfileEditUiEvent.DismissCropper) },
            onCropCompleted = { croppedPath ->
                viewModel.onEvent(ProfileEditUiEvent.CropCompleted(croppedPath))
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Escape && !uiState.isSaving) {
                    viewModel.onEvent(ProfileEditUiEvent.Cancel)
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = DesktopDimens.ScreenMaxContentWidth)
                .padding(
                    start = DesktopDimens.DrilldownHorizontalPadding,
                    end = DesktopDimens.DrilldownHorizontalPadding,
                    top = 20.dp,
                    bottom = DesktopDimens.WatermarkBottomInset,
                ),
        ) {
            val isWideLayout = maxWidth >= 900.dp
            val leftPaneWidth = if (maxWidth >= 1120.dp) 368.dp else 330.dp

            Column(modifier = Modifier.fillMaxSize()) {
                ProfileEditorHeader(
                    isCreating = uiState.isCreating,
                    onBack = { viewModel.onEvent(ProfileEditUiEvent.Cancel) },
                )

                AnimatedVisibility(visible = !uiState.saveError.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(
                            text = uiState.saveError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isWideLayout) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        ProfileIdentityAndAccessPanel(
                            state = uiState,
                            onEvent = viewModel::onEvent,
                            isBoundedHeight = true,
                            modifier = Modifier
                                .width(leftPaneWidth)
                                .fillMaxHeight(),
                        )

                        ProfileArtworkStudioPanel(
                            state = uiState,
                            onEvent = viewModel::onEvent,
                            onOpenCredits = { showCreditsDialog = true },
                            isBoundedHeight = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProfileIdentityAndAccessPanel(
                            state = uiState,
                            onEvent = viewModel::onEvent,
                            isBoundedHeight = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ProfileArtworkStudioPanel(
                            state = uiState,
                            onEvent = viewModel::onEvent,
                            onOpenCredits = { showCreditsDialog = true },
                            isBoundedHeight = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorHeader(
    isCreating: Boolean,
    onBack: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DesktopBackButton(onClick = onBack, contentDescription = "Back")
        Column {
            Text(
                text = if (isCreating) "Create Profile" else "Edit Profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = theme.TextPrimary,
            )
            Text(
                text = if (isCreating) {
                    "Set up profile identity, artwork, and access lock."
                } else {
                    "Customize profile name, avatar artwork, and security lock."
                },
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
            )
        }
    }
}

@Composable
private fun ProfileEditorSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    fillHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val containerColor = when {
        theme.isAmoled -> Color(0xFF0A0A0D)
        theme.isLightMode -> theme.SurfaceCard
        else -> theme.SurfaceCard.copy(alpha = 0.72f)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = (if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
private fun ProfileIdentityAndAccessPanel(
    state: ProfileEditUiState,
    onEvent: (ProfileEditUiEvent) -> Unit,
    isBoundedHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current
    val previewProfile = remember(
        state.editingProfileId,
        state.name,
        state.selectedColorIndex,
        state.customAvatarPath,
        state.avatarMode,
        state.generatedStyleId,
        state.generatedPreviewSeed,
        state.isKids,
        state.pinCode,
    ) {
        Profile(
            id = state.editingProfileId ?: -1,
            name = state.name.ifBlank { "Profile" },
            avatarColorIndex = state.selectedColorIndex,
            customAvatarPath = state.effectiveCustomAvatarSource,
            pinCode = state.pinCode.takeIf { state.hasPin },
            isKids = state.isKids,
        )
    }

    val previewBackdrop = remember(state.avatarMode, state.selectedBackgroundId) {
        if (state.avatarMode == 2) {
            backgroundToBrush(DiceBearCatalog.getBackground(state.selectedBackgroundId))
        } else {
            null
        }
    }

    val scrollState = rememberScrollState()

    ProfileEditorSurface(
        modifier = modifier,
        contentPadding = PaddingValues(18.dp),
        fillHeight = isBoundedHeight,
    ) {
        Column(modifier = if (isBoundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Box(
                modifier = if (isBoundedHeight) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isBoundedHeight) {
                                Modifier.verticalScroll(scrollState).padding(end = 6.dp)
                            } else {
                                Modifier
                            },
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(124.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), CircleShape)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(114.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (previewBackdrop != null) {
                                            Modifier.background(previewBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                ProfileAvatar(
                                    profile = previewProfile,
                                    size = 114.dp,
                                    shape = CircleShape,
                                    fontSize = 44.sp,
                                )
                            }
                        }

                        Text(
                            text = state.name.ifBlank { "Profile Name" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.name.isBlank()) theme.TextMuted else theme.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (state.isKids || state.hasPin || state.autoSignIn) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (state.isKids) {
                                    StatusChip(label = "KIDS", tint = MaterialTheme.colorScheme.secondary)
                                }
                                if (state.hasPin) {
                                    StatusChip(label = "PIN", tint = MaterialTheme.colorScheme.primary)
                                }
                                if (state.autoSignIn) {
                                    StatusChip(label = "AUTO SIGN-IN", tint = Color(0xFF4CAF50))
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.name,
                        onValueChange = { onEvent(ProfileEditUiEvent.NameChanged(it)) },
                        label = { Text("Profile Name") },
                        supportingText = {
                            Text(
                                "${state.name.length}/16",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider(color = theme.Divider.copy(alpha = 0.4f))

                    Text(
                        text = "PREFERENCES & LOCK",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextMuted,
                        letterSpacing = 0.6.sp,
                    )

                    ProfileToggleRow(
                        title = "Kids Profile",
                        subtitle = "Family and animation highlights.",
                        checked = state.isKids,
                        onCheckedChange = { onEvent(ProfileEditUiEvent.ToggleKids(it)) },
                    )

                    ProfileToggleRow(
                        title = "Auto Sign-In on Launch",
                        subtitle = "Skip switcher when opening the app.",
                        checked = state.autoSignIn,
                        onCheckedChange = { onEvent(ProfileEditUiEvent.ToggleAutoSignIn(it)) },
                    )

                    ProfileToggleRow(
                        title = "Profile PIN Lock",
                        subtitle = "Require a 4–6 digit PIN to enter.",
                        checked = state.hasPin,
                        onCheckedChange = { onEvent(ProfileEditUiEvent.TogglePin(it)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )

                    AnimatedVisibility(
                        visible = state.hasPin,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.pinCode,
                                onValueChange = { onEvent(ProfileEditUiEvent.PinChanged(it)) },
                                label = { Text("4 to 6 Digit PIN") },
                                supportingText = {
                                    Text(
                                        "${state.pinCode.length}/6 digits (min 4)",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.End,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "PINs cannot be recovered or reset if forgotten.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                if (isBoundedHeight) {
                    DesktopVerticalScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = theme.Divider.copy(alpha = 0.40f))
            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DesktopOutlinedButton(
                        onClick = { onEvent(ProfileEditUiEvent.Cancel) },
                        enabled = !state.isSaving,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    DesktopButton(
                        onClick = { onEvent(ProfileEditUiEvent.SaveProfile) },
                        enabled = state.isSaveEnabled,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) {
                        if (state.isSaving) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Text("Saving...", fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        } else {
                            Text(
                                text = if (state.isCreating) "Create Profile" else "Save Profile",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (state.canDelete) {
                    DesktopOutlinedButton(
                        onClick = { onEvent(ProfileEditUiEvent.RequestDelete) },
                        enabled = !state.isSaving,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.40f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Profile", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.16f),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProfileArtworkStudioPanel(
    state: ProfileEditUiState,
    onEvent: (ProfileEditUiEvent) -> Unit,
    onOpenCredits: () -> Unit,
    isBoundedHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current

    ProfileEditorSurface(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
        fillHeight = isBoundedHeight,
    ) {
        Column(
            modifier = if (isBoundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Avatar & Artwork",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextPrimary,
                    )
                    Text(
                        text = when (state.avatarMode) {
                            0 -> "Pick a color palette for your profile initial."
                            1 -> "Use a local image, animated GIF, or remote image URL."
                            else -> "Scroll to browse styles, choose a background, and select an avatar."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                SegmentedAvatarModeBar(
                    selectedMode = state.avatarMode,
                    onModeSelected = { onEvent(ProfileEditUiEvent.AvatarModeSelected(it)) },
                )
            }

            HorizontalDivider(color = theme.Divider.copy(alpha = 0.4f))

            Box(
                modifier = if (isBoundedHeight) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                when (state.avatarMode) {
                    0 -> GradientsArtworkSection(state = state, onEvent = onEvent)
                    1 -> CustomImageArtworkSection(state = state, onEvent = onEvent)
                    else -> AvatarGalleryStudio(
                        state = state,
                        onEvent = onEvent,
                        onOpenCredits = onOpenCredits,
                        isBoundedHeight = isBoundedHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedAvatarModeBar(
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.SurfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedModePill(
                label = "Gradients",
                icon = Icons.Default.Palette,
                selected = selectedMode == 0,
                onClick = { onModeSelected(0) },
            )
            SegmentedModePill(
                label = "Image / GIF",
                icon = Icons.Default.Image,
                selected = selectedMode == 1,
                onClick = { onModeSelected(1) },
            )
            SegmentedModePill(
                label = "Avatars",
                icon = Icons.Default.AutoAwesome,
                selected = selectedMode == 2,
                onClick = { onModeSelected(2) },
            )
        }
    }
}

@Composable
private fun SegmentedModePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val pillShape = RoundedCornerShape(7.dp)

    Surface(
        shape = pillShape,
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            isHovered -> theme.SurfaceCard.copy(alpha = 0.65f)
            else -> Color.Transparent
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.60f))
        } else {
            null
        },
        modifier = Modifier
            .clip(pillShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else theme.TextMuted,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else theme.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GradientsArtworkSection(
    state: ProfileEditUiState,
    onEvent: (ProfileEditUiEvent) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Color Palette",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = theme.TextPrimary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfilePalette.colors.indices.forEach { index ->
                val isSelected = state.selectedColorIndex == index
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(ProfilePalette.getBrush(index))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, indication = null) {
                            onEvent(ProfileEditUiEvent.ColorSelected(index))
                        }
                        .then(
                            when {
                                isSelected -> Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                isHovered -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape)
                                else -> Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.SurfaceElevated.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Gradient mode displays your profile's first letter on the selected color gradient.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun CustomImageArtworkSection(
    state: ProfileEditUiState,
    onEvent: (ProfileEditUiEvent) -> Unit,
) {
    val theme = LocalDesktopTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.customAvatarPath,
            onValueChange = { onEvent(ProfileEditUiEvent.CustomAvatarChanged(it)) },
            label = { Text("Image or Animated GIF URL / Local Path") },
            placeholder = { Text("https://example.com/avatar.gif") },
            singleLine = true,
            trailingIcon = {
                if (state.customAvatarPath.isNotEmpty()) {
                    DesktopIconButton(onClick = { onEvent(ProfileEditUiEvent.CustomAvatarChanged("")) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DesktopOutlinedButton(
                onClick = {
                    val pickedFile = NativeFileDialog.open(
                        title = "Choose Avatar Image / GIF",
                        allowedExtensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif"),
                        category = NativeFileDialog.Category.WALLPAPER,
                    )
                    if (pickedFile != null && pickedFile.exists()) {
                        onEvent(ProfileEditUiEvent.CustomAvatarChanged(pickedFile.absolutePath))
                        onEvent(ProfileEditUiEvent.OpenCropper(pickedFile.absolutePath))
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse Local Image / GIF", maxLines = 1)
            }

            if (state.customAvatarPath.isNotEmpty()) {
                DesktopOutlinedButton(
                    onClick = { onEvent(ProfileEditUiEvent.ResetCustomAvatar) },
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }

                DesktopFilledTonalButton(
                    onClick = { onEvent(ProfileEditUiEvent.OpenCropper(state.customAvatarPath)) },
                ) {
                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Crop & Adjust")
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.SurfaceElevated.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Supports static PNG, JPG, WebP, and animated GIF files. Remote URLs are cached locally as PNG/GIF when you save.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun AvatarGalleryStudio(
    state: ProfileEditUiState,
    onEvent: (ProfileEditUiEvent) -> Unit,
    onOpenCredits: () -> Unit,
    isBoundedHeight: Boolean,
) {
    val theme = LocalDesktopTheme.current
    var showCustomWord by remember { mutableStateOf(false) }
    val styleListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val selectedBackground = remember(state.selectedBackgroundId) {
        DiceBearCatalog.getBackground(state.selectedBackgroundId)
    }
    val selectedBackgroundBrush = remember(selectedBackground) {
        backgroundToBrush(selectedBackground)
    }

    LaunchedEffect(state.galleryFilterId, state.gallerySeeds.firstOrNull()) {
        gridState.scrollToItem(0)
    }

    val rootModifier = if (isBoundedHeight) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().height(460.dp)
    }

    Row(
        modifier = rootModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.SurfaceElevated.copy(alpha = 0.40f),
            border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.45f)),
            modifier = Modifier
                .width(172.dp)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "STYLES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.TextMuted,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = styleListState,
                        modifier = Modifier.fillMaxSize().padding(end = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        item(key = DiceBearCatalog.FEATURED_FILTER_ID) {
                            StyleRailItem(
                                label = "Curated Mix",
                                isSelected = state.galleryFilterId == DiceBearCatalog.FEATURED_FILTER_ID,
                                onClick = {
                                    onEvent(ProfileEditUiEvent.GalleryFilterSelected(DiceBearCatalog.FEATURED_FILTER_ID))
                                },
                            )
                        }
                        items(DiceBearCatalog.styles, key = { it.id }) { style ->
                            StyleRailItem(
                                label = style.label,
                                isSelected = state.galleryFilterId == style.id,
                                onClick = {
                                    onEvent(ProfileEditUiEvent.GalleryFilterSelected(style.id))
                                },
                            )
                        }
                    }

                    DesktopVerticalScrollbar(
                        listState = styleListState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val activeLabel = if (state.galleryFilterId == DiceBearCatalog.FEATURED_FILTER_ID) {
                    "Curated Mix"
                } else {
                    DiceBearCatalog.styleLabel(state.galleryFilterId)
                }
                Text(
                    text = activeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.TextPrimary,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "BG",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextMuted,
                    )
                    DiceBearCatalog.backgrounds.forEach { bg ->
                        BackgroundSwatchDot(
                            background = bg,
                            isSelected = state.selectedBackgroundId == bg.id,
                            onClick = { onEvent(ProfileEditUiEvent.GeneratedBackgroundSelected(bg.id)) },
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val columns = if (maxWidth >= 560.dp) 5 else 4
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = state.visibleGalleryPresets,
                            key = { "${it.styleId}:${it.seed}" },
                        ) { preset ->
                            val isSelected = state.avatarMode == 2 &&
                                state.generatedStyleId == preset.styleId &&
                                state.generatedPreviewSeed == preset.seed
                            AvatarGalleryTile(
                                preset = preset,
                                backdropBrush = selectedBackgroundBrush,
                                isSelected = isSelected,
                                onClick = { onEvent(ProfileEditUiEvent.GalleryPresetSelected(preset)) },
                            )
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DesktopOutlinedButton(
                                    onClick = { onEvent(ProfileEditUiEvent.LoadMoreGalleryAvatars) },
                                    shape = LocalDesktopButtonShapes.current.compact,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Load 20 more", style = MaterialTheme.typography.labelMedium)
                                }

                                Spacer(Modifier.width(10.dp))

                                DesktopTextButton(
                                    onClick = { onEvent(ProfileEditUiEvent.ShuffleGalleryBatch) },
                                    shape = LocalDesktopButtonShapes.current.compact,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Fresh set", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    DesktopVerticalScrollbar(
                        gridState = gridState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }

            AnimatedVisibility(
                visible = showCustomWord,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                OutlinedTextField(
                    value = state.generatedSeed,
                    onValueChange = { onEvent(ProfileEditUiEvent.GeneratedSeedChanged(it)) },
                    label = { Text("Custom word or phrase") },
                    placeholder = { Text("Type any word to generate an avatar") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.generatedSeed.isNotEmpty()) {
                            DesktopIconButton(onClick = { onEvent(ProfileEditUiEvent.GeneratedSeedChanged("")) }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DesktopTextButton(
                        onClick = { showCustomWord = !showCustomWord },
                        shape = LocalDesktopButtonShapes.current.compact,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (showCustomWord) "Hide custom word" else "Custom word",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    DesktopTextButton(
                        onClick = onOpenCredits,
                        shape = LocalDesktopButtonShapes.current.compact,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Credits", style = MaterialTheme.typography.labelMedium)
                    }
                }

                val cropUrl = state.saveableCustomAvatarSource
                if (!cropUrl.isNullOrBlank()) {
                    DesktopFilledTonalButton(
                        onClick = { onEvent(ProfileEditUiEvent.OpenCropper(cropUrl)) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Crop & Adjust", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSwatchDot(
    background: DiceBearBackground,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val borderStrokeColor = when {
        isSelected -> MaterialTheme.colorScheme.onSurface
        isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        else -> theme.Divider.copy(alpha = 0.7f)
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderStrokeColor,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (background.isTransparent) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color(0xFF2B2F3A))
                drawLine(
                    color = Color(0xFFE57373),
                    start = Offset(size.width * 0.2f, size.height * 0.8f),
                    end = Offset(size.width * 0.8f, size.height * 0.2f),
                    strokeWidth = 2f,
                )
            }
        } else {
            val brush = remember(background) { backgroundToBrush(background) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (brush != null) Modifier.background(brush) else Modifier),
            )
        }
    }
}

private fun backgroundToBrush(background: DiceBearBackground): Brush? {
    if (background.isTransparent) return null
    val parsed = background.hexStops.mapNotNull { parseHexColor(it) }
    return when {
        parsed.isEmpty() -> null
        parsed.size == 1 -> SolidColor(parsed.first())
        else -> Brush.linearGradient(colors = parsed)
    }
}

private fun parseHexColor(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    return clean.toLongOrNull(16)?.let { rgb ->
        Color((0xFF000000L or rgb).toInt())
    }
}

@Composable
private fun StyleRailItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val itemShape = RoundedCornerShape(8.dp)

    Surface(
        shape = itemShape,
        color = when {
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            isHovered -> theme.SurfaceCard.copy(alpha = 0.65f)
            else -> Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else theme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun AvatarGalleryTile(
    preset: DiceBearPreset,
    backdropBrush: Brush?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current
    val thumbUrl = remember(preset) {
        DiceBearCatalog.buildAvatarUrl(preset.styleId, preset.seed, size = 128)
    }
    var loadState by remember(thumbUrl) { mutableStateOf(TileLoadState.LOADING) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val tileShape = RoundedCornerShape(14.dp)
    Surface(
        shape = tileShape,
        color = when {
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            isHovered -> theme.SurfaceElevated.copy(alpha = 0.85f)
            else -> theme.SurfaceElevated.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
                else -> theme.Divider.copy(alpha = 0.45f)
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(tileShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.78f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .then(
                        if (backdropBrush != null) {
                            Modifier.background(backdropBrush)
                        } else {
                            Modifier.background(theme.SurfaceCard.copy(alpha = 0.85f))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = "${DiceBearCatalog.styleLabel(preset.styleId)} ${preset.seed}",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    onLoading = { loadState = TileLoadState.LOADING },
                    onSuccess = { loadState = TileLoadState.READY },
                    onError = { loadState = TileLoadState.FAILED },
                    modifier = Modifier.fillMaxSize(),
                )

                if (loadState == TileLoadState.LOADING) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                } else if (loadState == TileLoadState.FAILED) {
                    Text(
                        text = preset.seed.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.TextMuted,
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val theme = LocalDesktopTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingIcon != null) {
                leadingIcon()
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.TextPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.TextMuted,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
