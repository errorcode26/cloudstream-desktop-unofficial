package com.lagradost.runtime.loader

import android.content.res.DesktopResourceRegistry
import com.lagradost.cloudstream3.app
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginDiscoveryAndXmlPreferencesTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var originalBaseClient: OkHttpClient

    @BeforeEach
    fun setup() {
        originalBaseClient = app.baseClient
        PluginSettingsSchemaRegistry.schemas.remove("TestXmlPref_")
        PluginSettingsSchemaRegistry.schemas.remove("TestSubDisc_")
    }

    @AfterEach
    fun teardown() {
        app.baseClient = originalBaseClient
        PluginSettingsSchemaRegistry.schemas.remove("TestXmlPref_")
        PluginSettingsSchemaRegistry.schemas.remove("TestSubDisc_")
    }

    @Test
    fun `scanAllXmlPreferences parses values strings arrays and preference schemas`() {
        val jarFile = tempDir.resolve("prefs-plugin.jar").toFile()
        val stringsXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="default_host">https://cdn.example.invalid</string>
                <string-array name="quality_entries">
                    <item>Ultra HD</item>
                    <item>Standard</item>
                </string-array>
                <string-array name="quality_values">
                    <item>2160</item>
                    <item>720</item>
                </string-array>
            </resources>
        """.trimIndent()

        val prefsXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <SwitchPreferenceCompat
                    android:key="enable_hd"
                    android:defaultValue="true" />
                <ListPreference
                    android:key="preferred_quality"
                    android:entries="@array/quality_entries"
                    android:entryValues="@array/quality_values"
                    android:defaultValue="2160" />
                <EditTextPreference
                    android:key="custom_host"
                    android:defaultValue="@string/default_host" />
                <MultiSelectListPreference
                    android:key="enabled_langs"
                    android:entries="English|Japanese"
                    android:entryValues="en|ja" />
            </PreferenceScreen>
        """.trimIndent()

        val layoutXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <CheckBox android:key="show_badges" android:defaultValue="false" />
            </LinearLayout>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
            zos.putNextEntry(ZipEntry("res/values/strings.xml"))
            zos.write(stringsXml.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("res/xml/settings.xml"))
            zos.write(prefsXml.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("res/layout/settings_fragment.xml"))
            zos.write(layoutXml.toByteArray())
            zos.closeEntry()
        }

        PluginXmlPreferenceScanner.scanAllXmlPreferences(jarFile, "TestXmlPref")

        assertEquals("https://cdn.example.invalid", DesktopResourceRegistry.getString("default_host", "TestXmlPref"))
        assertEquals(listOf("Ultra HD", "Standard"), DesktopResourceRegistry.getStringArray("quality_entries", "TestXmlPref"))
        assertNotNull(DesktopResourceRegistry.getLayoutXml("settings_fragment", "TestXmlPref"))

        val schemas = PluginSettingsSchemaRegistry.getSettingsForPlugin("TestXmlPref_")
        val byKey = schemas.associateBy { it.key }

        assertEquals("Boolean", byKey["enable_hd"]?.type)
        assertEquals(true, byKey["enable_hd"]?.defaultValue)

        assertEquals("String", byKey["preferred_quality"]?.type)
        assertEquals("2160", byKey["preferred_quality"]?.defaultValue)
        assertEquals("2160", byKey["preferred_quality"]?.options?.get("Ultra HD"))
        assertEquals("720", byKey["preferred_quality"]?.options?.get("Standard"))

        assertEquals("String", byKey["custom_host"]?.type)
        assertEquals("https://cdn.example.invalid", byKey["custom_host"]?.defaultValue)

        assertEquals("StringSet", byKey["enabled_langs"]?.type)
        assertEquals("en", byKey["enabled_langs"]?.options?.get("English"))
        assertEquals("ja", byKey["enabled_langs"]?.options?.get("Japanese"))

        assertEquals("Boolean", byKey["show_badges"]?.type)
    }

    @Test
    fun `autoDiscoverPluginSubProviders registers disabled_providers schema`() {
        PluginDiscoveryScanner.autoDiscoverPluginSubProviders(
            classLoader = this::class.java.classLoader,
            classNames = setOf(SampleProviderRegistry::class.java.name),
            pluginInternalName = "TestSubDisc",
        )

        val schemas = PluginSettingsSchemaRegistry.getSettingsForPlugin("TestSubDisc_").associateBy { it.key }
        val disabledSchema = schemas["disabled_providers"]
        assertNotNull(disabledSchema)
        assertEquals("StringSet", disabledSchema?.type)
        assertEquals("Alpha Source", disabledSchema?.options?.get("alpha"))
        assertEquals("Beta Source", disabledSchema?.options?.get("beta"))
        assertTrue("alpha" in schemas && "beta" in schemas)
    }

    @Test
    fun `synchronizePluginNetworkClients replaces baseClient unless CloudflareKiller present`() {
        val markerClient = OkHttpClient.Builder().build()
        app.baseClient = markerClient

        val cfClient = OkHttpClient.Builder()
            .addInterceptor(CloudflareKillerStub())
            .build()
        SampleNetworkHolder.plainReq.baseClient = OkHttpClient.Builder().build()
        SampleNetworkHolder.cfReq.baseClient = cfClient

        PluginDiscoveryScanner.synchronizePluginNetworkClients(
            classLoader = URLClassLoader(emptyArray(), this::class.java.classLoader),
            classNames = setOf(SampleNetworkHolder::class.java.name),
        )

        assertSame(markerClient, SampleNetworkHolder.plainReq.baseClient)
        assertSame(cfClient, SampleNetworkHolder.cfReq.baseClient)
    }

    class SubItem(val id: String, val name: String)

    object SampleProviderRegistry {
        @JvmStatic
        fun buildProviders(): List<SubItem> = listOf(
            SubItem("alpha", "Alpha Source"),
            SubItem("beta", "Beta Source"),
        )
    }

    class CloudflareKillerStub : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
    }

    object SampleNetworkHolder {
        val plainReq = com.lagradost.nicehttp.Requests()
        val cfReq = com.lagradost.nicehttp.Requests()
    }
}
