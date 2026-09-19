package com.mcfrenchpants.activityledger.ui.log

import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/** Interpreter results used by the Log tests. */
internal object Results {
    fun existing(activityId: String, confidence: ConfidenceBand = ConfidenceBand.HIGH): InterpretationResult =
        InterpretationResult.Success(
            InterpretationCandidate(
                operation = InterpretationOperation.LOG_ACTIVITY,
                activityResolution = ActivityResolution.EXISTING_ACTIVITY,
                matchedActivityId = activityId,
                proposedCanonicalName = null,
                activityState = ActivityState.COMPLETED,
                temporalExpression = null,
                confidenceBand = confidence,
            ),
            structuredResultJson = null,
        )

    /** A valid but not-high-confidence match: the validator sends it to review. */
    fun needsReview(activityId: String): InterpretationResult = existing(activityId, ConfidenceBand.MEDIUM)

    /** A structurally unacceptable answer: the validator rejects it. */
    val invalid: InterpretationResult = InterpretationResult.Success(
        InterpretationCandidate(
            operation = InterpretationOperation.UNSUPPORTED,
            activityResolution = ActivityResolution.UNRESOLVED,
            matchedActivityId = null,
            proposedCanonicalName = null,
            activityState = null,
            temporalExpression = null,
            confidenceBand = ConfidenceBand.HIGH,
        ),
        structuredResultJson = null,
    )

    fun failure(kind: InterpreterFailureKind): InterpretationResult = InterpretationResult.Failure(kind, null)
}

/** Repository calls [RecordingRepository] can be told to fail, simulating a storage error. */
internal enum class FailPoint { CREATE, HIDE, CORRECT, ACCEPT, CATALOG, HISTORY }

/**
 * Records every raw capture as given, can run a hook right after one is stored, and throws a
 * storage-style error from any call listed in [failOn].
 */
internal class RecordingRepository(private val inner: ActivityRepository) : ActivityRepository by inner {
    val created = mutableListOf<NewRawCapture>()
    var afterCreate: (suspend (String) -> Unit)? = null
    var failOn: Set<FailPoint> = emptySet()

    private fun maybeFail(point: FailPoint) {
        if (point in failOn) throw IllegalStateException("simulated storage failure: $point")
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

    override suspend fun applyCorrection(
        occurrenceId: String,
        changes: CorrectionChanges,
        source: CorrectionSource,
        reason: String?,
        now: Instant,
    ): CorrectionOutcome {
        maybeFail(FailPoint.CORRECT)
        return inner.applyCorrection(occurrenceId, changes, source, reason, now)
    }

    override suspend fun acceptInterpretation(
        captureId: String,
        interpretation: InterpretationRecord,
        target: ActivityTarget,
        occurredAt: Instant,
        timePrecision: TimePrecision,
        activityState: ActivityState,
    ): String {
        maybeFail(FailPoint.ACCEPT)
        return inner.acceptInterpretation(captureId, interpretation, target, occurredAt, timePrecision, activityState)
    }

    override suspend fun loadCatalog(): List<CatalogActivity> {
        maybeFail(FailPoint.CATALOG)
        return inner.loadCatalog()
    }

    override suspend fun loadHistory(): List<HistoryEntry> {
        maybeFail(FailPoint.HISTORY)
        return inner.loadHistory()
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

/** An interpreter that suspends until [release] is called, then delegates. */
internal class GatedInterpreter(private val inner: ActivityInterpreter) : ActivityInterpreter {
    private val gate = CompletableDeferred<Unit>()
    override val provenance: InterpreterProvenance get() = inner.provenance

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun interpret(input: InterpretationInput): InterpretationResult {
        gate.await()
        return inner.interpret(input)
    }
}
