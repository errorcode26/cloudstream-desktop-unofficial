@file:Suppress("ktlint:standard:property-naming")

package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.logging.AppLogger
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

interface MpvLibrary : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_free(data: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)

    @Suppress("ktlint:standard:property-naming")
    @Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
    open class MpvEvent(p: Pointer? = null) : Structure(p) {
        @JvmField var event_id: Int = 0

        @JvmField var error: Int = 0

        @JvmField var reply_userdata: Long = 0

        @JvmField var data: Pointer? = null
        init {
            p?.let { read() }
        }
    }

    @Structure.FieldOrder("name", "format", "data")
    open class MpvEventProperty(p: Pointer? = null) : Structure(p) {
        @JvmField var name: String? = null

        @JvmField var format: Int = 0

        @JvmField var data: Pointer? = null
        init {
            p?.let { read() }
        }
    }

    @Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
    open class MpvEventEndFile(p: Pointer? = null) : Structure(p) {
        @JvmField var reason: Int = 0

        @JvmField var error: Int = 0

        @JvmField var playlist_entry_id: Long = 0

        @JvmField var playlist_insert_id: Long = 0

        @JvmField var playlist_insert_num_entries: Int = 0
        init {
            p?.let { read() }
        }
    }

    @Structure.FieldOrder("prefix", "level", "text", "log_level")
    open class MpvEventLogMessage(p: Pointer? = null) : Structure(p) {
        @JvmField var prefix: String? = null

        @JvmField var level: String? = null

        @JvmField var text: String? = null

        @JvmField var log_level: Int = 0
        init {
            p?.let { read() }
        }
    }

    companion object {
        fun getPropertyString(ctx: Pointer, name: String): String? {
            return try {
                val lib = instanceOrNull ?: return null
                val ptr = lib.mpv_get_property_string(ctx, name) ?: return null
                val str = ptr.getString(0)
                lib.mpv_free(ptr)
                str
            } catch (t: Throwable) {
                null
            }
        }

        fun getPropertyDouble(ctx: Pointer, name: String, fallback: Double = -1.0): Double {
            return try {
                val str = getPropertyString(ctx, name) ?: return fallback
                str.replace(",", ".").toDoubleOrNull() ?: fallback
            } catch (t: Throwable) {
                AppLogger.e("DEBUG_MPV: getPropertyDouble failed for $name: ${t.stackTraceToString()}")
                fallback
            }
        }

        val instanceOrNull: MpvLibrary? by lazy {
            val candidateDirs = listOfNotNull(
                java.io.File("appResources/windows/mpv"),
                java.io.File("desktop-app/appResources/windows/mpv"),
                System.getProperty("user.dir")?.let { java.io.File(it, "desktop-app/appResources/windows/mpv") },
                System.getProperty("user.dir")?.let { java.io.File(it, "appResources/windows/mpv") },
                System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "windows/mpv") },
                System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "mpv") },
            ).filter { it.isDirectory }

            val targets = listOf("libmpv-2", "mpv-2", "mpv-1", "mpv", "libmpv", "libmpv.so.1", "libmpv.so.2", "mpv-3.dll")

            // Register search paths with JNA
            for (dir in candidateDirs) {
                for (target in targets) {
                    try {
                        com.sun.jna.NativeLibrary.addSearchPath(target, dir.absolutePath)
                    } catch (_: Throwable) {}
                }
            }

            var loaded: MpvLibrary? = null

            // Direct file probe first
            for (dir in candidateDirs) {
                val directFiles = listOf(
                    java.io.File(dir, "libmpv-2.dll"),
                    java.io.File(dir, "mpv-2.dll"),
                    java.io.File(dir, "mpv-1.dll"),
                    java.io.File(dir, "libmpv.so.2"),
                    java.io.File(dir, "libmpv.dylib"),
                )
                for (f in directFiles) {
                    if (f.isFile && f.exists()) {
                        try {
                            loaded = com.sun.jna.Native.load(f.absolutePath, MpvLibrary::class.java) as MpvLibrary
                            AppLogger.i("Player:MPV", "Successfully loaded native mpv library directly: ${f.absolutePath}")
                            break
                        } catch (e: UnsatisfiedLinkError) {
                            AppLogger.d("Player:MPV", "Direct load failed for ${f.name}: ${e.message}")
                        } catch (e: Throwable) {
                            AppLogger.d("Player:MPV", "Direct load error for ${f.name}: ${e.message}")
                        }
                    }
                }
                if (loaded != null) break
            }

            // Fallback to JNA target name resolution
            if (loaded == null) {
                for (target in targets) {
                    try {
                        loaded = com.sun.jna.Native.load(target, MpvLibrary::class.java) as MpvLibrary
                        AppLogger.i("Player:MPV", "Successfully loaded native mpv library: $target")
                        break
                    } catch (e: UnsatisfiedLinkError) {
                        AppLogger.d("Player:MPV", "Failed to load native mpv library $target: ${e.message}")
                    } catch (e: IllegalArgumentException) {
                        AppLogger.d("Player:MPV", "Illegal arg for native mpv library $target: ${e.message}")
                    } catch (e: Throwable) {
                        AppLogger.d("Player:MPV", "Error loading native mpv library $target: ${e.message}")
                    }
                }
            }
            loaded
        }

        val isAvailable: Boolean
            get() = instanceOrNull != null

        val INSTANCE: MpvLibrary
            get() = instanceOrNull ?: throw RuntimeException("Failed to load native MPV library. Please ensure mpv is installed and in your system PATH.")
    }
}
