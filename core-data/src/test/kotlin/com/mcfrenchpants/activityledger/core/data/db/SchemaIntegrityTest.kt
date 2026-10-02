package com.mcfrenchpants.activityledger.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.id
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Integrity constraints of the current schema (version 2), exercised on the real database. */
@RunWith(AndroidJUnit4::class)
class SchemaIntegrityTest {

    private lateinit var db: ActivityLedgerDatabase
    private val sql get() = db.openHelper.writableDatabase

    private val activity = id(1)
    private val capture = id(2)
    private val interpretation = id(3)
    private val occurrence = id(4)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Activity + capture + interpretation + occurrence all linked together. */
    private fun insertLinkedGraph() {
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(activity))
        sql.insertRawCapture(Fixtures.rawCapture(capture))
        sql.insertInterpretation(Fixtures.interpretation(interpretation, capture, activity))
        sql.insertActivityOccurrence(Fixtures.occurrence(occurrence, activity, capture, interpretation))
    }

    private fun count(table: String, where: String = "1"): Int =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }

    @Test
    fun deletingRawCaptureReferencedByInterpretationFails() {
        sql.insertRawCapture(Fixtures.rawCapture(capture))
        sql.insertInterpretation(Fixtures.interpretation(interpretation, capture, matchedActivityId = null))
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("DELETE FROM raw_captures WHERE id = ?", arrayOf(capture))
        }
        assertEquals(1, count("raw_captures"))
    }

    @Test
    fun deletingRawCaptureReferencedByOccurrenceFails() {
        insertLinkedGraph()
        // A second capture referenced ONLY by an occurrence (no interpretation points at it),
        // so the failure can only come from the activity_occurrences.raw_capture_id foreign key.
        val secondCapture = id(20)
        val secondOccurrence = id(21)
        sql.insertRawCapture(Fixtures.rawCapture(secondCapture))
        sql.insertActivityOccurrence(Fixtures.occurrence(secondOccurrence, activity, secondCapture, interpretation))
        assertEquals(0, count("interpretations", "raw_capture_id = '$secondCapture'"))
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("DELETE FROM raw_captures WHERE id = ?", arrayOf(secondCapture))
        }
        assertEquals(2, count("raw_captures"))
    }

    @Test
    fun deletingInterpretationReferencedByOccurrenceFails() {
        insertLinkedGraph()
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("DELETE FROM interpretations WHERE id = ?", arrayOf(interpretation))
        }
        assertEquals(1, count("interpretations"))
    }

    @Test
    fun deletingCanonicalActivityReferencedByOccurrenceFails() {
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(activity))
        sql.insertRawCapture(Fixtures.rawCapture(capture))
        // Interpretation deliberately does not reference the activity, so the occurrence is the only referrer.
        sql.insertInterpretation(Fixtures.interpretation(interpretation, capture, matchedActivityId = null))
        sql.insertActivityOccurrence(Fixtures.occurrence(occurrence, activity, capture, interpretation))
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("DELETE FROM canonical_activities WHERE id = ?", arrayOf(activity))
        }
        assertEquals(1, count("canonical_activities"))
    }

    @Test
    fun insertingOccurrenceWithUnknownCanonicalActivityFails() {
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(activity))
        sql.insertRawCapture(Fixtures.rawCapture(capture))
        sql.insertInterpretation(Fixtures.interpretation(interpretation, capture, matchedActivityId = null))
        assertFailsWith<SQLiteConstraintException> {
            sql.insertActivityOccurrence(Fixtures.occurrence(occurrence, id(999), capture, interpretation))
        }
        assertEquals(0, count("activity_occurrences"))
    }

    @Test
    fun secondOccurrenceForSameRawCaptureFails() {
        insertLinkedGraph()
        assertFailsWith<SQLiteConstraintException> {
            sql.insertActivityOccurrence(Fixtures.occurrence(id(5), activity, capture, interpretation))
        }
        assertEquals(1, count("activity_occurrences"))
    }

    @Test
    fun duplicateNormalizedAliasIsRejectedPerActivityButAllowedAcrossActivities() {
        val otherActivity = id(6)
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(activity))
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(otherActivity))
        sql.insertActivityAlias(Fixtures.alias(id(7), activity, "run"))
        assertFailsWith<SQLiteConstraintException> {
            sql.insertActivityAlias(Fixtures.alias(id(8), activity, "run"))
        }
        sql.insertActivityAlias(Fixtures.alias(id(9), otherActivity, "run"))
        assertEquals(2, count("activity_aliases", "normalized_alias = 'run'"))
    }

    @Test
    fun canonicalActivitiesMayShareNormalizedName() {
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(1), normalizedName = "walk"))
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(2), normalizedName = "walk"))
        assertEquals(2, count("canonical_activities", "normalized_name = 'walk'"))
    }

    @Test
    fun everyEnumColumnIsStoredAsTheConstantName() {
        insertLinkedGraph()
        val merged = Fixtures.canonicalActivity(id(30)).copy(
            status = com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus.MERGED,
            mergedIntoActivityId = activity,
        )
        sql.insertCanonicalActivity(merged)
        sql.insertActivityAlias(Fixtures.alias(id(31), activity, "stroll"))
        val correction = Fixtures.correction(id(32), occurrence, activity, interpretation)
        sql.insertCorrection(correction)

        val raw = Fixtures.rawCapture(capture)
        val interp = Fixtures.interpretation(interpretation, capture, activity)
        val occ = Fixtures.occurrence(occurrence, activity, capture, interpretation)
        val alias = Fixtures.alias(id(31), activity, "stroll")

        assertRow("SELECT source, processing_state FROM raw_captures WHERE id = '$capture'",
            raw.source.name, raw.processingState.name)
        assertRow("SELECT status FROM canonical_activities WHERE id = '${merged.id}'", merged.status.name)
        assertRow("SELECT source FROM activity_aliases WHERE id = '${alias.id}'", alias.source.name)
        assertRow(
            "SELECT operation, activity_resolution, activity_state, time_precision, model_confidence_band, " +
                "validation_status FROM interpretations WHERE id = '$interpretation'",
            interp.operation.name, interp.activityResolution.name, interp.activityState!!.name,
            interp.timePrecision!!.name, interp.modelConfidenceBand!!.name, interp.validationStatus.name,
        )
        assertRow(
            "SELECT time_precision, activity_state, visibility_status FROM activity_occurrences WHERE id = '$occurrence'",
            occ.timePrecision.name, occ.activityState.name, occ.visibilityStatus.name,
        )
        assertRow(
            "SELECT source, previous_time_precision, new_time_precision, previous_activity_state, " +
                "new_activity_state FROM corrections WHERE id = '${correction.id}'",
            correction.source.name, correction.previousTimePrecision!!.name, correction.newTimePrecision!!.name,
            correction.previousActivityState!!.name, correction.newActivityState!!.name,
        )
    }

    /** Including the ADR-040 tag tables: every foreign key in the schema is NO ACTION on delete and update. */
    @Test
    fun everyForeignKeyIsNoAction() {
        val tables = sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata')",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val foreignKeys = mutableListOf<String>()
        for (table in tables) {
            sql.query("PRAGMA foreign_key_list(`$table`)").use { c ->
                val from = c.getColumnIndexOrThrow("from")
                val onUpdate = c.getColumnIndexOrThrow("on_update")
                val onDelete = c.getColumnIndexOrThrow("on_delete")
                while (c.moveToNext()) {
                    val where = "$table.${c.getString(from)}"
                    foreignKeys += where
                    assertEquals("NO ACTION", c.getString(onUpdate), "$where ON UPDATE")
                    assertEquals("NO ACTION", c.getString(onDelete), "$where ON DELETE")
                }
            }
        }
        val tagForeignKeys = listOf(
            "canonical_activities.subject_id", "canonical_activities.action_id",
            "subjects.merged_into_subject_id", "actions.merged_into_action_id",
            "subject_aliases.subject_id", "action_aliases.action_id",
        )
        assertTrue(foreignKeys.containsAll(tagForeignKeys), "tag foreign keys missing from $foreignKeys")
    }

    @Test
    fun duplicateTaggedPairIsRejectedButUntaggedActivitiesAreNot() {
        insertTags()
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(41)).copy(subjectId = id(40), actionId = id(50)))
        assertFailsWith<SQLiteConstraintException> {
            sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(42)).copy(subjectId = id(40), actionId = id(50)))
        }
        // The same subject with another action, and two untagged activities, are all allowed.
        sql.insertRow("actions", tagRow(id(51), "rake", "merged_into_action_id"))
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(43)).copy(subjectId = id(40), actionId = id(51)))
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(44)))
        sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(45)))
        assertEquals(4, count("canonical_activities"))
    }

    @Test
    fun tagForeignKeysAndAliasUniquenessAreEnforced() {
        insertTags()
        assertFailsWith<SQLiteConstraintException> {
            sql.insertCanonicalActivity(Fixtures.canonicalActivity(id(41)).copy(subjectId = id(40), actionId = id(999)))
        }
        sql.insertRow("subject_aliases", aliasRow(id(60), "subject_id", id(40), "boiler"))
        assertFailsWith<SQLiteConstraintException> {
            sql.insertRow("subject_aliases", aliasRow(id(61), "subject_id", id(40), "boiler"))
        }
        assertFailsWith<SQLiteConstraintException> {
            sql.insertRow("action_aliases", aliasRow(id(62), "action_id", id(999), "mow"))
        }
        assertFailsWith<SQLiteConstraintException> {
            sql.execSQL("DELETE FROM subjects WHERE id = ?", arrayOf(id(40)))
        }
        assertEquals(1, count("subjects"))
        assertEquals(1, count("subject_aliases"))
    }

    private fun insertTags() {
        sql.insertRow("subjects", tagRow(id(40), "furnace", "merged_into_subject_id"))
        sql.insertRow("actions", tagRow(id(50), "changefilter", "merged_into_action_id"))
    }

    private fun tagRow(id: String, key: String, mergedColumn: String) = mapOf(
        "id" to id, "display_name" to key, "normalized_name" to key, "status" to TagStatus.ACTIVE,
        mergedColumn to null, "created_at" to Fixtures.T0, "updated_at" to Fixtures.T0,
    )

    private fun aliasRow(id: String, ownerColumn: String, ownerId: String, key: String) = mapOf(
        "id" to id, ownerColumn to ownerId, "alias_text" to key, "normalized_alias" to key,
        "source" to AliasSource.MANUAL, "created_at" to Fixtures.T0,
    )

    private fun assertRow(query: String, vararg expected: String) {
        sql.query(query).use { c ->
            assertTrue(c.moveToFirst(), "no row for: $query")
            assertEquals(expected.toList(), (0 until c.columnCount).map { c.getString(it) })
        }
    }
}
