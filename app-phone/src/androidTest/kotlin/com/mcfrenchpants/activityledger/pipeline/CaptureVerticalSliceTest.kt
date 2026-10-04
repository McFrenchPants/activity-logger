package com.mcfrenchpants.activityledger.pipeline

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.MainActivity
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
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
 * through core-ai, the real tagged orchestrator, the real Room database on the device's disk.
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
    private lateinit var timing: TimingExtractor

    @Before
    fun setUp() {
        context = TestDatabaseContext(ApplicationProvider.getApplicationContext<Context>())
        context.clearTestDatabases()
        // A fixed clock so the capture's own time -- and therefore what "yesterday" means -- is
        // the same on every run and on every device.
        pipeline = CapturePipeline.create(
            context = context,
            clock = Clock.fixed(capturedAt, zone),
            extractorDecorator = { delegate -> TimingExtractor(delegate).also { timing = it } },
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

        // --- Seed the tag catalog with the subject "Lawn" and the action "Mow". ----------------
        // The supported path is to resolve a hand-written seed capture through the real tagged
        // resolution service, which creates the two tags and a seed occurrence. Every later
        // assertion therefore names THE occurrence for the capture under test, never "the only
        // occurrence in the database".
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
        val seeded = TaggedResolutionService(repository, pipeline.clock)
            .resolve(seedCaptureId, TagChoice.New("Lawn"), TagChoice.New("Mow"))
        check(seeded is TaggedResolutionResult.Resolved) { "seeding refused: ${seeded::class.simpleName}" }

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
        // The app must be the top foreground app: Google refuses GenAI calls from the background,
        // and without this the whole pipeline came back INTERPRETER_FAILED in a quarter of a
        // second while the same call from a foregrounded app succeeded.
        val startedAt = System.nanoTime()
        val outcome = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            pipeline.taggedOrchestrator.process(captureId)
        }
        val totalMillis = (System.nanoTime() - startedAt) / 1_000_000
        // Numbers only: how long the model call took, and how long the whole capture-to-save
        // path took. No capture text, prompt or model output is ever logged (AGENTS.md #11).
        Log.i(TAG, "extractMillis=${timing.elapsedMillis} captureToSaveMillis=$totalMillis")

        // --- What must have happened. ----------------------------------------------------------
        // A NeedsConfirm / NeedsReview is a real finding about the model, not something to
        // assert around. Failures report enum names and ids only, never text.
        val saved = when (outcome) {
            is TaggedProcessingOutcome.AutoSaved -> outcome
            is TaggedProcessingOutcome.NeedsConfirm ->
                throw AssertionError("expected AutoSaved, got NeedsConfirm")
            is TaggedProcessingOutcome.NeedsReview ->
                throw AssertionError(
                    "expected AutoSaved, got NeedsReview; reasons: ${outcome.reasons}, problems: ${outcome.problems}",
                )
            is TaggedProcessingOutcome.Rejected ->
                throw AssertionError("expected AutoSaved, got Rejected; validation reasons: ${outcome.reasons}")
            is TaggedProcessingOutcome.InterpreterUnavailable ->
                throw AssertionError("expected AutoSaved, got InterpreterUnavailable(${outcome.kind}) on a READY device")
            TaggedProcessingOutcome.AlreadyHasOccurrence ->
                throw AssertionError("expected AutoSaved, got AlreadyHasOccurrence for a freshly created capture")
        }

        val occurrence = requireNotNull(repository.getOccurrence(saved.occurrenceId)) {
            "occurrence ${saved.occurrenceId} was reported created but cannot be read back"
        }

        val activity = requireNotNull(repository.getActivity(occurrence.canonicalActivityId)) {
            "occurrence points at activity ${occurrence.canonicalActivityId}, which does not exist"
        }
        assertNotNull("a saved entry must carry a subject tag", activity.subjectId)
        assertNotNull("a saved entry must carry an action tag", activity.actionId)

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

    private companion object {
        const val TAG = "CaptureVerticalSlice"
    }
}

/**
 * Times the one extraction call, and does nothing else: it never reads, alters or logs what
 * passes through it. Wrapping the pipeline's own extractor is how the test gets a model-call
 * timing without wiring a second pipeline of its own.
 */
private class TimingExtractor(private val delegate: ActivityExtractor) : ActivityExtractor {

    /** Milliseconds the last [extract] call took, or -1 if it has not been called. */
    var elapsedMillis: Long = -1
        private set

    override val provenance: InterpreterProvenance get() = delegate.provenance

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val startedAt = System.nanoTime()
        try {
            return delegate.extract(input)
        } finally {
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        }
    }
}
