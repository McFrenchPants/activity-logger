package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.data.ledgerChanges
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ledgerChanges] on the real in-memory database: it emits after the writes a capture, a hide and
 * a correction make, never on collection, and is empty for a repository that is not the Room one.
 * Change reports arrive on Room's own background threads, so waits here are real-time and bounded.
 * Synthetic text only.
 */
@RunWith(AndroidJUnit4::class)
class LedgerChangesTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: LedgerRepository
    private val ids = DeterministicIdFactory(next = 0x70_0000L)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val received = Channel<Unit>(Channel.UNLIMITED)
    private var collector: Job? = null

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(
            db, ids, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("UTC")), Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        collector?.cancel()
        db.close()
    }

    private fun startCollecting() {
        collector = scope.launch { ledgerChanges(repository).collect { received.send(Unit) } }
    }

    private suspend fun nextChange(timeoutMs: Long = WAIT_MS): Unit? = withTimeoutOrNull(timeoutMs) { received.receive() }

    /** Waits for the reports of earlier writes to arrive, then throws them away. */
    private suspend fun drain() {
        delay(SETTLE_MS)
        while (received.tryReceive().isSuccess) Unit
    }

    /**
     * Room starts watching asynchronously after collection begins, so write a bare capture until
     * one change is reported: from then on the subscription is known to be live.
     */
    private suspend fun awaitLive() {
        repeat(MAX_WARM_UP_WRITES) {
            capture()
            if (nextChange(timeoutMs = 300) != null) {
                drain()
                return
            }
        }
        error("ledgerChanges never reported a write")
    }

    private suspend fun capture(): String = repository.createRawCapture(
        NewRawCapture(
            source = CaptureSource.PHONE_TEXT,
            sourceSurface = null,
            capturedAt = Instant.ofEpochMilli(NOW),
            zoneId = ZoneId.of("UTC"),
            rawText = "synthetic words",
            speechConfidence = null,
            speechAlternativesJson = null,
            processingState = ProcessingState.INTERPRETING,
        ),
    )

    private fun record() = InterpretationRecord(
        createdAt = Instant.ofEpochMilli(NOW - 5),
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 4,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.NEW_ACTIVITY,
        matchedActivityId = null,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = ConfidenceBand.HIGH,
        candidateContextHash = null,
        structuredResultJson = "{}",
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
        extractedSubject = "synthetic subject",
        extractedAction = "synthetic action",
        durationExpression = null,
        resolvedDurationSeconds = null,
    )

    private suspend fun accept(subject: String, action: String): String = repository.acceptTagged(
        TaggedAcceptRequest(
            captureId = capture(),
            interpretation = record(),
            subject = TagTarget.New(subject),
            action = TagTarget.New(action),
            occurredAt = Instant.ofEpochMilli(NOW - 1_000),
            timePrecision = TimePrecision.EXACT,
            activityState = ActivityState.COMPLETED,
            durationSeconds = null,
            learnSubjectAlias = null,
            learnActionAlias = null,
        ),
    )

    @Test
    fun noEmissionWhenCollectionStarts() = runBlocking<Unit> {
        accept("Lawn", "Mow")
        startCollecting()

        assertNull(nextChange(timeoutMs = 1_000), "nothing changed after collection started")
    }

    @Test
    fun emitsAfterAnAcceptedTaggedCapture() = runBlocking<Unit> {
        startCollecting()
        awaitLive()

        accept("Lawn", "Mow")

        assertNotNull(nextChange(), "an accepted tagged capture is reported")
    }

    @Test
    fun emitsAfterAHideAndAfterACorrection() = runBlocking<Unit> {
        val occurrence = accept("Hot tub", "Clean")
        val second = accept("Furnace", "Change filter")
        startCollecting()
        awaitLive()

        repository.hideOccurrence(occurrence)
        assertNotNull(nextChange(), "a hide is reported")
        drain()

        val outcome = repository.correctTags(
            TagCorrectionRequest(
                occurrenceId = second,
                subject = TagTarget.New("Heating unit"),
                source = CorrectionSource.USER,
                reason = null,
                now = Instant.ofEpochMilli(NOW),
            ),
        )
        assertIs<CorrectionOutcome.Applied>(outcome)
        assertNotNull(nextChange(), "a correction is reported")
    }

    @Test
    fun aRepositoryThatIsNotTheRoomOneGivesAnEmptyFlow() = runBlocking<Unit> {
        val other = object : LedgerRepository by repository {}

        val emitted = withTimeoutOrNull(WAIT_MS) { ledgerChanges(other).toList() }

        assertNotNull(emitted, "the flow completes at once")
        assertTrue(emitted.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 300L
        const val MAX_WARM_UP_WRITES = 20
    }
}
