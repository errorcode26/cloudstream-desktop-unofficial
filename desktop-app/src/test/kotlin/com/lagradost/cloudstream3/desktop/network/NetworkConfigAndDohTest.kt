package com.lagradost.cloudstream3.desktop.network

import com.lagradost.cloudstream3.app
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class NetworkConfigAndDohTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private lateinit var prevBaseClient: OkHttpClient

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_netcfg_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        prevBaseClient = app.baseClient
        DesktopCookieJar().removeAll()
    }

    @AfterEach
    fun teardown() {
        DesktopCookieJar().removeAll()
        app.baseClient = prevBaseClient
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `normalizeCustomDnsEndpoint and DohProvider display formatting cover schemes and ports`() {
        assertEquals("https://1.1.1.1/dns-query", normalizeCustomDnsEndpoint("1.1.1.1"))
        assertEquals("https://dns.example.org/dns-query", normalizeCustomDnsEndpoint("dns.example.org"))
        assertEquals("https://dns.example.org:8443/custom", normalizeCustomDnsEndpoint("https://dns.example.org:8443/custom"))
        assertEquals("https://8.8.8.8:53/dns-query", normalizeCustomDnsEndpoint("8.8.8.8:53"))

        assertEquals("1.1.1.1 (cloudflare-dns.com)", DohProvider.CLOUDFLARE.getServerDisplay())
        assertEquals("System Default", DohProvider.NONE.getServerDisplay())
        assertTrue(DohProvider.CUSTOM.getServerDisplay("https://dns.quad9.net/dns-query").contains("dns.quad9.net"))
    }

    @Test
    fun `UdpDns returns literal IPv4 immediately and fails cleanly on closed loopback`() {
        val dns = UdpDns("127.0.0.1", timeoutMs = 100)
        val direct = dns.lookup("127.0.0.1")
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), direct)

        assertThrows(Exception::class.java) {
            dns.lookup("invalid.nonexistent.local")
        }
    }

    @Test
    fun `DirectConnection407Interceptor normalizes 407 to 403 and AutoRetryInterceptor retries Connection reset`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(407).setBody("Proxy Auth Required"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("Recovered"))
        server.start()
        try {
            val direct407Client = OkHttpClient.Builder()
                .addNetworkInterceptor(DirectConnection407Interceptor())
                .build()
            direct407Client.newCall(Request.Builder().url(server.url("/auth-probe")).build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            var attempts = 0
            val retryClient = OkHttpClient.Builder()
                .addInterceptor(AutoRetryInterceptor(maxRetries = 1))
                .addInterceptor(
                    okhttp3.Interceptor { chain ->
                        attempts++
                        if (attempts == 1) {
                            throw java.net.SocketException("Connection reset")
                        }
                        chain.proceed(chain.request())
                    },
                )
                .build()
            retryClient.newCall(Request.Builder().url(server.url("/retry-probe")).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("Recovered", resp.body.string())
                assertEquals(2, attempts)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `DesktopCookieJar persists valid cookies skips expired and bloat cookies across resets`() {
        val url = "https://stream.example.invalid/watch".toHttpUrl()
        val now = System.currentTimeMillis()
        val good = Cookie.Builder()
            .name("cf_clearance")
            .value("tok123")
            .domain("stream.example.invalid")
            .expiresAt(now + 600_000L)
            .build()
        val bloat = Cookie.Builder()
            .name("_ga")
            .value("GA1.2.123")
            .domain("stream.example.invalid")
            .expiresAt(now + 600_000L)
            .build()
        val expired = Cookie.Builder()
            .name("old_sess")
            .value("gone")
            .domain("stream.example.invalid")
            .expiresAt(now - 10_000L)
            .build()

        val jar = DesktopCookieJar()
        jar.saveFromResponse(url, listOf(good, bloat, expired))
        val loaded = jar.loadForRequest(url)
        assertEquals(listOf("cf_clearance"), loaded.map { it.name })

        jar.removeCookiesForDomain("stream.example.invalid")
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `NetworkConfig updates global baseClient DoH URL and image client cleanly`() = runBlocking {
        DesktopDataStore.setKey(NetworkConfig.PREF_DOH_PROVIDER, DohProvider.ADGUARD.ordinal)
        assertEquals(DohProvider.ADGUARD.url, NetworkConfig.getCurrentDohUrl())

        NetworkConfig.updateGlobalNetworkClients()
        assertNotNull(NetworkConfig.getImageClient())
        assertTrue(app.baseClient.interceptors.any { it is TmdbMirrorInterceptor })
        assertTrue(app.baseClient.networkInterceptors.any { it is DirectConnection407Interceptor })

        DesktopDataStore.setKey(NetworkConfig.PREF_DOH_PROVIDER, DohProvider.CUSTOM.ordinal)
        DesktopDataStore.setKey(NetworkConfig.PREF_CUSTOM_DOH_URL, "https://1.1.1.1/dns-query")
        assertEquals("https://1.1.1.1/dns-query", NetworkConfig.getCurrentDohUrl())
        assertFalse(NetworkConfig.getCurrentDohUrl().isNullOrBlank())
    }
}
