package com.lagradost.common.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkTrafficBufferAndEntryTest {

    @BeforeTest
    fun setUp() {
        NetworkTrafficBuffer.clear()
    }

    @AfterTest
    fun tearDown() {
        NetworkTrafficBuffer.clear()
    }

    @Test
    fun `NetworkRequestEntry status predicates and formatting reflect HTTP state accurately`() {
        val pending = NetworkRequestEntry(id = 1L, method = "get", url = "https://example.com/a", host = "example.com", path = "/a")
        assertTrue(pending.isPending)
        assertFalse(pending.isSuccess)
        assertFalse(pending.isError)
        assertEquals("0 B", pending.formattedSize)
        assertTrue(pending.formattedTime.matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3}""")))

        val ok = pending.copy(statusCode = 204, responseSize = 2048L)
        assertFalse(ok.isPending)
        assertTrue(ok.isSuccess)
        assertFalse(ok.isError)
        assertEquals("2.0 KB", ok.formattedSize)

        val redirect = pending.copy(statusCode = 302, responseSize = 2L * 1024L * 1024L)
        assertTrue(redirect.isRedirect)
        assertFalse(redirect.isError)
        assertEquals("2.0 MB", redirect.formattedSize)

        val clientErr = pending.copy(statusCode = 404)
        assertTrue(clientErr.isClientError)
        assertTrue(clientErr.isError)

        val serverErr = pending.copy(statusCode = 503)
        assertTrue(serverErr.isServerError)
        assertTrue(serverErr.isError)

        val transportErr = pending.copy(statusCode = -1, error = "Timeout")
        assertFalse(transportErr.isPending)
        assertTrue(transportErr.isError)
    }

    @Test
    fun `toCurlCommand escapes quotes in headers and includes payload`() {
        val entry = NetworkRequestEntry(
            id = 2L,
            method = "post",
            url = "https://api.example.com/graphql",
            host = "api.example.com",
            path = "/graphql",
            requestHeaders = linkedMapOf(
                "Authorization" to "Bearer tok",
                "X-Note" to """a"b""",
            ),
            requestBody = """{"q":"test"}""",
        )

        val curl = entry.toCurlCommand()
        assertTrue(curl.startsWith("""curl -X POST "https://api.example.com/graphql""""))
        assertTrue(curl.contains("""-H "Authorization: Bearer tok""""))
        assertTrue(curl.contains("""-H "X-Note: a\"b""""))
        assertTrue(curl.contains("""--data "{\"q\":\"test\"}""""))
    }

    @Test
    fun `buffer tracks lifecycle filters exports and caps at capacity`() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<NetworkRequestEntry>())
        val collector = launch {
            emitted.add(
                withTimeout(2_000L) {
                    NetworkTrafficBuffer.networkFlow.first()
                },
            )
        }
        // Give flow collector a moment to subscribe before emitting
        kotlinx.coroutines.yield()

        val id1 = NetworkTrafficBuffer.recordStart(
            method = "GET",
            url = "https://cdn.example.org/manifest.m3u8",
            host = "cdn.example.org",
            path = "/manifest.m3u8",
            requestHeaders = mapOf("Accept" to "*/*"),
        )
        NetworkTrafficBuffer.recordComplete(
            id = id1,
            statusCode = 200,
            statusMessage = "OK",
            durationMs = 42L,
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
            responseSize = 512L,
            contentType = "application/vnd.apple.mpegurl",
        )

        val id2 = NetworkTrafficBuffer.recordStart(
            method = "POST",
            url = "https://api.example.org/submit",
            host = "api.example.org",
            path = "/submit",
            requestHeaders = emptyMap(),
            requestBody = "payload",
        )
        NetworkTrafficBuffer.recordComplete(
            id = id2,
            statusCode = 500,
            statusMessage = "Server Error",
            durationMs = 15L,
            responseHeaders = emptyMap(),
            error = "Upstream exploded",
        )

        // Completion without prior start still inserts safely
        NetworkTrafficBuffer.recordComplete(
            id = 999_999L,
            statusCode = 404,
            statusMessage = "Not Found",
            durationMs = 5L,
            responseHeaders = emptyMap(),
        )

        collector.join()
        assertTrue(emitted.isNotEmpty())

        val snapshot = NetworkTrafficBuffer.getSnapshot()
        assertEquals(3, snapshot.size)

        val errors = NetworkTrafficBuffer.filter(snapshot, errorsOnly = true)
        assertEquals(2, errors.size)

        val postErrors = NetworkTrafficBuffer.filter(snapshot, query = "exploded", methodFilter = "POST", errorsOnly = true)
        assertEquals(1, postErrors.size)
        assertEquals(id2, postErrors.first().id)

        val exported = NetworkTrafficBuffer.exportAsText(snapshot)
        assertTrue(exported.contains("[GET] [200] 42ms"))
        assertTrue(exported.contains("Error: Upstream exploded"))

        // Exceed DEFAULT_MAX_CAPACITY and verify oldest entries are evicted
        repeat(NetworkTrafficBuffer.DEFAULT_MAX_CAPACITY + 50) { idx ->
            NetworkTrafficBuffer.recordStart("GET", "https://example.org/$idx", "example.org", "/$idx", emptyMap())
        }
        assertEquals(NetworkTrafficBuffer.DEFAULT_MAX_CAPACITY, NetworkTrafficBuffer.getSnapshot().size)
    }

    @Test
    fun `concurrent recordStart and recordComplete never drop or duplicate entries`() = runBlocking {
        val workers = 16
        val perWorker = 20
        val total = workers * perWorker
        assertTrue(total <= NetworkTrafficBuffer.DEFAULT_MAX_CAPACITY)

        val ids = (1..workers).map { w ->
            async(Dispatchers.IO) {
                buildList {
                    repeat(perWorker) { i ->
                        val id = NetworkTrafficBuffer.recordStart(
                            method = "GET",
                            url = "https://host$w.example.org/$i",
                            host = "host$w.example.org",
                            path = "/$i",
                            requestHeaders = emptyMap(),
                        )
                        NetworkTrafficBuffer.recordComplete(
                            id = id,
                            statusCode = 200,
                            statusMessage = "OK",
                            durationMs = 1L,
                            responseHeaders = emptyMap(),
                        )
                        add(id)
                    }
                }
            }
        }.awaitAll().flatten()

        val snapshot = NetworkTrafficBuffer.getSnapshot()
        assertEquals(total, snapshot.size, "Expected all $total entries without drops or duplicates")
        assertEquals(ids.toSet(), snapshot.map { it.id }.toSet())
        assertTrue(snapshot.all { it.statusCode == 200 })
    }
}
