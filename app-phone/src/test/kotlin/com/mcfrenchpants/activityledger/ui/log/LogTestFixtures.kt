package com.mcfrenchpants.activityledger.ui.log

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/** Extraction results used by the Log tests. */
internal object Extracted {

    /** A logged activity as the extractor would report it (state COMPLETED unless told otherwise). */
    fun log(
        subject: String?,
        action: String?,
        state: ActivityState? = ActivityState.COMPLETED,
        time: String? = null,
        duration: String? = null,
    ): ExtractionResult = ExtractionResult.Success(
        ExtractionCandidate(
            operation = InterpretationOperation.LOG_ACTIVITY,
            subject = subject,
            action = action,
            activityState = state,
            temporalExpression = time,
            durationExpression = duration,
        ),
    )

    fun failure(kind: InterpreterFailureKind): ExtractionResult = ExtractionResult.Failure(kind)
}

/**
 * An [ActivityExtractor] that answers exactly what a test scripts for each raw text (and
 * [fallback] otherwise), optionally holding every answer back until [release] is called.
 */
internal class ScriptedExtractor : ActivityExtractor {
    override val provenance = InterpreterProvenance("test-extractor", "test-prompt", 1)

    private val answers = mutableMapOf<String, ExtractionResult>()
    private var gate: CompletableDeferred<Unit>? = null

    /** What is answered for a text nobody scripted. */
    var fallback: ExtractionResult = Extracted.failure(InterpreterFailureKind.OTHER)

    /** When set, the extractor throws this instead of answering (a crash after the words were stored). */
    var throwOnExtract: Exception? = null

    /** Every input that reached the extractor, in order. */
    val received = mutableListOf<ExtractionInput>()

    val callCount: Int get() = received.size

    fun on(rawText: String, result: ExtractionResult) {
        answers[rawText] = result
    }

    /** From now on every answer waits until [release]. */
    fun holdAnswers() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        received += input
        gate?.await()
        throwOnExtract?.let { throw it }
        return answers[input.rawText] ?: fallback
    }
}

/** Repository calls [RecordingLedger] can be told to fail, simulating a storage error. */
internal enum class FailPoint { CREATE, HIDE, CORRECT, ACCEPT, CATALOG, HISTORY }

/**
 * The real ledger with a recorder in front: remembers every raw capture as given, can run a hook
 * right after one is stored, and throws a storage-style error from any call listed in [failOn].
 */
internal class RecordingLedger(private val inner: LedgerRepository) : LedgerRepository by inner {
    val created = mutableListOf<NewRawCapture>()
    var afterCreate: (suspend (String) -> Unit)? = null
    var failOn: Set<FailPoint> = emptySet()

    /** Every tag correction that reached the ledger, in order. */
    val corrections = mutableListOf<TagCorrectionRequest>()

    /** What is thrown when a call in [failOn] is made. */
    var failWith: (String) -> Exception = { IllegalStateException(it) }

    private fun maybeFail(point: FailPoint) {
        if (point in failOn) throw failWith("simulated storage failure: $point")
    }

    override suspend fun createRawCapture(capture: NewRawCapture): String {
        maybeFail(FailPoint.CREATE)
        val id = inner.createRawCapture(capture)
        created += capture
        afterCreate?.invoke(id)
        return id
    }

    override suspend fun hideOccurrence(occurrenceId: String) {
        maybeFail(FailPoint.HIDE)
        inner.hideOccurrence(occurrenceId)
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome {
        maybeFail(FailPoint.CORRECT)
        corrections += request
        return inner.correctTags(request)
    }

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String {
        maybeFail(FailPoint.ACCEPT)
        return inner.acceptTagged(request)
    }

    override suspend fun loadTagCatalog(): TagCatalog {
        maybeFail(FailPoint.CATALOG)
        return inner.loadTagCatalog()
    }

    override suspend fun loadHistory(): List<HistoryEntry> {
        maybeFail(FailPoint.HISTORY)
        return inner.loadHistory()
    }
}

/**
 * Makes the ledger know a subject and an action as a pair, the way the owner's earlier entries
 * would have, without leaving an entry behind in History: the seeding entry is saved through the
 * real resolution service and then hidden.
 */
internal fun seedTags(ledger: LedgerRepository, clock: Clock, zone: ZoneId, subject: String, action: String) {
    runSuspend {
        val captureId = ledger.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = "seed",
                capturedAt = clock.instant().minus(Duration.ofHours(1)),
                zoneId = zone,
                rawText = "seed",
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        val resolved = TaggedResolutionService(ledger, clock).resolve(
            captureId,
            TagChoice.New(subject),
            TagChoice.New(action),
        )
        check(resolved is TaggedResolutionResult.Resolved) { "seeding refused: $resolved" }
        ledger.hideOccurrence(resolved.occurrenceId)
    }
}

/**
 * A [SpeechTranscriber] whose sessions emit exactly the events a test scripts, on the JVM, with
 * no recognizer anywhere.
 *
 * One collection is one session, like the real thing: [willEmit] loads what the *next* session
 * says, [emitNow] pushes an event into the session that is running, and the session ends by
 * itself after a terminal event (a final transcript or a failure) or when its collector is
 * cancelled. [sessionsStarted], [sessionsCancelled] and [isOpen] let a test assert that a second
 * microphone is never opened and that no session outlives the screen.
 */
internal class ScriptedTranscriber : SpeechTranscriber {

    var sessionsStarted = 0
        private set

    var sessionsCancelled = 0
        private set

    /** Whether a session is running right now. */
    var isOpen = false
        private set

    private var script: List<SpeechEvent> = emptyList()
    private var live: Channel<SpeechEvent>? = null

    /** What the next session emits as soon as it starts. */
    fun willEmit(vararg events: SpeechEvent) {
        script = events.toList()
    }

    /** Pushes one event into the session running now; ignored when none is. */
    fun emitNow(event: SpeechEvent) {
        live?.trySend(event)
    }

    override fun listen(): Flow<SpeechEvent> = flow {
        sessionsStarted++
        isOpen = true
        val inbox = Channel<SpeechEvent>(Channel.UNLIMITED)
        live = inbox
        script.forEach { inbox.trySend(it) }
        script = emptyList()
        try {
            for (event in inbox) {
                emit(event)
                // Exactly one terminal event, then the session's flow completes (SpeechEvent).
                if (event !is SpeechEvent.PartialTranscript) break
            }
        } catch (cancellation: CancellationException) {
            sessionsCancelled++
            throw cancellation
        } finally {
            isOpen = false
            if (live === inbox) live = null
        }
    }
}
