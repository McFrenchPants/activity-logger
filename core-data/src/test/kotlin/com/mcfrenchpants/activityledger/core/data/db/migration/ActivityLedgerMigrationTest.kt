package com.mcfrenchpants.activityledger.core.data.db.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data-survival migration tests: one test per start version, each calling
 * [MigrationHarness.migrateAndVerify] with that version's seed.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLedgerMigrationTest {

    @get:Rule
    val helper = MigrationHarness.migrationTestHelper(TEST_DB)

    private val harness = MigrationHarness(helper, TEST_DB)

    @Before
    fun deleteLeftoverDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun migratesFromVersion1() = harness.migrateAndVerify(MigrationSeeds.V1)

    private companion object {
        const val TEST_DB = "activity-ledger-migration-harness"
    }
}
