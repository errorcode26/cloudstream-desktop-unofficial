package com.lagradost.cloudstream3.network

import com.lagradost.common.logging.AppLogger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

data class HostClearance(
    val host: String,
    val cookies: List<Cookie>,
    val userAgent: String,
    val isTlsBound: Boolean = false,
    val isFailed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

object ClearanceStateManager {
    private const val TAG = "ClearanceStateManager"
    const val PREF_TLS_BOUND_HOSTS = "pref_tls_bound_hosts"

    private val hostStates = ConcurrentHashMap<String, HostClearance>()

    var globalCookieJar: CookieJar? = null
    val isWorker: Boolean get() = System.getProperty("cloudstream.sandbox.worker") == "true"

    @Volatile var lastChallengedHost: String? = null
    @Volatile var lastChallengedUrl: String? = null
    @Volatile var lastChallengeTimestamp: Long = 0L

    fun getClearance(url: HttpUrl): HostClearance? {
        val host = url.host.lowercase()
        hostStates[host]?.let { if (!it.isFailed) return it }

        val parts = host.split(".")
        if (parts.size > 2) {
            for (i in 1 until parts.size - 1) {
                val parent = parts.subList(i, parts.size).joinToString(".")
                val parentClearance = hostStates[parent]
                if (parentClearance != null && !parentClearance.isFailed) {
                    if (parentClearance.cookies.any { it.matches(url) }) {
                        return parentClearance
                    }
                }
            }
        }
        return getClearance(host)
    }

    fun getClearance(host: String): HostClearance? {
        val clean = host.lowercase().trim()
        hostStates[clean]?.let { if (!it.isFailed) return it }
        val suffixMatch = hostStates.entries.firstOrNull { (k, v) ->
            !v.isFailed && (clean.endsWith(".$k") || k.endsWith(".$clean"))
        }?.value
        if (suffixMatch != null) return suffixMatch

        val vaultCookie = com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.getClearanceCookie(clean)
        if (vaultCookie != null) {
            val allVault = com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.getAllUnexpired()
                .filter { clean == it.domain.trimStart('.') || clean.endsWith(".${it.domain.trimStart('.')}") }
            val restored = HostClearance(
                host = clean,
                cookies = allVault.ifEmpty { listOf(vaultCookie) },
                userAgent = com.lagradost.cloudstream3.USER_AGENT,
                isTlsBound = false,
                isFailed = false,
                timestamp = System.currentTimeMillis(),
            )
            hostStates[clean] = restored
            return restored
        }
        return null
    }

    fun getClearanceForAsset(url: HttpUrl, refererUrl: String? = null): Pair<HostClearance, String>? {
        getClearance(url)?.let { return it to it.host }

        if (!refererUrl.isNullOrBlank()) {
            val refHttp = try {
                refererUrl.toHttpUrlOrNull()
            } catch (e: Exception) {
                AppLogger.d("$TAG: Failed to parse referer URL '$refererUrl': ${e.message}")
                null
            }
            if (refHttp != null) {
                getClearance(refHttp)?.let { return it to refHttp.host }
            }
        }

        val hostParts = url.host.lowercase().split(".").filter { it.length >= 4 }
        for (token in hostParts) {
            val match = hostStates.entries.firstOrNull { (k, v) ->
                !v.isFailed && k.contains(token)
            }
            if (match != null) {
                return match.value to match.key
            }
        }

        val allVault = com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.getAllUnexpired()
        for (token in hostParts) {
            val vaultMatch = allVault.firstOrNull { it.domain.contains(token) }
            if (vaultMatch != null) {
                val cleanDomain = vaultMatch.domain.trimStart('.')
                val restored = getClearance(cleanDomain)
                if (restored != null) {
                    return restored to cleanDomain
                }
            }
        }

        return null
    }

    fun getSavedCookies(host: String): Map<String, String> {
        val clearance = getClearance(host)
        return clearance?.cookies?.associate { it.name to it.value } ?: emptyMap()
    }

    fun getSavedUserAgent(host: String): String? {
        return getClearance(host)?.userAgent
    }

    fun isTlsBound(host: String): Boolean {
        val clean = host.lowercase().trim()
        if (hostStates[clean]?.isTlsBound == true) return true
        if (hostStates.entries.any { (k, v) -> (clean == k || clean.endsWith(".$k") || k.endsWith(".$clean")) && v.isTlsBound }) return true
        val saved = try {
            com.lagradost.common.storage.DesktopDataStore.getKey<Set<String>>(PREF_TLS_BOUND_HOSTS)
        } catch (t: Throwable) {
            AppLogger.d("$TAG: Failed to load TLS-bound hosts: ${t.message}")
            null
        }
        if (saved != null && (saved.contains(clean) || saved.any { clean.endsWith(".$it") || it.endsWith(".$clean") })) {
            val current = hostStates[clean]
            if (current != null && !current.isTlsBound) {
                hostStates[clean] = current.copy(isTlsBound = true)
            }
            return true
        }
        return false
    }

    fun markTlsBound(host: String) {
        val clean = host.lowercase().trim()
        val current = hostStates[clean]
        if (current != null) {
            hostStates[clean] = current.copy(isTlsBound = true)
        }
        try {
            val saved = com.lagradost.common.storage.DesktopDataStore.getKey<Set<String>>(PREF_TLS_BOUND_HOSTS).orEmpty().toMutableSet()
            if (saved.add(clean)) {
                com.lagradost.common.storage.DesktopDataStore.setKey(PREF_TLS_BOUND_HOSTS, saved)
            }
        } catch (t: Throwable) {
            AppLogger.w("$TAG: Failed to persist TLS-bound host '$clean': ${t.message}")
        }
    }

    fun getApexDomain(host: String): String {
        val clean = host.lowercase().trim().removePrefix("www.")
        val parts = clean.split(".")
        if (parts.size <= 2) return clean
        return if (parts[parts.size - 2].length <= 3 && parts.last().length <= 3 && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    private fun markSingleFailed(target: String) {
        val existing = hostStates[target]
        if (existing != null) {
            hostStates[target] = existing.copy(isFailed = true)
        } else {
            hostStates[target] = HostClearance(
                host = target,
                cookies = emptyList(),
                userAgent = "",
                isFailed = true,
            )
        }
    }

    fun markFailed(host: String) {
        val clean = host.lowercase().trim()
        val apex = getApexDomain(clean)
        markSingleFailed(clean)
        if (apex.isNotBlank() && apex != clean) {
            markSingleFailed(apex)
        }
    }

    fun isFailed(host: String): Boolean {
        val clean = host.lowercase().trim()
        val apex = getApexDomain(clean)
        if (hostStates[clean]?.isFailed == true || (apex.isNotBlank() && hostStates[apex]?.isFailed == true)) return true
        return hostStates.entries.any { (k, v) ->
            v.isFailed && (clean == k || clean.endsWith(".$k") || k == apex || (apex.isNotBlank() && apex.endsWith(".$k")))
        }
    }

    fun unmarkFailed(host: String) {
        val clean = host.lowercase().trim()
        val apex = getApexDomain(clean)
        hostStates[clean]?.let { if (it.isFailed) hostStates[clean] = it.copy(isFailed = false) }
        if (apex.isNotBlank()) {
            hostStates[apex]?.let { if (it.isFailed) hostStates[apex] = it.copy(isFailed = false) }
        }
        hostStates.entries.filter { (k, v) ->
            (clean == k || clean.endsWith(".$k") || k.endsWith(".$clean") || (apex.isNotBlank() && (k == apex || apex.endsWith(".$k")))) && v.isFailed
        }.forEach { (k, v) -> hostStates[k] = v.copy(isFailed = false) }
    }

    fun unmarkAllFailed() {
        hostStates.entries.forEach { (k, v) ->
            if (v.isFailed) {
                hostStates[k] = v.copy(isFailed = false)
            }
        }
    }

    fun isImageAsset(url: HttpUrl): Boolean {
        val path = url.encodedPath.lowercase()
        return path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".svg") ||
            path.endsWith(".ico") || path.endsWith(".avif")
    }

    fun isStaticAsset(url: HttpUrl): Boolean {
        val path = url.encodedPath.lowercase()
        return isImageAsset(url) || path.endsWith(".mp4") ||
            path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mpd")
    }

    fun parseCookieMap(cookie: String): Map<String, String> {
        return cookie.split(";")
            .mapNotNull { pair ->
                val split = pair.split("=", limit = 2)
                val key = split.getOrNull(0)?.trim().orEmpty()
                val value = split.getOrNull(1)?.trim().orEmpty()
                if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
            }
            .toMap()
    }

    fun saveClearance(
        host: String,
        cookies: Map<String, String>? = null,
        userAgent: String,
        url: HttpUrl? = null,
        okCookies: List<Cookie>? = null,
        isTlsBound: Boolean = false,
    ) {
        val cleanHost = host.lowercase().trim()
        val finalCookies = okCookies ?: cookies?.mapNotNull { (k, v) ->
            try {
                Cookie.Builder().name(k).value(v).domain(cleanHost).path("/").build()
            } catch (e: Exception) {
                AppLogger.d("$TAG: Malformed cookie '$k' for $cleanHost: ${e.message}")
                null
            }
        } ?: emptyList()

        val effectiveTlsBound = isTlsBound || isTlsBound(cleanHost)
        if (effectiveTlsBound) {
            markTlsBound(cleanHost)
        }

        AppLogger.i("$TAG: Registered Cloudflare clearance for $cleanHost (${finalCookies.size} cookies, UA=$userAgent, isTlsBound=$effectiveTlsBound)")
        hostStates[cleanHost] = HostClearance(
            host = cleanHost,
            cookies = finalCookies,
            userAgent = userAgent,
            isTlsBound = effectiveTlsBound,
            isFailed = false,
            timestamp = System.currentTimeMillis(),
        )

        val jar = globalCookieJar as? com.lagradost.cloudstream3.desktop.network.DesktopCookieJar
            ?: com.lagradost.cloudstream3.desktop.network.DesktopCookieJar.activeInstance
        jar?.saveCookies(finalCookies)
        if (!isWorker) {
            com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.saveCookies(finalCookies)
        }
    }

    fun getAllStoredCookies(): Map<String, List<Cookie>> {
        val jar = globalCookieJar as? com.lagradost.cloudstream3.desktop.network.DesktopCookieJar
            ?: com.lagradost.cloudstream3.desktop.network.DesktopCookieJar.activeInstance
        val jarCookies = jar?.getAllStoredCookies()?.toMutableMap() ?: mutableMapOf()

        for ((h, clearance) in hostStates) {
            if (!jarCookies.containsKey(h) && clearance.cookies.isNotEmpty()) {
                jarCookies[h] = clearance.cookies
            }
        }
        if (!isWorker) {
            try {
                val vaultCookies = com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.getAllUnexpired()
                for (cookie in vaultCookies) {
                    val domainKey = cookie.domain.trimStart('.').lowercase()
                    val list = jarCookies.getOrPut(domainKey) { mutableListOf() } as? MutableList<Cookie>
                        ?: jarCookies[domainKey]?.toMutableList()?.also { jarCookies[domainKey] = it }
                    if (list != null && list.none { it.name.equals(cookie.name, ignoreCase = true) }) {
                        list.add(cookie)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w("$TAG: Failed to merge DPAPI vault cookies: ${t.message}")
            }
        }
        return jarCookies
    }

    fun clearClearanceForDomain(domain: String) {
        val clean = domain.lowercase().trimStart('.')
        hostStates.remove(clean)
        hostStates.keys.filter { it.endsWith(".$clean") }.forEach { hostStates.remove(it) }

        val jar = globalCookieJar as? com.lagradost.cloudstream3.desktop.network.DesktopCookieJar
            ?: com.lagradost.cloudstream3.desktop.network.DesktopCookieJar.activeInstance
        jar?.removeCookiesForDomain(clean)
        if (!isWorker) {
            com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.removeForHost(clean)
            if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
                com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.clearBrowserCookies(clean)
            }
        }
        if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
            com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.closeSession(clean)
        }
    }

    fun clearAllClearance() {
        hostStates.clear()
        runCatching {
            com.lagradost.common.storage.DesktopDataStore.removeKey(PREF_TLS_BOUND_HOSTS)
        }
        val jar = globalCookieJar as? com.lagradost.cloudstream3.desktop.network.DesktopCookieJar
            ?: com.lagradost.cloudstream3.desktop.network.DesktopCookieJar.activeInstance
        jar?.removeAll()
        if (!isWorker) {
            com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.clearAll()
            if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
                com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.clearBrowserCookies(null)
            }
        }
        if (com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.isAvailable) {
            com.lagradost.cloudstream3.desktop.network.webview.EmbeddedVerificationState.closeAllSessions()
        }
    }

    fun setHostClearance(host: String, clearance: HostClearance) {
        hostStates[host.lowercase().trim()] = clearance
    }

    fun removeHostClearance(host: String) {
        hostStates.remove(host.lowercase().trim())
    }
}
