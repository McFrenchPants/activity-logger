package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tagged capture orchestrator (TG3.1) running against the real (in-memory) Room ledger with
 * a scripted extractor. Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class TaggedCaptureOrchestratorRoomTest {

    private class ScriptedExtractor : ActivityExtractor {
        override val provenance = InterpreterProvenance("scripted-extractor", "scripted-prompt", 3)
        private val scripted = ArrayDeque<ExtractionResult>()
        fun enqueue(subject: String?, action: String?) {
            scripted.add(
                ExtractionResult.Success(
                    ExtractionCandidate(InterpretationOperation.LOG_ACTIVITY, subject, action, ActivityState.COMPLETED, null, null),
                ),
            )
        }

        override suspend fun extract(input: ExtractionInput): ExtractionResult =
            scripted.removeFirstOrNull() ?: error("no scripted extraction")
    }

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val extractor = ScriptedExtractor()
    private val zone = ZoneId.of("America/New_York")
    private val capturedAt = Instant.parse("2026-09-15T20:00:00Z")
    private val clock = Clock.fixed(capturedAt.plusSeconds(30), zone)
    private val sentence = "I just changed the hot tub filter"

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, DeterministicIdFactory(next = 0x30_0000L), clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun capture(text: String = sentence): String = repository.createRawCapture(
        NewRawCapture(CaptureSource.PHONE_TEXT, null, capturedAt, zone, text, null, null, ProcessingState.CAPTURED),
    )

    private fun run(id: String): TaggedProcessingOutcome = runBlocking {
        TaggedCaptureOrchestrator(repository, extractor, clock).process(id)
    }

    @Test
    fun firstEntryCreatesTagsAndPairAndRepeatReusesThem() = runBlocking {
        assertTrue(repository.loadTagCatalog().subjects.isEmpty())

        val first = capture()
        extractor.enqueue("hot tub", "change filter")
        val firstOutcome = assertIs<TaggedProcessingOutcome.AutoSaved>(run(first))
        val firstOccurrence = assertNotNull(repository.getOccurrence(firstOutcome.occurrenceId))
        val catalog = repository.loadTagCatalog()
        assertEquals(listOf("hot tub"), catalog.subjects.map { it.displayName.lowercase() })
        assertEquals(listOf("change filter"), catalog.actions.map { it.displayName.lowercase() })
        assertEquals(1, catalog.pairs.size)
        assertEquals(ProcessingState.PERSISTED, assertNotNull(repository.getCapture(first)).processingState)
        assertEquals(capturedAt, firstOccurrence.occurredAt)

        val second = capture()
        extractor.enqueue("Hot tub", "changed filter")
        val secondOutcome = assertIs<TaggedProcessingOutcome.AutoSaved>(run(second))
        val secondOccurrence = assertNotNull(repository.getOccurrence(secondOutcome.occurrenceId))
        assertEquals(firstOccurrence.canonicalActivityId, secondOccurrence.canonicalActivityId)
        val after = repository.loadTagCatalog()
        assertEquals(catalog.subjects, after.subjects)
        assertEquals(catalog.actions, after.actions)
        assertEquals(catalog.pairs, after.pairs)
    }

    @Test
    fun nearMatchLeavesCaptureForReviewAndWritesNoOccurrence() = runBlocking {
        val first = capture()
        extractor.enqueue("hot tub", "change filter")
        assertIs<TaggedProcessingOutcome.AutoSaved>(run(first))

        val near = capture("I changed the hot tup filter")
        extractor.enqueue("hot tup", "change filter")
        val outcome = assertIs<TaggedProcessingOutcome.NeedsConfirm>(run(near))
        assertEquals("hot tup", outcome.proposal.extractedSubject)
        assertEquals(ProcessingState.NEEDS_REVIEW, assertNotNull(repository.getCapture(near)).processingState)
        assertEquals(false, assertNotNull(repository.getCapture(near)).hasOccurrence)
        assertEquals(1, repository.loadTagCatalog().subjects.size)
        assertEquals(1, repository.loadHistory().count { it.occurrence != null })
        assertNull(repository.loadHistory().single { it.captureId == near }.occurrence)
    }
}
