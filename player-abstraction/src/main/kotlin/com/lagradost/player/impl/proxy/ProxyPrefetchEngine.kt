package com.lagradost.player.impl.proxy

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class InitCacheEntry(val data: ByteArray, val timestamp: Long)
data class SegmentCacheEntry(val data: ByteArray, val timestamp: Long)

internal object ProxyPrefetchEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val initSegmentCache = ConcurrentHashMap<String, InitCacheEntry>()
    val rawInitSegmentCache = ConcurrentHashMap<String, InitCacheEntry>()
    val decryptedSegmentCache = ConcurrentHashMap<String, SegmentCacheEntry>()

    const val INIT_CACHE_TTL_MS = 600_000L // 10 minutes
    const val SEGMENT_CACHE_TTL_MS = 60_000L
    private const val MAX_SEGMENT_CACHE_SIZE = 150

    private val prefetchingUrls = ConcurrentHashMap.newKeySet<String>()

    fun cleanupSegmentCache() {
        val now = System.currentTimeMillis()
        decryptedSegmentCache.entries.removeIf { now - it.value.timestamp > SEGMENT_CACHE_TTL_MS }
        if (decryptedSegmentCache.size > MAX_SEGMENT_CACHE_SIZE) {
            val oldest = decryptedSegmentCache.entries.sortedBy { it.value.timestamp }
                .take(decryptedSegmentCache.size - MAX_SEGMENT_CACHE_SIZE / 2)
            oldest.forEach { decryptedSegmentCache.remove(it.key) }
        }
    }

    fun clearMediaCaches() {
        decryptedSegmentCache.clear()
        initSegmentCache.clear()
        rawInitSegmentCache.clear()
        DashProxyHandler.clear()
    }

    fun prefetchM3u8(
        sessionId: String,
        url: String,
        sessions: Map<String, LocalStreamProxy.ProxySession>,
        getClientForSession: (LocalStreamProxy.ProxySession?) -> OkHttpClient,
        resolveEffectiveUserAgent: (String?, String?, String?) -> String,
        tracksListener: ProxyTracksListener?,
    ) {
        val session = sessions[sessionId] ?: return
        if (session.masterCache.containsKey(url)) return

        val deferred = scope.async(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK)
                val mergedHeaders = session.headers.toMutableMap()
                val keysToRemove = mergedHeaders.keys.filter {
                    it.equals("Host", ignoreCase = true)
                }
                keysToRemove.forEach { mergedHeaders.remove(it) }
                val sessionHost = try {
                    val base = session.originalUrl
                        ?: session.headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                        ?: session.headers.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
                    if (base != null) URI(base).host?.lowercase() else null
                } catch (e: Exception) {
                    AppLogger.d("Proxy:LocalStream", "Session host parse note: ${e.message}")
                    null
                }
                val requestHost = try { URI(url).host?.lowercase() } catch (e: Exception) { AppLogger.d("Proxy:LocalStream", "URL host parse note: ${e.message}"); null }
                val rawUa = mergedHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                val effectiveUa = resolveEffectiveUserAgent(sessionHost, requestHost, rawUa)

                mergedHeaders.keys.filter { it.equals("User-Agent", ignoreCase = true) }.toList().forEach { mergedHeaders.remove(it) }
                mergedHeaders["User-Agent"] = effectiveUa
                mergedHeaders.forEach { (k, v) -> requestBuilder.safeHeader(k, v) }

                var response: Response? = null
                var lastError: Exception? = null
                val client = getClientForSession(session)
                for (attempt in 1..4) {
                    try {
                        response = client.newCall(requestBuilder.build()).await()
                        if (response.isSuccessful || response.code in 400..499) break
                    } catch (e: Exception) {
                        lastError = e
                    }
                    if (attempt < 4) {
                        response?.body?.close()
                        delay(200L * attempt)
                    }
                }

                if (response == null || !response.isSuccessful) {
                    val code = response?.code
                    response?.body?.close()
                    throw Exception("Prefetch HTTP failed. Code: $code Error: ${lastError?.message}")
                }

                val m3u8Content = response.body?.source()?.readUtf8() ?: ""
                val finalUrl = response.request.url.toString()
                response.body?.close()

                val rewritten = HlsRewriter.rewriteM3u8(m3u8Content, finalUrl, sessionId, tracksListener)
                rewritten.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                AppLogger.e("Proxy:LocalStream", "Prefetch failed for $url", e)
                ByteArray(0)
            }
        }

        session.masterCache[url] = deferred
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = deferred.await()
                val content = String(bytes, Charsets.UTF_8)
                val isLiveSlidingPlaylist = !content.contains("#EXT-X-STREAM-INF") &&
                    !content.contains("#EXT-X-ENDLIST") &&
                    !content.contains("#EXT-X-PLAYLIST-TYPE:VOD")
                if (isLiveSlidingPlaylist) {
                    session.masterCache.remove(url)
                } else {
                    prefetchMasterRenditions(sessionId, session, content, url, getClientForSession, resolveEffectiveUserAgent, tracksListener)
                }
            } catch (e: Exception) {
                session.masterCache.remove(url)
                AppLogger.w("Proxy:LocalStream", "Error analyzing prefetch payload for caching: ${e.message}", e)
            }
        }
    }

    fun prefetchMasterRenditions(
        sessionId: String,
        session: LocalStreamProxy.ProxySession,
        masterContent: String,
        masterUrl: String,
        getClientForSession: (LocalStreamProxy.ProxySession?) -> OkHttpClient,
        resolveEffectiveUserAgent: (String?, String?, String?) -> String,
        tracksListener: ProxyTracksListener?,
    ) {
        val lines = masterContent.lines()
        val variantUrls = mutableListOf<String>()
        val audioUrls = mutableListOf<String>()
        var nextIsVariant = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                nextIsVariant = true
            } else if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                if (uriMatch != null) {
                    val rawUri = uriMatch.groupValues[1]
                    val resolved = HlsRewriter.resolveUrl(masterUrl, rawUri)
                    if (resolved.startsWith("http://", ignoreCase = true) || resolved.startsWith("https://", ignoreCase = true)) {
                        audioUrls.add(resolved)
                    }
                }
            } else if (nextIsVariant && !line.startsWith("#")) {
                val resolved = HlsRewriter.resolveUrl(masterUrl, line)
                if (resolved.startsWith("http://", ignoreCase = true) || resolved.startsWith("https://", ignoreCase = true)) {
                    variantUrls.add(resolved)
                }
                nextIsVariant = false
            }
        }

        val targets = (variantUrls.take(2) + audioUrls.take(2)).distinct()
        for (targetUrl in targets) {
            if (session.masterCache.containsKey(targetUrl)) continue
            val variantDeferred = scope.async(Dispatchers.IO) {
                try {
                    val requestBuilder = Request.Builder().url(targetUrl).cacheControl(CacheControl.FORCE_NETWORK)
                    val mergedHeaders = session.headers.toMutableMap()
                    mergedHeaders.remove("Host")
                    mergedHeaders.remove("Range")
                    mergedHeaders.remove("Accept-Encoding")
                    val targetHost = try { URI(targetUrl).host?.lowercase() } catch (e: Exception) { AppLogger.d("Proxy:LocalStream", "Target host parse note: ${e.message}"); null }
                    val rawUa = mergedHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    val effectiveUa = resolveEffectiveUserAgent(null, targetHost, rawUa)
                    mergedHeaders.keys.filter { it.equals("User-Agent", ignoreCase = true) }.toList().forEach { mergedHeaders.remove(it) }
                    mergedHeaders["User-Agent"] = effectiveUa
                    val client = getClientForSession(session)
                    mergedHeaders.forEach { (k, v) -> requestBuilder.safeHeader(k, v) }
                    val response = client.newCall(requestBuilder.build()).await()
                    if (!response.isSuccessful) {
                        response.body?.close()
                        return@async ByteArray(0)
                    }
                    val body = response.body?.source()?.readUtf8() ?: ""
                    val finalTargetUrl = response.request.url.toString()
                    response.body?.close()
                    val rewritten = HlsRewriter.rewriteM3u8(body, finalTargetUrl, sessionId, tracksListener)
                    rewritten.toByteArray(Charsets.UTF_8)
                } catch (e: Exception) {
                    AppLogger.d("Proxy:LocalStream", "Variant rendition prefetch note for $targetUrl: ${e.message}")
                    ByteArray(0)
                }
            }
            session.masterCache[targetUrl] = variantDeferred
        }
    }

    fun prefetchNextSegments(
        currentSegmentUrl: String,
        initUrl: String?,
        rawInitBytes: ByteArray?,
        kid: String,
        k: String,
        session: LocalStreamProxy.ProxySession?,
        headers: Map<String, String>,
        getClientForSession: (LocalStreamProxy.ProxySession?) -> OkHttpClient,
        count: Int = 3,
    ) {
        val regex = Regex("""([_\-\./])(\d+)(\.m4[sv]|\.mp4|\.ts)""")
        val match = regex.find(currentSegmentUrl) ?: return
        val prefix = match.groupValues[1]
        val numStr = match.groupValues[2]
        val suffix = match.groupValues[3]
        val currentNum = numStr.toIntOrNull() ?: return
        val numLength = numStr.length

        for (i in 1..count) {
            val nextNum = currentNum + i
            val paddedNextNum = if (numStr.startsWith("0")) nextNum.toString().padStart(numLength, '0') else nextNum.toString()
            val nextUrl = currentSegmentUrl.replace(
                match.value,
                "$prefix$paddedNextNum$suffix",
            )
            val nextCacheKey = "${nextUrl}_${kid}_$k"

            if (decryptedSegmentCache.containsKey(nextCacheKey) || !prefetchingUrls.add(nextCacheKey)) {
                continue
            }

            scope.launch {
                try {
                    val client = getClientForSession(session)
                    val reqBuilder = Request.Builder().url(nextUrl)
                    headers.forEach { (hKey, hVal) -> reqBuilder.safeHeader(hKey, hVal) }
                    val resp = client.newCall(reqBuilder.build()).await()
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.source()?.readByteArray()
                        resp.body?.close()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val decrypted = StreamDecryptor.decryptMediaSegment(
                                mediaSegment = bytes,
                                keyIdHex = kid,
                                keyHex = k,
                                initSegment = rawInitBytes,
                            )
                            decryptedSegmentCache[nextCacheKey] = SegmentCacheEntry(decrypted, System.currentTimeMillis())
                            cleanupSegmentCache()
                        }
                    } else {
                        resp.body?.close()
                    }
                } catch (e: Exception) {
                    AppLogger.d("Proxy:LocalStream", "Segment prefetch failed for $nextUrl: ${e.message}")
                } finally {
                    prefetchingUrls.remove(nextCacheKey)
                }
            }
        }
    }
}
