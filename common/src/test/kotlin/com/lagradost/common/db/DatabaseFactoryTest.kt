package com.lagradost.common.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseFactoryTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeTest
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_dbfactory_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    private fun querySingleString(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val value = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value

    @Test
    fun `fresh database uses WAL mode passes integrity check and supports tracker tables`() {
        val db = DatabaseFactory.database
        assertTrue(DatabaseFactory.isInitialized())

        val dbFile = File(PlatformPaths.dataDir, "cloudstream.db")
        assertTrue(dbFile.exists(), "Expected SQLite database file to be created")

        val queries = db.cloudstreamDBQueries
        queries.upsertTrackerBinding(
            profileId = 1L,
            trackerId = "anilist",
            showKey = "TestApi|https://example.org/show/1",
            mappedSeason = 1L,
            remoteMediaId = "12345",
            remoteTitle = "Example Show",
            bindMode = "AUTO_VERIFIED",
            episodeOffset = 0L,
            expectedTotalEpisodes = 12L,
            updatedAt = 1_700_000_000_000L,
        )
        val binding = queries.selectTrackerBinding(
            profileId = 1L,
            trackerId = "anilist",
            showKey = "TestApi|https://example.org/show/1",
            mappedSeason = 1L,
        ).executeAsOneOrNull()
        assertNotNull(binding)
        assertEquals("Example Show", binding.remoteTitle)

        // Close active singleton driver before inspecting raw SQLite pragmas cleanly on Windows
        DatabaseFactory.resetForTesting()
        val verifyDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            assertEquals("wal", querySingleString(verifyDriver, "PRAGMA journal_mode;")?.lowercase())
            assertEquals("ok", querySingleString(verifyDriver, "PRAGMA quick_check(1);")?.lowercase())
        } finally {
            verifyDriver.close()
        }
    }

    @Test
    fun `legacy schema without profile or tracker columns migrates cleanly and backfills profileId`() {
        val dataDir = PlatformPaths.dataDir
        val dbFile = File(dataDir, "cloudstream.db")
        dbFile.parentFile?.mkdirs()

        val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            legacyDriver.execute(
                null,
                "CREATE TABLE KeyValueStore (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);",
                0,
            )
            legacyDriver.execute(
                null,
                "CREATE TABLE Bookmarks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, apiName TEXT NOT NULL, posterUrl TEXT);",
                0,
            )
            legacyDriver.execute(
                null,
                "CREATE TABLE WatchHistory (parentId TEXT NOT NULL, episodeId TEXT NOT NULL, showName TEXT NOT NULL, showUrl TEXT NOT NULL, apiName TEXT NOT NULL, posterUrl TEXT, episode INTEGER, season INTEGER, position INTEGER NOT NULL, duration INTEGER NOT NULL, updateTime INTEGER NOT NULL, PRIMARY KEY (parentId, episodeId));",
                0,
            )
            legacyDriver.execute(
                null,
                "INSERT INTO Bookmarks(id, name, url, apiName, posterUrl) VALUES ('p2_legacy_1', 'Legacy Show', 'https://example.org/legacy', 'Demo', NULL);",
                0,
            )
            legacyDriver.execute(
                null,
                "INSERT INTO WatchHistory(parentId, episodeId, showName, showUrl, apiName, posterUrl, episode, season, position, duration, updateTime) VALUES ('p3_hist_1', 'ep1', 'Legacy History', 'https://example.org/h', 'Demo', NULL, 1, 1, 120, 1400, 1700000000);",
                0,
            )
        } finally {
            legacyDriver.close()
        }

        val migratedDb = DatabaseFactory.database
        val queries = migratedDb.cloudstreamDBQueries

        val migratedBookmarks = queries.selectBookmarksByProfile(2L).executeAsList()
        assertEquals(1, migratedBookmarks.size)
        assertEquals("Legacy Show", migratedBookmarks.first().name)
        assertEquals(2L, migratedBookmarks.first().profileId)

        val migratedHistory = queries.selectWatchHistoryByProfile(3L).executeAsList()
        assertEquals(1, migratedHistory.size)
        assertEquals("Legacy History", migratedHistory.first().showName)
        assertEquals(3L, migratedHistory.first().profileId)

        // New columns and tables must be queryable after migration
        queries.upsertTrackerBinding(
            profileId = 2L,
            trackerId = "mal",
            showKey = "Demo|https://example.org/legacy",
            mappedSeason = 1L,
            remoteMediaId = "999",
            remoteTitle = "Migrated MAL Binding",
            bindMode = "USER_PINNED",
            episodeOffset = 0L,
            expectedTotalEpisodes = 24L,
            updatedAt = 1_700_000_000_100L,
        )
        assertEquals(
            "Migrated MAL Binding",
            queries.selectTrackerBinding(2L, "mal", "Demo|https://example.org/legacy", 1L).executeAsOne().remoteTitle,
        )
    }

    @Test
    fun `corrupt database is quarantined and rebuilt cleanly including live repair`() {
        val dataDir = PlatformPaths.dataDir
        val dbFile = File(dataDir, "cloudstream.db")
        val walFile = File(dataDir, "cloudstream.db-wal")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes("THIS_IS_NOT_A_VALID_SQLITE_FILE_HEADER".encodeToByteArray())
        walFile.writeBytes("CORRUPT_WAL".encodeToByteArray())

        val recoveredDb = DatabaseFactory.database
        recoveredDb.cloudstreamDBQueries.insertKeyValue("after_quarantine", "healthy")
        assertEquals("healthy", recoveredDb.cloudstreamDBQueries.selectKeyValue("after_quarantine").executeAsOneOrNull())

        val quarantinedBackups = dataDir.listFiles { _, name ->
            name.startsWith("cloudstream.db.corrupt_") && name.endsWith(".bak")
        }.orEmpty()
        assertTrue(quarantinedBackups.isNotEmpty(), "Expected corrupt database to be quarantined as a .bak file")

        // Live repair while connection is open must close the handle and rebuild cleanly on Windows
        assertTrue(DatabaseFactory.repairDatabase())
        DatabaseFactory.database.cloudstreamDBQueries.insertKeyValue("after_live_repair", "ok")
        assertEquals(
            "ok",
            DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue("after_live_repair").executeAsOneOrNull(),
        )
    }

    @Test
    fun `legacy schema upgrade creates pre-upgrade snapshot stamps user_version and supports consistent snapshot`() {
        val dbFile = File(PlatformPaths.dataDir, "cloudstream.db")
        dbFile.parentFile?.mkdirs()

        val oldDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        try {
            oldDriver.execute(null, "CREATE TABLE KeyValueStore (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);", 0)
            oldDriver.execute(null, "INSERT INTO KeyValueStore(key, value) VALUES ('keep_key', '\"saved\"');", 0)
            oldDriver.execute(null, "PRAGMA user_version = 0;", 0)
        } finally {
            oldDriver.close()
        }

        val upgraded = DatabaseFactory.database
        assertEquals("\"saved\"", upgraded.cloudstreamDBQueries.selectKeyValue("keep_key").executeAsOneOrNull())

        val upgradeSnapshots = DatabaseFactory.getUpgradeSnapshotsDir()
            .listFiles { f -> f.isFile && f.name.startsWith("cloudstream.db.v0_pre_") }
            .orEmpty()
        assertTrue(upgradeSnapshots.isNotEmpty(), "Expected pre-upgrade snapshot before schema migration")

        // Verify consistent VACUUM INTO snapshot while WAL is active captures new uncommitted WAL writes cleanly
        upgraded.cloudstreamDBQueries.insertKeyValue("wal_key", "\"fresh\"")
        val snapshotFile = File(tempDir, "consistent_snapshot.db")
        DatabaseFactory.createConsistentSnapshot(snapshotFile)
        assertTrue(snapshotFile.exists() && snapshotFile.length() > 0L)

        val inspectDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshotFile.absolutePath}", Properties())
        try {
            assertEquals("ok", querySingleString(inspectDriver, "PRAGMA quick_check(1);")?.lowercase())
            assertEquals(DatabaseFactory.CURRENT_SCHEMA_VERSION.toString(), querySingleString(inspectDriver, "PRAGMA user_version;"))
            val snapVal = DesktopDatabase(inspectDriver).cloudstreamDBQueries.selectKeyValue("wal_key").executeAsOneOrNull()
            assertEquals("\"fresh\"", snapVal)
        } finally {
            inspectDriver.close()
        }
    }

    @Test
    fun `transient exclusive SQLite lock fails closed instead of quarantining healthy database`() {
        val dbFile = File(PlatformPaths.dataDir, "cloudstream.db")
        val seed = DatabaseFactory.database
        seed.cloudstreamDBQueries.insertKeyValue("protected_key", "\"intact\"")
        DatabaseFactory.resetForTesting()

        // Hold an exclusive write lock on another connection to force SQLITE_BUSY / locked error on migration/open
        DatabaseFactory.busyTimeoutMs = 50L
        val lockConn = java.sql.DriverManager.getConnection(
            "jdbc:sqlite:${dbFile.absolutePath}",
            Properties().apply { setProperty("busy_timeout", "50") },
        )
        try {
            lockConn.autoCommit = false
            lockConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA locking_mode = EXCLUSIVE;")
                stmt.execute("PRAGMA user_version = 1;")
                stmt.execute("INSERT OR REPLACE INTO KeyValueStore(key, value) VALUES ('lock_probe', '1');")
            }
            // Keep exclusive lock held while resetting user_version=1 forces write lock acquisition on reopen
            assertFailsWith<IllegalStateException> {
                DatabaseFactory.database
            }
            lockConn.rollback()
        } finally {
            lockConn.close()
            DatabaseFactory.busyTimeoutMs = 30_000L
        }

        // Healthy DB must NOT have been quarantined to .corrupt_*.bak
        val corruptBaks = PlatformPaths.dataDir.listFiles { _, name ->
            name.startsWith("cloudstream.db.corrupt_")
        }.orEmpty()
        assertTrue(corruptBaks.isEmpty(), "Transient lock must not quarantine healthy DB")
        assertEquals(
            "\"intact\"",
            DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue("protected_key").executeAsOneOrNull(),
        )
    }
}
