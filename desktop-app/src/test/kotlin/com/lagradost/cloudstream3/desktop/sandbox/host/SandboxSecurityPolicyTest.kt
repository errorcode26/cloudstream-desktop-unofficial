package com.lagradost.cloudstream3.desktop.sandbox.host

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxSecurityPolicyTest {

    @Test
    fun `pruneWorkerClasspath strips compose and ui libraries`() {
        val mockClasspath = listOf(
            "C:/lib/kotlin-stdlib-1.9.22.jar",
            "C:/lib/compose-ui-desktop-1.6.0.jar",
            "C:/lib/skiko-awt-0.7.85.jar",
            "C:/lib/okhttp-4.12.0.jar",
            "C:/lib/haze-desktop-0.5.0.jar",
            "C:/lib/jackson-databind-2.16.1.jar"
        ).joinToString(File.pathSeparator)

        val pruned = SandboxSecurityPolicy.pruneWorkerClasspath(mockClasspath)
        val entries = pruned.split(File.pathSeparator).filter { it.isNotBlank() }

        assertEquals(3, entries.size)
        assertTrue(entries.any { it.contains("kotlin-stdlib") })
        assertTrue(entries.any { it.contains("okhttp") })
        assertTrue(entries.any { it.contains("jackson-databind") })
        assertFalse(entries.any { it.contains("compose") })
        assertFalse(entries.any { it.contains("skiko") })
        assertFalse(entries.any { it.contains("haze") })
    }

    @Test
    fun `isBlockedOrPrivateUrl blocks local and private destinations`() {
        assertTrue(SandboxSecurityPolicy.isBlockedOrPrivateUrl("http://localhost:8080/test"))
        assertTrue(SandboxSecurityPolicy.isBlockedOrPrivateUrl("http://127.0.0.1/test"))
        assertTrue(SandboxSecurityPolicy.isBlockedOrPrivateUrl("http://0.0.0.0/test"))
        assertTrue(SandboxSecurityPolicy.isBlockedOrPrivateUrl("http://192.168.1.100/test"))
        assertTrue(SandboxSecurityPolicy.isBlockedOrPrivateUrl("ftp://example.com/file"))
        assertFalse(SandboxSecurityPolicy.isBlockedOrPrivateUrl("https://cloudflare.com"))
    }
}
