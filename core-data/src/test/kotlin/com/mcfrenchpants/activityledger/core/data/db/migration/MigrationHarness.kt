package com.mcfrenchpants.activityledger.core.data.db.migration

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A version-specific seed for [MigrationHarness]: raw SQL valid for the schema
 * of [startVersion], written against that version's exported schema JSON, never
 * against current entities or DAOs (so it stays valid when entities change).
 *
 * @property statements executed in order, with foreign keys ON, on a database
 *   MigrationTestHelper built at [startVersion].
 * @property coverage named `SELECT COUNT(*) ...` queries (valid at [startVersion])
 *   that must each return at least 1 before migrating; they prove the seed really
 *   contains the representative cases it claims to.
 * @property rawCaptureProbeId / [occurrenceProbeId] rows read back through the
 *   production factory and DAOs after migrating.
 */
internal class MigrationSeed(
    val startVersion: Int,
    val statements: List<String>,
    val coverage: Map<String, String>,
    val rawCaptureProbeId: String,
    val occurrenceProbeId: String,
)

/**
 * Reusable data-survival check for ActivityLedgerDatabase migrations.
 *
 * [migrateAndVerify] (a) builds the database file at the seed's start version
 * from the exported schema and seeds it with raw SQL, (b) snapshots every row of
 * every table (all columns, ordered by id), (c) runs
 * `runMigrationsAndValidate(CURRENT_VERSION, ActivityLedgerDatabaseFactory.MIGRATIONS)`,
 * (d) asserts row counts match, every snapshotted row still exists with
 * identical values for every column present in both versions, and
 * `PRAGMA foreign_key_check` is empty, then (e) opens the migrated file through
 * the production factory and reads a raw capture and an occurrence via the DAOs.
 *
 * Adding a future migration (say version 2 -> 3, CURRENT_VERSION becomes 3):
 * 1. add the Migration to ActivityLedgerDatabaseFactory.MIGRATIONS;
 * 2. write a seed for the new start version against 2.json and register it in
 *    [MigrationSeeds.byStartVersion];
 * 3. add one test to ActivityLedgerMigrationTest:
 *
 * ```
 * @Test
 * fun migratesFromVersion2() = harness.migrateAndVerify(MigrationSeeds.V2)
 * ```
 *
 * Existing seeds (e.g. V1) keep running and now exercise the full 1 -> 3 chain.
 */
internal class MigrationHarness(
    private val helper: MigrationTestHelper,
    private val databaseName: String,
) {

    fun migrateAndVerify(seed: MigrationSeed) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val before = helper.createDatabase(seed.startVersion).use { conn ->
            assertEquals(seed.startVersion.toLong(), conn.longValue("PRAGMA user_version"))
            // MigrationTestHelper connections do not enable foreign keys; the seed must obey them.
            conn.execSQL("PRAGMA foreign_keys = ON")
            seed.statements.forEach { conn.execSQL(it) }
            for ((name, sql) in seed.coverage) {
                assertTrue(conn.longValue(sql) >= 1, "seed for v${seed.startVersion} lacks case: $name")
            }
            assertEquals(emptyList(), conn.foreignKeyViolations(), "seed itself violates foreign keys")
            snapshot(conn)
        }
        assertTrue(before.keys.containsAll(CORE_TABLES), "seed database lacks tables: ${CORE_TABLES - before.keys}")
        for ((table, rows) in before) {
            assertTrue(rows.isNotEmpty(), "seed for v${seed.startVersion} leaves table $table empty")
        }

        val after = helper.runMigrationsAndValidate(CURRENT_VERSION, ActivityLedgerDatabaseFactory.MIGRATIONS)
            .use { conn ->
                assertEquals(CURRENT_VERSION.toLong(), conn.longValue("PRAGMA user_version"))
                assertEquals(emptyList(), conn.foreignKeyViolations(), "foreign keys broken by migration")
                snapshot(conn)
            }

        for ((table, beforeRows) in before) {
            val afterRows = assertNotNull(after[table], "table $table disappeared")
            assertEquals(beforeRows.size, afterRows.size, "row count of $table changed")
            for ((id, beforeRow) in beforeRows) {
                val afterRow = assertNotNull(afterRows[id], "row $table/$id disappeared")
                for (column in beforeRow.keys intersect afterRow.keys) {
                    assertEquals(beforeRow[column], afterRow[column], "value of $table/$id.$column changed")
                }
            }
        }

        val db = ActivityLedgerDatabaseFactory.builder(context, databaseName).allowMainThreadQueries().build()
        try {
            val capture = assertNotNull(db.rawCaptureDao().getById(seed.rawCaptureProbeId))
            after.getValue("raw_captures").getValue(seed.rawCaptureProbeId)["raw_text"]?.let {
                assertEquals(it, capture.rawText)
            }
            val occurrence = assertNotNull(db.activityOccurrenceDao().getById(seed.occurrenceProbeId))
            after.getValue("activity_occurrences").getValue(seed.occurrenceProbeId)["raw_capture_id"]?.let {
                assertEquals(it, occurrence.rawCaptureId)
            }
        } finally {
            db.close()
        }
    }

    /** table -> (id -> (column -> value)); values are Long, Double, String, List<Byte> or null. */
    private fun snapshot(conn: SQLiteConnection): Map<String, Map<String, Map<String, Any?>>> {
        val tables = conn.prepare(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { stmt -> buildList { while (stmt.step()) add(stmt.getText(0)) } }
        return tables.associateWith { table ->
            val rows = LinkedHashMap<String, Map<String, Any?>>()
            conn.prepare("SELECT * FROM `$table` ORDER BY id").use { stmt ->
                while (stmt.step()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (i in 0 until stmt.getColumnCount()) {
                        row[stmt.getColumnName(i)] = when (val type = stmt.getColumnType(i)) {
                            SQLITE_DATA_NULL -> null
                            SQLITE_DATA_INTEGER -> stmt.getLong(i)
                            SQLITE_DATA_FLOAT -> stmt.getDouble(i)
                            SQLITE_DATA_TEXT -> stmt.getText(i)
                            SQLITE_DATA_BLOB -> stmt.getBlob(i).toList()
                            else -> fail("unknown column type $type in $table")
                        }
                    }
                    val id = row["id"] as? String ?: fail("table $table has no text id column")
                    rows[id] = row
                }
            }
            rows
        }
    }

    private fun SQLiteConnection.foreignKeyViolations(): List<String> =
        prepare("PRAGMA foreign_key_check").use { stmt ->
            buildList {
                while (stmt.step()) {
                    add((0 until stmt.getColumnCount()).joinToString("|") { if (stmt.isNull(it)) "null" else stmt.getText(it) })
                }
            }
        }

    internal companion object {
        /** The version every migration test migrates to; tied to the exported schemas by SchemaVersionConsistencyTest. */
        const val CURRENT_VERSION = 2

        val CORE_TABLES = setOf(
            "raw_captures", "canonical_activities", "activity_aliases",
            "interpretations", "activity_occurrences", "corrections",
        )

        /** The driver-based helper (the Instrumentation/Class constructor fails on Windows hosts). */
        fun migrationTestHelper(databaseName: String): MigrationTestHelper {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context: Context = instrumentation.targetContext
            return MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = ActivityLedgerDatabase::class,
            )
        }

        fun SQLiteConnection.longValue(sql: String): Long =
            prepare(sql).use { stmt ->
                assertTrue(stmt.step(), "no row for: $sql")
                stmt.getLong(0)
            }
    }
}
