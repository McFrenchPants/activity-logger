package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The extractor twin of [BusyRetryInterpreter]: gives an [ActivityExtractor] the same short,
 * bounded second chance when it refuses a request without running inference (in practice, the
 * on-device model being busy with the previous request), using the same [RETRY_WAITS] schedule.
 *
 * Only an [ExtractionResult.Failure] of kind [InterpreterFailureKind.RETRYABLE] is retried; any
 * other result is returned at once, so an answer is never re-asked (ADR-030). The delegate makes
 * exactly one model call per [extract]. Cancellation propagates; nothing is caught or logged,
 * and the result passes through untouched (AGENTS.md #11).
 *
 * @param delegate the extractor to retry; its [provenance] is reported as this one's.
 * @param waits the retry schedule; [RETRY_WAITS] in production.
 * @param wait how a wait is performed; coroutine [delay] in production, injectable for tests.
 */
internal class BusyRetryExtractor(
    private val delegate: ActivityExtractor,
    private val waits: List<Duration> = RETRY_WAITS,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) : ActivityExtractor {

    override val provenance: InterpreterProvenance
        get() = delegate.provenance

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        var result = delegate.extract(input)
        for (pause in waits) {
            if (!result.isRetryableRefusal()) return result
            wait(pause)
            result = delegate.extract(input)
        }
        return result
    }
}

private fun ExtractionResult.isRetryableRefusal(): Boolean =
    this is ExtractionResult.Failure && kind == InterpreterFailureKind.RETRYABLE
