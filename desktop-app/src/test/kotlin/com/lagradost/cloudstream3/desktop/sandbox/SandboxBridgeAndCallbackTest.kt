package com.lagradost.cloudstream3.desktop.sandbox

import com.lagradost.cloudstream3.desktop.sandbox.host.ActiveLinkConsumer
import com.lagradost.cloudstream3.desktop.sandbox.host.SandboxHostCallbackHandler
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackRequest
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackResponse
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerProtocolSerializer
import com.lagradost.cloudstream3.desktop.sandbox.worker.WorkerHostBridge
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedWriter
import java.io.File
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SandboxBridgeAndCallbackTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_sandbox_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        PluginSettingsSchemaRegistry.schemas.remove("BridgeTest_")
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WorkerHostBridge.rpcWriter = null
        WorkerHostBridge.pendingCallbacks.clear()
    }

    @AfterEach
    fun teardown() {
        testScope.cancel()
        WorkerHostBridge.rpcWriter = null
        WorkerHostBridge.pendingCallbacks.clear()
        PluginSettingsSchemaRegistry.schemas.remove("BridgeTest_")
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `WorkerHostBridge returns null safely without writer and round-trips pending callback`() = runBlocking {
        assertNull(WorkerHostBridge.requestCloudflareSolve("https://example.invalid", "example.invalid"))

        val written = StringWriter()
        WorkerHostBridge.rpcWriter = BufferedWriter(written)

        val solveDeferred = CompletableDeferred<Any?>()
        val job = launch(Dispatchers.IO) {
            val res = WorkerHostBridge.requestCloudflareSolve(
                url = "https://example.invalid/challenge",
                host = "example.invalid",
                headers = mapOf("User-Agent" to "UA"),
            )
            solveDeferred.complete(res)
        }

        var callbackId: Long? = null
        repeat(50) {
            callbackId = WorkerHostBridge.pendingCallbacks.keys.firstOrNull()
            if (callbackId != null) return@repeat
            kotlinx.coroutines.delay(20)
        }
        val resolvedCallbackId = requireNotNull(callbackId)

        val pending = WorkerHostBridge.pendingCallbacks.remove(resolvedCallbackId)!!
        pending.complete(
            WorkerCallbackResponse(
                callbackId = resolvedCallbackId,
                success = true,
                cookies = emptyList(),
                userAgent = "SolvedUA/2.0",
            ),
        )
        job.join()

        val result = solveDeferred.await() as com.lagradost.cloudstream3.desktop.sandbox.worker.HostSolveResult?
        assertNotNull(result)
        assertEquals("SolvedUA/2.0", result!!.userAgent)
        assertTrue(written.toString().contains("solveCloudflare"))
    }

    @Test
    fun `SandboxHostCallbackHandler enforces setting key allowlist SSRF guard and streams links`() = runBlocking {
        val pluginFile = File(tempDir, "DemoBridgePlugin.jar").apply { writeText("stub") }
        val consumers = ConcurrentHashMap<Long, ActiveLinkConsumer>()
        val emittedLinks = CopyOnWriteArrayList<String>()
        consumers[101L] = ActiveLinkConsumer(
            onLink = { emittedLinks.add(it.url) },
            onSubtitle = null,
        )

        val responses = CopyOnWriteArrayList<WorkerCallbackResponse>()
        val handler = SandboxHostCallbackHandler(
            scope = testScope,
            activeLinkConsumers = consumers,
            loadedPluginPaths = setOf(pluginFile.absolutePath),
            sendCallbackResponse = { responses.add(it) },
        )

        val link = newExtractorLink("Src", "720p", "https://cdn.example.invalid/video.mp4", ExtractorLinkType.VIDEO)
        val linkJson = WorkerProtocolSerializer.mapper.writeValueAsString(link)
        handler.handleWorkerCallback(
            WorkerCallbackRequest(
                callbackId = 1L,
                action = WorkerCallbackRequest.ACTION_EMIT_LINK,
                requestId = 101L,
                linkJson = linkJson,
            ),
        )
        assertEquals(listOf("https://cdn.example.invalid/video.mp4"), emittedLinks)

        handler.handleWorkerCallback(
            WorkerCallbackRequest(
                callbackId = 2L,
                action = WorkerCallbackRequest.ACTION_SAVE_SETTING,
                key = "DemoBridgePlugin_quality",
                valueJson = "\"1080p\"",
            ),
        )
        assertEquals("1080p", DesktopDataStore.getKey<String>("DemoBridgePlugin_quality"))

        handler.handleWorkerCallback(
            WorkerCallbackRequest(
                callbackId = 3L,
                action = WorkerCallbackRequest.ACTION_REMOVE_SETTING,
                key = "DemoBridgePlugin_quality",
            ),
        )
        assertFalse(DesktopDataStore.containsKey("DemoBridgePlugin_quality"))

        handler.handleWorkerCallback(
            WorkerCallbackRequest(
                callbackId = 4L,
                action = WorkerCallbackRequest.ACTION_SAVE_SETTING,
                key = "unauthorized_other_key",
                valueJson = "\"hacked\"",
            ),
        )
        assertFalse(DesktopDataStore.containsKey("unauthorized_other_key"))

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/secret") { ex ->
            val bytes = "secret".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            handler.handleWorkerCallback(
                WorkerCallbackRequest(
                    callbackId = 5L,
                    action = WorkerCallbackRequest.ACTION_PROXY_FETCH,
                    url = "http://127.0.0.1:${server.address.port}/secret",
                ),
            )
            repeat(50) {
                if (responses.any { it.callbackId == 5L }) return@repeat
                kotlinx.coroutines.delay(20)
            }
            val ssrfResp = responses.firstOrNull { it.callbackId == 5L }
            assertNotNull(ssrfResp)
            assertFalse(ssrfResp!!.success)
        } finally {
            server.stop(0)
        }
    }
}
