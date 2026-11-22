package com.lagradost.cloudstream3.desktop.settings

import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsStorageEngine
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.StorageCacheType
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStorageEngineTest {

    @Test
    fun testGetDirectorySizeBytes() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cs3_storage_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            assertEquals(0L, SettingsStorageEngine.getDirectorySizeBytes(tempDir))

            val file1 = File(tempDir, "file1.txt")
            file1.writeBytes(ByteArray(512))
            val file2 = File(tempDir, "file2.txt")
            file2.writeBytes(ByteArray(1024))

            assertEquals(1536L, SettingsStorageEngine.getDirectorySizeBytes(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStorageCacheTypeMapping() {
        val types = StorageCacheType.entries
        assertEquals(5, types.size)
        assertTrue(types.contains(StorageCacheType.IMAGE))
        assertTrue(types.contains(StorageCacheType.NETWORK))
        assertTrue(types.contains(StorageCacheType.LOGS))
        assertTrue(types.contains(StorageCacheType.STREAM))
        assertTrue(types.contains(StorageCacheType.UPDATES))
    }
}
