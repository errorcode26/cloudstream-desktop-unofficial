package com.lagradost.runtime.loader

import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompatPluginClassLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private var prevDataDir: String? = null
    private lateinit var prevLocale: Locale
    private lateinit var prevTimeZone: TimeZone

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        System.setProperty("cloudstream.data.dir", tempDir.toFile().absolutePath)
        DesktopDataStore.resetForTesting()
        prevLocale = Locale.getDefault()
        prevTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @AfterEach
    fun teardown() {
        Locale.setDefault(prevLocale)
        TimeZone.setDefault(prevTimeZone)
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
    }

    private fun buildPrivacyProbeClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PrivacyProbe", null, "java/lang/Object", null)

        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "getLocale", "()Ljava/util/Locale;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Locale", "getDefault", "()Ljava/util/Locale;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "getTimeZone", "()Ljava/util/TimeZone;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/TimeZone", "getDefault", "()Ljava/util/TimeZone;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "getZoneId", "()Ljava/time/ZoneId;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/ZoneId", "systemDefault", "()Ljava/time/ZoneId;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `spoofs locale timezone and zoneId while serving resources and releasing jar handle`() {
        val jarFile = tempDir.resolve("compat-plugin.jar").toFile()
        ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
            zos.putNextEntry(ZipEntry("test/PrivacyProbe.class"))
            zos.write(buildPrivacyProbeClass())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("assets/plugin.json"))
            zos.write("""{"ok":true}""".toByteArray())
            zos.closeEntry()
        }

        CompatPluginClassLoader(arrayOf(jarFile.toURI().toURL()), this::class.java.classLoader).use { loader ->
            val clazz = loader.loadClass("test.PrivacyProbe")
            val instance = clazz.getDeclaredConstructor().newInstance()

            val locale = clazz.getMethod("getLocale").invoke(instance) as Locale
            val tz = clazz.getMethod("getTimeZone").invoke(instance) as TimeZone
            val zoneId = clazz.getMethod("getZoneId").invoke(instance) as ZoneId

            assertEquals(Locale.US, locale)
            assertEquals("UTC", tz.id)
            assertEquals(ZoneId.of("UTC"), zoneId)

            val resText = loader.getResourceAsStream("assets/plugin.json")?.bufferedReader()?.readText()
            assertEquals("""{"ok":true}""", resText)
        }

        assertTrue(jarFile.delete(), "Expected CompatPluginClassLoader.close() to release Windows file handle")
        assertFalse(jarFile.exists())
    }
}
