package com.lagradost.runtime.loader

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.dex.DexConverter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginDexTranspilerDexAndLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        System.setProperty("cloudstream.data.dir", tempDir.toFile().absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        ExtensionLoader.unloadAllPlugins()
        APIHolder.allProviders.clear()
    }

    @AfterEach
    fun teardown() {
        ExtensionLoader.unloadAllPlugins()
        APIHolder.allProviders.clear()
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
    }

    @Test
    fun `DexConverter translates minimal valid v035 dex with branches to runnable JVM class`() {
        val dexBytes = MinimalDexFixture.buildSamplePluginDex()
        val outJar = tempDir.resolve("dex-converted.jar").toFile()

        val result = DexConverter.convert(dexBytes, outJar)
        assertTrue(result.isClean(), "Expected clean DEX conversion, errors=${result.errors}")
        assertEquals(1, result.classCount)
        assertTrue(outJar.exists() && outJar.length() > 0)

        URLClassLoader(arrayOf(outJar.toURI().toURL()), this::class.java.classLoader).use { loader ->
            val pluginClass = loader.loadClass("com.example.DexSamplePlugin")
            val instance = pluginClass.getDeclaredConstructor().newInstance()
            assertTrue(instance is BasePlugin)

            val computeMethod = pluginClass.getMethod("compute", Int::class.javaPrimitiveType)
            assertEquals(1, computeMethod.invoke(instance, 0))
            assertEquals(15, computeMethod.invoke(instance, 10))
        }
    }

    @Test
    fun `ExtensionLoader loads dex cs3 and jvm jar caches secure jars rescans and unloads cleanly`() {
        // 1. DEX-based .cs3 plugin
        val cs3File = tempDir.resolve("dex-plugin.cs3").toFile()
        ZipOutputStream(FileOutputStream(cs3File)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                """{"name":"Dex Sample","internalName":"DexSample","pluginClassName":"com.example.DexSamplePlugin","version":1}"""
                    .toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(MinimalDexFixture.buildSamplePluginDex())
            zos.closeEntry()
        }

        val loadedDex = ExtensionLoader.loadAndInit(cs3File)
        assertTrue(ExtensionLoader.isPluginLoaded(cs3File.absolutePath))
        assertEquals(cs3File.absolutePath, loadedDex.filename)
        val cachedFiles = PlatformPaths.pluginCacheDir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(cachedFiles.any { it.contains("dex-plugin") && it.endsWith(".jar") })

        // 2. Directory rescan loads new JVM JAR plugin while skipping already loaded extensions
        val extDir = tempDir.resolve("extensions").toFile().apply { mkdirs() }
        val jvmJar = File(extDir, "jvm-sample.jar")
        buildMinimalJvmPluginJar(jvmJar, "com.example.JvmSamplePlugin")

        val firstScanCount = ExtensionLoader.rescanAndLoadNewPlugins(extDir)
        assertEquals(1, firstScanCount)
        assertTrue(ExtensionLoader.isPluginLoaded(jvmJar.absolutePath))

        val secondScanCount = ExtensionLoader.rescanAndLoadNewPlugins(extDir)
        assertEquals(0, secondScanCount)

        // 3. Unload cleans up tracked state
        ExtensionLoader.unloadPlugin(cs3File.absolutePath)
        assertFalse(ExtensionLoader.isPluginLoaded(cs3File.absolutePath))
        assertTrue(ExtensionLoader.isPluginLoaded(jvmJar.absolutePath))

        ExtensionLoader.unloadAllPlugins()
        assertFalse(ExtensionLoader.isPluginLoaded(jvmJar.absolutePath))
    }

    private fun buildMinimalJvmPluginJar(target: File, dottedClassName: String) {
        val internal = dottedClassName.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "com/lagradost/cloudstream3/plugins/Plugin", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "com/lagradost/cloudstream3/plugins/Plugin", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()

        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"name":"Jvm Sample","internalName":"JvmSample","pluginClassName":"$dottedClassName","version":1}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("$internal.class"))
            zos.write(cw.toByteArray())
            zos.closeEntry()
        }
    }

    /**
     * Synthesizes a valid minimal DEX v035 file defining:
     * `public class com.example.DexSamplePlugin extends com.lagradost.cloudstream3.plugins.Plugin`
     * with `<init>()V`, `compute(I)I` (branched arithmetic), and `load()V`.
     */
    private object MinimalDexFixture {
        fun buildSamplePluginDex(): ByteArray {
            val strings = listOf(
                "<init>",
                "I",
                "II",
                "Lcom/example/DexSamplePlugin;",
                "Lcom/lagradost/cloudstream3/plugins/Plugin;",
                "V",
                "compute",
                "load",
            )
            // Type table (sorted by string index): 0=I(1), 1=DexSamplePlugin(3), 2=Plugin(4), 3=V(5)
            val types = listOf(1, 3, 4, 5)

            // Allocate fixed-size tables immediately after 112-byte header
            val headerSize = 0x70
            val strIdsOff = headerSize
            val typeIdsOff = strIdsOff + strings.size * 4
            val protoIdsOff = typeIdsOff + types.size * 4
            val methodIdsOff = protoIdsOff + 2 * 12
            val classDefsOff = methodIdsOff + 4 * 8
            val dataOff = classDefsOff + 1 * 32

            val data = ByteArrayOutputStream()
            fun align4() {
                while ((dataOff + data.size()) % 4 != 0) data.write(0)
            }
            fun currentOff() = dataOff + data.size()
            fun writeU16(out: ByteArrayOutputStream, v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }
            fun writeU32(out: ByteArrayOutputStream, v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 24) and 0xFF)
            }
            fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
                var v = value
                do {
                    var b = v and 0x7F
                    v = v ushr 7
                    if (v != 0) b = b or 0x80
                    out.write(b)
                } while (v != 0)
            }

            // 1. type_list for (I)I
            align4()
            val typeListIntOff = currentOff()
            writeU32(data, 1)
            writeU16(data, 0) // type 0 = "I"

            // 2. code_items
            fun writeCodeItem(regs: Int, ins: Int, outs: Int, insns: List<Int>): Int {
                align4()
                val off = currentOff()
                writeU16(data, regs)
                writeU16(data, ins)
                writeU16(data, outs)
                writeU16(data, 0) // tries_size
                writeU32(data, 0) // debug_info_off
                writeU32(data, insns.size)
                insns.forEach { writeU16(data, it) }
                return off
            }

            // <init>()V: invoke-direct {p0}, method@3 ; return-void
            val initCodeOff = writeCodeItem(1, 1, 1, listOf(0x1070, 0x0003, 0x0000, 0x000E))
            val firstCodeOff = initCodeOff

            // compute(I)I: p0=v2, p1=v3; const/4 v0,1; if-eqz v3,+5; const/4 v1,5; add-int v0,v3,v1; return v0
            val computeCodeOff = writeCodeItem(
                4,
                2,
                0,
                listOf(0x1012, 0x0338, 0x0005, 0x5112, 0x0090, 0x0103, 0x000F),
            )

            // load()V: return-void
            val loadCodeOff = writeCodeItem(1, 1, 0, listOf(0x000E))

            // 3. string_data_items
            val stringDataOffsets = IntArray(strings.size)
            strings.forEachIndexed { idx, s ->
                stringDataOffsets[idx] = currentOff()
                val utf8 = s.toByteArray(Charsets.UTF_8)
                writeUleb128(data, s.length)
                data.write(utf8)
                data.write(0)
            }

            // 4. class_data_item
            val classDataOff = currentOff()
            writeUleb128(data, 0) // static_fields
            writeUleb128(data, 0) // instance_fields
            writeUleb128(data, 1) // direct_methods
            writeUleb128(data, 2) // virtual_methods
            // direct 0: method 0 (<init>)
            writeUleb128(data, 0)
            writeUleb128(data, 0x10001)
            writeUleb128(data, initCodeOff)
            // virtual 0: method 1 (compute)
            writeUleb128(data, 1)
            writeUleb128(data, 0x1)
            writeUleb128(data, computeCodeOff)
            // virtual 1: method 2 (load) -> delta 1
            writeUleb128(data, 1)
            writeUleb128(data, 0x1)
            writeUleb128(data, loadCodeOff)

            // 5. map_list
            align4()
            val mapOff = currentOff()
            val mapEntries = listOf(
                Triple(0x0000, 1, 0),
                Triple(0x0001, strings.size, strIdsOff),
                Triple(0x0002, types.size, typeIdsOff),
                Triple(0x0003, 2, protoIdsOff),
                Triple(0x0005, 4, methodIdsOff),
                Triple(0x0006, 1, classDefsOff),
                Triple(0x1001, 1, typeListIntOff),
                Triple(0x2001, 3, firstCodeOff),
                Triple(0x2002, strings.size, stringDataOffsets[0]),
                Triple(0x2000, 1, classDataOff),
                Triple(0x1000, 1, mapOff),
            )
            writeU32(data, mapEntries.size)
            mapEntries.forEach { (type, count, offset) ->
                writeU16(data, type)
                writeU16(data, 0)
                writeU32(data, count)
                writeU32(data, offset)
            }

            val dataBytes = data.toByteArray()
            val fileSize = dataOff + dataBytes.size
            val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

            // Header
            buf.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
            buf.putInt(0) // checksum placeholder
            buf.put(ByteArray(20)) // SHA-1 placeholder
            buf.putInt(fileSize)
            buf.putInt(headerSize)
            buf.putInt(0x12345678) // ENDIAN_CONSTANT
            buf.putInt(0) // link_size
            buf.putInt(0) // link_off
            buf.putInt(mapOff)
            buf.putInt(strings.size)
            buf.putInt(strIdsOff)
            buf.putInt(types.size)
            buf.putInt(typeIdsOff)
            buf.putInt(2) // proto_ids_size
            buf.putInt(protoIdsOff)
            buf.putInt(0) // field_ids_size
            buf.putInt(0) // field_ids_off
            buf.putInt(4) // method_ids_size
            buf.putInt(methodIdsOff)
            buf.putInt(1) // class_defs_size
            buf.putInt(classDefsOff)
            buf.putInt(dataBytes.size)
            buf.putInt(dataOff)

            // string_ids
            stringDataOffsets.forEach { buf.putInt(it) }
            // type_ids
            types.forEach { buf.putInt(it) }
            // proto_ids: (I)I then ()V
            buf.putInt(2); buf.putInt(0); buf.putInt(typeListIntOff)
            buf.putInt(5); buf.putInt(3); buf.putInt(0)
            // method_ids
            buf.putShort(1); buf.putShort(1); buf.putInt(0) // DexSamplePlugin.<init>()V
            buf.putShort(1); buf.putShort(0); buf.putInt(6) // DexSamplePlugin.compute(I)I
            buf.putShort(1); buf.putShort(1); buf.putInt(7) // DexSamplePlugin.load()V
            buf.putShort(2); buf.putShort(1); buf.putInt(0) // Plugin.<init>()V
            // class_defs[0]
            buf.putInt(1) // DexSamplePlugin
            buf.putInt(0x1) // PUBLIC
            buf.putInt(2) // Plugin
            buf.putInt(0) // interfaces_off
            buf.putInt(-1) // NO_INDEX source_file
            buf.putInt(0) // annotations_off
            buf.putInt(classDataOff)
            buf.putInt(0) // static_values_off

            buf.put(dataBytes)

            val raw = buf.array()
            val sha1 = MessageDigest.getInstance("SHA-1").apply { update(raw, 32, raw.size - 32) }.digest()
            System.arraycopy(sha1, 0, raw, 12, 20)

            val adler = Adler32().apply { update(raw, 12, raw.size - 12) }.value.toInt()
            ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(adler)
            return raw
        }
    }
}
