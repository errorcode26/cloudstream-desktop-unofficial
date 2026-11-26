package com.lagradost.cloudstream3.desktop.ui.screens.profile

import com.lagradost.cloudstream3.desktop.profile.Profile
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.DiceBearCatalog
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.profile.contract.ProfileEditUiState
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ProfileEditViewModel(
    profileId: Int? = null,
    private val switchOnSaveWhenCreated: Boolean = false,
) : BaseMviViewModel<ProfileEditUiState, ProfileEditUiEvent, ProfileEditUiEffect>(
    initialState = createInitialState(profileId),
) {
    private var seedDebounceJob: Job? = null

    init {
        viewModelScope.launch {
            ProfileManager.profiles.collect { profiles ->
                updateState { copy(canDelete = profiles.size > 1 && editingProfileId != null) }
            }
        }
    }

    override fun handleEvent(event: ProfileEditUiEvent) {
        when (event) {
            is ProfileEditUiEvent.NameChanged -> {
                if (event.name.length <= 16) {
                    updateState { copy(name = event.name, saveError = null) }
                }
            }
            is ProfileEditUiEvent.AvatarModeSelected -> {
                val targetMode = event.mode.coerceIn(0, 2)
                updateState {
                    if (targetMode == 2 && generatedPreviewSeed.isBlank()) {
                        val firstPreset = visibleGalleryPresets.firstOrNull()
                        val seed = firstPreset?.seed ?: name.trim().ifBlank { "Atlas" }
                        val style = firstPreset?.styleId ?: DiceBearCatalog.DEFAULT_STYLE_ID
                        copy(
                            avatarMode = targetMode,
                            generatedStyleId = style,
                            generatedSeed = seed,
                            generatedPreviewSeed = seed,
                        )
                    } else {
                        copy(avatarMode = targetMode)
                    }
                }
            }
            is ProfileEditUiEvent.ColorSelected -> {
                updateState { copy(selectedColorIndex = event.colorIndex) }
            }
            is ProfileEditUiEvent.CustomAvatarChanged -> {
                updateState { copy(customAvatarPath = event.path) }
            }
            is ProfileEditUiEvent.ResetCustomAvatar -> {
                updateState { copy(customAvatarPath = "", avatarMode = 0) }
            }
            is ProfileEditUiEvent.GalleryFilterSelected -> {
                updateState {
                    copy(
                        avatarMode = 2,
                        galleryFilterId = event.filterId,
                    )
                }
            }
            is ProfileEditUiEvent.GalleryPresetSelected -> {
                seedDebounceJob?.cancel()
                updateState {
                    copy(
                        avatarMode = 2,
                        generatedStyleId = event.preset.styleId,
                        generatedSeed = event.preset.seed,
                        generatedPreviewSeed = event.preset.seed,
                    )
                }
            }
            is ProfileEditUiEvent.GeneratedBackgroundSelected -> {
                updateState {
                    copy(
                        avatarMode = 2,
                        selectedBackgroundId = event.backgroundId,
                    )
                }
            }
            is ProfileEditUiEvent.LoadMoreGalleryAvatars -> {
                updateState {
                    copy(gallerySeeds = gallerySeeds + DiceBearCatalog.generateRandomSeeds(DiceBearCatalog.LOAD_MORE_COUNT))
                }
            }
            is ProfileEditUiEvent.ShuffleGalleryBatch -> {
                updateState {
                    copy(gallerySeeds = DiceBearCatalog.generateRandomSeeds(DiceBearCatalog.INITIAL_COUNT))
                }
            }
            is ProfileEditUiEvent.GeneratedSeedChanged -> {
                val trimmed = event.seed.take(40)
                updateState {
                    val activeStyle = if (galleryFilterId != DiceBearCatalog.FEATURED_FILTER_ID) {
                        galleryFilterId
                    } else {
                        generatedStyleId
                    }
                    copy(
                        generatedSeed = trimmed,
                        generatedStyleId = activeStyle,
                        avatarMode = 2,
                    )
                }
                seedDebounceJob?.cancel()
                seedDebounceJob = viewModelScope.launch {
                    delay(320L)
                    updateState { copy(generatedPreviewSeed = trimmed) }
                }
            }
            is ProfileEditUiEvent.OpenCropper -> {
                if (event.path.isNotBlank()) {
                    updateState { copy(cropperTargetPath = event.path) }
                }
            }
            is ProfileEditUiEvent.CropCompleted -> {
                updateState { copy(customAvatarPath = event.croppedPath, cropperTargetPath = null, avatarMode = 1) }
            }
            is ProfileEditUiEvent.DismissCropper -> {
                updateState { copy(cropperTargetPath = null) }
            }
            is ProfileEditUiEvent.ToggleKids -> {
                updateState { copy(isKids = event.enabled) }
            }
            is ProfileEditUiEvent.ToggleAutoSignIn -> {
                updateState { copy(autoSignIn = event.enabled) }
            }
            is ProfileEditUiEvent.TogglePin -> {
                updateState {
                    copy(
                        hasPin = event.enabled,
                        pinCode = if (!event.enabled) "" else pinCode,
                    )
                }
            }
            is ProfileEditUiEvent.PinChanged -> {
                if (event.pin.all { it.isDigit() } && event.pin.length <= 6) {
                    updateState { copy(pinCode = event.pin) }
                }
            }
            is ProfileEditUiEvent.RequestDelete -> {
                if (uiState.value.canDelete && !uiState.value.isCreating) {
                    updateState { copy(showDeleteConfirm = true) }
                }
            }
            is ProfileEditUiEvent.DismissDelete -> {
                updateState { copy(showDeleteConfirm = false) }
            }
            is ProfileEditUiEvent.ConfirmDelete -> {
                deleteProfile()
            }
            is ProfileEditUiEvent.SaveProfile -> {
                saveProfile()
            }
            is ProfileEditUiEvent.Cancel -> {
                if (!uiState.value.isSaving) {
                    sendEffect(ProfileEditUiEffect.NavigateBack)
                }
            }
        }
    }

    private fun deleteProfile() {
        val targetId = uiState.value.editingProfileId ?: return
        if (!uiState.value.canDelete) return

        updateState { copy(showDeleteConfirm = false, isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            ProfileManager.deleteProfile(targetId)
            withContext(Dispatchers.Main.immediate) {
                updateState { copy(isSaving = false) }
                sendEffect(ProfileEditUiEffect.NavigateBack)
            }
        }
    }

    private fun saveProfile() {
        val state = uiState.value
        if (!state.isSaveEnabled) return

        updateState { copy(isSaving = true, saveError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmedName = state.name.trim()
                val rawCustomAvatar = state.saveableCustomAvatarSource
                val finalAvatarPath = resolveAndCacheRemoteAvatar(rawCustomAvatar)
                val finalPin = if (state.hasPin && state.pinCode.isNotBlank()) state.pinCode.trim() else null

                if (state.isCreating) {
                    val created = ProfileManager.createProfile(
                        name = trimmedName,
                        avatarColorIndex = state.selectedColorIndex,
                        customAvatarPath = finalAvatarPath,
                        pinCode = finalPin,
                        isKids = state.isKids,
                    )
                    if (switchOnSaveWhenCreated || state.autoSignIn) {
                        ProfileManager.switchProfile(created.id, finalPin)
                    }
                    if (state.autoSignIn) {
                        ProfileManager.setAutoSignIn(true)
                    }
                } else {
                    val existing = state.editingProfileId?.let { id ->
                        ProfileManager.profiles.value.find { it.id == id }
                    }
                    if (existing != null) {
                        val updated = existing.copy(
                            name = trimmedName,
                            avatarColorIndex = state.selectedColorIndex,
                            customAvatarPath = finalAvatarPath,
                            pinCode = finalPin,
                            isKids = state.isKids,
                        )
                        ProfileManager.updateProfile(updated)
                        if (state.autoSignIn) {
                            if (ProfileManager.activeProfileId != updated.id) {
                                ProfileManager.switchProfile(updated.id, finalPin)
                            }
                            ProfileManager.setAutoSignIn(true)
                        } else if (ProfileManager.activeProfileId == updated.id && ProfileManager.autoSignIn.value) {
                            ProfileManager.setAutoSignIn(false)
                        }
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    updateState { copy(isSaving = false) }
                    sendEffect(ProfileEditUiEffect.NavigateBack)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed saving profile: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    updateState { copy(isSaving = false, saveError = e.message ?: "Failed to save profile") }
                }
            }
        }
    }

    private fun resolveAndCacheRemoteAvatar(source: String?): String? {
        if (source == null) return null
        val isRemote = source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        if (!isRemote) return source

        return try {
            val avatarDir = File(PlatformPaths.appDataDir, "profiles/avatars").also { it.mkdirs() }
            val req = Request.Builder().url(source).header("User-Agent", "Mozilla/5.0").build()
            val bytes = sharedHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body.bytes() else ByteArray(0)
            }
            if (bytes.isNotEmpty()) {
                val isGif = GifCropper.isGif(bytes) || GifCropper.isGif(source)
                val ext = if (isGif) "gif" else "png"
                val cachedFile = File(avatarDir, "cached_avatar_${System.currentTimeMillis()}.$ext")
                cachedFile.writeBytes(bytes)
                cachedFile.absolutePath
            } else {
                source
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Remote avatar fetch note: ${e.message}")
            source
        }
    }

    companion object {
        private const val TAG = "ProfileEditViewModel"

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private fun createInitialState(profileId: Int?): ProfileEditUiState {
            val profiles = ProfileManager.profiles.value
            val existing: Profile? = profileId?.let { id -> profiles.find { it.id == id } }
            val currentAutoSignIn = ProfileManager.autoSignIn.value
            val activeId = ProfileManager.activeProfile.value.id
            val initialAutoSignIn = if (existing == null) {
                false
            } else {
                currentAutoSignIn && existing.id == activeId
            }

            return ProfileEditUiState(
                editingProfileId = existing?.id,
                originalName = existing?.name.orEmpty(),
                canDelete = existing != null && profiles.size > 1,
                name = existing?.name.orEmpty(),
                selectedColorIndex = existing?.avatarColorIndex ?: 0,
                customAvatarPath = existing?.customAvatarPath.orEmpty(),
                avatarMode = if (!existing?.customAvatarPath.isNullOrBlank()) 1 else 0,
                generatedSeed = existing?.name.orEmpty(),
                generatedPreviewSeed = existing?.name.orEmpty(),
                isKids = existing?.isKids == true,
                autoSignIn = initialAutoSignIn,
                hasPin = existing?.hasPin == true,
                pinCode = existing?.pinCode.orEmpty(),
            )
        }
    }
}
