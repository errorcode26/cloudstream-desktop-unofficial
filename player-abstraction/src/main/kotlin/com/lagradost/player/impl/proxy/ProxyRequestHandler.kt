package com.lagradost.player.impl.proxy

import com.lagradost.common.logging.AppLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64

internal object ProxyRequestHandler {

    suspend fun handleRequest(
        call: ApplicationCall,
        sessions: Map<String, LocalStreamProxy.ProxySession>,
        port: Int,
        getClientForSession: (LocalStreamProxy.ProxySession?) -> OkHttpClient,
        resolveEffectiveUserAgent: (String?, String?, String?) -> String,
        tracksListener: ProxyTracksListener?,
    ) {
        try {
            val sessionId = call.request.queryParameters["s"]
            val encodedUrl = call.request.queryParameters["u"]
            val isFlatVtt = call.request.queryParameters["flatvtt"] == "true"
            val action = call.request.queryParameters["action"]
            val rep = call.request.queryParameters["rep"]
            val clearKey = call.request.queryParameters["ck"]
            val kid = call.request.queryParameters["kid"]
            val k = call.request.queryParameters["k"]
            val encodedInit = call.request.queryParameters["init"]
            val initUrl = encodedInit?.let {
                try {
                    String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
                } catch (e: Exception) {
                    AppLogger.d("Proxy:LocalStream", "Init URL decode note: ${e.message}")
                    null
                }
            }

            AppLogger.i("Proxy:LocalStream", "Action=$action, rep=$rep, hasCk=${clearKey != null}, hasKid=${kid != null}, hasK=${k != null}, encodedUrl=$encodedUrl")

            if (sessionId == null || encodedUrl == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val url = String(Base64.getUrlDecoder().decode(encodedUrl), Charsets.UTF_8)
            val session = sessions[sessionId]

            if (session == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val cachedDeferred = session.masterCache[url]
            if (cachedDeferred != null) {
                val bytes = cachedDeferred.await()
                if (bytes.isNotEmpty()) {
                    call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                    call.respondBytes(bytes, status = HttpStatusCode.OK)
                    return
                }
            }

            if (action == "init_decrypt" || action == "init") {
                val cached = ProxyPrefetchEngine.initSegmentCache[url]
                if (cached != null && System.currentTimeMillis() - cached.timestamp < ProxyPrefetchEngine.INIT_CACHE_TTL_MS) {
                    call.response.header("Content-Type", "video/mp4")
                    call.response.header("Accept-Ranges", "bytes")
                    call.respondBytes(cached.data, status = HttpStatusCode.OK)
                    return
                }
            }

            if (action == "decrypt") {
                val cacheKey = "${url}_${kid ?: ""}_${k ?: ""}"
                val cached = ProxyPrefetchEngine.decryptedSegmentCache[cacheKey]
                if (cached != null && System.currentTimeMillis() - cached.timestamp < ProxyPrefetchEngine.SEGMENT_CACHE_TTL_MS) {
                    call.response.header("Content-Type", "video/mp4")
                    call.response.header("Accept-Ranges", "bytes")
                    call.respondBytes(cached.data, status = HttpStatusCode.OK)
                    return
                }
            }

            if (action == "dash" && rep != null) {
                val cachedEntry = session.mpdCache[url]
                if (cachedEntry != null) {
                    val isLive = cachedEntry.content.contains("type=\"dynamic\"") || cachedEntry.content.contains("type='dynamic'")
                    val age = System.currentTimeMillis() - cachedEntry.timestamp
                    if (!isLive || age < 1000L) {
                        val m3u8 = NativeMpdConverter().convertMediaPlaylist(cachedEntry.content, rep, port, sessionId, url, clearKey)
                        call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                        call.respondBytes(m3u8.toByteArray(Charsets.UTF_8), status = HttpStatusCode.OK)
                        return
                    }
                }
            }

            // Local File Interception: bypass network stack for local subtitle paths
            val isLocalFilePath = (url.length >= 3 && url[1] == ':' && (url[2] == '\\' || url[2] == '/')) ||
                url.startsWith("/")
            if (isLocalFilePath) {
                val file = File(url)
                if (!file.exists() || !file.isFile) {
                    call.respond(HttpStatusCode.NotFound)
                    return
                }
                val ext = file.extension.lowercase()
                val contentType = when (ext) {
                    "srt" -> ContentType.parse("text/plain; charset=utf-8")
                    "vtt" -> ContentType.parse("text/vtt; charset=utf-8")
                    "ass", "ssa" -> ContentType.parse("text/plain; charset=utf-8")
                    else -> ContentType.Application.OctetStream
                }
                val bytes = withContext(Dispatchers.IO) {
                    file.readBytes()
                }
                call.respondBytes(bytes, contentType, HttpStatusCode.OK)
                return
            }

            val mergedHeaders = session.headers.toMutableMap()
            val keysToRemove = mergedHeaders.keys.filter {
                it.equals("Accept-Encoding", ignoreCase = true) ||
                    it.equals("Host", ignoreCase = true)
            }
            keysToRemove.forEach { mergedHeaders.remove(it) }

            val parsedUri = try {
                URI(url)
            } catch (e: Exception) {
                AppLogger.d("Proxy:LocalStream", "Parsed URI note: ${e.message}")
                null
            }

            val sessionHost = try {
                val base = session.originalUrl
                    ?: session.headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                    ?: session.headers.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
                if (base != null) URI(base).host?.lowercase() else null
            } catch (e: Exception) {
                AppLogger.d("Proxy:LocalStream", "Request session host note: ${e.message}")
                null
            }
            val requestHost = parsedUri?.host?.lowercase()
            val isCrossOrigin = sessionHost != null && requestHost != null &&
                !requestHost.equals(sessionHost, ignoreCase = true) &&
                !requestHost.endsWith(".$sessionHost", ignoreCase = true) &&
                !sessionHost.endsWith(".$requestHost", ignoreCase = true)

            if (isCrossOrigin) {
                val sessionOrigin = session.headers.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
                    ?: if (sessionHost != null) "https://$sessionHost" else null
                if (sessionOrigin != null) {
                    mergedHeaders["Origin"] = sessionOrigin
                }
                val sessionReferer = session.headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                    ?: if (sessionHost != null) "https://$sessionHost/" else null
                if (sessionReferer != null) {
                    mergedHeaders["Referer"] = sessionReferer
                }
            }

            val path = (parsedUri?.path ?: url.substringBefore('?')).lowercase()
            val query = (parsedUri?.query ?: url.substringAfter('?', "")).lowercase()
            val isM3u8Url = path.endsWith(".m3u8") ||
                path.endsWith(".m3u") ||
                path.contains(".m3u8") ||
                path.contains("/playlist") ||
                path.contains("/manifest") ||
                query.contains(".m3u8") ||
                query.contains("format=m3u8") ||
                query.contains("type=m3u8")

            if (!isM3u8Url) {
                mergedHeaders["Accept-Encoding"] = "identity"
                call.request.headers["Range"]?.let {
                    mergedHeaders["Range"] = it
                }
            } else {
                mergedHeaders.remove("Accept-Encoding")
                mergedHeaders.keys.filter { it.equals("Range", ignoreCase = true) }.forEach { mergedHeaders.remove(it) }
            }

            val currentUa = mergedHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            val effectiveUa = resolveEffectiveUserAgent(sessionHost, requestHost, currentUa)
            mergedHeaders.keys.filter { it.equals("User-Agent", ignoreCase = true) }.toList().forEach { mergedHeaders.remove(it) }
            mergedHeaders["User-Agent"] = effectiveUa
            val requestBuilder = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK)
            mergedHeaders.forEach { (k, v) -> requestBuilder.safeHeader(k, v) }

            var response: okhttp3.Response? = null
            var lastError: Exception? = null
            val client = getClientForSession(session)
            val maxAttempts = if (isM3u8Url) 1 else 3
            for (attempt in 1..maxAttempts) {
                try {
                    response = client.newCall(requestBuilder.build()).await()
                    if (response.isSuccessful || response.code in 400..499) break
                } catch (e: Exception) {
                    lastError = e
                }
                if (attempt < maxAttempts) {
                    response?.body?.close()
                    delay(100L * attempt)
                }
            }

            if (response != null && !response.isSuccessful && mergedHeaders.containsKey("Range")) {
                val code = response.code
                if (code == 403 || code == 400 || code == 416 || code == 405) {
                    AppLogger.w("Proxy:LocalStream", "Range request failed with HTTP $code, retrying WITHOUT Range header for URL: $url")
                    response.body?.close()
                    val retryHeaders = mergedHeaders.toMutableMap()
                    retryHeaders.remove("Range")
                    if (code == 403) {
                        retryHeaders.keys.filter { key ->
                            key.equals("Cookie", ignoreCase = true) ||
                                key.equals("Authorization", ignoreCase = true)
                        }.forEach { retryHeaders.remove(it) }
                    }
                    val retryBuilder = Request.Builder().url(url)
                    retryHeaders.forEach { (k, v) -> retryBuilder.safeHeader(k, v) }

                    var retryResponse: okhttp3.Response? = null
                    for (attempt in 1..3) {
                        try {
                            retryResponse = client.newCall(retryBuilder.build()).await()
                            if (retryResponse.isSuccessful || retryResponse.code in 400..499) break
                        } catch (e: Exception) {
                            lastError = e
                        }
                        if (attempt < 3) {
                            retryResponse?.body?.close()
                            delay(200L * attempt)
                        }
                    }
                    if (retryResponse != null && retryResponse.isSuccessful) {
                        response = retryResponse
                    } else {
                        retryResponse?.body?.close()
                    }
                }
            }

            if (response == null) {
                AppLogger.e("Proxy:LocalStream", "Proxy Request Failed after $maxAttempts attempts! URL: $url Error: ${lastError?.message}")
                val status = if (lastError is SocketTimeoutException) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway
                call.respond(status)
                return
            }

            if (!response.isSuccessful) {
                AppLogger.e("Proxy:LocalStream", "Proxy Request Failed! Code: ${response.code} URL: $url")
                response.body?.close()
                call.respond(HttpStatusCode.fromValue(response.code))
                return
            }

            if (action == "init_decrypt") {
                val rawBytes = withContext(Dispatchers.IO) {
                    response.body?.source()?.readByteArray() ?: ByteArray(0)
                }
                response.body?.close()
                ProxyPrefetchEngine.rawInitSegmentCache[url] = InitCacheEntry(rawBytes, System.currentTimeMillis())
                val cleaned = StreamDecryptor.cleanInitSegment(rawBytes)
                ProxyPrefetchEngine.initSegmentCache[url] = InitCacheEntry(cleaned, System.currentTimeMillis())
                call.response.header("Content-Type", "video/mp4")
                call.response.header("Accept-Ranges", "bytes")
                call.respondBytes(cleaned, status = HttpStatusCode.OK)
                return
            }

            if (action == "decrypt") {
                val mediaBytes = withContext(Dispatchers.IO) {
                    response.body?.source()?.readByteArray() ?: ByteArray(0)
                }
                response.body?.close()

                var rawInitBytes: ByteArray? = null
                if (initUrl != null) {
                    rawInitBytes = ProxyPrefetchEngine.rawInitSegmentCache[initUrl]?.data
                    if (rawInitBytes == null) {
                        try {
                            val initReqBuilder = Request.Builder().url(initUrl)
                            mergedHeaders.forEach { (k, v) -> initReqBuilder.safeHeader(k, v) }
                            val initResp = client.newCall(initReqBuilder.build()).await()
                            if (initResp.isSuccessful) {
                                val fetchedRaw = initResp.body?.source()?.readByteArray()
                                initResp.body?.close()
                                if (fetchedRaw != null && fetchedRaw.isNotEmpty()) {
                                    rawInitBytes = fetchedRaw
                                    ProxyPrefetchEngine.rawInitSegmentCache[initUrl] = InitCacheEntry(fetchedRaw, System.currentTimeMillis())
                                    val cleaned = StreamDecryptor.cleanInitSegment(fetchedRaw)
                                    ProxyPrefetchEngine.initSegmentCache[initUrl] = InitCacheEntry(cleaned, System.currentTimeMillis())
                                }
                            } else {
                                initResp.body?.close()
                            }
                        } catch (e: Exception) {
                            AppLogger.w("Proxy:LocalStream", "Failed to fetch init segment: ${e.message}")
                        }
                    }
                }

                val decrypted = try {
                    StreamDecryptor.decryptMediaSegment(
                        mediaSegment = mediaBytes,
                        keyIdHex = kid ?: "",
                        keyHex = k ?: "",
                        initSegment = rawInitBytes,
                    )
                } catch (e: Exception) {
                    AppLogger.e("Proxy:LocalStream", "Decryption failed for $url: ${e.message}", e)
                    mediaBytes
                }

                val cacheKey = "${url}_${kid ?: ""}_${k ?: ""}"
                ProxyPrefetchEngine.decryptedSegmentCache[cacheKey] = SegmentCacheEntry(decrypted, System.currentTimeMillis())
                ProxyPrefetchEngine.cleanupSegmentCache()

                ProxyPrefetchEngine.prefetchNextSegments(
                    currentSegmentUrl = url,
                    initUrl = initUrl,
                    rawInitBytes = rawInitBytes,
                    kid = kid ?: "",
                    k = k ?: "",
                    session = session,
                    headers = mergedHeaders,
                    getClientForSession = getClientForSession,
                )

                call.response.header("Content-Type", "video/mp4")
                call.response.header("Accept-Ranges", "bytes")
                call.respondBytes(decrypted, status = HttpStatusCode.OK)
                return
            }

            if (action == "dash") {
                val mpdContent = withContext(Dispatchers.IO) {
                    response.body?.source()?.readUtf8() ?: ""
                }
                response.body?.close()
                session.mpdCache[url] = LocalStreamProxy.MpdCacheEntry(mpdContent, System.currentTimeMillis())
                val m3u8 = if (rep == null) {
                    NativeMpdConverter().convertMasterPlaylist(mpdContent, port, sessionId, url, clearKey, tracksListener)
                } else {
                    NativeMpdConverter().convertMediaPlaylist(mpdContent, rep, port, sessionId, url, clearKey)
                }
                call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                call.respondBytes(m3u8.toByteArray(Charsets.UTF_8), status = HttpStatusCode.OK)
                return
            }

            val upstreamContentType = response.header("Content-Type") ?: ""
            val rawContentType = upstreamContentType.ifBlank { "application/octet-stream" }
            val isNonMediaType = rawContentType.contains("javascript", ignoreCase = true) ||
                rawContentType.contains("text/", ignoreCase = true) ||
                (rawContentType.contains("image/", ignoreCase = true) && !rawContentType.contains("mpegurl", ignoreCase = true))
            val contentTypeStr = if (isNonMediaType) "application/octet-stream" else rawContentType
            val isM3u8 = withContext(Dispatchers.IO) {
                try {
                    val s = response.body?.source()
                    if (s != null && s.request(1)) {
                        val peekLen = minOf(64L, s.buffer.size)
                        val peeked = s.peek().readUtf8(peekLen).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                        peeked.startsWith("#EXTM3U", ignoreCase = true) || peeked.startsWith("#EXT-X-", ignoreCase = true)
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }

            if (isM3u8) {
                try {
                    val m3u8Content = withContext(Dispatchers.IO) {
                        response.body?.source()?.readUtf8() ?: ""
                    }

                    val finalUrl = response.request.url.toString()

                    if (isFlatVtt) {
                        if (m3u8Content.contains("#EXT-X-KEY") || m3u8Content.contains("#EXT-X-MAP")) {
                            // Encrypted or fMP4 subtitles fallback to normal M3U8 proxying
                        } else {
                            call.response.header("Content-Type", "text/vtt")
                            call.respondBytesWriter(status = HttpStatusCode.OK) {
                                writeFully("WEBVTT\n\n".toByteArray(Charsets.UTF_8))
                                val lines = m3u8Content.lines()
                                val vttUrls = lines.filter { !it.startsWith("#") && it.trim().isNotEmpty() }.map { HlsRewriter.resolveUrl(finalUrl, it.trim()) }

                                val headersMap = session.headers.toMutableMap()
                                headersMap.remove("Accept-Encoding")
                                headersMap.remove("Host")
                                if (headersMap.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                                    headersMap["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                }

                                val chunkedBatches = vttUrls.chunked(16)
                                for (batch in chunkedBatches) {
                                    val downloadedSegments: List<String> = coroutineScope {
                                        val deferreds = batch.map { segUrl ->
                                            async(Dispatchers.IO) {
                                                val reqBuilder = Request.Builder().url(segUrl)
                                                headersMap.forEach { (k, v) -> reqBuilder.safeHeader(k, v) }
                                                var content = ""
                                                for (attempt in 1..2) {
                                                    try {
                                                        val segResp = client.newCall(reqBuilder.build()).await()
                                                        val segText = segResp.body?.source()?.readUtf8() ?: ""
                                                        segResp.body?.close()
                                                        if (segResp.isSuccessful && segText.isNotBlank()) {
                                                            content = segText
                                                            break
                                                        }
                                                    } catch (e: Exception) {
                                                        AppLogger.d("Proxy:LocalStream", "VTT segment fetch attempt $attempt failed for $segUrl: ${e.message}")
                                                    }
                                                }
                                                content
                                            }
                                        }
                                        deferreds.map { it.await() }
                                    }

                                    for (result in downloadedSegments) {
                                        if (result.isNotBlank()) {
                                            val segmentLines = result.trimStart('\uFEFF').lines()
                                            for (line in segmentLines) {
                                                val trimmed = line.trim()
                                                if (trimmed == "WEBVTT" || trimmed.startsWith("X-TIMESTAMP-MAP") || trimmed.startsWith("NOTE")) continue
                                                writeFully((line + "\n").toByteArray(Charsets.UTF_8))
                                            }
                                            writeFully("\n".toByteArray(Charsets.UTF_8))
                                        }
                                    }
                                    flush()
                                }
                            }
                            return
                        }
                    }

                    val rewritten = HlsRewriter.rewriteM3u8(m3u8Content, finalUrl, sessionId, tracksListener)
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)

                    if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                        session.masterCache[url] = kotlinx.coroutines.CompletableDeferred(bytes)
                        ProxyPrefetchEngine.prefetchMasterRenditions(sessionId, session, m3u8Content, finalUrl, getClientForSession, resolveEffectiveUserAgent, tracksListener)
                    }

                    call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                    call.respondBytes(bytes, status = HttpStatusCode.OK)
                } finally {
                    withContext(Dispatchers.IO) {
                        try {
                            response.body?.close()
                        } catch (e: Exception) {
                            AppLogger.d("Proxy:LocalStream", "Failed to close M3U8 response body: ${e.message}")
                        }
                    }
                }
                return
            } else {
                var finalCode = response.code
                var skipBytes = 0L
                val origRange = call.request.headers["Range"]
                if (origRange != null && origRange.startsWith("bytes=", ignoreCase = true) && response.code == 200) {
                    skipBytes = origRange.substringAfter("=").substringBefore("-").toLongOrNull() ?: 0L
                    if (skipBytes > 0) {
                        finalCode = 206
                    }
                }

                var detectedTsOffset = 0
                if (skipBytes == 0L) {
                    try {
                        val peekSource = response.body?.source()?.peek()
                        if (peekSource != null) {
                            val peekBuf = ByteArray(32768)
                            val n = peekSource.read(peekBuf)
                            if (n > 188 * 3 && peekBuf[0] != 0x47.toByte()) {
                                val offset = StreamTransferEngine.findTsSyncOffset(peekBuf, n)
                                if (offset > 0) {
                                    detectedTsOffset = offset
                                    AppLogger.i("Proxy:LocalStream", "Stripping $detectedTsOffset bytes of prepended header from segment: $url")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.d("Proxy:LocalStream", "TS sync offset probe note: ${e.message}")
                    }
                }

                val cl = response.body?.contentLength() ?: -1L
                val contentLengthParam = if (skipBytes == 0L && cl >= 0) {
                    if (detectedTsOffset > 0) cl - detectedTsOffset else cl
                } else {
                    null
                }

                val parsedContentType = try {
                    ContentType.parse(contentTypeStr)
                } catch (e: Exception) {
                    ContentType.Application.OctetStream
                }

                if (skipBytes > 0) {
                    val endPart = origRange?.substringAfter("-")
                    val endPos = if (endPart.isNullOrBlank()) (if (cl > 0) cl - 1 else "") else endPart
                    call.response.header("Content-Range", "bytes $skipBytes-$endPos/${if (cl > 0) cl else "*"}")
                } else {
                    response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                }

                call.response.header("Accept-Ranges", response.header("Accept-Ranges") ?: "bytes")

                StreamTransferEngine.transferStream(
                    call = call,
                    response = response,
                    parsedContentType = parsedContentType,
                    finalCode = finalCode,
                    contentLengthParam = contentLengthParam,
                    skipBytes = skipBytes,
                    detectedTsOffset = detectedTsOffset,
                    url = url,
                    requestBuilder = requestBuilder,
                    mergedHeaders = mergedHeaders,
                    client = client,
                    tracksListener = tracksListener,
                )
                return
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val isChannelClosed = e is io.ktor.utils.io.ClosedWriteChannelException ||
                e.cause is io.ktor.utils.io.ClosedWriteChannelException ||
                (e is java.io.IOException && e.message?.contains("Broken pipe", ignoreCase = true) == true)
            if (isChannelClosed) {
                AppLogger.d("Proxy:LocalStream", "Client disconnected from stream (normal player seek or exit)")
            } else {
                AppLogger.e("Proxy:LocalStream", "LocalStreamProxy error: ${e.message}", e)
                try {
                    call.respond(HttpStatusCode.InternalServerError)
                } catch (ex: Exception) {
                    AppLogger.e("Proxy:LocalStream", "Failed to send 500 status to client", ex)
                }
            }
        }
    }
}
