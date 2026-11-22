package com.lagradost.runtime.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class PluginTrustManagerTest {

    @Test
    fun `getPluginAliases generates aliases from file and internal name`() {
        val file = File("extensions/provider-sample.jar")
        val aliases = PluginTrustManager.getPluginAliases(
            jarFile = file,
            internalName = "SampleProvider",
            pluginClassName = "com.sample.provider.SamplePlugin",
            manifestName = "Sample",
        )

        assertTrue(aliases.contains("sampleprovider"))
        assertTrue(aliases.contains("sample"))
        assertTrue(aliases.contains("sampleplugin"))
    }

    @Test
    fun `getPluginAliases strips jvm and secure suffixes`() {
        val file = File("plugins/TestPlugin-jvm.jar")
        val aliases = PluginTrustManager.getPluginAliases(
            jarFile = file,
            internalName = "TestPlugin",
        )

        assertTrue(aliases.contains("testplugin"))
    }
}
