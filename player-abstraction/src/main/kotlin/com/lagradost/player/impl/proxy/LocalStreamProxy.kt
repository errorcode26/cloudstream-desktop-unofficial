package com.lagradost.player.impl.proxy

import com.lagradost.cloudstream3.app
import com.lagradost.common.logging.AppLogger
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (ex: Throwable) {
            AppLogger.w("Proxy:LocalStream", "Failed to cancel OkHttp call: ${ex.message}", ex)
        }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isCancelled) {
                response.body?.close()
                return
            }
            try {
                continuation.resume(response) {
                    response.body?.close()
                }
            } catch (e: Exception) {
                AppLogger.e("Proxy:LocalStream", "Error resuming coroutine onResponse: ${e.message}", e)
                response.body?.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            try {
                continuation.resumeWithException(e)
            } catch (ignored: Exception) {
                AppLogger.e("Proxy:LocalStream", "Error resuming coroutine onFailure: ${ignored.message}", ignored)
            }
        }
    })
}

object LocalStreamProxy {
    var tracksListener: ProxyTracksListener? = LocalStreamProxyState
    var clearanceUaProvider: ((host: String) -> String?)? = null
    private var server: EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    data class MpdCacheEntry(val content: String, val timestamp: Long)

    data class ProxySession(
        val headers: Map<String, String>,
        val masterCache: ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ByteArray>> = ConcurrentHashMap(),
        val mpdCache: ConcurrentHashMap<String, MpdCacheEntry> = ConcurrentHashMap(),
        val interceptor: Interceptor? = null,
        val originalUrl: String? = null,
    )

    // Capped LRU cache to prevent memory leaks from abandoned video sessions
    private val sessions = Collections.synchronizedMap(
        object : LinkedHashMap<String, ProxySession>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ProxySession>): Boolean {
                return size > 100
            }
        },
    )

    private val proxyClient by lazy {
        app.baseClient.newBuilder()
            .fastFallback(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(32, 60, TimeUnit.SECONDS))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 256
                    maxRequestsPerHost = 32
                },
            )
            .apply {
                interceptors().removeAll {
                    it.javaClass.simpleName == "RateLimitInterceptor" ||
                        it.javaClass.simpleName == "DevNetworkInterceptor"
                }
            }
            .build()
    }

    fun getClientForSession(session: ProxySession?): OkHttpClient {
        val interceptor = session?.interceptor ?: return proxyClient
        return proxyClient.newBuilder().addInterceptor(interceptor).build()
    }

    fun resolveEffectiveUserAgent(
        sessionHost: String?,
        requestHost: String?,
        currentUa: String?,
    ): String {
        val clearanceUa = sessionHost?.let { clearanceUaProvider?.invoke(it) }
            ?: requestHost?.let { clearanceUaProvider?.invoke(it) }
        if (!clearanceUa.isNullOrBlank()) {
            return clearanceUa
        }
        val isMobileStub = currentUa != null && (currentUa.contains("Android", ignoreCase = true) || currentUa.contains("Pixel", ignoreCase = true))
        if (!currentUa.isNullOrBlank() && !isMobileStub) {
            return currentUa
        }
        return com.lagradost.cloudstream3.USER_AGENT
    }

    fun start() {
        if (server != null) return
        val s = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                get("/proxy") {
                    ProxyRequestHandler.handleRequest(
                        call = call,
                        sessions = sessions,
                        port = port,
                        getClientForSession = ::getClientForSession,
                        resolveEffectiveUserAgent = ::resolveEffectiveUserAgent,
                        tracksListener = tracksListener,
                    )
                }
                get("/proxy/{tail...}") {
                    ProxyRequestHandler.handleRequest(
                        call = call,
                        sessions = sessions,
                        port = port,
                        getClientForSession = ::getClientForSession,
                        resolveEffectiveUserAgent = ::resolveEffectiveUserAgent,
                        tracksListener = tracksListener,
                    )
                }
                get("/dash/{id}/{name...}") {
                    DashProxyHandler.handleDashRequest(call, sessions, ::getClientForSession)
                }
                get("/image") {
                    ProxyImageHandler.handleImageRequest(call)
                }
                get("/trailer") {
                    ProxyTrailerHandler.handleTrailer(call)
                }
            }
        }
        s.engineConfig.requestReadTimeoutSeconds = 0
        s.engineConfig.responseWriteTimeoutSeconds = 120
        s.engineConfig.tcpKeepAlive = true
        val cpuCores = Runtime.getRuntime().availableProcessors()
        s.engineConfig.connectionGroupSize = 2
        s.engineConfig.workerGroupSize = cpuCores.coerceIn(4, 16)
        s.engineConfig.callGroupSize = cpuCores.coerceIn(4, 16)
        server = s.start(wait = false)

        port = kotlinx.coroutines.runBlocking {
            server?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: 0
        }
        AppLogger.i("Proxy:LocalStream", "LocalStreamProxy started on port $port")
    }

    fun clearMediaCaches() {
        ProxyPrefetchEngine.clearMediaCaches()
    }

    fun cleanupSegmentCache() {
        ProxyPrefetchEngine.cleanupSegmentCache()
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
        clearMediaCaches()
    }

    fun registerSession(headers: Map<String, String>, interceptor: Interceptor? = null, originalUrl: String? = null): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = ProxySession(headers = headers, interceptor = interceptor, originalUrl = originalUrl)
        LocalStreamProxyState.reset()
        return sessionId
    }

    fun hasInterceptor(sessionId: String): Boolean {
        return sessions[sessionId]?.interceptor != null
    }

    fun buildProxyUrl(sessionId: String, url: String, action: String? = null, clearKey: String? = null): String {
        val encodedUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
        var proxy = "http://127.0.0.1:$port/proxy?s=$sessionId&u=$encodedUrl"
        if (action != null) proxy += "&action=$action"
        if (clearKey != null) proxy += "&ck=$clearKey"
        return proxy
    }

    fun buildDashProxyUrl(sessionId: String, url: String, clearKey: String? = null): String =
        buildProxyUrl(sessionId, url, action = "dash", clearKey = clearKey)

    fun isDashLive(address: String): Boolean =
        DashProxyHandler.isDashLive(address)

    fun buildImageUrl(url: String): String =
        ProxyImageHandler.buildImageUrl(port, url)

    fun prefetchM3u8(sessionId: String, url: String) {
        ProxyPrefetchEngine.prefetchM3u8(
            sessionId = sessionId,
            url = url,
            sessions = sessions,
            getClientForSession = ::getClientForSession,
            resolveEffectiveUserAgent = ::resolveEffectiveUserAgent,
            tracksListener = tracksListener,
        )
    }

    fun resolveUrl(base: String, uri: String): String = HlsRewriter.resolveUrl(base, uri)
}
