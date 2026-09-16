package com.mcfrenchpants.activityledger.core.data.db.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ties [MigrationHarness.CURRENT_VERSION] to (1) the exported schema JSON files
 * and (2) the version the production factory actually opens, and requires a
 * harness seed for every start version. Bumping the database version without
 * exporting its schema, updating the harness, or adding a seed fails here.
 */
@RunWith(AndroidJUnit4::class)
class SchemaVersionConsistencyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteBefore() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun deleteAfter() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun harnessVersionEqualsHighestExportedSchema() {
        val schemaDir = ActivityLedgerDatabase::class.java.name
        val files = InstrumentationRegistry.getInstrumentation().context.assets.list(schemaDir).orEmpty().toList()
        val versions = files.mapNotNull { Regex("^(\\d+)\\.json$").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        assertTrue(versions.isNotEmpty(), "no exported schemas found under assets/$schemaDir (files: $files)")
        assertEquals(versions.max(), MigrationHarness.CURRENT_VERSION, "harness CURRENT_VERSION vs highest N.json")
        assertEquals((1..versions.max()).toList(), versions.sorted(), "every version must have an exported schema")
    }

    @Test
    fun harnessVersionEqualsOpenedDatabaseVersion() {
        val db = ActivityLedgerDatabaseFactory.builder(context, TEST_DB).allowMainThreadQueries().build()
        try {
            val userVersion = db.openHelper.writableDatabase.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                c.getInt(0)
            }
            assertEquals(MigrationHarness.CURRENT_VERSION, userVersion, "PRAGMA user_version of the opened database")
        } finally {
            db.close()
        }
    }

    @Test
    fun everyStartVersionHasAHarnessSeed() {
        // Version 1 is always seeded; once later versions exist, every version below current must be.
        val expected = (1..maxOf(1, MigrationHarness.CURRENT_VERSION - 1)).toSet()
        assertEquals(expected, MigrationSeeds.byStartVersion.keys, "start versions with a migration seed")
    }

    private companion object {
        const val TEST_DB = "activity-ledger-version-check"
    }
}
