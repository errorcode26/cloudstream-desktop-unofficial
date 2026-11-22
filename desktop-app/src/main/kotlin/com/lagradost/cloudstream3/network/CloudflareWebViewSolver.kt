package com.lagradost.cloudstream3.network

import com.lagradost.common.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

object CloudflareWebViewSolver {
    private const val TAG = "CloudflareWebViewSolver"

    private val inFlightSolvers = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>>()

    internal fun solveWithEmbeddedWebView(targetUrl: String, hostName: String, headers: Map<String, String> = emptyMap()): Boolean {
        val isBypassAllowed = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(
            com.lagradost.common.storage.DesktopDataStore.PREF_ALLOW_CF_BYPASS,
        ) ?: false
        if (!isBypassAllowed) {
            AppLogger.w("$TAG: Cloudflare solve requested for $hostName, but browser solver is disabled in Settings.")
            return false
        }
        if (!com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable ||
            com.lagradost.cloudstream3.desktop.ui.GlobalPlayerState.isPlaybackActive.value
        ) {
            if (com.lagradost.cloudstream3.desktop.ui.GlobalPlayerState.isPlaybackActive.value) {
                AppLogger.w("$TAG: Suppressing embedded WebView2 clearance for $hostName because media playback is active.")
            }
            return false
        }

        val cleanHost = hostName.lowercase().trim()
        val apex = ClearanceStateManager.getApexDomain(cleanHost).ifBlank { cleanHost }
        if (com.lagradost.common.storage.DesktopDataStore.isDomainCfSilenced(cleanHost) ||
            (apex.isNotBlank() && com.lagradost.common.storage.DesktopDataStore.isDomainCfSilenced(apex))
        ) {
            AppLogger.i("$TAG: Domain $cleanHost is ignored/silenced. Skipping embedded WebView2 solve.")
            return false
        }
        val lockKey = "GLOBAL_CF_SOLVER_LOCK"

        val myFuture = java.util.concurrent.CompletableFuture<Boolean>()
        val existing = inFlightSolvers.putIfAbsent(lockKey, myFuture)
        if (existing != null) {
            AppLogger.i("$TAG: Cloudflare solve already in progress for $lockKey, awaiting existing solver...")
            return try {
                existing.get(65, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Throwable) {
                AppLogger.w("$TAG: Timed out or interrupted waiting for active solver on $lockKey: ${e.message}")
                false
            }
        }

        try {
            val solved = executeEmbeddedWebViewSolve(targetUrl, hostName, headers)
            myFuture.complete(solved)
            return solved
        } catch (t: Throwable) {
            myFuture.complete(false)
            throw t
        } finally {
            inFlightSolvers.remove(lockKey)
        }
    }

    private fun executeEmbeddedWebViewSolve(targetUrl: String, hostName: String, headers: Map<String, String> = emptyMap()): Boolean {
        AppLogger.i("$TAG: Initializing in-process WebView2 solver for $hostName...")

        val start = System.currentTimeMillis()

        ClearanceStateManager.unmarkFailed(hostName)

        val session = com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Session()
        val active = com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.startSession(
            session = session,
            targetUrl = targetUrl,
            hostName = hostName,
        )

        val navigateUrl = if (targetUrl.contains("/api", ignoreCase = true) || targetUrl.endsWith(".json", ignoreCase = true)) {
            val httpUrl = targetUrl.toHttpUrlOrNull()
            if (httpUrl != null) "${httpUrl.scheme}://${httpUrl.host}/" else targetUrl
        } else {
            targetUrl
        }

        session.navigate(navigateUrl)

        val maxWaitMs = 60_000L
        val navStart = System.currentTimeMillis()
        val minStabilizeMs = 2_000L

        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (active.isDismissed.get()) {
                AppLogger.w("$TAG: Verification dialog dismissed by user for $hostName.")
                com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.closeSessionSafely(session)
                return false
            }

            val elapsedSinceNav = System.currentTimeMillis() - navStart
            val isChallengeOngoing = if (elapsedSinceNav < minStabilizeMs) {
                true
            } else {
                runCatching {
                    val statusJson = kotlinx.coroutines.runBlocking {
                        session.evaluateJs(
                            """
                            (function() {
                                if (document.location.href === 'about:blank' || document.location.href === '') return 'LOADING';
                                if (document.readyState === 'loading') return 'LOADING';
                                var title = document.title || '';
                                var body = document.body ? document.body.innerHTML : '';
                                if (title.includes('Just a moment') || title.includes('Attention Required') || title.includes('Cloudflare') || title.includes('Verify you are human') || body.includes('cf-please-wait') || body.includes('cf-spinner') || body.includes('challenges.cloudflare.com')) {
                                    return 'CHALLENGE';
                                }
                                return 'CLEAR';
                            })();
                            """.trimIndent(),
                            2000L,
                        )
                    } ?: ""

                    !statusJson.contains("CLEAR")
                }.getOrDefault(true)
            }

            val clearance = ClearanceStateManager.getClearance(hostName)
            val hasClearanceCookie = clearance?.cookies?.any { it.name.equals("cf_clearance", ignoreCase = true) } == true

            if (isChallengeOngoing && elapsedSinceNav >= 3_500L && com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.activeSession.value?.isDialogVisible != true) {
                com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.requestDialogVisibility()
            }

            if (hasClearanceCookie || (!isChallengeOngoing && elapsedSinceNav >= 12_000L && clearance != null && clearance.cookies.size >= 3)) {
                val settleDeadline = System.currentTimeMillis() + 1_500L
                while (System.currentTimeMillis() < settleDeadline) {
                    val settled = ClearanceStateManager.getClearance(hostName)
                    if (settled != null && settled.cookies.size >= 2) break
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }

                AppLogger.i("$TAG: Cloudflare challenge cleared for $hostName (${System.currentTimeMillis() - start}ms).")
                com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.finishSuccess()
                return true
            }

            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.dismiss()
        com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.closeSessionSafely(session)
        return false
    }

    internal fun fetchViaBrowserProxy(request: Request, isBinary: Boolean = false): Response? {
        val host = request.url.host.lowercase()
        val isApiEndpoint = request.url.encodedPath.contains("/api", ignoreCase = true) ||
            request.url.encodedPath.endsWith(".json", ignoreCase = true)

        val preparedHeaders = buildMap {
            for (name in request.headers.names()) {
                val lower = name.lowercase()
                if (lower == "sec-ch-ua-platform" || lower == "sec-ch-ua-mobile") continue
                put(name, request.header(name) ?: "")
            }
            if (isApiEndpoint && keys.none { it.equals("X-Requested-With", ignoreCase = true) }) {
                put("X-Requested-With", "XMLHttpRequest")
            }
            if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                put("Referer", "${request.url.scheme}://${request.url.host}/")
            }
        }
        val wv2Session = if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
            com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.getOrCreateSessionForHost(host)
        } else {
            null
        }

        val result = if (wv2Session != null) {
            kotlinx.coroutines.runBlocking {
                wv2Session.awaitOriginAndFetch(
                    url = request.url.toString(),
                    method = request.method,
                    headers = preparedHeaders,
                    body = request.body?.let { body ->
                        val buffer = okio.Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8()
                    },
                )
            }
        } else {
            null
        }

        if (result == null || result.statusCode == 0) {
            AppLogger.e("$TAG: fetchViaBrowserProxy returned null/error for ${request.url}")
            return null
        }

        AppLogger.i("$TAG: Browser proxy returned HTTP ${result.statusCode} for ${request.url}")
        val contentTypeString = result.contentType ?: "application/octet-stream"
        val bodyBytes = result.bodyBytes ?: result.body.toByteArray(Charsets.UTF_8)

        val preview = if (result.body.length > 250) result.body.substring(0, 250) else result.body
        AppLogger.i("$TAG: Proxy response body start: ${preview.replace("\n", " ")}")

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.statusCode)
            .message(if (result.statusCode in 200..299) "OK" else "Proxied")
            .header("content-type", contentTypeString)
            .header("content-length", bodyBytes.size.toString())
            .body(bodyBytes.toResponseBody(contentTypeString.toMediaTypeOrNull()))
            .build()
    }
}
