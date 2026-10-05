package com.mcfrenchpants.activityledger.debugtools

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecording
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecordingEntry
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/**
 * DEBUG BUILD ONLY. One run of the QUESTION corpus through a [QuestionExtractor], producing a
 * [QuestionRecording] for `scripts/semantic/import-question-recording.sh` (see
 * [TagCorpusRunnerActivity], "Run question set").
 *
 * The same contract as [TagCorpusRun], and the same backoff constants (reused from its companion):
 * - **Every case once, in corpus order**; the extractor's only input is the case's question
 *   text; the entry comes from [QuestionRecordingEntry.of]; no repair and no second opinion.
 * - **Fast-refusal backoff**: a [TagCorpusRun.THROTTLE_KINDS] failure faster than
 *   [TagCorpusRun.FAST_REFUSAL_MS] cannot have run inference, so it is asked again after each wait
 *   in [TagCorpusRun.BUSY_BACKOFF_MS]; once those are used up the last failure is recorded.
 * - **Cancellation propagates**: a cancelled run throws and returns no partial recording.
 * - **Holds no text**: the result carries case ids, the extractor's answers and numbers only.
 *
 * @param deviceModel Recorded as the recording's `deviceModel` (the activity passes Build.MODEL).
 * @param nanoTime Monotonic clock in nanoseconds, used for per-call latency.
 * @param now Wall clock, read once at the start for `recordedAt`.
 * @param delay Suspends for the given milliseconds; must be cancellable.
 */
class QuestionCorpusRun(
    private val extractor: QuestionExtractor,
    private val corpus: QuestionCorpus,
    private val deviceModel: String?,
    private val nanoTime: () -> Long,
    private val now: () -> Instant,
    private val delay: suspend (Long) -> Unit,
) {

    /**
     * Runs every case and returns the recording plus summary numbers. [onProgress] is called with
     * (0, total) first and then after each case with the number of cases done so far.
     */
    suspend fun run(onProgress: (done: Int, total: Int) -> Unit): QuestionRecordingResult {
        val cases = corpus.cases
        val total = cases.size
        val recordedAt = now()
        val entries = ArrayList<QuestionRecordingEntry>(total)
        val latencies = ArrayList<Long>(total)
        var busyRetries = 0

        onProgress(0, total)
        for ((index, case) in cases.withIndex()) {
            currentCoroutineContext().ensureActive()
            var attempt = 0
            var result: QuestionExtractionResult
            var latencyMs: Long
            while (true) {
                val startedAt = nanoTime()
                result = extractor.extract(case.question)
                latencyMs = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
                val fastRefusal = result is QuestionExtractionResult.Failure &&
                    result.kind in TagCorpusRun.THROTTLE_KINDS && latencyMs < TagCorpusRun.FAST_REFUSAL_MS
                if (!fastRefusal || attempt >= TagCorpusRun.BUSY_BACKOFF_MS.size) break
                // Throttled, not answered: wait and ask again (see the class comment).
                delay(TagCorpusRun.BUSY_BACKOFF_MS[attempt])
                attempt++
                busyRetries++
            }
            latencies += latencyMs
            entries += QuestionRecordingEntry.of(case.id, result, latencyMs)
            onProgress(index + 1, total)
        }
        currentCoroutineContext().ensureActive()

        val provenance = extractor.provenance
        val recording = QuestionRecording(
            source = RecordingSource.DEVICE,
            modelLabel = TagCorpusRun.MODEL_LABEL,
            deviceModel = deviceModel,
            interpreterVersion = provenance.interpreterVersion,
            promptVersion = provenance.promptVersion,
            schemaVersion = provenance.schemaVersion,
            questionCorpusSha256 = corpus.sha256,
            recordedAt = recordedAt.toString(),
            notes = "in-app runner; busy retries: $busyRetries",
            entries = entries,
        )
        val failed = entries.count { it.failureKind != null }
        return QuestionRecordingResult(
            recording = recording,
            total = entries.size,
            answered = entries.size - failed,
            failed = failed,
            busyRetries = busyRetries,
            totalLatencyMs = latencies.sum(),
            medianLatencyMs = TagCorpusRun.median(latencies),
        )
    }
}

/**
 * A finished question run: the recording to write and numbers-only summary counts for the screen.
 *
 * @property total Cases recorded (always the corpus size).
 * @property answered Cases with an answer.
 * @property failed Cases recorded as a failure.
 * @property busyRetries Fast refusals that were waited out and asked again.
 * @property totalLatencyMs Sum of the recorded per-case latencies.
 * @property medianLatencyMs Median recorded per-case latency.
 */
data class QuestionRecordingResult(
    val recording: QuestionRecording,
    val total: Int,
    val answered: Int,
    val failed: Int,
    val busyRetries: Int,
    val totalLatencyMs: Long,
    val medianLatencyMs: Long,
)
