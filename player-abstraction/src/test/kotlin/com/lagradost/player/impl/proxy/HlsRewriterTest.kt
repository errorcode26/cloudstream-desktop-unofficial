package com.lagradost.player.impl.proxy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HlsRewriterTest {

    @BeforeEach
    fun setup() {
        LocalStreamProxy.start()
        LocalStreamProxyState.reset()
    }

    @AfterEach
    fun teardown() {
        LocalStreamProxyState.reset()
        LocalStreamProxy.clearMediaCaches()
        LocalStreamProxy.stop()
    }

    @Test
    fun `resolveUrl resolves relative root-relative protocol-relative and absolute URIs`() {
        val base = "https://cdn.example.invalid/live/ch1/master.m3u8?token=abc"
        assertEquals("https://other.example.invalid/v.m3u8", HlsRewriter.resolveUrl(base, "https://other.example.invalid/v.m3u8"))
        assertEquals("https://cdn.example.invalid/sub/en.vtt?token=abc", HlsRewriter.resolveUrl(base, "//cdn.example.invalid/sub/en.vtt"))
        assertEquals("https://cdn.example.invalid/root/seg.ts?token=abc", HlsRewriter.resolveUrl(base, "/root/seg.ts"))
        assertEquals("https://cdn.example.invalid/live/ch1/720p/index.m3u8?token=abc", HlsRewriter.resolveUrl(base, "720p/index.m3u8"))
        assertEquals("https://cdn.example.invalid/live/ch1/master.m3u8?part=2&token=abc", HlsRewriter.resolveUrl(base, "?part=2"))
    }

    @Test
    fun `rewriteM3u8 master playlist selects top variant publishes tracks and proxies audio and video URIs`() {
        val sessionId = LocalStreamProxy.registerSession(mapOf("User-Agent" to "TestUA"))
        var discoveredAudios = emptyList<ProxyTrack>()
        var discoveredSubs = emptyList<ProxyTrack>()
        var discoveredVideos = emptyList<ProxyTrack>()
        val listener = object : ProxyTracksListener {
            override fun onTracksDiscovered(
                audioTracks: List<ProxyTrack>,
                subtitleTracks: List<ProxyTrack>,
                videoTracks: List<ProxyTrack>,
            ) {
                discoveredAudios = audioTracks
                discoveredSubs = subtitleTracks
                discoveredVideos = videoTracks
            }

            override fun onLoadingComplete() {}
        }

        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japanese",LANGUAGE="ja",DEFAULT=NO,URI="audio/ja.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English CC",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,AUDIO="aud",SUBTITLES="subs"
            720p/media.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,AUDIO="aud",SUBTITLES="subs"
            1080p/media.m3u8
        """.trimIndent()

        val rewritten = HlsRewriter.rewriteM3u8(
            content = master,
            baseUrl = "https://origin.example.invalid/hls/master.m3u8",
            sessionId = sessionId,
            tracksListener = listener,
        )

        assertEquals(2, discoveredAudios.size)
        assertEquals(1, discoveredSubs.size)
        assertEquals(2, discoveredVideos.size)
        assertTrue(discoveredSubs.first().url.contains("/proxy"))
        assertTrue(rewritten.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
        assertTrue(rewritten.contains("http://127.0.0.1:${LocalStreamProxy.port}/proxy"))
        assertFalse(rewritten.contains("TYPE=SUBTITLES"))
        // Only best audio rendition (DEFAULT=YES English) is retained in master manifest
        assertEquals(1, Regex("#EXT-X-MEDIA:TYPE=AUDIO").findAll(rewritten).count())
        assertTrue(rewritten.contains("#EXT-X-STREAM-INF:"))
    }

    @Test
    fun `rewriteM3u8 media playlist rewrites KEY MAP segments and appends ENDLIST for non-live playlists`() {
        val plainSession = LocalStreamProxy.registerSession(emptyMap())
        val interceptorSession = LocalStreamProxy.registerSession(
            headers = emptyMap(),
            interceptor = okhttp3.Interceptor { chain -> chain.proceed(chain.request()) },
        )
        val vodMedia = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="keys/enc.key",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg_001.m4s
            #EXTINF:6.0,
            seg_002.m4s
        """.trimIndent()

        // Without custom interceptor: KEY & MAP are proxied (2), segments are resolved direct URLs
        val rewrittenDirect = HlsRewriter.rewriteM3u8(
            content = vodMedia,
            baseUrl = "https://origin.example.invalid/hls/1080p/media.m3u8",
            sessionId = plainSession,
            tracksListener = null,
        )
        assertTrue(rewrittenDirect.contains("#EXT-X-ENDLIST"))
        assertEquals(2, Regex("http://127\\.0\\.0\\.1:${LocalStreamProxy.port}/proxy\\?").findAll(rewrittenDirect).count())
        assertTrue(rewrittenDirect.contains("https://origin.example.invalid/hls/1080p/seg_001.m4s"))

        // With interceptor: KEY, MAP, and both segments route through proxy (4) with &_ext=.m4s hint
        val rewrittenProxied = HlsRewriter.rewriteM3u8(
            content = vodMedia,
            baseUrl = "https://origin.example.invalid/hls/1080p/media.m3u8",
            sessionId = interceptorSession,
            tracksListener = null,
        )
        assertTrue(rewrittenProxied.contains("#EXT-X-ENDLIST"))
        assertEquals(4, Regex("http://127\\.0\\.0\\.1:${LocalStreamProxy.port}/proxy\\?").findAll(rewrittenProxied).count())
        assertTrue(rewrittenProxied.contains("&_ext=.m4s"))

        val liveMedia = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:42
            #EXTINF:4.0,
            live_42.ts
        """.trimIndent()

        val rewrittenLive = HlsRewriter.rewriteM3u8(
            content = liveMedia,
            baseUrl = "https://origin.example.invalid/live/media.m3u8",
            sessionId = plainSession,
            tracksListener = null,
        )
        assertFalse(rewrittenLive.contains("#EXT-X-ENDLIST"))
    }
}
