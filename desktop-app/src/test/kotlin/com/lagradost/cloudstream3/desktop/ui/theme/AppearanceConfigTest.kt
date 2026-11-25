package com.lagradost.cloudstream3.desktop.ui.theme

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceConfigTest {

    @Test
    fun testDockItemKeyParseOrder() {
        val defaultOrder = DockItemKey.DEFAULT_ORDER
        assertEquals(defaultOrder, DockItemKey.parseOrder(null))
        assertEquals(defaultOrder, DockItemKey.parseOrder(""))
        assertEquals(defaultOrder, DockItemKey.parseOrder("   "))

        val customRaw = "search,home,settings"
        val parsed = DockItemKey.parseOrder(customRaw)
        assertEquals(DockItemKey.SEARCH, parsed[0])
        assertEquals(DockItemKey.HOME, parsed[1])
        assertEquals(DockItemKey.SETTINGS, parsed[2])
        // Missing keys must be appended
        assertEquals(DockItemKey.entries.size, parsed.size)
        assertTrue(parsed.containsAll(DockItemKey.entries))
    }

    @Test
    fun testDockItemKeyParseDisabled() {
        assertEquals(DockItemKey.DEFAULT_DISABLED, DockItemKey.parseDisabled(null))
        assertEquals(emptySet(), DockItemKey.parseDisabled(""))
        assertEquals(emptySet(), DockItemKey.parseDisabled("NONE"))
        assertEquals(emptySet(), DockItemKey.parseDisabled("none"))

        // Attempting to disable a required item (e.g. HOME, SETTINGS) should be filtered out
        val disabledWithRequired = DockItemKey.parseDisabled("home,history,settings,extensions")
        assertFalse(disabledWithRequired.contains(DockItemKey.HOME))
        assertFalse(disabledWithRequired.contains(DockItemKey.SETTINGS))
        assertTrue(disabledWithRequired.contains(DockItemKey.HISTORY))
        assertTrue(disabledWithRequired.contains(DockItemKey.EXTENSIONS))
    }

    @Test
    fun testDockItemKeySerialization() {
        val items = listOf(DockItemKey.HOME, DockItemKey.EXPLORE, DockItemKey.SEARCH)
        assertEquals("home,explore,search", DockItemKey.serializeOrder(items))

        val disabled = setOf(DockItemKey.HISTORY, DockItemKey.DOWNLOADS)
        assertEquals("history,downloads", DockItemKey.serializeDisabled(disabled))
        assertEquals("NONE", DockItemKey.serializeDisabled(emptySet()))
    }

    @Test
    fun testPosterEnumsFallback() {
        assertEquals(PosterTitlePosition.BELOW, PosterTitlePosition.fromString(null))
        assertEquals(PosterTitlePosition.INSIDE, PosterTitlePosition.fromString("inside"))
        assertEquals(PosterTitlePosition.HIDDEN, PosterTitlePosition.fromString("HIDDEN"))
        assertEquals(PosterTitlePosition.BELOW, PosterTitlePosition.fromString("invalid"))

        assertEquals(PosterMetadataPosition.BELOW_POSTER, PosterMetadataPosition.fromString(null))
        assertEquals(PosterMetadataPosition.HYBRID, PosterMetadataPosition.fromString("hybrid"))
        assertEquals(PosterMetadataPosition.BELOW_POSTER, PosterMetadataPosition.fromString("unknown"))

        assertEquals(PosterTextAlignment.LEFT, PosterTextAlignment.fromString(null))
        assertEquals(PosterTextAlignment.CENTER, PosterTextAlignment.fromString("center"))
        assertEquals(PosterTextAlignment.LEFT, PosterTextAlignment.fromString("invalid"))
    }

    @Test
    fun testNavigationAndHeroBannerStyleFallback() {
        assertEquals(NavigationStyle.SEAMLESS_BAR, NavigationStyle.fromString(null))
        assertEquals(NavigationStyle.FLOATING_DOCK, NavigationStyle.fromString("FLOATING_DOCK"))
        assertEquals(NavigationStyle.SEAMLESS_BAR, NavigationStyle.fromString("unknown"))

        assertEquals(HeroBannerStyle.CINEMA_PEEKING, HeroBannerStyle.fromString(null))
        assertEquals(HeroBannerStyle.FULLSCREEN_IMMERSIVE, HeroBannerStyle.fromString("FULLSCREEN_IMMERSIVE"))
        assertEquals(HeroBannerStyle.CARDS_CAROUSEL, HeroBannerStyle.fromString("Cards Carousel (Peeking)"))
    }

    @Test
    fun testButtonShapeStyleFallbackAndMapping() {
        assertEquals(ButtonShapeStyle.ROUNDED, ButtonShapeStyle.fromString(null))
        assertEquals(ButtonShapeStyle.ROUNDED, ButtonShapeStyle.fromString("unknown"))
        assertEquals(ButtonShapeStyle.PILL, ButtonShapeStyle.fromString("pill"))
        assertEquals(ButtonShapeStyle.SOFT, ButtonShapeStyle.fromString("Soft"))
        assertEquals(ButtonShapeStyle.SHARP, ButtonShapeStyle.fromString("SHARP"))

        ButtonShapeStyle.entries.forEach { style ->
            val tokens = DesktopButtonShapes.fromStyle(style)
            assertEquals(style, tokens.style)
            assertTrue(style.label.isNotBlank())
            assertTrue(style.description.isNotBlank())
        }

        val pillTokens = DesktopButtonShapes.fromStyle(ButtonShapeStyle.PILL)
        assertEquals(androidx.compose.foundation.shape.CircleShape, pillTokens.primary)
        assertEquals(androidx.compose.foundation.shape.CircleShape, pillTokens.medium)
        assertEquals(androidx.compose.foundation.shape.CircleShape, pillTokens.compact)
        assertEquals(androidx.compose.foundation.shape.CircleShape, pillTokens.icon)
    }

    @Test
    fun testCastProfileShapeStyleFallbackAndMapping() {
        assertEquals(CastProfileShapeStyle.ROUNDED, CastProfileShapeStyle.fromString(null))
        assertEquals(CastProfileShapeStyle.ROUNDED, CastProfileShapeStyle.fromString("unknown"))
        assertEquals(CastProfileShapeStyle.CIRCLE, CastProfileShapeStyle.fromString("circle"))
        assertEquals(CastProfileShapeStyle.SOFT, CastProfileShapeStyle.fromString("Soft"))
        assertEquals(CastProfileShapeStyle.SHARP, CastProfileShapeStyle.fromString("SHARP"))

        CastProfileShapeStyle.entries.forEach { style ->
            val tokens = DesktopProfileShapes.fromStyle(style)
            assertEquals(style, tokens.style)
            assertTrue(style.label.isNotBlank())
            assertTrue(style.description.isNotBlank())
            if (style == CastProfileShapeStyle.CIRCLE) {
                assertTrue(tokens.isCircular)
                assertEquals(1f, tokens.portraitAspectRatio)
                assertEquals(androidx.compose.foundation.shape.CircleShape, tokens.card)
                assertEquals(androidx.compose.foundation.shape.CircleShape, tokens.hero)
                assertEquals(androidx.compose.foundation.shape.CircleShape, tokens.compact)
                assertEquals(androidx.compose.foundation.shape.CircleShape, tokens.badge)
                assertFalse(tokens.portraitAlignment == androidx.compose.ui.Alignment.Center)
            } else {
                assertFalse(tokens.isCircular)
                assertEquals(2f / 3f, tokens.portraitAspectRatio)
                assertEquals(androidx.compose.ui.Alignment.Center, tokens.portraitAlignment)
            }
        }
    }
}
