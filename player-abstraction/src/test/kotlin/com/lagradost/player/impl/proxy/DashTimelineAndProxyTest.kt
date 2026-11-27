package com.lagradost.player.impl.proxy

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class DashTimelineAndProxyTest {

    private val client = OkHttpClient()

    @BeforeEach
    fun setup() {
        DashProxyHandler.clear()
        LocalStreamProxy.clearMediaCaches()
        LocalStreamProxy.start()
    }

    @AfterEach
    fun teardown() {
        DashProxyHandler.clear()
        LocalStreamProxy.clearMediaCaches()
        LocalStreamProxy.stop()
    }

    @Test
    fun `DashTimeline parses SegmentTimeline clock templates XXE defense and ISO durations`() {
        val numberMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD type="dynamic" minBufferTime="PT2S">
              <Period start="PT0S">
                <AdaptationSet>
                  <SegmentTemplate timescale="1000" startNumber="100" media="chunk-${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s">
                    <SegmentTimeline>
                      <S t="10000" d="2000" r="2"/>
                      <S d="2000" r="1"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="v1" bandwidth="2000000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val timeline = DashTimeline.parse(numberMpd)
        assertNotNull(timeline)
        val matched = timeline!!.match("chunk-v1-00104.m4s")
        assertNotNull(matched)
        assertEquals("v1", matched!!.first.id)
        assertEquals(104L, matched.second)
        assertEquals(104L, matched.first.newest(System.currentTimeMillis()))

        // Clock-based live MPD without SegmentTimeline
        val clockMpd = """
            <MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z">
              <Period start="PT0S">
                <AdaptationSet>
                  <SegmentTemplate timescale="1000" duration="2000" startNumber="1" media="seg_${'$'}RepresentationID${'$'}_${'$'}Number${'$'}.m4s"/>
                  <Representation id="a1" bandwidth="128000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val clockTimeline = DashTimeline.parse(clockMpd)
        assertNotNull(clockTimeline)
        val clockMatch = clockTimeline!!.match("seg_a1_50.m4s")
        assertNotNull(clockMatch)
        assertTrue(clockMatch!!.first.newest(20_000L) >= 10L)

        // XXE DOCTYPE must be rejected cleanly
        val xxeMpd = """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><MPD><Period/></MPD>"""
        assertNull(DashTimeline.parse(xxeMpd))

        assertEquals(3_661_500L, DashTimeline.isoDurationMs("PT1H1M1.5S"))
        assertNull(DashTimeline.isoDurationMs("invalid"))
    }

    @Test
    fun `live DASH proxy serves manifest supports Range and HEAD deduplicates repeats and retries unpublished 404`() {
        val seg1Requests = AtomicInteger(0)
        val seg2Requests = AtomicInteger(0)

        lateinit var mpdUrl: String
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val originBase = "http://127.0.0.1:${server.address.port}"
        mpdUrl = "$originBase/dash/live.mpd"

        server.createContext("/dash/") { ex ->
            val path = ex.requestURI.path
            when {
                path.endsWith("/live.mpd") -> {
                    val mpd = """
                        <MPD type="dynamic">
                          <Period>
                            <AdaptationSet>
                              <SegmentTemplate timescale="1000" startNumber="1" media="seg-${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s">
                                <SegmentTimeline>
                                  <S t="0" d="2000" r="4"/>
                                </SegmentTimeline>
                              </SegmentTemplate>
                              <Representation id="v" bandwidth="1000000"/>
                            </AdaptationSet>
                          </Period>
                        </MPD>
                    """.trimIndent().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/dash+xml")
                    ex.sendResponseHeaders(200, mpd.size.toLong())
                    ex.responseBody.use { it.write(mpd) }
                }
                path.endsWith("/seg-v-00001.m4s") -> {
                    seg1Requests.incrementAndGet()
                    val fullData = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
                    val range = ex.requestHeaders.getFirst("Range")
                    if (ex.requestMethod == "HEAD") {
                        ex.responseHeaders.add("Content-Length", fullData.size.toString())
                        ex.sendResponseHeaders(200, -1)
                        ex.responseBody.close()
                    } else if (range == "bytes=2-5") {
                        val slice = fullData.copyOfRange(2, 6)
                        ex.responseHeaders.add("Content-Range", "bytes 2-5/8")
                        ex.sendResponseHeaders(206, slice.size.toLong())
                        ex.responseBody.use { it.write(slice) }
                    } else {
                        ex.sendResponseHeaders(200, fullData.size.toLong())
                        ex.responseBody.use { it.write(fullData) }
                    }
                }
                path.endsWith("/seg-v-00002.m4s") -> {
                    val attempt = seg2Requests.incrementAndGet()
                    if (attempt == 1) {
                        ex.sendResponseHeaders(404, -1)
                        ex.responseBody.close()
                    } else {
                        val payload = "SEG2-OK".toByteArray()
                        ex.sendResponseHeaders(200, payload.size.toLong())
                        ex.responseBody.use { it.write(payload) }
                    }
                }
                else -> {
                    ex.sendResponseHeaders(404, -1)
                    ex.responseBody.close()
                }
            }
        }
        server.start()
        try {
            val sessionId = LocalStreamProxy.registerSession(mapOf("User-Agent" to "DashTest"))
            val proxyMpdUrl = DashProxyHandler.buildDashProxyUrl(LocalStreamProxy.port, sessionId, mpdUrl)
            assertFalse(LocalStreamProxy.isDashLive(proxyMpdUrl))

            // 1. Fetch manifest via proxy -> triggers dynamic live detection
            client.newCall(Request.Builder().url(proxyMpdUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body!!.string().contains("SegmentTimeline"))
            }
            assertTrue(LocalStreamProxy.isDashLive(proxyMpdUrl))

            // 2. Range request for live segment 1 delivers partial content and records last segment
            val seg1Url = proxyMpdUrl.replace("live.mpd", "seg-v-00001.m4s")
            client.newCall(Request.Builder().url(seg1Url).header("Range", "bytes=2-5").build()).execute().use { resp ->
                assertEquals(206, resp.code)
                assertArrayEquals(byteArrayOf(12, 13, 14, 15), resp.body.bytes())
            }

            // 3. Duplicate immediate fetch on identical live segment -> 404 dedup refusal
            client.newCall(Request.Builder().url(seg1Url).build()).execute().use { resp ->
                assertEquals(404, resp.code)
            }

            // 4. Segment 2: origin returns 404 first, then 200 on retry within wait window
            val seg2Url = proxyMpdUrl.replace("live.mpd", "seg-v-00002.m4s")
            client.newCall(Request.Builder().url(seg2Url).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("SEG2-OK", resp.body!!.string())
            }
            assertTrue(seg2Requests.get() >= 2)
        } finally {
            server.stop(0)
        }
    }
}
