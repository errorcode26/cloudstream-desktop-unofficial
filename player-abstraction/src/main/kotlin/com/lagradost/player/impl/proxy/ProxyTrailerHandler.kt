package com.lagradost.player.impl.proxy

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

internal object ProxyTrailerHandler {

    private fun escapeHtmlAttr(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    suspend fun handleTrailer(call: ApplicationCall) {
        val rawId = call.request.queryParameters["id"] ?: ""
        val rawUrl = call.request.queryParameters["u"] ?: ""
        val id = escapeHtmlAttr(rawId)
        val u = escapeHtmlAttr(rawUrl)
        val html = if (rawId.isNotBlank()) {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                    iframe { width: 100%; height: 100%; border: none; background: #000; }
                </style>
            </head>
            <body style="background: #000;">
                <iframe
                    src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=0&playsinline=1&rel=0&modestbranding=1&fs=1"
                    style="background: #000;"
                    allowtransparency="true"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share; fullscreen"
                    allowfullscreen="true"
                    referrerpolicy="strict-origin-when-cross-origin">
                </iframe>
                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        if (window.chrome && window.chrome.webview) {
                            window.chrome.webview.postMessage({ type: 'ui_ready' });
                        }
                    });
                    window.addEventListener('keydown', function(e) {
                        if (e.key === 'Escape') {
                            if (window.chrome && window.chrome.webview) {
                                window.chrome.webview.postMessage({ type: 'close' });
                            }
                        }
                    });
                    document.addEventListener('fullscreenchange', function() {
                        if (window.chrome && window.chrome.webview) {
                            window.chrome.webview.postMessage({ type: 'fullscreen' });
                        }
                    });
                </script>
            </body>
            </html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    html, body { width: 100%; height: 100%; background: #000; overflow: hidden; display: flex; align-items: center; justify-content: center; }
                    video { width: 100%; height: 100%; object-fit: contain; background: #000; }
                </style>
            </head>
            <body style="background: #000;">
                <video src="$u" autoplay controls playsinline style="background: #000;"></video>
                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        if (window.chrome && window.chrome.webview) {
                            window.chrome.webview.postMessage({ type: 'ui_ready' });
                        }
                    });
                    window.addEventListener('keydown', function(e) {
                        if (e.key === 'Escape') {
                            if (window.chrome && window.chrome.webview) {
                                window.chrome.webview.postMessage({ type: 'close' });
                            }
                        }
                    });
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        call.respondText(html, ContentType.Text.Html)
    }
}
