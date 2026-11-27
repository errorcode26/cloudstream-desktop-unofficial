package com.lagradost.cloudstream3.desktop.init

import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CrashRecoveryManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        System.setProperty("cloudstream.data.dir", tempDir.toFile().absolutePath)
        CrashRecoveryManager.resetForTesting()
    }

    @AfterEach
    fun teardown() {
        CrashRecoveryManager.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
    }

    @Test
    fun `fresh session creates session lock and clean shutdown removes it`() {
        val lockFile = File(PlatformPaths.appDataDir, ".session_active")
        assertFalse(lockFile.exists())

        CrashRecoveryManager.checkPreviousSession()
        assertFalse(CrashRecoveryManager.hasCrashDetected)
        assertTrue(lockFile.exists())
        assertEqualsCurrentPid(lockFile)

        CrashRecoveryManager.markCleanShutdown()
        assertFalse(lockFile.exists())
    }

    @Test
    fun `dead PID with fatal crash log triggers crash recovery once until dismissed`() {
        val lockFile = File(PlatformPaths.appDataDir, ".session_active")
        val crashLog = File(PlatformPaths.appDataDir, "crash.log")
        lockFile.writeText("99999999")
        crashLog.writeText("FATAL: Unhandled exception\n")

        CrashRecoveryManager.checkPreviousSession()
        assertTrue(CrashRecoveryManager.hasCrashDetected)

        CrashRecoveryManager.dismiss()
        assertFalse(CrashRecoveryManager.hasCrashDetected)
        assertTrue(File(PlatformPaths.appDataDir, ".last_acknowledged_crash").exists())

        // Clean shutdown removes lock file; stale crash.log alone does not re-trigger
        CrashRecoveryManager.markCleanShutdown()
        CrashRecoveryManager.checkPreviousSession()
        assertFalse(CrashRecoveryManager.hasCrashDetected)
    }

    @Test
    fun `live PID lock file is treated as concurrent session without false crash`() {
        val lockFile = File(PlatformPaths.appDataDir, ".session_active")
        val crashLog = File(PlatformPaths.appDataDir, "crash.log")
        lockFile.writeText(ProcessHandle.current().pid().toString())
        crashLog.writeText("FATAL: Old exception\n")

        CrashRecoveryManager.checkPreviousSession()
        assertFalse(CrashRecoveryManager.hasCrashDetected)
    }

    private fun assertEqualsCurrentPid(lockFile: File) {
        val stored = lockFile.readText().trim().toLongOrNull()
        assertTrue(stored == ProcessHandle.current().pid())
    }
}
