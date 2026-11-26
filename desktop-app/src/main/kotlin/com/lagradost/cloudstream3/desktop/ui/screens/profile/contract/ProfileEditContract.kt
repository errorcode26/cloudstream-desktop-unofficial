package com.lagradost.cloudstream3.desktop.ui.screens.profile.contract

import com.lagradost.cloudstream3.desktop.ui.base.UiEffect
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.cloudstream3.desktop.ui.base.UiState
import java.net.URLEncoder

data class DiceBearStyle(
    val id: String,
    val label: String,
    val creator: String,
    val license: String,
)

data class DiceBearPreset(
    val styleId: String,
    val seed: String,
)

data class DiceBearBackground(
    val id: String,
    val label: String,
    val hexStops: List<String>,
) {
    val isTransparent: Boolean
        get() = hexStops.isEmpty()
}

object DiceBearCatalog {
    const val FEATURED_FILTER_ID = "featured"
    const val DEFAULT_STYLE_ID = "adventurer"
    const val DEFAULT_BACKGROUND_ID = "transparent"
    const val INITIAL_COUNT = 40
    const val LOAD_MORE_COUNT = 20

    val backgrounds = listOf(
        DiceBearBackground("transparent", "Transparent", emptyList()),
        DiceBearBackground("cream", "Soft Cream", listOf("f1f5f9")),
        DiceBearBackground("sky", "Sky Blue", listOf("b6e3f4")),
        DiceBearBackground("lavender", "Lavender", listOf("c0aede")),
        DiceBearBackground("peach", "Peach", listOf("ffd5dc")),
        DiceBearBackground("mint", "Mint", listOf("d1f4e0")),
        DiceBearBackground("amber", "Apricot", listOf("ffdfbf")),
        DiceBearBackground("slate", "Slate Night", listOf("1e293b")),
        DiceBearBackground("ocean", "Deep Ocean", listOf("1e3c72", "2a5298")),
        DiceBearBackground("violet", "Royal Violet", listOf("654ea3", "eaafc8")),
        DiceBearBackground("sunset", "Sunset", listOf("ff512f", "f09819")),
    )

    // Full DiceBear v9 art collection; per-style creators and licenses credited in README & app dialog.
    val styles = listOf(
        DiceBearStyle("adventurer", "Adventurer", "Lisa Wischofsky", "CC BY 4.0"),
        DiceBearStyle("adventurer-neutral", "Adventurer Neutral", "Lisa Wischofsky", "CC BY 4.0"),
        DiceBearStyle("avataaars", "Avataaars", "Pablo Stanley", "Free Personal & Commercial"),
        DiceBearStyle("avataaars-neutral", "Avataaars Neutral", "Pablo Stanley", "Free Personal & Commercial"),
        DiceBearStyle("lorelei", "Lorelei", "Lisa Wischofsky", "CC0 1.0"),
        DiceBearStyle("lorelei-neutral", "Lorelei Neutral", "Lisa Wischofsky", "CC0 1.0"),
        DiceBearStyle("notionists", "Notionists", "Zoish", "CC0 1.0"),
        DiceBearStyle("notionists-neutral", "Notionists Neutral", "Zoish", "CC0 1.0"),
        DiceBearStyle("open-peeps", "Open Peeps", "Pablo Stanley", "CC0 1.0"),
        DiceBearStyle("micah", "Micah", "Micah Lanier", "CC BY 4.0"),
        DiceBearStyle("bottts", "Bottts", "Pablo Stanley", "Free Personal & Commercial"),
        DiceBearStyle("bottts-neutral", "Bottts Neutral", "Pablo Stanley", "Free Personal & Commercial"),
        DiceBearStyle("pixel-art", "Pixel Art", "DiceBear", "CC0 1.0"),
        DiceBearStyle("pixel-art-neutral", "Pixel Art Neutral", "DiceBear", "CC0 1.0"),
        DiceBearStyle("fun-emoji", "Fun Emoji", "Davis Uche", "CC BY 4.0"),
        DiceBearStyle("big-smile", "Big Smile", "Ashley Seo", "CC BY 4.0"),
        DiceBearStyle("big-ears", "Big Ears", "The Visual Team", "CC BY 4.0"),
        DiceBearStyle("big-ears-neutral", "Big Ears Neutral", "The Visual Team", "CC BY 4.0"),
        DiceBearStyle("croodles", "Croodles", "vijay verma", "CC BY 4.0"),
        DiceBearStyle("croodles-neutral", "Croodles Neutral", "vijay verma", "CC BY 4.0"),
        DiceBearStyle("dylan", "Dylan", "Natalia Spivak", "CC BY 4.0"),
        DiceBearStyle("miniavs", "Miniavs", "Webpixels", "CC BY 4.0"),
        DiceBearStyle("personas", "Personas", "Draftbit", "CC BY 4.0"),
        DiceBearStyle("thumbs", "Thumbs", "DiceBear", "CC0 1.0"),
        DiceBearStyle("glass", "Glass", "DiceBear", "CC0 1.0"),
        DiceBearStyle("shapes", "Shapes", "DiceBear", "CC0 1.0"),
        DiceBearStyle("rings", "Rings", "DiceBear", "CC0 1.0"),
        DiceBearStyle("identicon", "Identicon", "DiceBear", "CC0 1.0"),
    )

    private val featuredStyleRotation = listOf(
        "adventurer",
        "avataaars",
        "lorelei",
        "open-peeps",
        "bottts",
        "notionists",
        "micah",
        "pixel-art",
        "fun-emoji",
        "big-smile",
        "croodles",
        "dylan",
        "miniavs",
        "personas",
        "thumbs",
        "glass",
    )

    val initialGallerySeeds = listOf(
        "Atlas", "Nova", "Zephyr", "Orion", "Lyra", "Echo", "Sol", "Vega", "Jasper", "Haven",
        "Luna", "Kestrel", "Ember", "Pixel", "Cobalt", "Onyx", "Quill", "Breeze", "Bram", "Freya",
        "Sora", "Aria", "Koda", "Milo", "Cleo", "Juno", "Iris", "Rune", "Arlo", "Rhea",
        "Astra", "Zane", "Frey", "Tala", "Nyx", "Vale", "Kael", "Cyrus", "Knox", "Lira",
    )

    private val seedWords = listOf(
        "Atlas", "Nova", "Zephyr", "Orion", "Lyra", "Echo", "Sol", "Drift", "Kestrel", "Vega",
        "Luna", "Breeze", "Cedar", "Ember", "Spark", "Mist", "Cobalt", "Onyx", "Quill", "Pixel",
        "Sora", "Aria", "Koda", "Milo", "Cleo", "Juno", "Iris", "Rune", "Astra", "Tala",
    )

    fun styleLabel(styleId: String): String =
        styles.firstOrNull { it.id == styleId }?.label ?: "Curated"

    fun getBackground(backgroundId: String): DiceBearBackground =
        backgrounds.firstOrNull { it.id == backgroundId } ?: backgrounds.first()

    fun buildPresets(filterId: String, seeds: List<String>): List<DiceBearPreset> {
        return if (filterId == FEATURED_FILTER_ID) {
            seeds.mapIndexed { index, seed ->
                DiceBearPreset(
                    styleId = featuredStyleRotation[index % featuredStyleRotation.size],
                    seed = seed,
                )
            }
        } else {
            val safeStyle = styles.firstOrNull { it.id == filterId }?.id ?: DEFAULT_STYLE_ID
            seeds.map { seed -> DiceBearPreset(styleId = safeStyle, seed = seed) }
        }
    }

    fun generateRandomSeeds(count: Int): List<String> {
        return List(count) { index ->
            val word = seedWords[(seedWords.indices).random()]
            "$word-${(100..999).random()}-$index"
        }
    }

    fun buildAvatarUrl(
        styleId: String,
        seed: String,
        size: Int = 256,
        backgroundId: String = DEFAULT_BACKGROUND_ID,
    ): String {
        val safeStyle = styles.firstOrNull { it.id == styleId }?.id ?: DEFAULT_STYLE_ID
        val safeSeed = seed.trim().ifBlank { "CloudStream" }
        val encodedSeed = URLEncoder.encode(safeSeed, "UTF-8").replace("+", "%20")
        val clampedSize = size.coerceIn(64, 512)
        val base = "https://api.dicebear.com/9.x/$safeStyle/png?seed=$encodedSeed&size=$clampedSize"
        val bg = getBackground(backgroundId)
        return when {
            bg.isTransparent -> base
            bg.hexStops.size == 1 -> "$base&backgroundColor=${bg.hexStops.first()}&backgroundType=solid"
            else -> "$base&backgroundColor=${bg.hexStops.joinToString(",")}&backgroundType=gradientLinear"
        }
    }
}

data class ProfileEditUiState(
    val editingProfileId: Int? = null,
    val originalName: String = "",
    val canDelete: Boolean = false,
    val name: String = "",
    val selectedColorIndex: Int = 0,
    val customAvatarPath: String = "",
    val avatarMode: Int = 0,
    val galleryFilterId: String = DiceBearCatalog.FEATURED_FILTER_ID,
    val gallerySeeds: List<String> = DiceBearCatalog.initialGallerySeeds,
    val selectedBackgroundId: String = DiceBearCatalog.DEFAULT_BACKGROUND_ID,
    val generatedStyleId: String = DiceBearCatalog.DEFAULT_STYLE_ID,
    val generatedSeed: String = "",
    val generatedPreviewSeed: String = "",
    val isKids: Boolean = false,
    val autoSignIn: Boolean = false,
    val hasPin: Boolean = false,
    val pinCode: String = "",
    val isSaving: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val cropperTargetPath: String? = null,
    val saveError: String? = null,
) : UiState {
    val isCreating: Boolean
        get() = editingProfileId == null

    val isSaveEnabled: Boolean
        get() = name.isNotBlank() && (!hasPin || pinCode.length >= 4) && !isSaving

    val visibleGalleryPresets: List<DiceBearPreset>
        get() = DiceBearCatalog.buildPresets(galleryFilterId, gallerySeeds)

    val effectiveCustomAvatarSource: String?
        get() = when (avatarMode) {
            1 -> customAvatarPath.trim().takeIf { it.isNotBlank() }
            2 -> DiceBearCatalog.buildAvatarUrl(
                styleId = generatedStyleId,
                seed = generatedPreviewSeed.ifBlank { name.ifBlank { "CloudStream" } },
                size = 256,
            )
            else -> null
        }

    val saveableCustomAvatarSource: String?
        get() = when (avatarMode) {
            1 -> customAvatarPath.trim().takeIf { it.isNotBlank() }
            2 -> DiceBearCatalog.buildAvatarUrl(
                styleId = generatedStyleId,
                seed = generatedPreviewSeed.ifBlank { name.ifBlank { "CloudStream" } },
                size = 256,
                backgroundId = selectedBackgroundId,
            )
            else -> null
        }
}

sealed interface ProfileEditUiEvent : UiEvent {
    data class NameChanged(val name: String) : ProfileEditUiEvent
    data class AvatarModeSelected(val mode: Int) : ProfileEditUiEvent
    data class ColorSelected(val colorIndex: Int) : ProfileEditUiEvent
    data class CustomAvatarChanged(val path: String) : ProfileEditUiEvent
    data object ResetCustomAvatar : ProfileEditUiEvent
    data class GalleryFilterSelected(val filterId: String) : ProfileEditUiEvent
    data class GalleryPresetSelected(val preset: DiceBearPreset) : ProfileEditUiEvent
    data class GeneratedBackgroundSelected(val backgroundId: String) : ProfileEditUiEvent
    data object LoadMoreGalleryAvatars : ProfileEditUiEvent
    data object ShuffleGalleryBatch : ProfileEditUiEvent
    data class GeneratedSeedChanged(val seed: String) : ProfileEditUiEvent
    data class OpenCropper(val path: String) : ProfileEditUiEvent
    data class CropCompleted(val croppedPath: String) : ProfileEditUiEvent
    data object DismissCropper : ProfileEditUiEvent
    data class ToggleKids(val enabled: Boolean) : ProfileEditUiEvent
    data class ToggleAutoSignIn(val enabled: Boolean) : ProfileEditUiEvent
    data class TogglePin(val enabled: Boolean) : ProfileEditUiEvent
    data class PinChanged(val pin: String) : ProfileEditUiEvent
    data object RequestDelete : ProfileEditUiEvent
    data object DismissDelete : ProfileEditUiEvent
    data object ConfirmDelete : ProfileEditUiEvent
    data object SaveProfile : ProfileEditUiEvent
    data object Cancel : ProfileEditUiEvent
}

sealed interface ProfileEditUiEffect : UiEffect {
    data object NavigateBack : ProfileEditUiEffect
}
