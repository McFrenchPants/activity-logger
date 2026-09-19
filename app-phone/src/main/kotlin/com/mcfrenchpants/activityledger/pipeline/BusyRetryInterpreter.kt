package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The waits [BusyRetryInterpreter] makes between attempts, in order: one entry per retry, so at
 * most `RETRY_WAITS.size + 1` delegate calls and at most the sum of these waits (~6 s) in total.
 *
 * Short on purpose: the person has just typed or spoken a capture and is looking at the screen.
 * If the model is still refusing after this, the capture is left for a later attempt instead of
 * holding them up.
 */
internal val RETRY_WAITS: List<Duration> = listOf(2.seconds, 4.seconds)

/**
 * Gives an interpreter a short, bounded second chance when it refuses a request without running
 * inference -- in practice, the on-device model being busy with the previous request.
 *
 * Only an [InterpretationResult.Failure] of kind [InterpreterFailureKind.RETRYABLE] is retried:
 * it waits the next entry of [waits] and asks the delegate again, until the schedule runs out.
 * If every attempt is RETRYABLE, the last failure is returned unchanged and the orchestrator
 * leaves the capture queued for a later attempt as before.
 *
 * Everything else -- a [InterpretationResult.Success], or a MALFORMED, OTHER or UNAVAILABLE
 * failure -- is returned at once. In particular an answer is never re-asked: a MALFORMED or
 * successful result means inference ran, and asking again would be the repair loop ADR-030
 * forbids. The delegate itself still makes exactly one model call per [interpret].
 *
 * Cancellation during a wait or a delegate call propagates; nothing here catches it. Nothing is
 * logged, and the result passes through untouched (AGENTS.md #11).
 *
 * @param delegate the interpreter to retry; its [provenance] is reported as this one's.
 * @param waits the retry schedule; [RETRY_WAITS] in production.
 * @param wait how a wait is performed; coroutine [delay] in production, injectable so tests do
 *   not really sleep.
 */
internal class BusyRetryInterpreter(
    private val delegate: ActivityInterpreter,
    private val waits: List<Duration> = RETRY_WAITS,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) : ActivityInterpreter {

    override val provenance: InterpreterProvenance
        get() = delegate.provenance

    override suspend fun interpret(input: InterpretationInput): InterpretationResult {
        var result = delegate.interpret(input)
        for (pause in waits) {
            if (!result.isRetryableRefusal()) return result
            wait(pause)
            result = delegate.interpret(input)
        }
        return result
    }
}

private fun InterpretationResult.isRetryableRefusal(): Boolean =
    this is InterpretationResult.Failure && kind == InterpreterFailureKind.RETRYABLE
