package com.lagradost.cloudstream3.network

import com.lagradost.common.logging.AppLogger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class CloudflareKiller(private val cookieJar: CookieJar? = null) : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        const val PREF_TLS_BOUND_HOSTS = ClearanceStateManager.PREF_TLS_BOUND_HOSTS

        var globalCookieJar: CookieJar?
            get() = ClearanceStateManager.globalCookieJar
            set(value) {
                ClearanceStateManager.globalCookieJar = value
            }

        val isWorker: Boolean get() = ClearanceStateManager.isWorker

        var lastChallengedHost: String?
            get() = ClearanceStateManager.lastChallengedHost
            set(value) {
                ClearanceStateManager.lastChallengedHost = value
            }

        var lastChallengedUrl: String?
            get() = ClearanceStateManager.lastChallengedUrl
            set(value) {
                ClearanceStateManager.lastChallengedUrl = value
            }

        var lastChallengeTimestamp: Long
            get() = ClearanceStateManager.lastChallengeTimestamp
            set(value) {
                ClearanceStateManager.lastChallengeTimestamp = value
            }

        fun getClearance(url: HttpUrl): HostClearance? = ClearanceStateManager.getClearance(url)
        fun getClearance(host: String): HostClearance? = ClearanceStateManager.getClearance(host)
        fun getClearanceForAsset(url: HttpUrl, refererUrl: String? = null): Pair<HostClearance, String>? =
            ClearanceStateManager.getClearanceForAsset(url, refererUrl)

        fun getSavedCookies(host: String): Map<String, String> = ClearanceStateManager.getSavedCookies(host)
        fun getSavedUserAgent(host: String): String? = ClearanceStateManager.getSavedUserAgent(host)

        fun isTlsBound(host: String): Boolean = ClearanceStateManager.isTlsBound(host)
        fun markTlsBound(host: String) = ClearanceStateManager.markTlsBound(host)

        fun getApexDomain(host: String): String = ClearanceStateManager.getApexDomain(host)

        fun markFailed(host: String) = ClearanceStateManager.markFailed(host)
        fun isFailed(host: String): Boolean = ClearanceStateManager.isFailed(host)
        fun unmarkFailed(host: String) = ClearanceStateManager.unmarkFailed(host)
        fun unmarkAllFailed() = ClearanceStateManager.unmarkAllFailed()

        fun isImageAsset(url: HttpUrl): Boolean = ClearanceStateManager.isImageAsset(url)
        fun isStaticAsset(url: HttpUrl): Boolean = ClearanceStateManager.isStaticAsset(url)

        fun parseCookieMap(cookie: String): Map<String, String> = ClearanceStateManager.parseCookieMap(cookie)

        fun saveClearance(
            host: String,
            cookies: Map<String, String>? = null,
            userAgent: String,
            url: HttpUrl? = null,
            okCookies: List<Cookie>? = null,
            isTlsBound: Boolean = false,
        ) = ClearanceStateManager.saveClearance(host, cookies, userAgent, url, okCookies, isTlsBound)

        fun getAllStoredCookies(): Map<String, List<Cookie>> = ClearanceStateManager.getAllStoredCookies()

        fun clearClearanceForDomain(domain: String) = ClearanceStateManager.clearClearanceForDomain(domain)
        fun clearAllClearance() = ClearanceStateManager.clearAllClearance()

        internal fun solveWithEmbeddedWebView(targetUrl: String, hostName: String, headers: Map<String, String> = emptyMap()): Boolean =
            CloudflareWebViewSolver.solveWithEmbeddedWebView(targetUrl, hostName, headers)

        internal fun fetchViaBrowserProxy(request: Request, isBinary: Boolean = false): Response? =
            CloudflareWebViewSolver.fetchViaBrowserProxy(request, isBinary)
    }

    init {
        if (cookieJar != null) {
            globalCookieJar = cookieJar
        }
    }

    fun getCookieHeaders(url: String): Headers = CloudflareHeaderBuilder.getCookieHeaders(url)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        val isStatic = isStaticAsset(request.url)

        val hasWv2 = !isWorker && com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable &&
            com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.getSessionForHost(host) != null
        val hasActiveProxy = hasWv2

        if (hasActiveProxy) {
            unmarkFailed(host)
        }

        if (isFailed(host)) {
            return chain.proceed(request)
        }

        if (!isStatic && hasActiveProxy) {
            val proxyResponse = fetchViaBrowserProxy(request, isBinary = false)
            if (proxyResponse != null && proxyResponse.code !in ERROR_CODES) return proxyResponse
            AppLogger.w("$TAG: Browser proxy failed for $host, falling through to normal path.")
        } else if (!isStatic && isWorker && isTlsBound(host)) {
            val proxyResult = kotlinx.coroutines.runBlocking {
                com.lagradost.cloudstream3.desktop.sandbox.worker.WorkerHostBridge.requestProxyFetch(
                    url = request.url.toString(),
                    host = host,
                    method = request.method,
                    headers = request.headers.toMap(),
                    body = request.body?.let { b ->
                        val buf = Buffer()
                        b.writeTo(buf)
                        buf.readUtf8()
                    }
                )
            }
            if (proxyResult != null && proxyResult.statusCode in 200..299) {
                val cType = proxyResult.contentType ?: "application/json"
                val bodyBytes = proxyResult.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(proxyResult.statusCode)
                    .message("OK")
                    .header("content-type", cType)
                    .header("content-length", bodyBytes.size.toString())
                    .body(bodyBytes.toResponseBody(cType.toMediaTypeOrNull()))
                    .build()
            }
        } else if (!isStatic && !isWorker && isTlsBound(host) && !hasActiveProxy) {
            AppLogger.w("$TAG: Browser proxy is dead for TLS-bound host $host. Clearing clearance state.")
            ClearanceStateManager.removeHostClearance(host)
        }

        var response: Response? = null
        var usedClearance: HostClearance? = null

        val clearance = getClearance(request.url)
        if (clearance != null && clearance.cookies.isNotEmpty()) {
            usedClearance = clearance
            response = CloudflareHeaderBuilder.proceed(chain, request, clearance.cookies, clearance.userAgent)
        } else {
            response = chain.proceed(request)
        }

        val serverHeader = response.header("Server") ?: ""
        val cfMitigated = response.header("cf-mitigated") ?: ""
        val isCloudflareServer = CLOUDFLARE_SERVERS.any { serverHeader.contains(it, ignoreCase = true) }

        val isCloudflareChallenge = !isStatic && response.code in ERROR_CODES && isCloudflareServer && run {
            if (cfMitigated.equals("challenge", ignoreCase = true)) return@run true
            val bodyPreview = try {
                response.peekBody(4096).string()
            } catch (e: Exception) {
                AppLogger.d("$TAG: Failed to peek response body for challenge check: ${e.message}")
                ""
            }
            val trimmed = bodyPreview.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return@run false

            bodyPreview.contains("Just a moment...") ||
                bodyPreview.contains("challenge-platform") ||
                bodyPreview.contains("cf-chl-") ||
                bodyPreview.contains("_cf_chl_opt") ||
                bodyPreview.contains("turnstile", ignoreCase = true)
        }

        if (isCloudflareChallenge) {
            AppLogger.w("$TAG: Cloudflare challenge (HTTP ${response.code}) detected for $host.")

            val isBypassAllowed = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(
                com.lagradost.common.storage.DesktopDataStore.PREF_ALLOW_CF_BYPASS,
            ) ?: false

            if (!isBypassAllowed) {
                AppLogger.w("$TAG: Cloudflare challenge detected for $host, but browser solver is disabled by user.")
                if (!isWorker) {
                    try {
                        com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showWarning(
                            "This source requires Cloudflare clearance. Enable 'Experimental Cloudflare Solver' in Settings > Network. It uses a secure, isolated sandbox browser with zero access to your personal data, passwords, or accounts.",
                            durationMs = 7000L,
                        )
                    } catch (t: Throwable) {
                        AppLogger.w("$TAG: Failed to show challenge bypass toast: ${t.message}")
                    }
                }
                return response
            }
            if (isWorker) {
                if (!isStatic) {
                    val candidateClearance = usedClearance ?: getClearance(request.url) ?: getClearance(host)
                    if (candidateClearance != null && candidateClearance.cookies.isNotEmpty()) {
                        val proxyResult = kotlinx.coroutines.runBlocking {
                            com.lagradost.cloudstream3.desktop.sandbox.worker.WorkerHostBridge.requestProxyFetch(
                                url = request.url.toString(),
                                host = host,
                                method = request.method,
                                headers = request.headers.toMap(),
                                body = request.body?.let { b ->
                                    val buf = Buffer()
                                    b.writeTo(buf)
                                    buf.readUtf8()
                                }
                            )
                        }
                        if (proxyResult != null && proxyResult.statusCode in 200..299) {
                            AppLogger.i("$TAG: Existing credentials succeeded via host browser proxy for $host (HTTP ${proxyResult.statusCode}). Marking as TLS-bound.")
                            markTlsBound(host)
                            response.close()
                            val cType = proxyResult.contentType ?: "application/json"
                            val bodyBytes = proxyResult.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                            return Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(proxyResult.statusCode)
                                .message("OK")
                                .header("content-type", cType)
                                .header("content-length", bodyBytes.size.toString())
                                .body(bodyBytes.toResponseBody(cType.toMediaTypeOrNull()))
                                .build()
                        }
                    }
                }

                AppLogger.w("$TAG: Cloudflare challenge detected inside sandboxed worker for $host. Requesting host challenge solve via IPC bridge...")
                val solveResult = kotlinx.coroutines.runBlocking {
                    com.lagradost.cloudstream3.desktop.sandbox.worker.WorkerHostBridge.requestCloudflareSolve(
                        url = request.url.toString(),
                        host = host,
                        headers = request.headers.toMap()
                    )
                }
                if (solveResult != null && solveResult.cookies.isNotEmpty()) {
                    AppLogger.i("$TAG: Host solved challenge successfully for $host (${solveResult.cookies.size} cookies). Retrying request.")
                    com.lagradost.cloudstream3.desktop.network.DesktopCookieJar.activeInstance?.saveCookies(solveResult.cookies)
                    val ua = solveResult.userAgent?.takeIf { it.isNotBlank() } ?: com.lagradost.cloudstream3.USER_AGENT
                    saveClearance(host = host, userAgent = ua, okCookies = solveResult.cookies)
                    response.close()
                    val newRequest = request.newBuilder().build()
                    val retryResponse = CloudflareHeaderBuilder.proceed(chain, newRequest, solveResult.cookies, ua)
                    if (retryResponse.code in ERROR_CODES && !isStatic) {
                        AppLogger.w("$TAG: Worker OkHttp retry returned HTTP ${retryResponse.code} for $host (TLS fingerprint blocked). Requesting host browser proxy fetch via IPC bridge...")
                        retryResponse.close()
                        val proxyResult = kotlinx.coroutines.runBlocking {
                            com.lagradost.cloudstream3.desktop.sandbox.worker.WorkerHostBridge.requestProxyFetch(
                                url = newRequest.url.toString(),
                                host = host,
                                method = newRequest.method,
                                headers = newRequest.headers.toMap(),
                                body = newRequest.body?.let { b ->
                                    val buf = Buffer()
                                    b.writeTo(buf)
                                    buf.readUtf8()
                                }
                            )
                        }
                        if (proxyResult != null && proxyResult.statusCode in 200..299) {
                            AppLogger.i("$TAG: Worker browser proxy fetch succeeded for $host (HTTP ${proxyResult.statusCode})")
                            markTlsBound(host)
                            ClearanceStateManager.setHostClearance(host, HostClearance(host, solveResult.cookies, ua, isTlsBound = true))
                            val cType = proxyResult.contentType ?: "application/json"
                            val bodyBytes = proxyResult.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                            return Response.Builder()
                                .request(newRequest)
                                .protocol(Protocol.HTTP_1_1)
                                .code(proxyResult.statusCode)
                                .message("OK")
                                .header("content-type", cType)
                                .header("content-length", bodyBytes.size.toString())
                                .body(bodyBytes.toResponseBody(cType.toMediaTypeOrNull()))
                                .build()
                        } else {
                            AppLogger.w("$TAG: Worker browser proxy fetch failed for $host (status: ${proxyResult?.statusCode}, error: ${proxyResult?.error})")
                        }
                    }
                    return retryResponse
                } else {
                    AppLogger.w("$TAG: Host was unable to solve challenge for $host. Returning original response.")
                    return response
                }
            }
            lastChallengedHost = host
            lastChallengedUrl = request.url.toString()
            lastChallengeTimestamp = System.currentTimeMillis()

            if (usedClearance != null && !isTlsBound(host)) {
                AppLogger.w("$TAG: Expired or rejected Cloudflare credentials for $host. Removing from cache.")
                clearClearanceForDomain(host)
                val apex = getApexDomain(host)
                if (apex.isNotBlank() && apex != host) {
                    clearClearanceForDomain(apex)
                }
            }

            if (!isFailed(host)) {
                val solved: Boolean = synchronized(CloudflareKiller::class.java) {
                    val current = getClearance(request.url)
                    if (current != null && current.cookies.isNotEmpty()) {
                        return@synchronized true
                    }

                    if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
                        solveWithEmbeddedWebView(request.url.toString(), host, request.headers.toMap())
                    } else {
                        AppLogger.w("$TAG: Cloudflare challenge for $host cannot be solved; embedded WebView2 is unavailable.")
                        false
                    }
                }

                val rawClearance = getClearance(request.url)
                val solvedClearance = rawClearance ?: HostClearance(request.url.host, emptyList(), com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.userAgent)

                if (solved) {
                    AppLogger.i("$TAG: Successfully cleared challenge gate for $host. Retrying request.")
                    response.close()

                    val isApiEndpoint = request.url.encodedPath.contains("/api", ignoreCase = true) ||
                        request.url.encodedPath.endsWith(".json", ignoreCase = true)

                    if (!isApiEndpoint) {
                        val cachedPage = com.lagradost.cloudstream3.desktop.network.SettledPageCache.consume(request.url.toString())
                        if (cachedPage != null) {
                            AppLogger.i("$TAG: Serving settled HTML from cache after clearance for ${request.url}")
                            val bodyBytes = cachedPage.html.toByteArray(Charsets.UTF_8)
                            return Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("content-type", "text/html; charset=utf-8")
                                .header("content-length", bodyBytes.size.toString())
                                .body(bodyBytes.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
                                .build()
                        }
                    }
                    val newRequest = request.newBuilder()
                        .headers(request.headers)
                        .apply {
                            if (isApiEndpoint && !request.headers.names().any { it.equals("X-Requested-With", ignoreCase = true) }) {
                                AppLogger.i("$TAG: [DEBUG] Injecting X-Requested-With for API endpoint OkHttp retry")
                                addHeader("X-Requested-With", "XMLHttpRequest")
                            }
                        }
                        .build()

                    val retryResponse = CloudflareHeaderBuilder.proceed(chain, newRequest, solvedClearance.cookies, solvedClearance.userAgent)

                    AppLogger.i("$TAG: [DEBUG] OkHttp retry returned HTTP ${retryResponse.code} for ${retryResponse.request.url}")
                    AppLogger.i("$TAG: [DEBUG] Response Headers: ${retryResponse.headers.toMap()}")
                    AppLogger.i("$TAG: [DEBUG] Response Body Preview: ${retryResponse.peekBody(512).string().replace("\n", " ")}")

                    if (retryResponse.code !in ERROR_CODES) {
                        if (isApiEndpoint && retryResponse.header("Content-Type")?.contains("text/html", ignoreCase = true) == true) {
                            AppLogger.w("$TAG: [DEBUG] OkHttp returned HTML for API endpoint! This is a redirect to the homepage! Forcing proxy fallback!")
                            retryResponse.close()
                        } else {
                            return retryResponse
                        }
                    }
                    AppLogger.w("$TAG: OkHttp retry rejected (HTTP ${retryResponse.code}). TLS fingerprint mismatch confirmed for $host.")

                    val wv2Session = if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
                        com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.getSessionForHost(host)
                    } else {
                        null
                    }

                    if (wv2Session != null && !isStatic) {
                        ClearanceStateManager.setHostClearance(host, solvedClearance.copy(isTlsBound = true))
                        var proxyResponse = fetchViaBrowserProxy(newRequest, isBinary = false)
                        if (proxyResponse != null && proxyResponse.code in ERROR_CODES) {
                            proxyResponse.close()
                            try {
                                Thread.sleep(1500)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            proxyResponse = fetchViaBrowserProxy(newRequest, isBinary = false)
                        }
                        if (proxyResponse != null && proxyResponse.code !in ERROR_CODES) {
                            retryResponse.close()
                            return proxyResponse
                        }
                    }

                    AppLogger.w("$TAG: Browser navigation failed for $host. Returning last response.")
                    return retryResponse
                } else {
                    AppLogger.w("$TAG: Manual clearance was closed or failed for $host.")
                    try {
                        com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showWarning(
                            "Cloudflare verification window was closed before completion.",
                            durationMs = 4000L,
                        )
                    } catch (t: Throwable) {
                        AppLogger.w("$TAG: Failed to show window closed toast: ${t.message}")
                    }
                    markFailed(host)
                }
            }
        }

        return response
    }

    private suspend fun bypassCloudflare(chain: Interceptor.Chain, request: Request): Response? {
        val url = request.url.toString()
        val host = request.url.host
        AppLogger.i("$TAG: Loading Native Edge/Chrome to solve Cloudflare for $host")

        var solved = false
        val result = WebViewResolver(
            Regex(".^"),
            additionalUrls = listOf(Regex(".")),
            userAgent = null,
            useOkhttp = false,
        ).resolveUsingWebView(url) {
            false
        }

        val resolvedRequest = result.first ?: return null

        val cookieHeader = resolvedRequest.header("cookie")
        val userAgentHeader = resolvedRequest.header("user-agent")

        if (cookieHeader != null && cookieHeader.contains("cf_clearance")) {
            val parsed = parseCookieMap(cookieHeader)
            val cookiesList = parsed.mapNotNull { (k, v) ->
                try {
                    Cookie.Builder().name(k).value(v).domain(host).path("/").build()
                } catch (e: Exception) {
                    AppLogger.d("$TAG: Malformed resolved cookie '$k': ${e.message}")
                    null
                }
            }
            saveClearance(host, parsed, userAgentHeader ?: "", okCookies = cookiesList)
            solved = true
        }

        if (solved) {
            AppLogger.i("$TAG: Cloudflare bypassed successfully for $host")
            val clearance = getClearance(host)
            return CloudflareHeaderBuilder.proceed(chain, request, clearance?.cookies ?: emptyList(), clearance?.userAgent)
        }

        return null
    }
}
