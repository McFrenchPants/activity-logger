package com.mcfrenchpants.activityledger.debugtools

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordedExtraction
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpusExtractionInput
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** JVM tests of the debug-only in-app tag corpus runner's run logic. */
class TagCorpusRunTest {

    private val corpus = TagCorpus.load()
    private val recordedAt = Instant.parse("2026-10-01T12:00:00Z")

    /** One scripted extractor step: what to return and how long the call "took". */
    private data class Step(val result: ExtractionResult, val latencyMs: Long)

    /**
     * Fake extractor on a fake monotonic clock. Each call consumes the next scripted step for its
     * case (or the default answer), advancing the clock by that step's latency.
     */
    private class FakeExtractor(
        private val script: Map<String, List<Step>> = emptyMap(),
        private val defaultLatencyMs: Long = 4_000,
    ) : ActivityExtractor {
        var nanos = 0L
        val calls = mutableListOf<ExtractionInput>()
        private val consumed = mutableMapOf<String, Int>()
        lateinit var idByText: Map<ExtractionInput, String>

        override val provenance = InterpreterProvenance("fake-extract-1", "4", 1)

        override suspend fun extract(input: ExtractionInput): ExtractionResult {
            calls += input
            val id = idByText.getValue(input)
            val steps = script[id]
            val step = if (steps == null) {
                Step(answer(id), defaultLatencyMs)
            } else {
                val n = consumed.getOrDefault(id, 0)
                consumed[id] = n + 1
                steps[minOf(n, steps.size - 1)]
            }
            nanos += step.latencyMs * 1_000_000
            return step.result
        }
    }

    /**
     * Some cases share an identical input (EMPTY_START reuses REAL_ENTRY sentences and times), so
     * an input maps to the FIRST case with it; scripted cases must have an input of their own.
     */
    private fun fake(script: Map<String, List<Step>> = emptyMap()) = FakeExtractor(script).also { f ->
        val byInput = corpus.cases.groupBy { TagCorpusExtractionInput.forCase(it) }
        script.keys.forEach { id ->
            val input = TagCorpusExtractionInput.forCase(checkNotNull(corpus.case(id)))
            check(byInput.getValue(input).size == 1) { "scripted case $id does not have a unique input" }
        }
        f.idByText = byInput.mapValues { (_, cases) -> cases.first().id }
    }

    private fun runner(extractor: FakeExtractor, delays: MutableList<Long>) = TagCorpusRun(
        extractor = extractor,
        corpus = corpus,
        deviceModel = "Test Phone",
        nanoTime = { extractor.nanos },
        now = { recordedAt },
        delay = { delays += it },
    )

    @Test
    fun everyCaseIsExtractedOnceInCorpusOrder() = runTest {
        val extractor = fake()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = runner(extractor, mutableListOf()).run { done, total -> progress += done to total }

        assertEquals(72, corpus.cases.size)
        assertEquals(corpus.cases.map { TagCorpusExtractionInput.forCase(it) }, extractor.calls)
        assertEquals(corpus.cases.map { it.id }, result.recording.entries.map { it.caseId })
        assertEquals((0..72).map { it to 72 }, progress)
        assertEquals(72, result.total)
        assertEquals(72, result.answered)
        assertEquals(0, result.failed)
        assertEquals(0, result.busyRetries)
        assertEquals(72 * 4_000L, result.totalLatencyMs)
        assertEquals(4_000L, result.medianLatencyMs)
    }

    @Test
    fun entriesMapAnswersAndFailures() = runTest {
        val failedId = corpus.cases[3].id
        val extractor = fake(mapOf(failedId to listOf(Step(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), 4_500))))
        val result = runner(extractor, mutableListOf()).run { _, _ -> }

        val first = result.recording.entries[0]
        assertEquals(
            RecordedExtraction(InterpretationOperation.LOG_ACTIVITY, "subject ${first.caseId}", "action", null, null, null),
            first.answer,
        )
        assertNull(first.failureKind)
        assertEquals(4_000L, first.latencyMs)

        val failed = result.recording.entries[3]
        assertEquals(failedId, failed.caseId)
        assertNull(failed.answer)
        assertEquals(InterpreterFailureKind.MALFORMED, failed.failureKind)
        assertEquals(4_500L, failed.latencyMs)
        assertEquals(71, result.answered)
        assertEquals(1, result.failed)
    }

    @Test
    fun fastRetryableRefusalIsRetriedWithTheBackoffScheduleThenRecorded() = runTest {
        val id = corpus.cases[0].id
        val busy = Step(ExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), 50)
        val extractor = fake(mapOf(id to listOf(busy, busy, Step(answer(id), 5_000))))
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertEquals(listOf(5_000L, 10_000L), delays)
        assertEquals(2, result.busyRetries)
        assertEquals(74, extractor.calls.size)
        val entry = result.recording.entries[0]
        assertEquals(answer(id).candidate, entry.answer?.toCandidate())
        assertEquals(5_000L, entry.latencyMs)
        assertEquals("in-app runner; busy retries: 2", result.recording.notes)
    }

    @Test
    fun fastOtherRefusalIsAlsoRetried() = runTest {
        val id = corpus.cases[1].id
        val extractor = fake(
            mapOf(id to listOf(Step(ExtractionResult.Failure(InterpreterFailureKind.OTHER), 10), Step(answer(id), 4_000))),
        )
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertEquals(listOf(5_000L), delays)
        assertEquals(1, result.busyRetries)
        assertEquals(72, result.answered)
    }

    @Test
    fun slowFailureIsNotRetried() = runTest {
        val id = corpus.cases[0].id
        val extractor = fake(
            mapOf(id to listOf(Step(ExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), 1_000), Step(answer(id), 4_000))),
        )
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertTrue(delays.isEmpty())
        assertEquals(72, extractor.calls.size)
        assertEquals(InterpreterFailureKind.RETRYABLE, result.recording.entries[0].failureKind)
        assertEquals(1_000L, result.recording.entries[0].latencyMs)
        assertEquals(0, result.busyRetries)
    }

    @Test
    fun fastNonThrottleFailureIsNotRetried() = runTest {
        val id = corpus.cases[0].id
        val extractor = fake(mapOf(id to listOf(Step(ExtractionResult.Failure(InterpreterFailureKind.UNAVAILABLE), 5))))
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertTrue(delays.isEmpty())
        assertEquals(InterpreterFailureKind.UNAVAILABLE, result.recording.entries[0].failureKind)
    }

    @Test
    fun backoffExhaustionRecordsTheLastFailure() = runTest {
        val id = corpus.cases[0].id
        val busy = Step(ExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), 20)
        val last = Step(ExtractionResult.Failure(InterpreterFailureKind.OTHER), 30)
        val extractor = fake(mapOf(id to List(6) { busy } + last))
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 60_000L), delays)
        assertEquals(6, result.busyRetries)
        assertEquals(72 + 6, extractor.calls.size)
        val entry = result.recording.entries[0]
        assertEquals(InterpreterFailureKind.OTHER, entry.failureKind)
        assertEquals(30L, entry.latencyMs)
        assertEquals(1, result.failed)
    }

    @Test
    fun headerCarriesProvenanceHashSourceAndNotes() = runTest {
        val result = runner(fake(), mutableListOf()).run { _, _ -> }
        val recording = result.recording

        assertEquals(RecordingSource.DEVICE, recording.source)
        assertEquals("gemini-nano (AICore)", recording.modelLabel)
        assertEquals("Test Phone", recording.deviceModel)
        assertEquals(InterpreterProvenance("fake-extract-1", "4", 1), recording.provenance)
        assertEquals(corpus.sha256, recording.tagCorpusSha256)
        assertEquals("2026-10-01T12:00:00Z", recording.recordedAt)
        assertEquals("in-app runner; busy retries: 0", recording.notes)
        // Round-trips through the strict reader the import check uses.
        assertEquals(recording, TagRecording.read(TagRecording.write(recording)))
    }

    @Test
    fun cancellationPropagatesAndReturnsNothing() = runTest {
        val stallAt = corpus.cases[5]
        val stallInput = TagCorpusExtractionInput.forCase(stallAt)
        val reached = CompletableDeferred<Unit>()
        val inner = fake()
        val extractor = object : ActivityExtractor {
            override val provenance = inner.provenance
            override suspend fun extract(input: ExtractionInput): ExtractionResult {
                if (input == stallInput) {
                    reached.complete(Unit)
                    awaitCancellation()
                }
                return inner.extract(input)
            }
        }
        var returned: TagRecordingResult? = null
        var cancelled = false
        val run = TagCorpusRun(extractor, corpus, "Test Phone", { inner.nanos }, { recordedAt }, { })
        val job = launch {
            try {
                returned = run.run { _, _ -> }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        reached.await()
        job.cancel()
        job.join()
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue(cancelled)
        assertNull(returned)
    }

    @Test
    fun writeAtomicallyLeavesOnlyTheTargetFile() = runTest {
        val dir = Files.createTempDirectory("tag-corpus-run").toFile()
        try {
            val target = File(File(dir, "tag-corpus"), "tag-device-recording.json")
            val recording = runner(fake(), mutableListOf()).run { _, _ -> }.recording
            TagCorpusRunnerActivity.writeAtomically(recording, target)

            assertEquals(listOf("tag-device-recording.json"), target.parentFile!!.list()!!.toList())
            assertEquals(recording, TagRecording.read(target.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun medianOfEvenAndEmptyLists() {
        assertEquals(0L, TagCorpusRun.median(emptyList()))
        assertEquals(15L, TagCorpusRun.median(listOf(20, 10)))
        assertEquals(20L, TagCorpusRun.median(listOf(30, 10, 20)))
    }
}

/** The fake extractor's answer for case [id]: carries the id so tests can tell entries apart. */
private fun answer(id: String) = ExtractionResult.Success(
    ExtractionCandidate(InterpretationOperation.LOG_ACTIVITY, "subject $id", "action", null, null, null),
)
