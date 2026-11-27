package com.lagradost.common.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.SQLException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

object DatabaseFactory {
    const val CURRENT_SCHEMA_VERSION: Long = 2L
    private const val MAX_PRE_UPGRADE_SNAPSHOTS = 5
    private const val LOCK_RETRY_ATTEMPTS = 3

    @Volatile
    private var instance: DesktopDatabase? = null
    private var activeDriver: JdbcSqliteDriver? = null
    private val lock = Any()

    @Volatile
    var lastQuarantinedBackupFile: File? = null
        private set

    @Volatile
    internal var busyTimeoutMs: Long = 30_000L

    val database: DesktopDatabase
        get() {
            instance?.let { return it }
            synchronized(lock) {
                instance?.let { return it }
                val db = initDatabaseWithRecovery()
                instance = db
                return db
            }
        }

    fun isInitialized(): Boolean = instance != null

    fun clearLastQuarantinedBackupNotice() {
        lastQuarantinedBackupFile = null
    }

    fun getUpgradeSnapshotsDir(): File =
        File(PlatformPaths.backupsDir, "db_upgrades").also { it.mkdirs() }

    private fun initDatabaseWithRecovery(): DesktopDatabase {
        if (System.getProperty("cloudstream.sandbox.worker") == "true") {
            throw IllegalStateException("Direct SQLite access is strictly forbidden in sandboxed worker mode.")
        }

        val dir = PlatformPaths.dataDir
        if (!dir.exists()) dir.mkdirs()
        val dbFile = File(dir, "cloudstream.db")
        dbFile.parentFile?.mkdirs()

        var lastLockError: Throwable? = null
        for (attempt in 0 until LOCK_RETRY_ATTEMPTS) {
            try {
                return createAndMigrateDatabase(dbFile)
            } catch (t: Throwable) {
                if (isTransientLockError(t)) {
                    lastLockError = t
                    AppLogger.w("DatabaseFactory: Transient SQLite lock on attempt ${attempt + 1}: ${t.message}")
                    if (attempt < LOCK_RETRY_ATTEMPTS - 1) {
                        Thread.sleep(150L * (attempt + 1))
                        continue
                    }
                    // Never quarantine a locked database as corrupt; fail closed so caller keeps original file intact.
                    throw IllegalStateException("Database temporarily locked by another process: ${t.message}", t)
                }
                AppLogger.e("DatabaseFactory: SQLite database corrupted or migration failed. Quarantining and rebuilding...", t)
                return quarantineAndRebuildDatabase(dbFile)
            }
        }
        throw IllegalStateException("Database initialization exhausted lock retries", lastLockError)
    }

    private fun isTransientLockError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("sqlite_busy") ||
                msg.contains("sqlite_locked") ||
                msg.contains("database is locked") ||
                msg.contains("database table is locked") ||
                msg.contains("used by another process")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun createAndMigrateDatabase(dbFile: File): DesktopDatabase {
        val hadExistingContent = dbFile.exists() && dbFile.length() > 0L
        val timeoutStr = busyTimeoutMs.coerceAtLeast(10L).toString()
        val properties = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", timeoutStr)
            setProperty("synchronous", "NORMAL")
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", properties)

        try {
            try {
                driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
                driver.execute(null, "PRAGMA busy_timeout = $timeoutStr;", 0)
                driver.execute(null, "PRAGMA synchronous = NORMAL;", 0)
            } catch (e: Exception) {
                AppLogger.w("DatabaseFactory: Failed to apply performance PRAGMAs: ${e.message}")
            }

            if (hadExistingContent) {
                verifyDatabaseIntegrity(driver, dbFile.name)
            }

            val currentVersion = queryUserVersion(driver)
            if (hadExistingContent && currentVersion < CURRENT_SCHEMA_VERSION) {
                takePreUpgradeSnapshot(driver, currentVersion)
            }

            if (currentVersion < CURRENT_SCHEMA_VERSION || !hadExistingContent) {
                runSchemaMigrationTransaction(driver)
            }

            activeDriver = driver
            return DesktopDatabase(driver)
        } catch (t: Throwable) {
            try {
                driver.close()
            } catch (closeErr: Throwable) {
                AppLogger.d("DatabaseFactory: Error closing driver after initialization failure: ${closeErr.message}")
            }
            throw t
        }
    }

    private fun verifyDatabaseIntegrity(driver: JdbcSqliteDriver, dbName: String) {
        val integrityResult = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA quick_check(1);",
            mapper = { cursor ->
                val row = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(row)
            },
            parameters = 0,
        ).value
        if (!integrityResult.equals("ok", ignoreCase = true)) {
            throw SQLException("SQLite quick_check reported corruption: $integrityResult on $dbName")
        }
    }

    private fun queryUserVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                val value = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value

    private fun hasColumn(driver: JdbcSqliteDriver, table: String, column: String): Boolean {
        return driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table);",
            mapper = { cursor ->
                var found = false
                while (cursor.next().value) {
                    if (column.equals(cursor.getString(1), ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                QueryResult.Value(found)
            },
            parameters = 0,
        ).value
    }

    private fun ensureColumn(driver: JdbcSqliteDriver, table: String, column: String, ddlTypeAndDefault: String) {
        if (!hasColumn(driver, table, column)) {
            driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $ddlTypeAndDefault;", 0)
        }
    }

    private fun runSchemaMigrationTransaction(driver: JdbcSqliteDriver) {
        DesktopDatabase(driver).transaction {
            driver.execute(null, "DROP TABLE IF EXISTS PluginUpdates;", 0)
            DesktopDatabase.Schema.create(driver)

            ensureColumn(driver, "Bookmarks", "watchType", "INTEGER DEFAULT 0")
            ensureColumn(driver, "Bookmarks", "dateAdded", "INTEGER DEFAULT 0")
            ensureColumn(driver, "Bookmarks", "profileId", "INTEGER DEFAULT 0")

            ensureColumn(driver, "WatchHistory", "episodeThumbnailUrl", "TEXT")
            ensureColumn(driver, "WatchHistory", "screenshotUrl", "TEXT")
            ensureColumn(driver, "WatchHistory", "episodeName", "TEXT")
            ensureColumn(driver, "WatchHistory", "episodeDescription", "TEXT")
            ensureColumn(driver, "WatchHistory", "profileId", "INTEGER DEFAULT 0")

            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_bookmarks_profile ON Bookmarks(profileId);", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_watchhistory_profile ON WatchHistory(profileId);", 0)
            driver.execute(null, "UPDATE Bookmarks SET profileId = CAST(SUBSTR(id, 2, INSTR(id, '_') - 2) AS INTEGER) WHERE id GLOB 'p[0-9]*_*' AND profileId = 0;", 0)
            driver.execute(null, "UPDATE WatchHistory SET profileId = CAST(SUBSTR(parentId, 2, INSTR(parentId, '_') - 2) AS INTEGER) WHERE parentId GLOB 'p[0-9]*_*' AND profileId = 0;", 0)

            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_tracker_audit_lookup ON TrackerSyncAudit(profileId, trackerId, timestamp);", 0)
            driver.execute(null, "PRAGMA user_version = $CURRENT_SCHEMA_VERSION;", 0)
        }
    }

    private fun takePreUpgradeSnapshot(driver: JdbcSqliteDriver, fromVersion: Long) {
        try {
            val dir = getUpgradeSnapshotsDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(Date())
            val snapshotFile = File(dir, "cloudstream.db.v${fromVersion}_pre_${stamp}.db")
            writeSnapshotWithDriver(driver, snapshotFile)
            pruneOldFiles(dir, prefix = "cloudstream.db.", maxKeep = MAX_PRE_UPGRADE_SNAPSHOTS)
            AppLogger.i("DatabaseFactory: Saved pre-migration DB snapshot '${snapshotFile.name}'")
        } catch (t: Throwable) {
            AppLogger.w("DatabaseFactory: Pre-upgrade snapshot failed (${t.message}); continuing migration")
        }
    }

    private fun writeSnapshotWithDriver(driver: JdbcSqliteDriver, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        try {
            val escaped = tmpFile.absolutePath.replace("'", "''")
            driver.execute(null, "VACUUM INTO '$escaped';", 0)
            moveReplacing(tmpFile, targetFile)
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pruneOldFiles(dir: File, prefix: String, maxKeep: Int) {
        val candidates = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        candidates.drop(maxKeep).forEach { old ->
            try {
                old.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun createConsistentSnapshot(targetFile: File) {
        synchronized(lock) {
            val driver = activeDriver
            if (driver != null) {
                writeSnapshotWithDriver(driver, targetFile)
                return
            }
            val dbFile = File(PlatformPaths.dataDir, "cloudstream.db")
            if (!dbFile.exists() || dbFile.length() == 0L) {
                throw IllegalStateException("No database file found at ${dbFile.absolutePath}")
            }
            val tempDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            try {
                writeSnapshotWithDriver(tempDriver, targetFile)
            } finally {
                try {
                    tempDriver.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun closeForRestore() {
        resetForTesting()
    }

    fun reopenAfterRestore(): DesktopDatabase = database

    fun vacuumDatabase() {
        synchronized(lock) {
            val driver = activeDriver
            if (driver != null) {
                driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0)
                driver.execute(null, "VACUUM;", 0)
                return
            }
            val dbFile = File(PlatformPaths.dataDir, "cloudstream.db")
            if (!dbFile.exists() || dbFile.length() == 0L) return
            val tempDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            try {
                tempDriver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0)
                tempDriver.execute(null, "VACUUM;", 0)
            } finally {
                try {
                    tempDriver.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun clearAllKeyValues() {
        synchronized(lock) {
            val queries = database.cloudstreamDBQueries
            queries.transaction {
                queries.deleteAllKeyValues()
            }
        }
    }

    private fun closeActiveDriver() {
        try {
            activeDriver?.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0)
        } catch (_: Throwable) {
        }
        try {
            activeDriver?.close()
        } catch (t: Throwable) {
            AppLogger.d("DatabaseFactory: Error closing active SQLite driver: ${t.message}")
        } finally {
            activeDriver = null
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            closeActiveDriver()
            instance = null
            busyTimeoutMs = 30_000L
        }
    }

    private fun quarantineAndRebuildDatabase(dbFile: File): DesktopDatabase {
        closeActiveDriver()
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val corruptBackup = File(dbFile.parentFile, "cloudstream.db.corrupt_$timestamp.bak")
            val walFile = File(dbFile.parentFile, "cloudstream.db-wal")
            val shmFile = File(dbFile.parentFile, "cloudstream.db-shm")

            if (dbFile.exists()) {
                val renamed = dbFile.renameTo(corruptBackup)
                if (!renamed) {
                    try {
                        dbFile.copyTo(corruptBackup, overwrite = true)
                        dbFile.delete()
                    } catch (t: Throwable) {
                        AppLogger.w("DatabaseFactory: Could not delete or copy original corrupt DB file: ${t.message}")
                    }
                }
                if (corruptBackup.exists()) {
                    lastQuarantinedBackupFile = corruptBackup
                }
                AppLogger.w("DatabaseFactory: Quarantined corrupted database to '${corruptBackup.name}'")
            }
            if (walFile.exists()) {
                val walBackup = File(dbFile.parentFile, "cloudstream.db-wal.corrupt_$timestamp.bak")
                if (!walFile.renameTo(walBackup)) {
                    walFile.delete()
                }
            }
            if (shmFile.exists()) {
                shmFile.delete()
            }
        } catch (e: Throwable) {
            AppLogger.e("DatabaseFactory: Failed to quarantine corrupted database files", e)
        }

        return createAndMigrateDatabase(dbFile)
    }

    fun repairDatabase(): Boolean {
        synchronized(lock) {
            val dir = PlatformPaths.dataDir
            val dbFile = File(dir, "cloudstream.db")
            return try {
                val newDb = quarantineAndRebuildDatabase(dbFile)
                instance = newDb
                true
            } catch (t: Throwable) {
                AppLogger.e("DatabaseFactory: Manual database repair failed", t)
                false
            }
        }
    }
}
