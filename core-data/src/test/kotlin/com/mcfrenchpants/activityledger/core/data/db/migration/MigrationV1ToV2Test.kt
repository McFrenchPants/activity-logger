package com.mcfrenchpants.activityledger.core.data.db.migration

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabaseFactory
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectEntity
import com.mcfrenchpants.activityledger.core.data.db.migration.MigrationHarness.Companion.longValue
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Version-2-specific checks of MIGRATION_1_2 on the V1 harness seed (the
 * generic data-survival check is ActivityLedgerMigrationTest): the added
 * columns are NULL on every migrated row, the tag tables start empty, and the
 * migrated file accepts tag, alias and tagged-pair rows through the production
 * factory with its foreign keys and unique pair index in force.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV1ToV2Test {

    @get:Rule
    val helper = MigrationHarness.migrationTestHelper(TEST_DB)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteBefore() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun deleteAfter() {
        context.deleteDatabase(TEST_DB)
    }

    private fun migrateSeededV1() {
        helper.createDatabase(1).use { conn ->
            conn.execSQL("PRAGMA foreign_keys = ON")
            MigrationSeeds.V1.statements.forEach { conn.execSQL(it) }
        }
        helper.runMigrationsAndValidate(2, ActivityLedgerDatabaseFactory.MIGRATIONS).use { conn ->
            assertNewColumnsNullAndTagTablesEmpty(conn)
        }
    }

    private fun assertNewColumnsNullAndTagTablesEmpty(conn: SQLiteConnection) {
        val newColumns = mapOf(
            "canonical_activities" to listOf("subject_id", "action_id"),
            "activity_occurrences" to listOf("duration_seconds"),
            "interpretations" to listOf(
                "extracted_subject", "extracted_action", "duration_expression", "resolved_duration_seconds",
            ),
            "corrections" to listOf("previous_duration_seconds", "new_duration_seconds"),
        )
        for ((table, columns) in newColumns) {
            val rows = conn.longValue("SELECT COUNT(*) FROM $table")
            check(rows >= 1) { "seed left $table empty" }
            for (column in columns) {
                assertEquals(rows, conn.longValue("SELECT COUNT(*) FROM $table WHERE $column IS NULL"), "$table.$column")
            }
        }
        for (table in listOf("subjects", "actions", "subject_aliases", "action_aliases")) {
            assertEquals(0L, conn.longValue("SELECT COUNT(*) FROM $table"), "$table must start empty")
        }
    }

    @Test
    fun migratedRowsHaveNullNewColumnsAndTagTablesAreEmpty() = migrateSeededV1()

    @Test
    fun migratedDatabaseAcceptsTagsAliasesAndUniqueTaggedPairs() {
        migrateSeededV1()
        val db = ActivityLedgerDatabaseFactory.builder(context, TEST_DB).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { c ->
                check(c.moveToFirst())
                assertEquals(1, c.getInt(0), "foreign keys must be on")
            }
            val subject = SubjectEntity(id(901), "Furnace", "furnace", TagStatus.ACTIVE, null, T, T)
            val action = ActionEntity(id(902), "Change filter", "changefilter", TagStatus.ACTIVE, null, T, T)
            db.subjectDao().insert(subject)
            db.actionDao().insert(action)
            db.subjectAliasDao().insert(
                SubjectAliasEntity(id(903), subject.id, "boiler", "boiler", AliasSource.MANUAL, T),
            )
            db.actionAliasDao().insert(
                ActionAliasEntity(id(904), action.id, "swap filter", "swapfilter", AliasSource.USER_CORRECTION, T),
            )
            assertEquals(listOf(subject), db.subjectDao().listActive())
            assertEquals(listOf(action), db.actionDao().listActive())
            assertEquals(subject, db.subjectDao().getById(subject.id))
            assertEquals(action, db.actionDao().getById(action.id))
            assertEquals(listOf(id(903)), db.subjectAliasDao().listForActiveSubjects().map { it.id })
            assertEquals(listOf(id(904)), db.actionAliasDao().listForActiveActions().map { it.id })

            val pair = pair(id(905), subject.id, action.id)
            db.canonicalActivityDao().insert(pair)
            assertEquals(pair, db.canonicalActivityDao().getById(pair.id))

            assertFailsWith<SQLiteConstraintException>("duplicate (subject_id, action_id) must be rejected") {
                db.canonicalActivityDao().insert(pair(id(906), subject.id, action.id))
            }
            assertFailsWith<SQLiteConstraintException>("unknown subject_id must be rejected") {
                db.canonicalActivityDao().insert(pair(id(907), id(999), action.id))
            }
            assertFailsWith<SQLiteConstraintException>("alias of unknown action must be rejected") {
                db.actionAliasDao().insert(ActionAliasEntity(id(908), id(999), "x", "x", AliasSource.MANUAL, T))
            }

            // Untagged pairs (the v3 path) are unconstrained by the unique pair index.
            db.canonicalActivityDao().insert(pair(id(909), null, null))
            db.canonicalActivityDao().insert(pair(id(910), null, null))
            assertNull(db.canonicalActivityDao().getById(id(910))?.subjectId)
            assertEquals(5, db.canonicalActivityDao().listActive().size, "2 seeded ACTIVE + 3 inserted")
        } finally {
            db.close()
        }
    }

    private fun pair(id: String, subjectId: String?, actionId: String?) = CanonicalActivityEntity(
        id = id,
        displayName = "Pair $id",
        normalizedName = "pair",
        status = CanonicalActivityStatus.ACTIVE,
        createdAt = T,
        updatedAt = T,
        mergedIntoActivityId = null,
        subjectId = subjectId,
        actionId = actionId,
    )

    private companion object {
        const val TEST_DB = "activity-ledger-migration-v1-v2"
        const val T = 1_760_000_000_000L
        fun id(n: Int): String = "00000000-0000-7000-8000-%012d".format(n)
    }
}
