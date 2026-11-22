package com.lagradost.cloudstream3.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearanceStateManagerTest {

    @BeforeEach
    @AfterEach
    fun resetState() {
        ClearanceStateManager.clearAllClearance()
    }

    @Test
    fun testApexDomainExtraction() {
        assertEquals("example.com", ClearanceStateManager.getApexDomain("example.com"))
        assertEquals("example.com", ClearanceStateManager.getApexDomain("www.example.com"))
        assertEquals("example.com", ClearanceStateManager.getApexDomain("sub.domain.example.com"))
        assertEquals("example.co.uk", ClearanceStateManager.getApexDomain("sub.example.co.uk"))
        assertEquals("provider.ru", ClearanceStateManager.getApexDomain("api.provider.ru"))
    }

    @Test
    fun testParseCookieMap() {
        val raw = "cf_clearance=token_12345; __cf_bm=bm_value; session=xyz"
        val map = ClearanceStateManager.parseCookieMap(raw)
        assertEquals(3, map.size)
        assertEquals("token_12345", map["cf_clearance"])
        assertEquals("bm_value", map["__cf_bm"])
        assertEquals("xyz", map["session"])

        assertEquals(emptyMap(), ClearanceStateManager.parseCookieMap(""))
        assertEquals(emptyMap(), ClearanceStateManager.parseCookieMap("   "))
    }

    @Test
    fun testAssetDetection() {
        assertTrue(ClearanceStateManager.isImageAsset("https://example.com/poster.webp".toHttpUrl()))
        assertTrue(ClearanceStateManager.isImageAsset("https://example.com/cover.jpg".toHttpUrl()))
        assertTrue(ClearanceStateManager.isImageAsset("https://example.com/icon.png".toHttpUrl()))
        assertTrue(ClearanceStateManager.isImageAsset("https://example.com/image.avif".toHttpUrl()))
        assertFalse(ClearanceStateManager.isImageAsset("https://example.com/video.mp4".toHttpUrl()))

        assertTrue(ClearanceStateManager.isStaticAsset("https://example.com/poster.webp".toHttpUrl()))
        assertTrue(ClearanceStateManager.isStaticAsset("https://example.com/video.mp4".toHttpUrl()))
        assertTrue(ClearanceStateManager.isStaticAsset("https://example.com/playlist.m3u8".toHttpUrl()))
        assertFalse(ClearanceStateManager.isStaticAsset("https://example.com/api/data.json".toHttpUrl()))
    }

    @Test
    fun testClearanceStorageAndRetrieval() {
        val host = "api.target.com"
        val cookie = Cookie.Builder()
            .name("cf_clearance")
            .value("token_abc")
            .domain(host)
            .path("/")
            .build()

        ClearanceStateManager.saveClearance(
            host = host,
            userAgent = "Mozilla/5.0 TestUA",
            okCookies = listOf(cookie),
        )

        val retrieved = ClearanceStateManager.getClearance(host)
        assertNotNull(retrieved)
        assertEquals(host, retrieved.host)
        assertEquals("Mozilla/5.0 TestUA", retrieved.userAgent)
        assertEquals("token_abc", retrieved.cookies.first().value)

        val urlRetrieved = ClearanceStateManager.getClearance("https://api.target.com/v1/search".toHttpUrl())
        assertNotNull(urlRetrieved)
        assertEquals(host, urlRetrieved.host)
    }

    @Test
    fun testFailureStateTracking() {
        val host = "sub.broken.com"
        assertFalse(ClearanceStateManager.isFailed(host))

        ClearanceStateManager.markFailed(host)
        assertTrue(ClearanceStateManager.isFailed(host))
        // Apex should also be marked failed
        assertTrue(ClearanceStateManager.isFailed("broken.com"))

        ClearanceStateManager.unmarkFailed(host)
        assertFalse(ClearanceStateManager.isFailed(host))

        ClearanceStateManager.markFailed(host)
        ClearanceStateManager.unmarkAllFailed()
        assertFalse(ClearanceStateManager.isFailed(host))
    }
}
