package com.lagradost.common.logging

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogBufferTest {

    @BeforeTest
    fun setup() {
        LogBuffer.clear()
    }

    @Test
    fun testRecordAndSnapshot() {
        AppLogger.i("TestTag", "Hello World")
        AppLogger.w("TestTag", "Warning Message", RuntimeException("Sample warn"))
        AppLogger.e("TestTag", "Error Message", IllegalStateException("Sample error"))

        val snapshot = LogBuffer.getSnapshot()
        assertEquals(3, snapshot.size)
        assertEquals(LogLevel.INFO, snapshot[0].level)
        assertEquals(LogLevel.WARN, snapshot[1].level)
        assertEquals(LogLevel.ERROR, snapshot[2].level)
        assertEquals("Hello World", snapshot[0].message)
        assertNotNull(snapshot[2].stackTraceString)
    }

    @Test
    fun testFilterByLevelAndSubsystem() {
        LogBuffer.record(LogLevel.DEBUG, "Network:OkHttp", "GET https://example.com/api")
        LogBuffer.record(LogLevel.INFO, "PluginLoader:ProviderX", "Loading provider X")
        LogBuffer.record(LogLevel.ERROR, "MpvBridge:Player", "Frame drop error", RuntimeException("MPV crash"))
        LogBuffer.record(LogLevel.VERBOSE, "UI:Home", "Recomposing category row")

        val snapshot = LogBuffer.getSnapshot()

        val errorsOnly = LogBuffer.filter(snapshot, minLevel = LogLevel.ERROR)
        assertEquals(1, errorsOnly.size)
        assertEquals("MpvBridge:Player", errorsOnly[0].tag)

        val pluginsOnly = LogBuffer.filter(snapshot, subsystem = LogSubsystem.PLUGINS)
        assertEquals(1, pluginsOnly.size)
        assertEquals("PluginLoader:ProviderX", pluginsOnly[0].tag)

        val networkOnly = LogBuffer.filter(snapshot, subsystem = LogSubsystem.PROXY_NETWORK)
        assertEquals(1, networkOnly.size)
        assertEquals("Network:OkHttp", networkOnly[0].tag)
    }

    @Test
    fun testFilterByQuery() {
        LogBuffer.record(LogLevel.INFO, "SafePluginInvoker:ProviderA", "Scraping links for movie")
        LogBuffer.record(LogLevel.INFO, "SafePluginInvoker:ProviderB", "Scraping subtitles")

        val snapshot = LogBuffer.getSnapshot()

        val results = LogBuffer.filter(snapshot, query = "ProviderA")
        assertEquals(1, results.size)
        assertEquals("SafePluginInvoker:ProviderA", results[0].tag)
    }

    @Test
    fun testAiDebugSnapshotGeneration() {
        for (i in 1..10) {
            LogBuffer.record(LogLevel.DEBUG, "AppInit", "Step $i initialized")
        }
        val errEntry = LogBuffer.record(LogLevel.ERROR, "CrashHandler", "Fatal crash occurred", NullPointerException("test null pointer"))

        val aiSnapshot = LogBuffer.buildAiDebugSnapshot(errEntry.id, precedingCount = 5)

        assertTrue(aiSnapshot.contains("CloudStream Diagnostics & AI Debug Snapshot"))
        assertTrue(aiSnapshot.contains("Fatal crash occurred"))
        assertTrue(aiSnapshot.contains("NullPointerException"))
        assertTrue(aiSnapshot.contains("Step 10 initialized"))
    }

    @Test
    fun testSanitizeRedactsUrlsJsonHeadersAndExceptionsWithoutFalsePositives() {
        val rawUrl = "https://proxyuser:proxypass@api.example.com/auth?api_key=secretApi123&code=oauthCode456&code_verifier=pkce789&status_code=200&author=alice"
        val cleanUrl = LogBuffer.sanitize(rawUrl)
        assertFalse(cleanUrl.contains("proxypass"))
        assertFalse(cleanUrl.contains("secretApi123"))
        assertFalse(cleanUrl.contains("oauthCode456"))
        assertFalse(cleanUrl.contains("pkce789"))
        assertTrue(cleanUrl.contains("proxyuser:***MASKED***@"))
        assertTrue(cleanUrl.contains("api_key=***MASKED***"))
        assertTrue(cleanUrl.contains("code=***MASKED***"))
        assertTrue(cleanUrl.contains("code_verifier=***MASKED***"))
        assertTrue(cleanUrl.contains("status_code=200"))
        assertTrue(cleanUrl.contains("author=alice"))

        val rawJson = """{"access_token":"tok_abc","refresh_token":"ref_xyz","client_secret":"sec_999","name":"demo"}"""
        val cleanJson = LogBuffer.sanitize(rawJson)
        assertFalse(cleanJson.contains("tok_abc"))
        assertFalse(cleanJson.contains("ref_xyz"))
        assertFalse(cleanJson.contains("sec_999"))
        assertTrue(cleanJson.contains(""""access_token":"***MASKED***""""))
        assertTrue(cleanJson.contains(""""refresh_token":"***MASKED***""""))
        assertTrue(cleanJson.contains(""""client_secret":"***MASKED***""""))
        assertTrue(cleanJson.contains(""""name":"demo""""))

        val rawHeaders = "Authorization: Bearer jwt_secret_1\nx-api-key: sub_secret_2\nsimkl-api-key: simkl_id_3"
        val cleanHeaders = LogBuffer.sanitize(rawHeaders)
        assertFalse(cleanHeaders.contains("jwt_secret_1"))
        assertFalse(cleanHeaders.contains("sub_secret_2"))
        assertFalse(cleanHeaders.contains("simkl_id_3"))
        assertTrue(cleanHeaders.contains("Authorization: Bearer ***MASKED***"))
        assertTrue(cleanHeaders.contains("x-api-key: ***MASKED***"))
        assertTrue(cleanHeaders.contains("simkl-api-key: ***MASKED***"))

        val secretException = IllegalStateException("HTTP failed at https://api.example.com/v1?client_secret=sec_val_1&refresh_token=ref_val_2")
        val entry = LogBuffer.record(LogLevel.ERROR, "AuthTest", "Token exchange failed", secretException)
        val stack = entry.stackTraceString.orEmpty()
        assertFalse(stack.contains("sec_val_1"))
        assertFalse(stack.contains("ref_val_2"))
        assertTrue(stack.contains("client_secret=***MASKED***"))
        assertTrue(stack.contains("refresh_token=***MASKED***"))

        val aiSnapshot = LogBuffer.buildAiDebugSnapshot(entry.id)
        assertFalse(aiSnapshot.contains("sec_val_1"))
        assertFalse(aiSnapshot.contains("ref_val_2"))
    }

    @Test
    fun testAppLoggerRedactsMessagesAndExceptionsBeforeRecording() {
        val ex = RuntimeException("GET https://api.example.com?api_key=leakKey999 failed")
        AppLogger.e("TrackerTest", "Request header Authorization: Bearer leakBearer888", ex)

        val snapshot = LogBuffer.getSnapshot()
        assertEquals(1, snapshot.size)
        val recorded = snapshot.first()
        assertFalse(recorded.message.contains("leakBearer888"))
        assertTrue(recorded.message.contains("Authorization: Bearer ***MASKED***"))
        assertFalse(recorded.stackTraceString.orEmpty().contains("leakKey999"))
        assertTrue(recorded.stackTraceString.orEmpty().contains("api_key=***MASKED***"))
    }
}
