package com.lagradost.common.logging

import org.slf4j.LoggerFactory

/**
 * Centralized logging utility for CloudStream Desktop.
 * Wraps SLF4J to provide an API similar to android.util.Log,
 * and simultaneously pipes logs into [LogBuffer] for live Dev Studio LogCat.
 */
object AppLogger {
    private val logger = LoggerFactory.getLogger("CloudStreamDesktop")

    @JvmOverloads
    fun v(tag: String, message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.VERBOSE, tag, message, t)
    }

    @JvmOverloads
    fun v(message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.VERBOSE, null, message, t)
    }

    @JvmOverloads
    fun d(tag: String, message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.DEBUG, tag, message, t)
    }

    @JvmOverloads
    fun d(message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.DEBUG, null, message, t)
    }

    @JvmOverloads
    fun i(tag: String, message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.INFO, tag, message, t)
    }

    @JvmOverloads
    fun i(message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.INFO, null, message, t)
    }

    private fun isIgnorableException(t: Throwable?): Boolean {
        if (t == null) return false
        val name = t::class.qualifiedName ?: ""
        if (name.contains("CancellationException", ignoreCase = true)) return true
        if (name.contains("ForgottenCoroutineScopeException", ignoreCase = true)) return true
        if (t.message?.contains("StandaloneCoroutine was cancelled", ignoreCase = true) == true) return true
        return false
    }

    @JvmOverloads
    fun w(tag: String, message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.WARN, tag, message, t)
    }

    @JvmOverloads
    fun w(message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.WARN, null, message, t)
    }

    @JvmOverloads
    fun e(tag: String, message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.ERROR, tag, message, t)
    }

    @JvmOverloads
    fun e(message: String, t: Throwable? = null) {
        logAndRecord(LogLevel.ERROR, null, message, t)
    }

    private fun logAndRecord(level: LogLevel, tag: String?, message: String, t: Throwable?) {
        if ((level == LogLevel.WARN || level == LogLevel.ERROR) && isIgnorableException(t)) return
        val safeMessage = LogBuffer.sanitize(message)
        val slf4jText = if (tag != null) "[$tag] $safeMessage" else safeMessage
        val rawTrace = t?.let { LogBuffer.rawStackTrace(it) }
        val safeTrace = rawTrace?.let { LogBuffer.sanitize(it) }
        // Omit raw Throwable when its formatted trace needed redaction so SLF4J appenders never write secrets.
        val passThrowable = t != null && safeTrace == rawTrace
        val finalSlf4jText = if (t != null && !passThrowable) "$slf4jText\n$safeTrace" else slf4jText

        when (level) {
            LogLevel.VERBOSE -> if (passThrowable) logger.trace(finalSlf4jText, t) else logger.trace(finalSlf4jText)
            LogLevel.DEBUG -> if (passThrowable) logger.debug(finalSlf4jText, t) else logger.debug(finalSlf4jText)
            LogLevel.INFO -> if (passThrowable) logger.info(finalSlf4jText, t) else logger.info(finalSlf4jText)
            LogLevel.WARN -> if (passThrowable) logger.warn(finalSlf4jText, t) else logger.warn(finalSlf4jText)
            LogLevel.ERROR -> if (passThrowable) logger.error(finalSlf4jText, t) else logger.error(finalSlf4jText)
        }
        LogBuffer.record(level, tag ?: "General", safeMessage, t)
    }
}
