package com.lagradost.runtime.loader

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PluginBytecodeTransformerTest {

    @TempDir
    lateinit var tempDir: Path

    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        System.setProperty("cloudstream.data.dir", tempDir.toFile().absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
    }

    @AfterEach
    fun teardown() {
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
    }

    private fun buildSyntheticProbeClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "test/StubProbePlugin",
            null,
            "java/lang/Object",
            null,
        )

        // Missing Android field/method to verify android/* stripping
        cw.visitField(Opcodes.ACC_PRIVATE, "androidView", "Landroid/view/View;", null, null).visitEnd()

        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeUnresolvedAndroid", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "android/app/NonExistentAndroidApi", "getToken", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeAndroidUserAgent", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "android/webkit/WebSettings", "getDefaultUserAgent", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeExit", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeEnv", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("PATH")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeExec", "()Ljava/lang/Process;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
            visitLdcInsn("whoami")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probePath", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ICONST_0)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/Paths", "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;", false)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "toString", "()Ljava/lang/String;", true)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeFile", "()Ljava/io/File;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/io/File")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("sandboxed.txt")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeFileUrl", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/net/URL")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("file:///etc/passwd")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }

        cw.visitMethod(Opcodes.ACC_PUBLIC, "probeReflection", "(Ljava/lang/reflect/Method;)Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ICONST_0)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 2)
            visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `transform rewrites security stubs strips android refs and executes safely`() {
        val jarFile = tempDir.resolve("probe.jar").toFile()
        writeJar(
            jarFile,
            mapOf(
                "test/StubProbePlugin.class" to buildSyntheticProbeClass(),
                "assets/info.txt" to "hello".toByteArray(),
            ),
        )

        PluginBytecodeTransformer.transform(jarFile)

        // Version stamp + passthrough resource verification
        ZipFile(jarFile).use { zip ->
            val versionEntry = zip.getEntry("META-INF/shadowui.version")
            assertEquals("3", zip.getInputStream(versionEntry).bufferedReader().readText())
            assertEquals("hello", zip.getInputStream(zip.getEntry("assets/info.txt")).bufferedReader().readText())

            val transformedBytes = zip.getInputStream(zip.getEntry("test/StubProbePlugin.class")).readBytes()
            val node = ClassNode()
            ClassReader(transformedBytes).accept(node, 0)

            // Unresolved android/* instruction stripped to safe null push
            val unresolvedMethod = node.methods.first { it.name == "probeUnresolvedAndroid" }
            val unresolvedCalls = unresolvedMethod.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(unresolvedCalls.none { it.owner.startsWith("android/") })

            val ownersCalled = node.methods.flatMap { m ->
                m.instructions.toArray().filterIsInstance<MethodInsnNode>().map { it.owner }
            }.toSet()

            assertTrue("com/lagradost/runtime/loader/stubs/SystemStub" in ownersCalled)
            assertTrue("com/lagradost/runtime/loader/stubs/RuntimeStub" in ownersCalled)
            assertTrue("com/lagradost/runtime/loader/stubs/PluginFileSecurityStub" in ownersCalled)
            assertTrue("com/lagradost/runtime/loader/stubs/URLStub" in ownersCalled)
            assertTrue("com/lagradost/runtime/loader/stubs/ReflectionStub" in ownersCalled)
        }

        URLClassLoader(arrayOf(jarFile.toURI().toURL()), this::class.java.classLoader).use { loader ->
            val clazz = loader.loadClass("test.StubProbePlugin")
            val instance = clazz.getDeclaredConstructor().newInstance()

            // Blocked System.exit, Runtime.exec & stripped Android call return safely
            clazz.getMethod("probeExit").invoke(instance)
            assertNull(clazz.getMethod("probeEnv").invoke(instance))
            assertNull(clazz.getMethod("probeExec").invoke(instance))
            assertEquals("", clazz.getMethod("probeUnresolvedAndroid").invoke(instance))
            val ua = clazz.getMethod("probeAndroidUserAgent").invoke(instance) as String
            assertTrue(ua.contains("Mozilla/5.0"))

            // Paths.get outside plugin root is blocked by PluginFileSecurityStub jail; relative File("sandboxed.txt") redirects into Extensions storage
            val outsidePath = tempDir.root.resolve("outside-secret.txt").toString()
            val pathEx = assertThrows(InvocationTargetException::class.java) {
                clazz.getMethod("probePath", String::class.java).invoke(instance, outsidePath)
            }
            assertTrue(pathEx.cause is SecurityException)

            val redirectedFile = clazz.getMethod("probeFile").invoke(instance) as File
            val extensionsRoot = File(PlatformPaths.appDataDir, "Extensions").canonicalPath
            assertTrue(
                redirectedFile.canonicalPath.startsWith(extensionsRoot),
                "Expected file under $extensionsRoot, got ${redirectedFile.canonicalPath}",
            )

            // file:// URL blocked by URLStub
            val urlEx = assertThrows(InvocationTargetException::class.java) {
                clazz.getMethod("probeFileUrl").invoke(instance)
            }
            assertTrue(urlEx.cause is SecurityException)

            // Reflection invoke on java.lang.Runtime blocked by ReflectionStub
            val getRuntimeMethod = Runtime::class.java.getMethod("getRuntime")
            val reflEx = assertThrows(InvocationTargetException::class.java) {
                clazz.getMethod("probeReflection", java.lang.reflect.Method::class.java).invoke(instance, getRuntimeMethod)
            }
            assertTrue(reflEx.cause is SecurityException)
        }

        // Second pass skips redundant transform when version stamp matches
        PluginBytecodeTransformer.transform(jarFile)
        assertTrue(PluginDexTranspiler.checkJarHasTransformerVersion(jarFile))
    }

    @Test
    fun `transform cleans up temp jar cleanly when class bytecode is corrupt`() {
        val jarFile = tempDir.resolve("corrupt.jar").toFile()
        val garbageClass = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x01, 0x02))
        }.toByteArray()
        writeJar(jarFile, mapOf("broken/Bad.class" to garbageClass))

        assertThrows(SecurityException::class.java) {
            PluginBytecodeTransformer.transform(jarFile)
        }

        // Verify no leaked .tmp files remain locked in tempDir
        val leftoverTmps = tempDir.toFile().listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftoverTmps.isEmpty(), "Expected no leftover .tmp files, found $leftoverTmps")
        assertTrue(jarFile.exists())
    }
}
