package com.mcfrenchpants.activityledger.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Host-side Room tooling proofs against the real [ActivityLedgerDatabase]:
 * in-memory open, Room's own foreign-key enforcement, and MigrationTestHelper
 * building version 1 from the tracked exported schema.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLedgerDatabaseToolingTest {

    // Driver-based constructor, deliberately NOT MigrationTestHelper(Instrumentation, Class):
    // the SupportSQLite path fails on a Windows host (backslash path vs '/' name split).
    // See docs/proposals/room-schema/TOOLING_NOTES.md.
    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = ActivityLedgerDatabase::class,
    )

    @Test
    fun inMemoryDatabaseOpensAndRoundTrips() {
        val db = inMemoryTestDatabase<ActivityLedgerDatabase>()
        try {
            db.fixtureInsertDao().insertRawCapture(Fixtures.rawCapture(Fixtures.id(1)))
            db.openHelper.writableDatabase.query("SELECT id, raw_text FROM raw_captures").use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(Fixtures.id(1), c.getString(0))
                assertEquals("fixture text", c.getString(1))
            }
        } finally {
            db.close()
        }
    }

    /** No callback, no pragma: plain Room must switch foreign keys on for this schema. */
    @Test
    fun plainRoomBuildEnforcesForeignKeys() {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ActivityLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA foreign_keys").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            assertFailsWith<Exception>("dangling interpretations.raw_capture_id must be rejected") {
                db.fixtureInsertDao().insertInterpretation(
                    Fixtures.interpretation(Fixtures.id(10), rawCaptureId = Fixtures.id(999), matchedActivityId = null),
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationTestHelperCreatesVersion1FromExportedSchema() {
        migrationTestHelper.createDatabase(1).use { conn ->
            assertEquals(1L, conn.singleLong("PRAGMA user_version"))
            conn.execSQL(
                "INSERT INTO raw_captures (id, source, source_surface, captured_at, captured_zone_id, raw_text, " +
                    "speech_confidence, speech_alternatives_json, processing_state, created_at, updated_at) " +
                    "VALUES ('${Fixtures.id(1)}', 'PHONE_TEXT', NULL, 1, 'UTC', 'fixture', NULL, NULL, 'CAPTURED', 1, 1)",
            )
            assertEquals(1L, conn.singleLong("SELECT COUNT(*) FROM raw_captures"))
        }
        // Re-open through Room and validate the on-disk schema against the exported version 1 JSON.
        migrationTestHelper.runMigrationsAndValidate(1).use { conn ->
            assertEquals(1L, conn.singleLong("SELECT COUNT(*) FROM raw_captures"))
        }
    }

    private fun SQLiteConnection.singleLong(sql: String): Long =
        prepare(sql).use { stmt ->
            assertTrue(stmt.step())
            stmt.getLong(0)
        }

    private companion object {
        const val TEST_DB = "activity-ledger-migration-test"
    }
}
