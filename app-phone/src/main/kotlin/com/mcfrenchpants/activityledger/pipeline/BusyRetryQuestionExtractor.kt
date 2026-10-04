package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Gives a [QuestionExtractor] the same short, bounded second chance [BusyRetryExtractor] gives an
 * activity extractor, on the same [RETRY_WAITS] schedule.
 *
 * Only a [QuestionExtractionResult.Failure] of kind [InterpreterFailureKind.RETRYABLE] is retried;
 * any other result is returned at once. The delegate makes exactly one model call per [extract].
 * Cancellation propagates; nothing is caught or logged, and the result passes through untouched
 * (AGENTS.md #11).
 *
 * @param delegate the extractor to retry; its [provenance] is reported as this one's.
 * @param waits the retry schedule; [RETRY_WAITS] in production.
 * @param wait how a wait is performed; coroutine [delay] in production, injectable for tests.
 */
internal class BusyRetryQuestionExtractor(
    private val delegate: QuestionExtractor,
    private val waits: List<Duration> = RETRY_WAITS,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) : QuestionExtractor {

    override val provenance: InterpreterProvenance
        get() = delegate.provenance

    override suspend fun extract(questionText: String): QuestionExtractionResult {
        var result = delegate.extract(questionText)
        for (pause in waits) {
            if (!result.isRetryableRefusal()) return result
            wait(pause)
            result = delegate.extract(questionText)
        }
        return result
    }
}

private fun QuestionExtractionResult.isRetryableRefusal(): Boolean =
    this is QuestionExtractionResult.Failure && kind == InterpreterFailureKind.RETRYABLE
