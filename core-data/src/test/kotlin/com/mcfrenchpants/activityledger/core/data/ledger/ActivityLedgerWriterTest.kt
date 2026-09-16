package com.mcfrenchpants.activityledger.core.data.ledger

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.Fixtures
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.T0
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.id
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.dao.ActivityOccurrenceDao
import com.mcfrenchpants.activityledger.core.data.db.dao.DaoWriteSurfaceGuardTest
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.insertActivityOccurrence
import com.mcfrenchpants.activityledger.core.data.db.insertInterpretation
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data layer's restricted write operations, exercised on the real Room database. */
@RunWith(AndroidJUnit4::class)
class ActivityLedgerWriterTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var writer: ActivityLedgerWriter
    private val sql get() = db.openHelper.writableDatabase

    // Generated ids start far above the hand-picked Fixtures.id(n) values, so they never collide.
    private val ids = DeterministicIdFactory(next = 0x10_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        writer = ActivityLedgerWriter(db, ids)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers -------------------------------------------------------------

    private fun capture(captureId: String, text: String = "synthetic capture") = RawCaptureEntity(
        id = captureId,
        source = CaptureSource.PHONE_VOICE,
        sourceSurface = "test",
        capturedAt = T0,
        capturedZoneId = "Europe/London",
        rawText = text,
        speechConfidence = 0.8,
        speechAlternativesJson = null,
        processingState = ProcessingState.INTERPRETED,
        createdAt = T0,
        updatedAt = T0,
    ).also { db.rawCaptureDao().insert(it) }

    private fun activity(activityId: String, name: String) =
        Fixtures.canonicalActivity(activityId, normalizedName = name.lowercase())
            .copy(displayName = name)
            .also { db.canonicalActivityDao().insert(it) }

    private fun newInterpretation(captureId: String, matched: String?, createdAt: Long = T0 + 1) = NewInterpretation(
        rawCaptureId = captureId,
        createdAt = createdAt,
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 1,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = if (matched == null) ActivityResolution.NEW_ACTIVITY else ActivityResolution.EXISTING_ACTIVITY,
        matchedActivityId = matched,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = T0,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = ConfidenceBand.HIGH,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
    )

    private fun acceptRequest(
        captureId: String,
        activityId: String? = null,
        newActivity: NewCanonicalActivity? = null,
        now: Long = T0 + 100,
    ) = AcceptInterpretationRequest(
        rawCaptureId = captureId,
        interpretation = newInterpretation(captureId, matched = activityId),
        newActivity = newActivity,
        canonicalActivityId = activityId,
        occurredAt = T0 - 60_000,
        timePrecision = TimePrecision.APPROXIMATE,
        activityState = ActivityState.COMPLETED,
        now = now,
    )

    private fun occurrence(occurrenceId: String) = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))

    // --- correction scenario (TEST_STRATEGY section 8) -------------------------

    @Test
    fun correctionScenarioMowLawnCorrectedToEdgeLawn() {
        val mow = activity(id(1), "Mow lawn")
        val edge = activity(id(2), "Edge lawn")
        val raw = capture(id(3), text = "I edged the lawn.")

        val occurrenceId = writer.acceptInterpretation(acceptRequest(raw.id, activityId = mow.id, now = T0 + 100))
        val originalInterpretations = db.interpretationDao().listForRawCapture(raw.id)
        assertEquals(1, originalInterpretations.size)
        val original = originalInterpretations.single()
        assertEquals(mow.id, occurrence(occurrenceId).canonicalActivityId)
        assertEquals(occurrenceId, db.activityOccurrenceDao().latestOccurrenceOfActivity(mow.id)?.id)

        val correctionId = writer.applyCorrection(
            occurrenceId, OccurrenceChanges(canonicalActivityId = edge.id), CorrectionSource.USER, reason = null,
            now = T0 + 200,
        )

        assertEquals("I edged the lawn.", assertNotNull(db.rawCaptureDao().getById(raw.id)).rawText)
        assertEquals(originalInterpretations, db.interpretationDao().listForRawCapture(raw.id))

        val corrections = db.correctionDao().listForOccurrence(occurrenceId)
        assertEquals(1, corrections.size)
        val correction = corrections.single()
        assertEquals(correctionId, correction.id)
        assertEquals(CorrectionSource.USER, correction.source)
        assertEquals(mow.id, correction.previousCanonicalActivityId)
        assertEquals(edge.id, correction.newCanonicalActivityId)
        assertNull(correction.previousOccurredAt); assertNull(correction.newOccurredAt)
        assertNull(correction.previousTimePrecision); assertNull(correction.newTimePrecision)
        assertNull(correction.previousActivityState); assertNull(correction.newActivityState)
        assertNull(correction.previousEffectiveInterpretationId); assertNull(correction.newEffectiveInterpretationId)

        val after = occurrence(occurrenceId)
        assertEquals(edge.id, after.canonicalActivityId)
        assertEquals(T0 + 200, after.updatedAt)
        assertTrue(after.updatedAt > after.createdAt)
        assertNull(db.activityOccurrenceDao().latestOccurrenceOfActivity(mow.id))
        assertEquals(occurrenceId, db.activityOccurrenceDao().latestOccurrenceOfActivity(edge.id)?.id)
        assertEquals(original.id, after.effectiveInterpretationId)
    }

    // --- raw text is write-once ------------------------------------------------

    @Test
    fun rawCaptureEvidenceIsUnchangedByEveryWriteOperation() {
        val first = activity(id(1), "First")
        val second = activity(id(2), "Second")
        val inserted = capture(id(3), text = "original words")

        assertEquals(1, db.rawCaptureDao().updateProcessingState(inserted.id, ProcessingState.INTERPRETING, T0 + 10))
        val occurrenceId = writer.acceptInterpretation(
            acceptRequest(inserted.id, newActivity = NewCanonicalActivity("Brand new", "brand new")),
        )
        assertEquals(
            occurrenceId,
            writer.acceptInterpretation(acceptRequest(inserted.id, activityId = first.id, now = T0 + 150)),
        )

        // A second interpretation of the same capture, so the effective interpretation can be corrected.
        val reinterpretation = Fixtures.interpretation(id(4), inserted.id, second.id)
        db.interpretationDao().insert(reinterpretation)

        val now = T0 + 1_000
        listOf(
            OccurrenceChanges(canonicalActivityId = second.id),
            OccurrenceChanges(occurredAt = T0 - 1),
            OccurrenceChanges(timePrecision = TimePrecision.DATE_ONLY),
            OccurrenceChanges(activityState = ActivityState.IN_PROGRESS),
            OccurrenceChanges(effectiveInterpretationId = reinterpretation.id),
        ).forEachIndexed { i, change ->
            assertNotNull(writer.applyCorrection(occurrenceId, change, CorrectionSource.USER, "fix $i", now + i))
        }
        assertEquals(5, db.correctionDao().listForOccurrence(occurrenceId).size)

        val after = assertNotNull(db.rawCaptureDao().getById(inserted.id))
        assertEquals(inserted.rawText, after.rawText)
        assertEquals(inserted.source, after.source)
        assertEquals(inserted.capturedAt, after.capturedAt)
        assertEquals(inserted.capturedZoneId, after.capturedZoneId)
        // And at the storage level, bypassing converters.
        sql.query(
            "SELECT raw_text, source, captured_at, captured_zone_id FROM raw_captures WHERE id = ?",
            arrayOf(inserted.id),
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(listOf("original words", "PHONE_VOICE", T0.toString(), "Europe/London"),
                (0 until 4).map { c.getString(it) })
        }
        assertEquals(ProcessingState.PERSISTED, after.processingState)
    }

    // --- atomicity ---------------------------------------------------------------

    @Test
    fun acceptWithUnknownCanonicalActivityRollsBackEverything() {
        activity(id(1), "Existing")
        val raw = capture(id(3))
        val activitiesBefore = sql.count("canonical_activities")

        assertFailsWith<SQLiteConstraintException> {
            writer.acceptInterpretation(acceptRequest(raw.id, activityId = id(999)).copy(
                interpretation = newInterpretation(raw.id, matched = null),
            ))
        }

        assertEquals(0, sql.count("interpretations"))
        assertEquals(activitiesBefore, sql.count("canonical_activities"))
        assertEquals(0, sql.count("activity_occurrences"))
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(raw.processingState, after.processingState)
        assertEquals(raw.updatedAt, after.updatedAt)
    }

    @Test
    fun acceptCreatingNewActivityRollsBackActivityWhenLaterStepFails() {
        // An existing occurrence (for a different capture) whose id the scripted factory will reuse.
        val existingActivity = activity(id(1), "Existing")
        val otherCapture = capture(id(2))
        val existingOccurrence = writer.acceptInterpretation(acceptRequest(otherCapture.id, activityId = existingActivity.id))
        val raw = capture(id(3))

        val interpretationsBefore = sql.count("interpretations")
        val activitiesBefore = sql.count("canonical_activities")
        val occurrencesBefore = sql.count("activity_occurrences")

        // interpretation id, new activity id, then a DUPLICATE occurrence id -> the occurrence insert fails
        // after the interpretation and the new canonical activity were already inserted.
        val scripted = ArrayDeque(listOf(id(50), id(51), existingOccurrence))
        val failingWriter = ActivityLedgerWriter(db, IdFactory { scripted.removeFirst() })

        assertFailsWith<SQLiteConstraintException> {
            failingWriter.acceptInterpretation(
                acceptRequest(raw.id, newActivity = NewCanonicalActivity("Would be new", "would be new")),
            )
        }
        assertTrue(scripted.isEmpty(), "all three ids were consumed, so both earlier inserts really ran")

        assertEquals(interpretationsBefore, sql.count("interpretations"))
        assertEquals(0, sql.count("interpretations", "raw_capture_id = ?", raw.id))
        assertEquals(activitiesBefore, sql.count("canonical_activities"))
        assertNull(db.canonicalActivityDao().getById(id(51)))
        assertEquals(occurrencesBefore, sql.count("activity_occurrences"))
        assertEquals(0, sql.count("activity_occurrences", "raw_capture_id = ?", raw.id))
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(raw.processingState, after.processingState)
        assertEquals(raw.updatedAt, after.updatedAt)
    }

    @Test
    fun acceptRejectsInterpretationOfAnotherRawCapture() {
        val a = activity(id(1), "Existing")
        val raw = capture(id(3))
        val other = capture(id(4))
        assertFailsWith<IllegalArgumentException> {
            writer.acceptInterpretation(
                acceptRequest(raw.id, activityId = a.id).copy(interpretation = newInterpretation(other.id, a.id)),
            )
        }
        assertEquals(0, sql.count("interpretations"))
        assertEquals(0, sql.count("activity_occurrences"))
        assertEquals(raw.processingState, db.rawCaptureDao().getById(raw.id)?.processingState)
    }

    // --- idempotency -------------------------------------------------------------

    @Test
    fun acceptTwiceForSameRawCaptureIsIdempotent() {
        val raw = capture(id(3))
        val activitiesBefore = sql.count("canonical_activities")

        val first = writer.acceptInterpretation(
            acceptRequest(raw.id, newActivity = NewCanonicalActivity("Walk dog", "walk dog"), now = T0 + 100),
        )
        val second = writer.acceptInterpretation(
            acceptRequest(raw.id, newActivity = NewCanonicalActivity("Walk the dog", "walk the dog"), now = T0 + 500),
        )

        assertEquals(first, second)
        assertEquals(1, sql.count("activity_occurrences"))
        assertEquals(1, sql.count("interpretations"))
        assertEquals(activitiesBefore + 1, sql.count("canonical_activities"))
        assertEquals(0, sql.count("canonical_activities", "normalized_name = ?", "walk the dog"))
        val created = assertNotNull(db.canonicalActivityDao().getById(occurrence(first).canonicalActivityId))
        assertEquals(CanonicalActivityStatus.ACTIVE, created.status)
        // The retry wrote nothing: the capture still carries the first call's timestamp.
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(ProcessingState.PERSISTED, after.processingState)
        assertEquals(T0 + 100, after.updatedAt)
        val occ = occurrence(first)
        assertEquals(raw.capturedAt, occ.capturedAt)
        assertEquals(VisibilityStatus.ACTIVE, occ.visibilityStatus)
        assertEquals(T0 + 100, occ.createdAt)
        assertEquals(T0 + 100, occ.updatedAt)
    }

    // --- applyCorrection ---------------------------------------------------------

    private fun acceptedOccurrence(): Pair<String, String> {
        val a = activity(id(1), "Existing")
        val raw = capture(id(3))
        return writer.acceptInterpretation(acceptRequest(raw.id, activityId = a.id)) to raw.id
    }

    @Test
    fun noOpCorrectionReturnsNullAndWritesNothing() {
        val (occurrenceId, _) = acceptedOccurrence()
        val before = occurrence(occurrenceId)

        assertNull(writer.applyCorrection(occurrenceId, OccurrenceChanges(), CorrectionSource.USER, null, T0 + 900))
        assertNull(
            writer.applyCorrection(
                occurrenceId,
                OccurrenceChanges(
                    canonicalActivityId = before.canonicalActivityId,
                    occurredAt = before.occurredAt,
                    timePrecision = before.timePrecision,
                    activityState = before.activityState,
                    effectiveInterpretationId = before.effectiveInterpretationId,
                ),
                CorrectionSource.USER, null, T0 + 900,
            ),
        )
        assertEquals(0, sql.count("corrections"))
        assertEquals(before, occurrence(occurrenceId))
    }

    @Test
    fun effectiveInterpretationFromAnotherRawCaptureIsRejected() {
        val (occurrenceId, _) = acceptedOccurrence()
        val before = occurrence(occurrenceId)
        val otherCapture = capture(id(10))
        val foreign = Fixtures.interpretation(id(11), otherCapture.id, matchedActivityId = null)
        db.interpretationDao().insert(foreign)

        assertFailsWith<IllegalArgumentException> {
            writer.applyCorrection(
                occurrenceId, OccurrenceChanges(effectiveInterpretationId = foreign.id, occurredAt = T0 + 5),
                CorrectionSource.REINTERPRETATION, null, T0 + 900,
            )
        }
        assertEquals(0, sql.count("corrections"))
        assertEquals(before, occurrence(occurrenceId))
    }

    @Test
    fun multiFieldCorrectionRecordsAllChangedPairsInOneRow() {
        val (occurrenceId, _) = acceptedOccurrence()
        val before = occurrence(occurrenceId)
        val target = activity(id(20), "Target")

        val correctionId = writer.applyCorrection(
            occurrenceId,
            OccurrenceChanges(
                canonicalActivityId = target.id,
                occurredAt = T0 + 42,
                timePrecision = TimePrecision.EXACT,
                activityState = ActivityState.IN_PROGRESS,
                effectiveInterpretationId = before.effectiveInterpretationId, // unchanged -> null pair
            ),
            CorrectionSource.USER, "several things", T0 + 900,
        )

        val row = db.correctionDao().listForOccurrence(occurrenceId).single()
        assertEquals(correctionId, row.id)
        assertEquals(before.canonicalActivityId, row.previousCanonicalActivityId)
        assertEquals(target.id, row.newCanonicalActivityId)
        assertEquals(before.occurredAt, row.previousOccurredAt)
        assertEquals(T0 + 42, row.newOccurredAt)
        assertEquals(TimePrecision.APPROXIMATE, row.previousTimePrecision)
        assertEquals(TimePrecision.EXACT, row.newTimePrecision)
        assertEquals(ActivityState.COMPLETED, row.previousActivityState)
        assertEquals(ActivityState.IN_PROGRESS, row.newActivityState)
        assertNull(row.previousEffectiveInterpretationId)
        assertNull(row.newEffectiveInterpretationId)
        assertEquals("several things", row.reason)
        assertEquals(T0 + 900, row.createdAt)

        val after = occurrence(occurrenceId)
        assertEquals(
            before.copy(
                canonicalActivityId = target.id,
                occurredAt = T0 + 42,
                timePrecision = TimePrecision.EXACT,
                activityState = ActivityState.IN_PROGRESS,
                updatedAt = T0 + 900,
            ),
            after,
        )
    }

    @Test
    fun userAndReinterpretationSourcesArePersisted() {
        val (occurrenceId, rawId) = acceptedOccurrence()
        val reinterpretation = Fixtures.interpretation(id(30), rawId, matchedActivityId = null)
        db.interpretationDao().insert(reinterpretation)

        val userCorrection = writer.applyCorrection(
            occurrenceId, OccurrenceChanges(activityState = ActivityState.IN_PROGRESS), CorrectionSource.USER, null, T0 + 800,
        )
        val machineCorrection = writer.applyCorrection(
            occurrenceId, OccurrenceChanges(effectiveInterpretationId = reinterpretation.id),
            CorrectionSource.REINTERPRETATION, null, T0 + 900,
        )
        assertNotEquals(userCorrection, machineCorrection)

        val rows = db.correctionDao().listForOccurrence(occurrenceId)
        assertEquals(listOf(userCorrection, machineCorrection), rows.map { it.id })
        assertEquals(listOf(CorrectionSource.USER, CorrectionSource.REINTERPRETATION), rows.map { it.source })
        assertEquals(1, sql.count("corrections", "id = ? AND source = 'USER'", userCorrection))
        assertEquals(1, sql.count("corrections", "id = ? AND source = 'REINTERPRETATION'", machineCorrection))
        assertEquals(reinterpretation.id, occurrence(occurrenceId).effectiveInterpretationId)
        assertEquals(reinterpretation.id, rows[1].newEffectiveInterpretationId)
    }

    // --- latestOccurrenceOfActivity ------------------------------------------

    private fun rawOccurrence(n: Int, activityId: String, occurredAt: Long, visibility: VisibilityStatus): String {
        val captureId = id(100 + n)
        val interpretationId = id(200 + n)
        val occurrenceId = id(300 + n)
        capture(captureId)
        sql.insertInterpretation(Fixtures.interpretation(interpretationId, captureId, matchedActivityId = null))
        sql.insertActivityOccurrence(
            Fixtures.occurrence(occurrenceId, activityId, captureId, interpretationId)
                .copy(occurredAt = occurredAt, visibilityStatus = visibility),
        )
        return occurrenceId
    }

    @Test
    fun latestOccurrenceIgnoresHiddenAndPicksGreatestOccurredAt() {
        val a = activity(id(1), "A")
        val b = activity(id(2), "B")
        val onlyHidden = activity(id(3), "Only hidden")
        rawOccurrence(1, a.id, T0 + 10, VisibilityStatus.ACTIVE)
        val newestActive = rawOccurrence(2, a.id, T0 + 30, VisibilityStatus.ACTIVE)
        rawOccurrence(3, a.id, T0 + 20, VisibilityStatus.ACTIVE)
        rawOccurrence(4, a.id, T0 + 50, VisibilityStatus.HIDDEN)
        rawOccurrence(5, b.id, T0 + 100, VisibilityStatus.ACTIVE)
        rawOccurrence(6, onlyHidden.id, T0 + 100, VisibilityStatus.HIDDEN)

        assertEquals(newestActive, db.activityOccurrenceDao().latestOccurrenceOfActivity(a.id)?.id)
        assertNull(db.activityOccurrenceDao().latestOccurrenceOfActivity(onlyHidden.id))
        assertNull(db.activityOccurrenceDao().latestOccurrenceOfActivity(id(999)))
    }

    @Test
    fun latestOccurrenceQueryUsesCanonicalActivityOccurredAtIndex() {
        val querySql = DaoWriteSurfaceGuardTest.querySql(ActivityOccurrenceDao::class.java, "latestOccurrenceOfActivity")
        val plan = sql.query("EXPLAIN QUERY PLAN $querySql", arrayOf(id(1))).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            buildList { while (c.moveToNext()) add(c.getString(detail)) }
        }
        assertTrue(
            plan.any { it.contains("USING INDEX index_activity_occurrences_canonical_activity_id_occurred_at") },
            "query plan: $plan",
        )
        assertTrue(plan.none { it.startsWith("SCAN activity_occurrences") && !it.contains("INDEX") }, "plan: $plan")
    }
}
