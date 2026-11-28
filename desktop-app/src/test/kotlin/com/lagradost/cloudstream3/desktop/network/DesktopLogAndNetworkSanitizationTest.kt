package com.lagradost.cloudstream3.desktop.network

import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.common.logging.LogBuffer
import com.lagradost.common.net.NetworkTrafficBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLogAndNetworkSanitizationTest {

    @BeforeEach
    fun setUp() {
        NetworkTrafficBuffer.clear()
        LogBuffer.clear()
    }

    @AfterEach
    fun tearDown() {
        NetworkTrafficBuffer.clear()
        LogBuffer.clear()
    }

    @Test
    fun `DevNetworkInterceptor masks sensitive headers query params and JSON tokens`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"secretAccessTok","refresh_token":"secretRefreshTok","ok":true}"""),
        )
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(DevNetworkInterceptor())
                .build()

            val req = Request.Builder()
                .url(server.url("/oauth/token?api_key=querySecret777"))
                .header("Authorization", "Bearer headerSecret888")
                .header("simkl-api-key", "simklClient999")
                .header("x-api-key", "subApiSecret555")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
            }

            val entry = NetworkTrafficBuffer.getSnapshot().single()
            assertFalse(entry.url.contains("querySecret777"))
            assertTrue(entry.url.contains("api_key=***MASKED***"))
            assertEquals("***MASKED***", entry.requestHeaders["Authorization"])
            assertEquals("***MASKED***", entry.requestHeaders["simkl-api-key"])
            assertEquals("***MASKED***", entry.requestHeaders["x-api-key"])
            val body = entry.responseBody.orEmpty()
            assertFalse(body.contains("secretAccessTok"))
            assertFalse(body.contains("secretRefreshTok"))
            assertTrue(body.contains(""""access_token":"***MASKED***""""))
            assertTrue(body.contains(""""refresh_token":"***MASKED***""""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `DevNetworkInterceptor sanitizes transport failure error strings`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(DevNetworkInterceptor())
            .addInterceptor { _ ->
                throw java.io.IOException(
                    "Connect failed for https://api.example.com/item?api_key=failSecret111 with header Authorization: Bearer failBearer222",
                )
            }
            .build()

        val req = Request.Builder()
            .url("https://api.example.com/item")
            .get()
            .build()

        runCatching { client.newCall(req).execute() }

        val entry = NetworkTrafficBuffer.getSnapshot().single()
        val recordedErr = entry.error.orEmpty()
        assertFalse(recordedErr.contains("failSecret111"))
        assertFalse(recordedErr.contains("failBearer222"))
        assertTrue(recordedErr.contains("api_key=***MASKED***"))
        assertTrue(recordedErr.contains("Authorization: Bearer ***MASKED***"))
    }

    @Test
    fun `DesktopErrorReporter redacts secrets from titles and stack traces`() {
        val marker = "ReporterSanitizeTestMarker"
        DesktopErrorReporter.report(
            "$marker failed for https://api.example.com/search?api_key=reportKey123",
            IllegalStateException("Authorization: Bearer reportTok456"),
        )

        val snapshot = DesktopErrorReporter.getSnapshot()
        assertTrue(snapshot.contains(marker))
        assertFalse(snapshot.contains("reportKey123"))
        assertFalse(snapshot.contains("reportTok456"))
        assertTrue(snapshot.contains("api_key=***MASKED***"))
        assertTrue(snapshot.contains("Authorization: Bearer ***MASKED***"))
    }
}
