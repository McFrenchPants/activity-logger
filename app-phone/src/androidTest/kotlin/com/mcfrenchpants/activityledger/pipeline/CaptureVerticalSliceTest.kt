package com.mcfrenchpants.activityledger.pipeline

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The on-device vertical slice: one hardcoded sentence goes into the real pipeline on a real
 * phone, and a correctly matched, correctly dated occurrence must come out of a real database.
 *
 * What makes this test worth running is that nothing in it is faked: the real Gemini Nano model
 * through core-ai, the real domain orchestrator, the real Room database on the device's disk.
 *
 * **It skips itself** (never fails, never passes vacuously) on a device whose on-device model is
 * not ready -- most devices, and every CI machine.
 *
 * **It cannot touch a real user's data**: [TestDatabaseContext] renames every database file it
 * opens, so this runs against `instrumentation_test_activity_ledger.db`, a file no production
 * code path ever names, deleted before and after the test.
 *
 * **It logs nothing but numbers** (AGENTS.md #11): no capture text, no prompt, no model output,
 * not even in a failure message -- failures report enum names and ids only.
 */
@RunWith(AndroidJUnit4::class)
class CaptureVerticalSliceTest {

    /** The one sentence under test. */
    private val captureText = "I cut the grass yesterday."

    private val zone: ZoneId = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()

    /** "yesterday", resolved deterministically by the domain: the local day before, at its start. */
    private val expectedOccurredAt: Instant =
        ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, zone).toInstant()

    private lateinit var context: TestDatabaseContext
    private lateinit var pipeline: CapturePipeline
    private lateinit var timing: TimingInterpreter

    @Before
    fun setUp() {
        context = TestDatabaseContext(ApplicationProvider.getApplicationContext<Context>())
        context.clearTestDatabases()
        // A fixed clock so the capture's own time -- and therefore what "yesterday" means -- is
        // the same on every run and on every device.
        pipeline = CapturePipeline.create(
            context = context,
            clock = Clock.fixed(capturedAt, zone),
            interpreterDecorator = { delegate -> TimingInterpreter(delegate).also { timing = it } },
        )
    }

    @After
    fun tearDown() {
        if (::pipeline.isInitialized) pipeline.close()
        if (::context.isInitialized) context.clearTestDatabases()
    }

    @Test
    fun oneSentenceBecomesOneCorrectlyMatchedOccurrence() = runBlocking {
        val readiness = pipeline.capability.readiness()
        assumeTrue(
            "Skipped: this device's on-device model readiness is $readiness, not ${ModelReadiness.READY}. " +
                "Nothing was downloaded; downloading the model is the owner's decision alone.",
            readiness == ModelReadiness.READY,
        )

        val repository = pipeline.repository

        // --- Seed the catalog with the canonical activity "Mow lawn". -------------------------
        // There is no direct create-activity call on the repository: the supported path is to
        // accept a hand-written interpretation for a seed capture, which also creates a seed
        // occurrence. Every later assertion therefore names THE occurrence for the capture under
        // test, never "the only occurrence in the database".
        val seedCaptureId = repository.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = null,
                capturedAt = capturedAt,
                zoneId = zone,
                rawText = "seed",
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        val seedOccurrenceId = repository.acceptInterpretation(
            captureId = seedCaptureId,
            interpretation = seedInterpretation(),
            target = ActivityTarget.New("Mow lawn"),
            occurredAt = capturedAt,
            timePrecision = TimePrecision.DATE_ONLY,
            activityState = ActivityState.COMPLETED,
        )
        val seededActivityId = requireNotNull(repository.getOccurrence(seedOccurrenceId)).canonicalActivityId

        // --- The capture under test. ----------------------------------------------------------
        val captureId = repository.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = null,
                capturedAt = capturedAt,
                zoneId = zone,
                rawText = captureText,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )

        // --- Capture to saved occurrence, through the real model. ------------------------------
        val startedAt = System.nanoTime()
        val outcome = pipeline.orchestrator.process(captureId)
        val totalMillis = (System.nanoTime() - startedAt) / 1_000_000
        // Numbers only: how long the model call took, and how long the whole capture-to-save
        // path took. No capture text, prompt or model output is ever logged (AGENTS.md #11).
        Log.i(TAG, "interpretMillis=${timing.elapsedMillis} captureToSaveMillis=$totalMillis")

        // --- What must have happened. ----------------------------------------------------------
        val accepted = when (outcome) {
            is CaptureProcessingOutcome.AutoAccepted -> outcome
            // A NeedsReview is a real finding about the model, not something to assert around.
            is CaptureProcessingOutcome.NeedsReview ->
                throw AssertionError("expected AutoAccepted, got NeedsReview; validation reasons: ${outcome.reasons}")
            is CaptureProcessingOutcome.Rejected ->
                throw AssertionError("expected AutoAccepted, got Rejected; validation reasons: ${outcome.reasons}")
            is CaptureProcessingOutcome.InterpreterUnavailable ->
                throw AssertionError("expected AutoAccepted, got InterpreterUnavailable(${outcome.kind}) on a READY device")
            CaptureProcessingOutcome.AlreadyHasOccurrence ->
                throw AssertionError("expected AutoAccepted, got AlreadyHasOccurrence for a freshly created capture")
        }

        val occurrence = requireNotNull(repository.getOccurrence(accepted.occurrenceId)) {
            "occurrence ${accepted.occurrenceId} was reported created but cannot be read back"
        }

        assertEquals(
            "occurrence must belong to the seeded activity, not a newly created one",
            seededActivityId,
            occurrence.canonicalActivityId,
        )
        val activity = requireNotNull(repository.getActivity(occurrence.canonicalActivityId)) {
            "occurrence points at activity ${occurrence.canonicalActivityId}, which does not exist"
        }
        assertEquals("seeded activity's display name", "Mow lawn", activity.displayName)
        assertEquals("seeded activity must still be active", CanonicalActivityStatus.ACTIVE, activity.status)

        assertEquals("'yesterday' is the local day before the capture's local day", expectedOccurredAt, occurrence.occurredAt)
        assertEquals("day-level precision", TimePrecision.DATE_ONLY, occurrence.timePrecision)
        assertEquals("occurrence must point back at the capture under test", captureId, occurrence.rawCaptureId)
        assertEquals("occurrence's captured time", capturedAt, occurrence.capturedAt)
        assertNotNull("occurrence must have an effective interpretation", occurrence.effectiveInterpretationId)

        val stored = requireNotNull(repository.getCapture(captureId))
        assertEquals("raw captures are immutable: stored text must be character-identical", captureText, stored.rawText)
        assertEquals("capture must be marked persisted", ProcessingState.PERSISTED, stored.processingState)
        assertTrue("capture must now report an occurrence", stored.hasOccurrence)

        // The whole run must have happened in the redirected database, never the production one.
        assertTrue(
            "the test must have written to a prefixed database file only",
            context.testDatabaseFiles().isNotEmpty(),
        )
    }

    /** A hand-written interpretation, used only to seed the catalog. Not model output. */
    private fun seedInterpretation() = InterpretationRecord(
        createdAt = capturedAt,
        interpreterVersion = "vertical-slice-seed",
        promptVersion = "vertical-slice-seed",
        schemaVersion = 1,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.NEW_ACTIVITY,
        matchedActivityId = null,
        proposedCanonicalName = "Mow lawn",
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = capturedAt,
        timePrecision = TimePrecision.DATE_ONLY,
        modelConfidenceBand = null,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
    )

    private companion object {
        const val TAG = "CaptureVerticalSlice"
    }
}

/**
 * Times the one interpretation call, and does nothing else: it never reads, alters or logs what
 * passes through it. Wrapping the pipeline's own interpreter is how the test gets a model-call
 * timing without wiring a second pipeline of its own.
 */
private class TimingInterpreter(private val delegate: ActivityInterpreter) : ActivityInterpreter {

    /** Milliseconds the last [interpret] call took, or -1 if it has not been called. */
    var elapsedMillis: Long = -1
        private set

    override val provenance: InterpreterProvenance get() = delegate.provenance

    override suspend fun interpret(input: InterpretationInput): InterpretationResult {
        val startedAt = System.nanoTime()
        try {
            return delegate.interpret(input)
        } finally {
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        }
    }
}
