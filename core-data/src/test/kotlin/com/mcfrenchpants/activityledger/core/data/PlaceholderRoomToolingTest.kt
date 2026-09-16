package com.mcfrenchpants.activityledger.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proof that the host-side Room tooling works (DB1.1). These tests target the
 * scaffold placeholder database and may be deleted together with it when the
 * real schema lands; the tooling itself (Robolectric, MigrationTestHelper,
 * schema JSON exposed as test assets, [inMemoryTestDatabase]) stays.
 */
@RunWith(AndroidJUnit4::class)
class PlaceholderRoomToolingTest {

    // Driver-based constructor, deliberately NOT MigrationTestHelper(Instrumentation, Class):
    // the SupportSQLite path fails on a Windows host because SupportSQLiteDriver
    // compares the database name using substringAfterLast('/') against a
    // backslash path. See docs/proposals/room-schema/TOOLING_NOTES.md.
    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = ScaffoldPlaceholderDatabase::class,
    )

    @Test
    fun inMemoryDatabaseOpensAndRoundTrips() {
        val db = inMemoryTestDatabase<ScaffoldPlaceholderDatabase>()
        try {
            runBlocking {
                db.scaffoldPlaceholderDao().insert(ScaffoldPlaceholderEntity(1, "x"))
                assertEquals(1, db.scaffoldPlaceholderDao().count())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun inMemoryDatabaseEnforcesForeignKeys() {
        val db = inMemoryTestDatabase<ScaffoldPlaceholderDatabase>()
        try {
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA foreign_keys").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            // Behavioural check, not just the pragma: a dangling reference is rejected.
            sql.execSQL("CREATE TABLE fk_parent (id INTEGER PRIMARY KEY)")
            sql.execSQL(
                "CREATE TABLE fk_child (id INTEGER PRIMARY KEY, " +
                    "parent_id INTEGER NOT NULL REFERENCES fk_parent(id))",
            )
            val attempt = runCatching { sql.execSQL("INSERT INTO fk_child (id, parent_id) VALUES (1, 42)") }
            assertTrue(attempt.isFailure, "insert with a dangling foreign key must fail")
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationTestHelperCreatesVersion1FromExportedSchema() {
        migrationTestHelper.createDatabase(1).use { conn ->
            assertEquals(1L, conn.singleLong("PRAGMA user_version"))
            conn.execSQL("INSERT INTO scaffold_placeholder (id, placeholder) VALUES (7, 'p')")
            assertEquals(1L, conn.singleLong("SELECT COUNT(*) FROM scaffold_placeholder"))
        }
        // Re-open through Room and validate the on-disk schema against the exported version 1 JSON.
        migrationTestHelper.runMigrationsAndValidate(1).use { conn ->
            assertEquals(1L, conn.singleLong("SELECT COUNT(*) FROM scaffold_placeholder"))
        }
    }

    private fun SQLiteConnection.singleLong(sql: String): Long =
        prepare(sql).use { stmt ->
            assertTrue(stmt.step())
            stmt.getLong(0)
        }

    private companion object {
        const val TEST_DB = "placeholder-migration-test"
    }
}
