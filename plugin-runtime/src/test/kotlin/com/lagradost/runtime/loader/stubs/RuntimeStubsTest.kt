package com.lagradost.runtime.loader.stubs

import com.lagradost.cloudstream3.app
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.nicehttp.Requests
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.file.Path

class RuntimeStubsTest {

    @TempDir
    lateinit var tempDir: Path

    private var prevDataDir: String? = null
    private lateinit var prevClient: OkHttpClient

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        System.setProperty("cloudstream.data.dir", tempDir.toFile().absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        prevClient = app.baseClient
        PluginFileSecurityStub.clearGrantedPaths()
    }

    @AfterEach
    fun teardown() {
        PluginFileSecurityStub.clearGrantedPaths()
        app.baseClient = prevClient
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
    }

    @Test
    fun `SystemStub neutralizes dangerous calls and limits exposed properties`() {
        SystemStub.exit(1)
        SystemStub.loadLibrary("evil")
        SystemStub.load("C:\\evil.dll")
        @Suppress("DEPRECATION")
        SystemStub.setSecurityManager(null)

        assertNull(SystemStub.getenv("PATH"))
        assertTrue(SystemStub.getenv().isEmpty())

        assertEquals("17", SystemStub.getProperty("java.version"))
        assertEquals("/", SystemStub.getProperty("file.separator"))
        assertNull(SystemStub.getProperty("user.home"))
        assertEquals("fallback", SystemStub.getProperty("user.home", "fallback"))

        val props = SystemStub.getProperties()
        assertEquals("17", props.getProperty("java.version"))
        assertNull(props.getProperty("os.name"))
    }

    @Test
    fun `RuntimeStub blocks process execution and exposes static capability profile`() {
        val rt = Runtime.getRuntime()
        assertNull(RuntimeStub.exec(rt, "calc"))
        assertNull(RuntimeStub.exec(rt, arrayOf("cmd", "/c", "echo")))
        assertNull(RuntimeStub.exec(rt, arrayOf("sh"), arrayOf("A=B")))
        assertNull(RuntimeStub.exec(rt, arrayOf("sh"), null, tempDir.toFile()))
        assertNull(RuntimeStub.exec(rt, "calc", null))
        assertNull(RuntimeStub.exec(rt, "calc", null, tempDir.toFile()))
        RuntimeStub.loadLibrary(rt, "native")
        RuntimeStub.load(rt, "/tmp/native.so")
        RuntimeStub.exit(rt, 9)
        RuntimeStub.halt(rt, 9)

        assertEquals(8, RuntimeStub.availableProcessors(rt))
        assertEquals(16L * 1024 * 1024 * 1024, RuntimeStub.maxMemory(rt))
        assertEquals(16L * 1024 * 1024 * 1024, RuntimeStub.totalMemory(rt))
        assertEquals(12L * 1024 * 1024 * 1024, RuntimeStub.freeMemory(rt))
    }

    @Test
    fun `URLStub blocks file protocol while permitting http local server`() {
        val fileUrl = URI.create("file:///C:/Windows/win.ini").toURL()
        assertThrows(SecurityException::class.java) { URLStub.openConnection(fileUrl) }
        assertThrows(SecurityException::class.java) { URLStub.openStream(fileUrl) }
        assertThrows(SecurityException::class.java) { URLStub.getContent(fileUrl) }

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ok") { ex ->
            val payload = "stub-ok".toByteArray()
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val httpUrl = URI.create("http://127.0.0.1:${server.address.port}/ok").toURL()
            val body = URLStub.openStream(httpUrl).bufferedReader().use { it.readText() }
            assertEquals("stub-ok", body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `RequestsStub attaches and syncs global baseClient`() {
        val clientA = OkHttpClient.Builder().build()
        val clientB = OkHttpClient.Builder().build()
        app.baseClient = clientA

        val req = Requests()
        RequestsStub.attachGlobalBaseClient(null)
        RequestsStub.attachGlobalBaseClient(req)
        assertSame(clientA, req.baseClient)

        app.baseClient = clientB
        RequestsStub.syncAllKnownClients()
        assertSame(clientB, req.baseClient)
    }

    @Test
    fun `ReflectionStub permits safe whitelisted classes and blocks process reflection`() {
        assertTrue(ReflectionStub.isReflectionAllowed(String::class.java))
        assertTrue(ReflectionStub.isReflectionAllowed(Int::class.javaPrimitiveType!!))
        assertTrue(ReflectionStub.isReflectionAllowed(Array<String>::class.java))
        assertTrue(ReflectionStub.isReflectionAllowed(java.util.ArrayList::class.java))
        assertFalse(ReflectionStub.isReflectionAllowed(ProcessBuilder::class.java))
        assertFalse(ReflectionStub.isReflectionAllowed(File::class.java))

        val lengthMethod = String::class.java.getMethod("length")
        assertEquals(3, ReflectionStub.invoke(lengthMethod, "abc", emptyArray()))

        val rtMethod = Runtime::class.java.getMethod("getRuntime")
        assertThrows(SecurityException::class.java) {
            ReflectionStub.invoke(rtMethod, null, emptyArray())
        }
    }

    @Test
    fun `PluginFileSecurityStub grant revoke checkParentChild and checkUri behave cleanly`() {
        val grantedDir = tempDir.resolve("custom-grant").toFile().apply { mkdirs() }
        val allowedFile = File(grantedDir, "data.txt")
        PluginFileSecurityStub.grantAllowedPath("UnknownPlugin", grantedDir)

        val resolvedGranted = PluginFileSecurityStub.checkPath(allowedFile.absolutePath)
        assertEquals(allowedFile.canonicalPath, File(resolvedGranted).canonicalPath)

        PluginFileSecurityStub.revokeAllowedPath("UnknownPlugin", grantedDir)
        assertThrows(SecurityException::class.java) {
            PluginFileSecurityStub.checkPath(allowedFile.absolutePath)
        }

        val child = PluginFileSecurityStub.checkFileChild(PlatformPaths.cacheDir, "thumb.jpg")
        val pluginCacheJail = File(PluginFileSecurityStub.getStorageRootForPlugin("UnknownPlugin"), "cache").canonicalPath
        assertTrue(child.canonicalPath.startsWith(pluginCacheJail))

        val outsideUri = tempDir.root.resolve("outside-hosts.txt").toUri()
        assertThrows(SecurityException::class.java) {
            PluginFileSecurityStub.checkUri(outsideUri)
        }
    }
}
