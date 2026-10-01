package com.mcfrenchpants.activityledger.debugtools

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpusExtractionInput
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecording
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecordingEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/**
 * DEBUG BUILD ONLY. One run of the tag corpus through an [ActivityExtractor], producing the same
 * [TagRecording] the instrumented recorder (androidTest `TagCorpusRecorderTest`) writes, so the
 * owner can record on the phone without a PC (see [TagCorpusRunnerActivity]).
 *
 * Plain class with every time source injected, so it is tested on the JVM (TagCorpusRunTest).
 *
 * - **Every case once, in corpus order**, input from [TagCorpusExtractionInput.forCase], entry
 *   from [TagRecordingEntry.of]; no repair and no second opinion.
 * - **Fast-refusal backoff, identical to the instrumented recorder**: a RETRYABLE/OTHER failure
 *   faster than [FAST_REFUSAL_MS] cannot have run inference (AICore throttling back-to-back
 *   requests), so it is asked again after each wait in [BUSY_BACKOFF_MS]; once those are used up
 *   the last failure is recorded as-is. Any other result is recorded at once.
 * - **Cancellation propagates**: a cancelled run throws and returns no partial recording.
 * - **Holds no text**: the result carries case ids, the extractor's answers and numbers only.
 *
 * @param deviceModel Recorded as the recording's `deviceModel` (the activity passes Build.MODEL).
 * @param nanoTime Monotonic clock in nanoseconds, used for per-call latency.
 * @param now Wall clock, read once at the start for `recordedAt`.
 * @param delay Suspends for the given milliseconds; must be cancellable.
 */
class TagCorpusRun(
    private val extractor: ActivityExtractor,
    private val corpus: TagCorpus,
    private val deviceModel: String?,
    private val nanoTime: () -> Long,
    private val now: () -> Instant,
    private val delay: suspend (Long) -> Unit,
) {

    /**
     * Runs every case and returns the recording plus summary numbers. [onProgress] is called with
     * (0, total) first and then after each case with the number of cases done so far.
     */
    suspend fun run(onProgress: (done: Int, total: Int) -> Unit): TagRecordingResult {
        val cases = corpus.cases
        val total = cases.size
        val recordedAt = now()
        val entries = ArrayList<TagRecordingEntry>(total)
        val latencies = ArrayList<Long>(total)
        var busyRetries = 0

        onProgress(0, total)
        for ((index, case) in cases.withIndex()) {
            currentCoroutineContext().ensureActive()
            val input = TagCorpusExtractionInput.forCase(case)
            var attempt = 0
            var result: ExtractionResult
            var latencyMs: Long
            while (true) {
                val startedAt = nanoTime()
                result = extractor.extract(input)
                latencyMs = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
                val fastRefusal = result is ExtractionResult.Failure &&
                    result.kind in THROTTLE_KINDS && latencyMs < FAST_REFUSAL_MS
                if (!fastRefusal || attempt >= BUSY_BACKOFF_MS.size) break
                // Throttled, not answered: wait and ask again (see the class comment).
                delay(BUSY_BACKOFF_MS[attempt])
                attempt++
                busyRetries++
            }
            latencies += latencyMs
            entries += TagRecordingEntry.of(case.id, result, latencyMs)
            onProgress(index + 1, total)
        }
        currentCoroutineContext().ensureActive()

        val provenance = extractor.provenance
        val recording = TagRecording(
            source = RecordingSource.DEVICE,
            modelLabel = MODEL_LABEL,
            deviceModel = deviceModel,
            interpreterVersion = provenance.interpreterVersion,
            promptVersion = provenance.promptVersion,
            schemaVersion = provenance.schemaVersion,
            tagCorpusSha256 = corpus.sha256,
            recordedAt = recordedAt.toString(),
            notes = "in-app runner; busy retries: $busyRetries",
            entries = entries,
        )
        val failed = entries.count { it.failureKind != null }
        return TagRecordingResult(
            recording = recording,
            total = entries.size,
            answered = entries.size - failed,
            failed = failed,
            busyRetries = busyRetries,
            totalLatencyMs = latencies.sum(),
            medianLatencyMs = median(latencies),
        )
    }

    companion object {
        /** The recording's model label; the same as the instrumented recorder's. */
        const val MODEL_LABEL: String = "gemini-nano (AICore)"

        /** A failure faster than this cannot have run inference (real answers take ~4-6 s). */
        const val FAST_REFUSAL_MS: Long = 1_000L

        /** The failure kinds a throttled call surfaces as (BUSY is RETRYABLE; OTHER is kept, harmlessly). */
        val THROTTLE_KINDS: Set<InterpreterFailureKind> =
            setOf(InterpreterFailureKind.OTHER, InterpreterFailureKind.RETRYABLE)

        /** Waits before each repeat of a fast-refused call; its size is the retry limit. */
        val BUSY_BACKOFF_MS: List<Long> = listOf(5_000, 10_000, 20_000, 30_000, 60_000, 60_000)

        internal fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}

/**
 * A finished run: the recording to write and numbers-only summary counts for the screen.
 *
 * @property total Cases recorded (always the corpus size).
 * @property answered Cases with an answer.
 * @property failed Cases recorded as a failure.
 * @property busyRetries Fast refusals that were waited out and asked again.
 * @property totalLatencyMs Sum of the recorded per-case latencies.
 * @property medianLatencyMs Median recorded per-case latency.
 */
data class TagRecordingResult(
    val recording: TagRecording,
    val total: Int,
    val answered: Int,
    val failed: Int,
    val busyRetries: Int,
    val totalLatencyMs: Long,
    val medianLatencyMs: Long,
)
