package com.lagradost.player.impl.proxy

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProxyHandlersAndTransferE2ETest {

    @TempDir
    lateinit var tempDir: Path

    private val client = OkHttpClient()

    @BeforeEach
    fun setup() {
        DashProxyHandler.clear()
        LocalStreamProxy.clearMediaCaches()
        LocalStreamProxyState.reset()
        LocalStreamProxy.start()
    }

    @AfterEach
    fun teardown() {
        DashProxyHandler.clear()
        LocalStreamProxy.clearMediaCaches()
        LocalStreamProxyState.reset()
        LocalStreamProxy.stop()
    }

    @Test
    fun `ProxyHeaderUtils sanitizes control chars and preserves valid tokens`() {
        assertEquals("Test", sanitizeHeaderValue("T\u0435st"))
        val builder = Request.Builder().url("https://example.invalid")
        builder.safeHeader("X-Test", "ok\r\nvalue")
        val built = builder.build()
        assertEquals("okvalue", built.header("X-Test"))
    }

    @Test
    fun `StreamTransferEngine findTsSyncOffset aligns TS 188-byte packet stride`() {
        val buf = ByteArray(600)
        buf[5] = 0x47
        buf[5 + 188] = 0x47
        buf[5 + 376] = 0x47
        assertEquals(5, StreamTransferEngine.findTsSyncOffset(buf, buf.size))
        assertEquals(0, StreamTransferEngine.findTsSyncOffset(ByteArray(100), 100))
    }

    @Test
    fun `proxy serves local files with Range flatvtt subtitles two-char URLs images and escaped trailers`() {
        val localFile = tempDir.resolve("sample.bin").toFile()
        localFile.writeBytes("0123456789ABCDEF".toByteArray())

        val sessionId = LocalStreamProxy.registerSession(mapOf("Referer" to "https://ref.invalid/"))

        // 1. Local file interception bypasses HTTP stack cleanly
        val localProxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, localFile.absolutePath)
        client.newCall(Request.Builder().url(localProxyUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("0123456789ABCDEF", resp.body.string())
        }

        // 2. Regression check: 2-char drive string like "C:" must not throw StringIndexOutOfBoundsException
        val driveProxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, "C:")
        client.newCall(Request.Builder().url(driveProxyUrl).build()).execute().use { resp ->
            assertTrue(resp.code in 400..599)
        }

        // 3. Origin HTTP server for VTT, image, and resumed stream
        val streamCalls = AtomicInteger(0)
        val fullStream = ByteArray(1024) { (it and 0xFF).toByte() }

        val origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        origin.createContext("/sub.vtt") { ex ->
            val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<b>First line</b>\n<i>Second</i>\n\n"
            val bytes = vtt.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/vtt")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        origin.createContext("/poster.png") { ex ->
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }
        origin.createContext("/resumable.ts") { ex ->
            val callNum = streamCalls.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range")
            if (callNum == 1 && range == null) {
                // Announce Content-Length=1024 but close early after 400 bytes -> triggers resume!
                ex.responseHeaders.add("Content-Type", "video/mp2t")
                ex.sendResponseHeaders(200, fullStream.size.toLong())
                ex.responseBody.write(fullStream, 0, 400)
                ex.responseBody.close()
            } else {
                val start = range?.removePrefix("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
                val tail = fullStream.copyOfRange(start, fullStream.size)
                ex.responseHeaders.add("Content-Type", "video/mp2t")
                ex.responseHeaders.add("Content-Range", "bytes $start-${fullStream.size - 1}/${fullStream.size}")
                ex.sendResponseHeaders(206, tail.size.toLong())
                ex.responseBody.use { it.write(tail) }
            }
        }
        origin.start()
        try {
            val base = "http://127.0.0.1:${origin.address.port}"

            // flatvtt=true conversion
            val vttUrl = LocalStreamProxy.buildProxyUrl(sessionId, "$base/sub.vtt") + "&flatvtt=true"
            client.newCall(Request.Builder().url(vttUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body!!.string()
                assertTrue(body.contains("First line Second") || body.contains("First line"))
            }

            // /image endpoint
            val imgProxyUrl = LocalStreamProxy.buildImageUrl("$base/poster.png")
            client.newCall(Request.Builder().url(imgProxyUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals(4, resp.body!!.bytes().size)
            }

            // StreamTransferEngine premature EOF resume
            val streamProxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, "$base/resumable.ts")
            client.newCall(Request.Builder().url(streamProxyUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertArrayEquals(fullStream, resp.body!!.bytes())
            }
            assertTrue(streamCalls.get() >= 2)

            // /trailer HTML escaping
            val trailerUrl = "http://127.0.0.1:${LocalStreamProxy.port}/trailer?id=abc%22%3Cscript%3E&u=https://x.test/%22%3E"
            client.newCall(Request.Builder().url(trailerUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val html = resp.body!!.string()
                assertFalse(html.contains("\"<script>"))
                assertTrue(html.contains("&quot;&lt;script&gt;"))
            }
        } finally {
            origin.stop(0)
        }
    }

    @Test
    fun `CENC init_decrypt and decrypt endpoints and streamingDecryptMediaSegment round-trip real AES-CTR payload`() {
        val keyHex = "00112233445566778899aabbccddeeff"
        val kidHex = "ffeeddccbbaa99887766554433221100"
        val keyBytes = StreamDecryptor.hexStringToByteArray(keyHex)
        val iv8 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val plaintext = "Hello CENC Decrypted Sample Payload 123456!".toByteArray(Charsets.UTF_8)

        val iv16 = ByteArray(16).apply { System.arraycopy(iv8, 0, this, 0, 8) }
        val ciphertext = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv16))
            doFinal(plaintext)
        }

        // Build synthetic init MP4 (ftyp + pssh + moov(stsd(encv -> sinf(frma("avc1"), tenc(ivSize=8)))))
        val tencPayload = ByteBuffer.allocate(24).apply {
            putInt(0) // version + flags
            putInt(0x000108) // isProtected=1, ivSize=8
            put(StreamDecryptor.hexStringToByteArray(kidHex))
        }.array()
        val schi = box("schi", box("tenc", tencPayload))
        val frma = box("frma", "avc1".toByteArray(Charsets.ISO_8859_1))
        val sinf = box("sinf", frma + schi)
        val encvHeader = ByteArray(78)
        val encv = box("encv", encvHeader + sinf)
        val stsdHeader = ByteBuffer.allocate(8).apply { putInt(0); putInt(1) }.array()
        val stbl = box("stbl", box("stsd", stsdHeader + encv))
        val moov = box("moov", box("trak", box("mdia", box("minf", stbl))))
        val initSegment = box("ftyp", "isom".toByteArray()) + box("pssh", ByteArray(16)) + moov

        // Build synthetic media fragment (moof(mfhd + traf(tfhd + trun + senc)) + mdat)
        val trunData = ByteBuffer.allocate(16).apply {
            putInt(0x000201) // data_offset + sample_size flags
            putInt(1) // sample_count
            putInt(0) // data_offset
            putInt(ciphertext.size)
        }.array()
        val sencData = ByteBuffer.allocate(8 + 8).apply {
            putInt(0) // version + flags (no subsample encryption)
            putInt(1) // sample_count
            put(iv8)
        }.array()
        val traf = box("traf", box("tfhd", ByteArray(8)) + box("trun", trunData) + box("senc", sencData))
        val moof = box("moof", box("mfhd", ByteArray(8)) + traf)
        val mediaSegment = moof + box("mdat", ciphertext)

        // Direct streamingDecryptMediaSegment check
        val streamedOut = ByteArrayOutputStream()
        runBlocking {
            StreamDecryptor.streamingDecryptMediaSegment(
                source = Buffer().write(mediaSegment),
                keyIdHex = kidHex,
                keyHex = keyHex,
                initSegment = initSegment,
            ) { chunk ->
                streamedOut.write(chunk)
            }
        }
        assertTrue(String(streamedOut.toByteArray(), Charsets.UTF_8).contains(String(plaintext, Charsets.UTF_8)))

        // End-to-end HTTP /proxy?action=init_decrypt and ?action=decrypt
        val origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        origin.createContext("/init.mp4") { ex ->
            ex.sendResponseHeaders(200, initSegment.size.toLong())
            ex.responseBody.use { it.write(initSegment) }
        }
        origin.createContext("/seg.m4s") { ex ->
            ex.sendResponseHeaders(200, mediaSegment.size.toLong())
            ex.responseBody.use { it.write(mediaSegment) }
        }
        origin.start()
        try {
            val base = "http://127.0.0.1:${origin.address.port}"
            val sessionId = LocalStreamProxy.registerSession(emptyMap())

            val initProxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, "$base/init.mp4", action = "init_decrypt")
            client.newCall(Request.Builder().url(initProxyUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body!!.bytes()
                val str = String(body, Charsets.ISO_8859_1)
                assertTrue(str.contains("avc1"))
                assertFalse(str.contains("pssh"))
                assertFalse(str.contains("sinf"))
            }

            val encodedInit = java.net.URLEncoder.encode("$base/init.mp4", "UTF-8")
            val segProxyUrl = LocalStreamProxy.buildProxyUrl(sessionId, "$base/seg.m4s", action = "decrypt") +
                "&kid=$kidHex&k=$keyHex&init=$encodedInit"
            client.newCall(Request.Builder().url(segProxyUrl).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val decryptedBytes = resp.body!!.bytes()
                val text = String(decryptedBytes, Charsets.UTF_8)
                assertTrue(text.contains("Hello CENC Decrypted Sample Payload 123456!"))
                assertFalse(String(decryptedBytes, Charsets.ISO_8859_1).contains("senc"))
            }
        } finally {
            origin.stop(0)
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        return ByteBuffer.allocate(total).apply {
            putInt(total)
            put(type.toByteArray(Charsets.ISO_8859_1))
            put(payload)
        }.array()
    }
}
