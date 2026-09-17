package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.Fixtures
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.T0
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.id
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.dao.ActivityOccurrenceDao
import com.mcfrenchpants.activityledger.core.data.db.dao.DaoWriteSurfaceGuardTest
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.insertActivityAlias
import com.mcfrenchpants.activityledger.core.data.db.insertActivityOccurrence
import com.mcfrenchpants.activityledger.core.data.db.insertCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.db.insertInterpretation
import com.mcfrenchpants.activityledger.core.data.db.insertRawCapture
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
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
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityView
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.OccurrenceView
import com.mcfrenchpants.activityledger.core.domain.repository.StoredCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RoomActivityRepository on the real (in-memory) Room database. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class RoomActivityRepositoryTest {

    /** A fixed clock that tests can move forward explicitly. */
    private class SteppingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: RoomActivityRepository
    private val sql get() = db.openHelper.writableDatabase
    private val clock = SteppingClock(Instant.ofEpochMilli(NOW))
    private val ids = DeterministicIdFactory(next = 0x10_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, ids, clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers -------------------------------------------------------------

    private fun newCapture(text: String = "synthetic words") = NewRawCapture(
        source = CaptureSource.WATCH_VOICE,
        sourceSurface = "test-surface",
        capturedAt = Instant.ofEpochMilli(CAPTURED),
        zoneId = ZoneId.of("America/New_York"),
        rawText = text,
        speechConfidence = 0.73,
        speechAlternativesJson = "[\"synthetic alternative\"]",
        processingState = ProcessingState.INTERPRETING,
    )

    private fun record(
        matched: String? = null,
        interpreterVersion: String = "test-interpreter",
        validationStatus: ValidationStatus = ValidationStatus.VALID,
        validationReason: String? = null,
    ) = InterpretationRecord(
        createdAt = Instant.ofEpochMilli(NOW - 5),
        interpreterVersion = interpreterVersion,
        promptVersion = "test-prompt",
        schemaVersion = 3,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = if (matched == null) ActivityResolution.NEW_ACTIVITY else ActivityResolution.EXISTING_ACTIVITY,
        matchedActivityId = matched,
        proposedCanonicalName = if (matched == null) "Synthetic proposal" else null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = "yesterday",
        resolvedOccurredAt = Instant.ofEpochMilli(CAPTURED - 86_400_000),
        timePrecision = TimePrecision.DATE_ONLY,
        modelConfidenceBand = ConfidenceBand.MEDIUM,
        candidateContextHash = "hash",
        structuredResultJson = "{}",
        validationStatus = validationStatus,
        validationReason = validationReason,
    )

    /** Inserts a canonical activity via test SQL (any status). */
    private fun activity(
        activityId: String,
        displayName: String = "Activity $activityId",
        status: CanonicalActivityStatus = CanonicalActivityStatus.ACTIVE,
        mergedInto: String? = null,
    ) = sql.insertCanonicalActivity(
        Fixtures.canonicalActivity(activityId, normalizedName = NameNormalizer.normalize(displayName))
            .copy(displayName = displayName, status = status, mergedIntoActivityId = mergedInto),
    )

    private suspend fun accept(captureId: String, target: ActivityTarget, matched: String? = null) =
        repository.acceptInterpretation(
            captureId, record(matched), target, Instant.ofEpochMilli(CAPTURED - 60_000),
            TimePrecision.APPROXIMATE, ActivityState.COMPLETED,
        )

    private fun rawRow(captureId: String): RawCaptureEntity = assertNotNull(db.rawCaptureDao().getById(captureId))

    private fun advanceClock(millis: Long = 1_000) {
        clock.now = clock.now.plusMillis(millis)
    }

    // --- round trips ---------------------------------------------------------

    @Test
    fun createRawCaptureAndGetCaptureRoundTrip() = runBlocking<Unit> {
        val input = newCapture()
        val captureId = repository.createRawCapture(input)

        assertEquals(
            StoredCapture(
                id = captureId,
                rawText = "synthetic words",
                source = CaptureSource.WATCH_VOICE,
                capturedAt = Instant.ofEpochMilli(CAPTURED),
                zoneId = ZoneId.of("America/New_York"),
                speechConfidence = 0.73,
                processingState = ProcessingState.INTERPRETING,
                hasOccurrence = false,
            ),
            repository.getCapture(captureId),
        )
        val row = rawRow(captureId)
        assertEquals("test-surface", row.sourceSurface)
        assertEquals("[\"synthetic alternative\"]", row.speechAlternativesJson)
        assertEquals(NOW, row.createdAt)
        assertEquals(NOW, row.updatedAt)

        advanceClock()
        activity(id(1))
        accept(captureId, ActivityTarget.Existing(id(1)), matched = id(1))
        val after = assertNotNull(repository.getCapture(captureId))
        assertTrue(after.hasOccurrence)
        assertEquals(ProcessingState.PERSISTED, after.processingState)
        assertEquals(clock.millis(), rawRow(captureId).updatedAt)
    }

    @Test
    fun getOccurrenceAndGetActivityMapEveryField() = runBlocking<Unit> {
        val captureId = repository.createRawCapture(newCapture())
        advanceClock()
        val occurrenceId = accept(captureId, ActivityTarget.New("  Walk the Dog! "))

        val interpretation = db.interpretationDao().listForRawCapture(captureId).single()
        val entity = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))
        assertEquals(
            OccurrenceView(
                id = occurrenceId,
                canonicalActivityId = entity.canonicalActivityId,
                rawCaptureId = captureId,
                effectiveInterpretationId = interpretation.id,
                capturedAt = Instant.ofEpochMilli(CAPTURED),
                occurredAt = Instant.ofEpochMilli(CAPTURED - 60_000),
                timePrecision = TimePrecision.APPROXIMATE,
                activityState = ActivityState.COMPLETED,
                visibilityStatus = VisibilityStatus.ACTIVE,
            ),
            repository.getOccurrence(occurrenceId),
        )
        assertEquals(clock.millis(), entity.createdAt)
        assertEquals(
            ActivityView(
                id = entity.canonicalActivityId,
                displayName = "  Walk the Dog! ",
                normalizedName = NameNormalizer.normalize("  Walk the Dog! "),
                status = CanonicalActivityStatus.ACTIVE,
            ),
            repository.getActivity(entity.canonicalActivityId),
        )
        assertEquals(clock.millis(), db.canonicalActivityDao().getById(entity.canonicalActivityId)?.createdAt)

        // The interpretation record was mapped field by field.
        assertEquals(captureId, interpretation.rawCaptureId)
        assertEquals(NOW - 5, interpretation.createdAt)
        assertEquals(3, interpretation.schemaVersion)
        assertEquals(CAPTURED - 86_400_000, interpretation.resolvedOccurredAt)
        assertEquals("yesterday", interpretation.temporalExpression)
        assertEquals(ConfidenceBand.MEDIUM, interpretation.modelConfidenceBand)
        assertEquals(TimePrecision.DATE_ONLY, interpretation.timePrecision)
        assertEquals("hash", interpretation.candidateContextHash)
        assertEquals("{}", interpretation.structuredResultJson)
        assertEquals("Synthetic proposal", interpretation.proposedCanonicalName)
    }

    @Test
    fun unknownIdsReturnNull() = runBlocking<Unit> {
        assertNull(repository.getCapture(id(999)))
        assertNull(repository.getOccurrence(id(999)))
        assertNull(repository.getActivity(id(999)))
    }

    // --- loadCatalog ---------------------------------------------------------

    private fun occurrenceOf(n: Int, activityId: String, occurredAt: Long, visibility: VisibilityStatus) {
        val captureId = id(100 + n)
        sql.insertRawCapture(Fixtures.rawCapture(captureId))
        sql.insertInterpretation(Fixtures.interpretation(id(200 + n), captureId, matchedActivityId = null))
        sql.insertActivityOccurrence(
            Fixtures.occurrence(id(300 + n), activityId, captureId, id(200 + n))
                .copy(occurredAt = occurredAt, visibilityStatus = visibility),
        )
    }

    @Test
    fun loadCatalogReturnsActiveActivitiesWithAliasesAndLastOccurrence() = runBlocking<Unit> {
        activity(id(3), "A name")
        activity(id(2), "B name")
        activity(id(1), "A name")
        activity(id(4), "Archived", CanonicalActivityStatus.ARCHIVED)
        activity(id(5), "Merged", CanonicalActivityStatus.MERGED, mergedInto = id(1))

        sql.insertActivityAlias(Fixtures.alias(id(12), id(3), "zeta").copy(createdAt = T0 + 5))
        sql.insertActivityAlias(Fixtures.alias(id(11), id(3), "yolo").copy(createdAt = T0 + 5))
        sql.insertActivityAlias(Fixtures.alias(id(13), id(3), "alpha").copy(createdAt = T0 + 1))
        sql.insertActivityAlias(Fixtures.alias(id(14), id(4), "archived alias"))
        sql.insertActivityAlias(Fixtures.alias(id(15), id(5), "merged alias"))

        occurrenceOf(1, id(3), T0 + 10, VisibilityStatus.ACTIVE)
        occurrenceOf(2, id(3), T0 + 30, VisibilityStatus.ACTIVE)
        occurrenceOf(3, id(3), T0 + 50, VisibilityStatus.HIDDEN)
        occurrenceOf(4, id(2), T0 + 70, VisibilityStatus.HIDDEN)
        occurrenceOf(5, id(4), T0 + 90, VisibilityStatus.ACTIVE)

        assertEquals(
            listOf(
                CatalogActivity(id(1), "A name", "a name", emptyList(), null),
                CatalogActivity(id(3), "A name", "a name", listOf("alpha", "yolo", "zeta"), Instant.ofEpochMilli(T0 + 30)),
                CatalogActivity(id(2), "B name", "b name", emptyList(), null),
            ),
            repository.loadCatalog(),
        )
    }

    @Test
    fun lastOccurredAtComesFromOneGroupedQuery() {
        val querySql = DaoWriteSurfaceGuardTest.querySql(
            ActivityOccurrenceDao::class.java, "lastActiveOccurredAtPerActivity",
        )
        assertTrue(querySql.contains("MAX(occurred_at)"), querySql)
        assertTrue(querySql.contains("GROUP BY canonical_activity_id"), querySql)
        assertTrue(!querySql.contains(":"), "the query takes no per-activity parameter: $querySql")
    }

    // --- recordOutcome -------------------------------------------------------

    @Test
    fun recordOutcomeWithInterpretationStoresItAndSetsState() = runBlocking<Unit> {
        val captureId = repository.createRawCapture(newCapture())
        advanceClock()
        repository.recordOutcome(
            captureId, record(validationStatus = ValidationStatus.INVALID, validationReason = "REASON_CODE"),
            ProcessingState.NEEDS_REVIEW,
        )
        val stored = db.interpretationDao().listForRawCapture(captureId).single()
        assertEquals(captureId, stored.rawCaptureId)
        assertEquals(ValidationStatus.INVALID, stored.validationStatus)
        assertEquals("REASON_CODE", stored.validationReason)
        val row = rawRow(captureId)
        assertEquals(ProcessingState.NEEDS_REVIEW, row.processingState)
        assertEquals(clock.millis(), row.updatedAt)
        assertEquals(0, sql.count("activity_occurrences"))
    }

    @Test
    fun recordOutcomeWithoutInterpretationOnlyChangesState() = runBlocking<Unit> {
        val captureId = repository.createRawCapture(newCapture())
        advanceClock()
        repository.recordOutcome(captureId, null, ProcessingState.FAILED_RETRYABLE)
        assertEquals(0, sql.count("interpretations"))
        val row = rawRow(captureId)
        assertEquals(ProcessingState.FAILED_RETRYABLE, row.processingState)
        assertEquals(clock.millis(), row.updatedAt)
    }

    @Test
    fun recordOutcomeFailureAfterStateUpdateRollsBackEverything() = runBlocking<Unit> {
        val captureId = repository.createRawCapture(newCapture())
        val before = rawRow(captureId)
        advanceClock()

        // The DAO updates processing_state first, then inserts the interpretation, whose
        // matched_activity_id references a missing activity: the foreign key fails after the
        // state update, so an unchanged state proves the rollback.
        assertFailsWith<IllegalArgumentException> {
            repository.recordOutcome(captureId, record(matched = id(999)), ProcessingState.NEEDS_REVIEW)
        }
        assertEquals(0, sql.count("interpretations"))
        assertEquals(before, rawRow(captureId))
    }

    @Test
    fun recordOutcomeRefusalsWriteNothing() = runBlocking<Unit> {
        activity(id(1))
        val accepted = repository.createRawCapture(newCapture())
        accept(accepted, ActivityTarget.Existing(id(1)))
        val pending = repository.createRawCapture(newCapture())
        advanceClock()
        val interpretationsBefore = sql.count("interpretations")
        val acceptedBefore = rawRow(accepted)
        val pendingBefore = rawRow(pending)

        assertFailsWith<IllegalArgumentException> {
            repository.recordOutcome(id(999), record(), ProcessingState.NEEDS_REVIEW)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.recordOutcome(accepted, record(), ProcessingState.NEEDS_REVIEW)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.recordOutcome(accepted, null, ProcessingState.FAILED_FINAL)
        }
        for (state in listOf(ProcessingState.PERSISTED, ProcessingState.CAPTURED)) {
            assertFailsWith<IllegalArgumentException> { repository.recordOutcome(pending, record(), state) }
            assertFailsWith<IllegalArgumentException> { repository.recordOutcome(pending, null, state) }
        }

        assertEquals(interpretationsBefore, sql.count("interpretations"))
        assertEquals(acceptedBefore, rawRow(accepted))
        assertEquals(pendingBefore, rawRow(pending))
        assertEquals(0, sql.count("raw_captures", "id = ?", id(999)))
    }

    // --- acceptInterpretation ------------------------------------------------

    @Test
    fun acceptWithExistingTargetPointsAtIt() = runBlocking<Unit> {
        activity(id(1))
        val captureId = repository.createRawCapture(newCapture())
        val occurrenceId = accept(captureId, ActivityTarget.Existing(id(1)), matched = id(1))
        assertEquals(id(1), repository.getOccurrence(occurrenceId)?.canonicalActivityId)
        assertEquals(id(1), db.interpretationDao().listForRawCapture(captureId).single().matchedActivityId)
        assertEquals(1, sql.count("canonical_activities"))
    }

    @Test
    fun acceptWithNewTargetCreatesNormalizedActiveActivity() = runBlocking<Unit> {
        val captureId = repository.createRawCapture(newCapture())
        val occurrenceId = accept(captureId, ActivityTarget.New("Re-Caulk  the TUB."))
        val activityId = assertNotNull(repository.getOccurrence(occurrenceId)).canonicalActivityId
        val created = assertNotNull(repository.getActivity(activityId))
        assertEquals(CanonicalActivityStatus.ACTIVE, created.status)
        assertEquals(NameNormalizer.normalize("Re-Caulk  the TUB."), created.normalizedName)
        assertEquals("re-caulk the tub", created.normalizedName)
    }

    @Test
    fun acceptRetryReturnsSameOccurrenceAndWritesNothing() = runBlocking<Unit> {
        activity(id(1))
        val captureId = repository.createRawCapture(newCapture())
        val first = accept(captureId, ActivityTarget.New("First name"))
        val counts = listOf("interpretations", "canonical_activities", "activity_occurrences").map { sql.count(it) }
        val captureBefore = rawRow(captureId)
        val occurrenceBefore = db.activityOccurrenceDao().getById(first)
        advanceClock()

        assertEquals(first, accept(captureId, ActivityTarget.New("Other name")))
        assertEquals(first, accept(captureId, ActivityTarget.Existing(id(1))))

        assertEquals(counts, listOf("interpretations", "canonical_activities", "activity_occurrences").map { sql.count(it) })
        assertEquals(captureBefore, rawRow(captureId))
        assertEquals(occurrenceBefore, db.activityOccurrenceDao().getById(first))
    }

    @Test
    fun acceptWithArchivedMergedOrMissingTargetIsRefused() = runBlocking<Unit> {
        activity(id(1))
        activity(id(2), status = CanonicalActivityStatus.ARCHIVED)
        activity(id(3), status = CanonicalActivityStatus.MERGED, mergedInto = id(1))
        val captureId = repository.createRawCapture(newCapture())
        val before = rawRow(captureId)
        advanceClock()

        for (target in listOf(id(2), id(3), id(999))) {
            assertFailsWith<IllegalArgumentException>("target $target") {
                accept(captureId, ActivityTarget.Existing(target))
            }
        }
        assertEquals(0, sql.count("interpretations"))
        assertEquals(0, sql.count("activity_occurrences"))
        assertEquals(3, sql.count("canonical_activities"))
        assertEquals(before, rawRow(captureId))
        assertEquals(false, repository.getCapture(captureId)?.hasOccurrence)
    }

    // --- review resolution shape -----------------------------------------------

    @Test
    fun invalidModelInterpretationThenUserResolutionAccept() = runBlocking<Unit> {
        activity(id(1))
        val captureId = repository.createRawCapture(newCapture())
        repository.recordOutcome(
            captureId,
            record(interpreterVersion = "model-v1", validationStatus = ValidationStatus.INVALID, validationReason = "UNKNOWN_CANDIDATE"),
            ProcessingState.NEEDS_REVIEW,
        )
        val invalidRow = db.interpretationDao().listForRawCapture(captureId).single()
        advanceClock()

        val occurrenceId = repository.acceptInterpretation(
            captureId,
            record(matched = id(1), interpreterVersion = "user-resolution"),
            ActivityTarget.Existing(id(1)),
            Instant.ofEpochMilli(CAPTURED), TimePrecision.EXACT, ActivityState.COMPLETED,
        )

        val rows = db.interpretationDao().listForRawCapture(captureId)
        assertEquals(2, rows.size)
        assertEquals(invalidRow, rows.single { it.id == invalidRow.id })
        val resolution = rows.single { it.id != invalidRow.id }
        assertEquals("user-resolution", resolution.interpreterVersion)
        assertEquals(resolution.id, repository.getOccurrence(occurrenceId)?.effectiveInterpretationId)
        assertEquals(ProcessingState.PERSISTED, repository.getCapture(captureId)?.processingState)
    }

    // --- applyCorrection -------------------------------------------------------

    private suspend fun acceptedOccurrence(): String {
        activity(id(1), "Mow lawn")
        val captureId = repository.createRawCapture(newCapture())
        return accept(captureId, ActivityTarget.Existing(id(1)), matched = id(1))
    }

    @Test
    fun correctionToExistingActivityRecordsPreviousAndNewIds() = runBlocking<Unit> {
        val occurrenceId = acceptedOccurrence()
        activity(id(2), "Edge lawn")

        val outcome = repository.applyCorrection(
            occurrenceId, CorrectionChanges(activity = ActivityTarget.Existing(id(2))), CorrectionSource.USER,
            "wrong activity", Instant.ofEpochMilli(NOW + 500),
        )

        val applied = assertIs<CorrectionOutcome.Applied>(outcome)
        val row = db.correctionDao().listForOccurrence(occurrenceId).single()
        assertEquals(applied.correctionId, row.id)
        assertEquals(id(1), row.previousCanonicalActivityId)
        assertEquals(id(2), row.newCanonicalActivityId)
        assertEquals("wrong activity", row.reason)
        assertEquals(NOW + 500, row.createdAt)
        assertEquals(id(2), repository.getOccurrence(occurrenceId)?.canonicalActivityId)
    }

    @Test
    fun correctionToNewActivityCreatesActiveActivityAndOneCorrection() = runBlocking<Unit> {
        val occurrenceId = acceptedOccurrence()
        val activitiesBefore = sql.count("canonical_activities")

        val outcome = repository.applyCorrection(
            occurrenceId, CorrectionChanges(activity = ActivityTarget.New("Edge Lawn")), CorrectionSource.USER,
            null, Instant.ofEpochMilli(NOW + 500),
        )

        val applied = assertIs<CorrectionOutcome.Applied>(outcome)
        assertEquals(activitiesBefore + 1, sql.count("canonical_activities"))
        val row = db.correctionDao().listForOccurrence(occurrenceId).single()
        assertEquals(applied.correctionId, row.id)
        val newId = assertNotNull(row.newCanonicalActivityId)
        assertNotEquals(id(1), newId)
        assertEquals(id(1), row.previousCanonicalActivityId)
        assertEquals(ActivityView(newId, "Edge Lawn", "edge lawn", CanonicalActivityStatus.ACTIVE), repository.getActivity(newId))
        assertEquals(newId, repository.getOccurrence(occurrenceId)?.canonicalActivityId)
    }

    @Test
    fun correctionToNewActivityForUnknownOccurrenceLeavesNoActivity() = runBlocking<Unit> {
        acceptedOccurrence()
        val activitiesBefore = sql.count("canonical_activities")
        assertFailsWith<IllegalArgumentException> {
            repository.applyCorrection(
                id(999), CorrectionChanges(activity = ActivityTarget.New("Orphan")), CorrectionSource.USER,
                null, Instant.ofEpochMilli(NOW + 500),
            )
        }
        assertEquals(activitiesBefore, sql.count("canonical_activities"))
        assertEquals(0, sql.count("canonical_activities", "normalized_name = ?", "orphan"))
        assertEquals(0, sql.count("corrections"))
    }

    @Test
    fun timePrecisionAndStateCorrectionsMapCorrectly() = runBlocking<Unit> {
        val occurrenceId = acceptedOccurrence()
        val before = assertNotNull(repository.getOccurrence(occurrenceId))
        val newTime = Instant.ofEpochMilli(CAPTURED - 3_600_000)

        val outcome = repository.applyCorrection(
            occurrenceId,
            CorrectionChanges(occurredAt = newTime, timePrecision = TimePrecision.EXACT, activityState = ActivityState.IN_PROGRESS),
            CorrectionSource.REINTERPRETATION, null, Instant.ofEpochMilli(NOW + 700),
        )

        assertIs<CorrectionOutcome.Applied>(outcome)
        assertEquals(
            before.copy(occurredAt = newTime, timePrecision = TimePrecision.EXACT, activityState = ActivityState.IN_PROGRESS),
            repository.getOccurrence(occurrenceId),
        )
        val row = db.correctionDao().listForOccurrence(occurrenceId).single()
        assertEquals(CorrectionSource.REINTERPRETATION, row.source)
        assertEquals(before.occurredAt.toEpochMilli(), row.previousOccurredAt)
        assertEquals(newTime.toEpochMilli(), row.newOccurredAt)
        assertEquals(TimePrecision.APPROXIMATE, row.previousTimePrecision)
        assertEquals(TimePrecision.EXACT, row.newTimePrecision)
        assertEquals(ActivityState.COMPLETED, row.previousActivityState)
        assertEquals(ActivityState.IN_PROGRESS, row.newActivityState)
        assertNull(row.previousCanonicalActivityId)
        assertNull(row.newCanonicalActivityId)
        assertEquals(NOW + 700, db.activityOccurrenceDao().getById(occurrenceId)?.updatedAt)
    }

    @Test
    fun correctionEqualToCurrentValuesChangesNothing() = runBlocking<Unit> {
        val occurrenceId = acceptedOccurrence()
        val current = assertNotNull(repository.getOccurrence(occurrenceId))
        val entityBefore = db.activityOccurrenceDao().getById(occurrenceId)

        for (changes in listOf(
            CorrectionChanges(),
            CorrectionChanges(
                activity = ActivityTarget.Existing(current.canonicalActivityId),
                occurredAt = current.occurredAt,
                timePrecision = current.timePrecision,
                activityState = current.activityState,
            ),
        )) {
            assertEquals(
                CorrectionOutcome.NothingChanged,
                repository.applyCorrection(occurrenceId, changes, CorrectionSource.USER, null, Instant.ofEpochMilli(NOW + 900)),
            )
        }
        assertEquals(0, sql.count("corrections"))
        assertEquals(entityBefore, db.activityOccurrenceDao().getById(occurrenceId))
    }

    @Test
    fun correctionToArchivedActivityIsRefused() = runBlocking<Unit> {
        val occurrenceId = acceptedOccurrence()
        activity(id(2), "Old thing", CanonicalActivityStatus.ARCHIVED)
        val entityBefore = db.activityOccurrenceDao().getById(occurrenceId)

        assertFailsWith<IllegalArgumentException> {
            repository.applyCorrection(
                occurrenceId, CorrectionChanges(activity = ActivityTarget.Existing(id(2)), activityState = ActivityState.IN_PROGRESS),
                CorrectionSource.USER, null, Instant.ofEpochMilli(NOW + 900),
            )
        }
        assertEquals(0, sql.count("corrections"))
        assertEquals(entityBefore, db.activityOccurrenceDao().getById(occurrenceId))
    }

    // --- raw capture immutability ------------------------------------------------

    @Test
    fun rawCaptureEvidenceIsUnchangedByEveryRepositoryWritePath() = runBlocking<Unit> {
        activity(id(1), "First")
        activity(id(2), "Second")
        val captureId = repository.createRawCapture(newCapture(text = "original synthetic words"))
        val storedBefore = storageEvidence(captureId)
        assertEquals(listOf("original synthetic words", "WATCH_VOICE", CAPTURED.toString(), "America/New_York"), storedBefore)

        repository.recordOutcome(captureId, null, ProcessingState.FAILED_RETRYABLE)
        repository.recordOutcome(captureId, record(validationStatus = ValidationStatus.INVALID), ProcessingState.NEEDS_REVIEW)
        val occurrenceId = accept(captureId, ActivityTarget.Existing(id(1)), matched = id(1))
        assertEquals(occurrenceId, accept(captureId, ActivityTarget.New("Retry")))

        var step = 0L
        suspend fun correct(changes: CorrectionChanges) = assertIs<CorrectionOutcome.Applied>(
            repository.applyCorrection(occurrenceId, changes, CorrectionSource.USER, "fix", Instant.ofEpochMilli(NOW + ++step)),
        )
        correct(CorrectionChanges(activity = ActivityTarget.Existing(id(2))))
        correct(CorrectionChanges(activity = ActivityTarget.New("Third")))
        correct(CorrectionChanges(occurredAt = Instant.ofEpochMilli(CAPTURED - 1)))
        correct(CorrectionChanges(timePrecision = TimePrecision.DATE_ONLY))
        correct(CorrectionChanges(activityState = ActivityState.IN_PROGRESS))
        assertEquals(5, db.correctionDao().listForOccurrence(occurrenceId).size)

        assertEquals(storedBefore, storageEvidence(captureId))
        val after = assertNotNull(repository.getCapture(captureId))
        assertEquals("original synthetic words", after.rawText)
        assertEquals(CaptureSource.WATCH_VOICE, after.source)
        assertEquals(Instant.ofEpochMilli(CAPTURED), after.capturedAt)
        assertEquals(ZoneId.of("America/New_York"), after.zoneId)
    }

    /** raw_text, source, captured_at, captured_zone_id as stored, bypassing converters. */
    private fun storageEvidence(captureId: String): List<String> =
        sql.query(
            "SELECT raw_text, source, captured_at, captured_zone_id FROM raw_captures WHERE id = ?",
            arrayOf(captureId),
        ).use { c ->
            assertTrue(c.moveToFirst())
            (0 until 4).map { c.getString(it) }
        }

    private companion object {
        const val NOW = 1_760_000_000_123L
        const val CAPTURED = 1_759_999_000_456L
    }
}
