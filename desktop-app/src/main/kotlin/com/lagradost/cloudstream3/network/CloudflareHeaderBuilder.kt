package com.lagradost.cloudstream3.network

import com.lagradost.common.logging.AppLogger
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

object CloudflareHeaderBuilder {
    private const val TAG = "CloudflareHeaderBuilder"

    fun getCookieHeaders(url: String): Headers {
        val httpUrl = try {
            url.toHttpUrlOrNull()
        } catch (e: Exception) {
            AppLogger.d("$TAG: Failed to parse URL for cookie headers '$url': ${e.message}")
            null
        }
        val builder = Headers.Builder()

        if (httpUrl != null) {
            val clearance = ClearanceStateManager.getClearance(httpUrl)
            if (clearance != null && clearance.cookies.isNotEmpty()) {
                if (clearance.userAgent.isNotBlank()) {
                    builder.add("user-agent", clearance.userAgent)
                }
                val matching = clearance.cookies.filter { it.matches(httpUrl) }
                if (matching.isNotEmpty()) {
                    builder.add("cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
                }
            }
        }
        return builder.build()
    }

    fun proceed(chain: Interceptor.Chain, request: Request, cookies: List<Cookie>, userAgent: String?): Response {
        val builder = request.newBuilder()
        if (!userAgent.isNullOrBlank()) {
            builder.header("user-agent", userAgent)

            val chromeVersionMatch = Regex("Chrome/([0-9]+)").find(userAgent)
            val edgeVersionMatch = Regex("Edg/([0-9]+)").find(userAgent)
            val version = edgeVersionMatch?.groupValues?.get(1) ?: chromeVersionMatch?.groupValues?.get(1) ?: "133"

            val brand = if (userAgent.contains("Edg/")) {
                "\"Not(A:Brand\";v=\"99\", \"Microsoft Edge\";v=\"$version\", \"Chromium\";v=\"$version\""
            } else {
                "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$version\", \"Chromium\";v=\"$version\""
            }

            val platform = when {
                System.getProperty("os.name", "").lowercase().contains("mac") -> "\"macOS\""
                System.getProperty("os.name", "").lowercase().contains("linux") -> "\"Linux\""
                else -> "\"Windows\""
            }
            builder.header("sec-ch-ua", brand)
            builder.header("sec-ch-ua-mobile", "?0")
            builder.header("sec-ch-ua-platform", platform)

            val isStatic = ClearanceStateManager.isStaticAsset(request.url)
            val hostLower = request.url.host.lowercase()
            val apex = ClearanceStateManager.getApexDomain(hostLower)
            val isSubdomain = apex.isNotBlank() && apex != hostLower

            val site = if (isSubdomain) "same-site" else "same-origin"
            val mode = if (isStatic) "no-cors" else "cors"
            val dest = if (isStatic) "image" else "empty"

            builder.header("sec-fetch-site", site)
            builder.header("sec-fetch-mode", mode)
            builder.header("sec-fetch-dest", dest)
            builder.header("accept-language", "en-US,en;q=0.9")
            if (request.header("referer") == null) {
                val refHost = if (isSubdomain) apex else hostLower
                builder.header("referer", "${request.url.scheme}://$refHost/")
            }
        }

        val matchingCookies = cookies.filter { it.matches(request.url) }
        val cookieHeader = request.header("cookie")
        val existingMap = cookieHeader?.let { ClearanceStateManager.parseCookieMap(it) } ?: emptyMap()
        val mergedMap = existingMap.toMutableMap()
        matchingCookies.forEach { mergedMap[it.name] = it.value }

        if (mergedMap.isNotEmpty()) {
            builder.header("cookie", mergedMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        val finalRequest = builder.build()
        AppLogger.d("$TAG: Retrying request to ${finalRequest.url} with headers: ${finalRequest.headers}")

        val response = chain.proceed(finalRequest)
        AppLogger.d("$TAG: Retry response code: ${response.code}, CipherSuite: ${response.handshake?.cipherSuite}")

        return response
    }
}
