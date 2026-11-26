package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class LoopbackOAuthMode {
    IMPLICIT_TOKEN,
    AUTHORIZATION_CODE,
}

/**
 * Shared localhost loopback listener and PKCE helper for OAuth2 desktop flows
 * (fragment relay for implicit grant and direct `?code=` capture for PKCE code flow).
 */
object TrackerOAuthLoopback {
    private const val TAG = "TrackerOAuthLoopback"
    private const val PKCE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val secureRandom = SecureRandom()

    fun generateCodeVerifier(length: Int = 64): String {
        val safeLen = length.coerceIn(43, 128)
        val sb = StringBuilder(safeLen)
        repeat(safeLen) {
            sb.append(PKCE_CHARSET[secureRandom.nextInt(PKCE_CHARSET.length)])
        }
        return sb.toString()
    }

    fun generateState(length: Int = 24): String = generateCodeVerifier(length.coerceAtLeast(43)).take(length)

    fun sha256Base64Url(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun buildFragmentRelayHtml(serviceName: String, path: String): String = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>$serviceName Authorization</title>
  <style>
    body { font-family: system-ui, sans-serif; background: #0f1419; color: #e6edf3; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
    .card { max-width: 440px; padding: 28px; border-radius: 12px; background: #161b22; border: 1px solid #30363d; text-align: center; }
    h2 { margin: 0 0 10px; font-size: 18px; }
    p { margin: 0; color: #8b949e; font-size: 14px; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <h2 id="title">Finishing $serviceName sign-in...</h2>
    <p id="msg">Returning authorization token to CloudStream Desktop.</p>
  </div>
  <script>
    (function() {
      var hash = window.location.hash ? window.location.hash.substring(1) : "";
      var search = window.location.search ? window.location.search.substring(1) : "";
      var params = hash || search;
      if (!params || params.indexOf("access_token=") === -1) {
        document.getElementById("title").textContent = "Authorization incomplete";
        document.getElementById("msg").textContent = "No access token was included in this redirect. You can close this window.";
        return;
      }
      fetch("$path/callback?" + params, { method: "POST" })
        .then(function(res) {
          if (res.ok) {
            document.getElementById("title").textContent = "$serviceName connected";
            document.getElementById("msg").textContent = "Authorization received. You can close this browser tab and return to CloudStream.";
          } else {
            document.getElementById("title").textContent = "Relay failed";
            document.getElementById("msg").textContent = "Could not deliver token to CloudStream Desktop.";
          }
        })
        .catch(function() {
          document.getElementById("title").textContent = "Connection closed";
          document.getElementById("msg").textContent = "Unable to contact CloudStream Desktop listener.";
        });
    })();
  </script>
</body>
</html>"""

    private fun buildAuthCodeResultHtml(serviceName: String, success: Boolean): String {
        val title = if (success) "$serviceName connected" else "Authorization failed"
        val message = if (success) {
            "Authorization code received. You can close this browser tab and return to CloudStream Desktop."
        } else {
            "Authorization was denied or did not return an authorization code."
        }
        return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>$title</title>
  <style>
    body { font-family: system-ui, sans-serif; background: #0f1419; color: #e6edf3; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
    .card { max-width: 440px; padding: 28px; border-radius: 12px; background: #161b22; border: 1px solid #30363d; text-align: center; }
    h2 { margin: 0 0 10px; font-size: 18px; }
    p { margin: 0; color: #8b949e; font-size: 14px; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <h2>$title</h2>
    <p>$message</p>
  </div>
</body>
</html>"""
    }

    class LoopbackSession internal constructor(
        private val serverSocket: ServerSocket,
        private val redirectResult: CompletableDeferred<String?>,
    ) : AutoCloseable {
        suspend fun awaitRedirectInput(timeoutMs: Long = 180_000L): String? =
            withTimeoutOrNull(timeoutMs) { redirectResult.await() }

        override fun close() {
            if (!redirectResult.isCompleted) {
                redirectResult.complete(null)
            }
            runCatching { serverSocket.close() }
        }
    }

    suspend fun startSession(
        port: Int,
        path: String,
        serviceName: String,
        mode: LoopbackOAuthMode,
    ): LoopbackSession? = withContext(Dispatchers.IO) {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val server = runCatching {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 1000
            }
        }.getOrElse { err ->
            AppLogger.w(TAG, "Unable to bind loopback OAuth port $port: ${err.message}")
            return@withContext null
        }

        val redirectDeferred = CompletableDeferred<String?>()
        val session = LoopbackSession(server, redirectDeferred)

        Thread({
            try {
                while (!server.isClosed && !redirectDeferred.isCompleted) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    socket.use { client ->
                        client.soTimeout = 3000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                        val requestLine = reader.readLine() ?: return@use
                        val target = requestLine.split(" ").getOrNull(1).orEmpty()

                        when (mode) {
                            LoopbackOAuthMode.IMPLICIT_TOKEN -> {
                                if (target.startsWith("$normalizedPath/callback") && target.contains("access_token=")) {
                                    writeHttpResponse(client.getOutputStream(), 200, "text/plain; charset=utf-8", "ok")
                                    redirectDeferred.complete("http://127.0.0.1:$port$target")
                                } else if (target.startsWith(normalizedPath)) {
                                    val html = buildFragmentRelayHtml(serviceName, normalizedPath)
                                    writeHttpResponse(client.getOutputStream(), 200, "text/html; charset=utf-8", html)
                                    if (target.contains("access_token=")) {
                                        redirectDeferred.complete("http://127.0.0.1:$port$target")
                                    }
                                } else {
                                    writeHttpResponse(client.getOutputStream(), 404, "text/plain; charset=utf-8", "Not found")
                                }
                            }
                            LoopbackOAuthMode.AUTHORIZATION_CODE -> {
                                if (target.startsWith(normalizedPath)) {
                                    val hasCode = target.contains("code=") && !target.contains("error=")
                                    val html = buildAuthCodeResultHtml(serviceName, success = hasCode)
                                    writeHttpResponse(client.getOutputStream(), 200, "text/html; charset=utf-8", html)
                                    if (hasCode || target.contains("error=")) {
                                        redirectDeferred.complete("http://127.0.0.1:$port$target")
                                    }
                                } else {
                                    writeHttpResponse(client.getOutputStream(), 404, "text/plain; charset=utf-8", "Not found")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Loopback OAuth listener stopped: ${e.message}")
            } finally {
                runCatching { server.close() }
            }
        }, "tracker-oauth-loopback-$port").apply {
            isDaemon = true
            start()
        }

        session
    }

    private fun writeHttpResponse(
        out: java.io.OutputStream,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Not Found"
        val writer = OutputStreamWriter(out, StandardCharsets.US_ASCII)
        writer.write("HTTP/1.1 $status $reason\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }
}

object AniListOAuthFlow {
    const val LOOPBACK_PORT = 41932
    const val LOOPBACK_PATH = "/anilistlogin"
    const val LOOPBACK_REDIRECT_URI = "http://127.0.0.1:$LOOPBACK_PORT$LOOPBACK_PATH"

    const val MAL_LOOPBACK_PORT = 41933
    const val MAL_LOOPBACK_PATH = "/mallogin"
    const val MAL_LOOPBACK_REDIRECT_URI = "http://127.0.0.1:$MAL_LOOPBACK_PORT$MAL_LOOPBACK_PATH"

    const val SIMKL_LOOPBACK_PORT = 41934
    const val SIMKL_LOOPBACK_PATH = "/simkllogin"
    const val SIMKL_LOOPBACK_REDIRECT_URI = "http://127.0.0.1:$SIMKL_LOOPBACK_PORT$SIMKL_LOOPBACK_PATH"

    suspend fun startLoopbackSession(): TrackerOAuthLoopback.LoopbackSession? =
        TrackerOAuthLoopback.startSession(
            port = LOOPBACK_PORT,
            path = LOOPBACK_PATH,
            serviceName = "AniList",
            mode = LoopbackOAuthMode.AUTHORIZATION_CODE,
        )
}
