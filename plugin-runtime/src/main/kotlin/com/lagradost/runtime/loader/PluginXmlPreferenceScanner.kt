package com.lagradost.runtime.loader

import android.content.res.DesktopResourceRegistry
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import com.lagradost.runtime.loader.utils.PluginSettingsScanner
import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object PluginXmlPreferenceScanner {

    @JvmStatic
    fun parsePluginPreferences(fragment: Any, resId: Int) {
        try {
            val classLoader = fragment.javaClass.classLoader
            val jarFile = ExtensionLoader.classLoaderToJar[classLoader] ?: return
            val pluginPrefName = ExtensionLoader.classLoaders[classLoader] ?: return

            scanAllXmlPreferences(jarFile, pluginPrefName)
        } catch (e: Exception) {
            AppLogger.e("Failed to parse plugin preferences", e)
        }
    }

    @JvmStatic
    fun scanAllXmlPreferences(jarFile: File, pluginPrefName: String) {
        val finalPrefName = pluginPrefName + "_"
        try {
            val jvmJar = File(jarFile.parentFile, jarFile.nameWithoutExtension.removeSuffix("-jvm") + "-jvm.jar")
            val scanTarget = if (jvmJar.exists()) jvmJar else jarFile
            PluginSettingsScanner.scanJarForSettings(pluginPrefName, scanTarget)
            AppLogger.i("Scanning all XML preferences for $finalPrefName from ${scanTarget.absolutePath}")

            var apkFileLazy: ApkFile? = null
            try {
                ZipFile(jarFile).use { zip ->
                    val allEntries = zip.entries().toList()

                    // 1. First scan all res/values/*.xml (arrays.xml, strings.xml, values.xml)
                    val valueEntries = allEntries.filter {
                        it.name.startsWith("res/values") && it.name.endsWith(".xml")
                    }
                    for (entry in valueEntries) {
                        try {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            var xmlString = String(bytes, Charsets.UTF_8)
                            if (!xmlString.trimStart().startsWith("<")) {
                                if (apkFileLazy == null) {
                                    try {
                                        apkFileLazy = ApkFile(jarFile)
                                    } catch (e: Exception) {
                                        AppLogger.d("[PluginLoader] Failed to init ApkFile for value XML in ${jarFile.name}: ${e.message}")
                                    }
                                }
                                if (apkFileLazy != null) {
                                    xmlString = apkFileLazy.transBinaryXml(entry.name) ?: ""
                                }
                            }
                            if (xmlString.isNotEmpty()) {
                                parseAndRegisterValueResources(xmlString, pluginPrefName)
                            }
                        } catch (e: Exception) {
                            AppLogger.d("Failed to parse value XML ${entry.name}: ${e.message}")
                        }
                    }

                    // 2. Scan preferences and layout XMLs
                    val xmlEntries = allEntries.filter {
                        (it.name.startsWith("res/xml") || it.name.startsWith("res/layout")) && it.name.endsWith(".xml")
                    }
                    for (entry in xmlEntries) {
                        val path = entry.name
                        AppLogger.i("Found XML path: $path")

                        try {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            var xmlString = String(bytes, Charsets.UTF_8)

                            if (!xmlString.trimStart().startsWith("<")) {
                                if (apkFileLazy == null) {
                                    try {
                                        apkFileLazy = ApkFile(jarFile)
                                    } catch (e: Exception) {
                                        AppLogger.i("Failed to init ApkFile for binary XML decoding: ${e.message}")
                                    }
                                }
                                if (apkFileLazy != null) {
                                    xmlString = apkFileLazy.transBinaryXml(path) ?: ""
                                }
                            }

                            if (xmlString.isNullOrEmpty()) continue

                            val layoutName = path.substringAfterLast('/').substringBeforeLast('.')
                            if (path.startsWith("res/layout")) {
                                DesktopResourceRegistry.registerPluginLayout(pluginPrefName, 0, layoutName, xmlString)
                                DesktopResourceRegistry.registerLayout(0, "${finalPrefName}$layoutName", xmlString)
                            } else if (path.startsWith("res/xml")) {
                                DesktopResourceRegistry.registerPluginXml(pluginPrefName, 0, layoutName, xmlString)
                                DesktopResourceRegistry.registerXml(0, "${finalPrefName}$layoutName", xmlString)
                            }

                            val factory = DocumentBuilderFactory.newInstance()
                            val builder = factory.newDocumentBuilder()
                            val document = builder.parse(InputSource(StringReader(xmlString)))

                            val nodeList = document.getElementsByTagName("*")
                            for (i in 0 until nodeList.length) {
                                val node = nodeList.item(i)
                                if (node.nodeType == Node.ELEMENT_NODE) {
                                    val element = node as Element
                                    val key = element.getAttribute("android:key")
                                    if (key.isNotEmpty()) {
                                        val defValueStr = element.getAttribute("android:defaultValue")
                                        var type = "String"
                                        var defValue: Any = defValueStr
                                        var optionsMap: Map<String, String>? = null

                                        if (defValueStr.startsWith("@string/")) {
                                            val resolved = DesktopResourceRegistry.getString(defValueStr, pluginPrefName)
                                            if (resolved != null) defValue = resolved
                                        }

                                        when (element.tagName) {
                                            "CheckBoxPreference", "SwitchPreference", "SwitchPreferenceCompat" -> {
                                                type = "Boolean"
                                                defValue = defValueStr.equals("true", ignoreCase = true)
                                            }
                                            "ListPreference", "DropDownPreference" -> {
                                                type = "String"
                                                val entriesStr = element.getAttribute("android:entries")
                                                val valuesStr = element.getAttribute("android:entryValues")

                                                val entriesList = if (entriesStr.startsWith("@")) {
                                                    DesktopResourceRegistry.getStringArray(entriesStr, pluginPrefName)
                                                } else if (entriesStr.isNotBlank()) {
                                                    entriesStr.split("|", ",").map { it.trim() }
                                                } else {
                                                    null
                                                }

                                                val valuesList = if (valuesStr.startsWith("@")) {
                                                    DesktopResourceRegistry.getStringArray(valuesStr, pluginPrefName)
                                                } else if (valuesStr.isNotBlank()) {
                                                    valuesStr.split("|", ",").map { it.trim() }
                                                } else {
                                                    null
                                                }

                                                if (!entriesList.isNullOrEmpty() && !valuesList.isNullOrEmpty() && entriesList.size == valuesList.size) {
                                                    optionsMap = entriesList.zip(valuesList).toMap()
                                                }
                                            }
                                            "MultiSelectListPreference" -> {
                                                type = "StringSet"
                                                val entriesStr = element.getAttribute("android:entries")
                                                val valuesStr = element.getAttribute("android:entryValues")

                                                val entriesList = if (entriesStr.startsWith("@")) {
                                                    DesktopResourceRegistry.getStringArray(entriesStr, pluginPrefName)
                                                } else if (entriesStr.isNotBlank()) {
                                                    entriesStr.split("|", ",").map { it.trim() }
                                                } else {
                                                    null
                                                }

                                                val valuesList = if (valuesStr.startsWith("@")) {
                                                    DesktopResourceRegistry.getStringArray(valuesStr, pluginPrefName)
                                                } else if (valuesStr.isNotBlank()) {
                                                    valuesStr.split("|", ",").map { it.trim() }
                                                } else {
                                                    null
                                                }

                                                if (!entriesList.isNullOrEmpty() && !valuesList.isNullOrEmpty() && entriesList.size == valuesList.size) {
                                                    optionsMap = entriesList.zip(valuesList).toMap()
                                                }
                                            }
                                            "SeekBarPreference" -> {
                                                type = "Int"
                                                defValue = defValueStr.toIntOrNull() ?: 0
                                            }
                                            "EditTextPreference" -> {
                                                type = "String"
                                            }
                                            else -> {
                                                if (defValueStr.equals("true", ignoreCase = true) || defValueStr.equals("false", ignoreCase = true)) {
                                                    type = "Boolean"
                                                    defValue = defValueStr.equals("true", ignoreCase = true)
                                                } else if (defValueStr.toIntOrNull() != null) {
                                                    type = "Int"
                                                    defValue = defValueStr.toInt()
                                                }
                                            }
                                        }
                                        PluginSettingsSchemaRegistry.register(
                                            finalPrefName,
                                            key,
                                            type,
                                            defValue,
                                            false,
                                            optionsMap,
                                        )
                                        AppLogger.i("Registered XML plugin setting: $finalPrefName -> $key ($type = $defValue)")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.e("Failed to parse XML path $path", e)
                        }
                    }
                }
            } finally {
                try {
                    apkFileLazy?.close()
                } catch (t: Throwable) {
                    AppLogger.d("[PluginLoader] Error closing Closeable resource: ${t.message}")
                }
            }

            val cl = ExtensionLoader.classLoaderToJar.entries.find {
                it.value.nameWithoutExtension.substringBefore("-jvm") == jarFile.nameWithoutExtension.substringBefore("-jvm")
            }?.key
            if (cl != null) {
                val classNames = ExtensionLoader.classLoaderToClassNames[cl] ?: emptySet()
                for (cn in classNames) {
                    if (cn.endsWith("\$layout") || cn.endsWith(".R\$layout")) {
                        val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                        for (field in clazz.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                                val name = field.name
                                val xml = DesktopResourceRegistry.getLayoutXml(name, pluginPrefName)
                                if (xml != null) {
                                    DesktopResourceRegistry.registerPluginLayout(pluginPrefName, id, name, xml)
                                }
                            }
                        }
                    } else if (cn.endsWith("\$id") || cn.endsWith(".R\$id")) {
                        val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                        for (field in clazz.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                                DesktopResourceRegistry.registerId(field.name, id)
                            }
                        }
                    } else if (cn.endsWith("\$xml") || cn.endsWith(".R\$xml")) {
                        val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                        for (field in clazz.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                                val name = field.name
                                val xml = DesktopResourceRegistry.getLayoutXml(name, pluginPrefName)
                                if (xml != null) {
                                    DesktopResourceRegistry.registerPluginXml(pluginPrefName, id, name, xml)
                                }
                            }
                        }
                    } else if (cn.endsWith("\$array") || cn.endsWith(".R\$array")) {
                        val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                        for (field in clazz.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                                DesktopResourceRegistry.registerArrayId(field.name, id)
                            }
                        }
                    } else if (cn.endsWith("\$string") || cn.endsWith(".R\$string")) {
                        val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                        for (field in clazz.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                                DesktopResourceRegistry.registerStringId(field.name, id)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to parse plugin preferences", e)
        }
    }

    private fun parseAndRegisterValueResources(xmlString: String, pluginPrefName: String) {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(xmlString)))

            // Parse <string-array name="...">
            val arrayNodes = document.getElementsByTagName("string-array")
            for (i in 0 until arrayNodes.length) {
                val arrayElem = arrayNodes.item(i) as? Element ?: continue
                val name = arrayElem.getAttribute("name")
                if (name.isNotEmpty()) {
                    val items = mutableListOf<String>()
                    val itemNodes = arrayElem.getElementsByTagName("item")
                    for (j in 0 until itemNodes.length) {
                        val itemElem = itemNodes.item(j)
                        items.add(itemElem.textContent?.trim() ?: "")
                    }
                    DesktopResourceRegistry.registerPluginStringArray(pluginPrefName, 0, name, items)
                    AppLogger.d("Registered plugin string-array: $name (${items.size} items)")
                }
            }

            // Parse <string name="...">
            val stringNodes = document.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val strElem = stringNodes.item(i) as? Element ?: continue
                val name = strElem.getAttribute("name")
                if (name.isNotEmpty()) {
                    val value = strElem.textContent?.trim() ?: ""
                    DesktopResourceRegistry.registerPluginString(pluginPrefName, 0, name, value)
                }
            }
        } catch (e: Exception) {
            AppLogger.d("[PluginLoader] Failed to parse value resources for $pluginPrefName: ${e.message}")
        }
    }
}
