package com.mcfrenchpants.activityledger.debugtools

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecording
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordedQuestion
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** JVM tests of the debug-only in-app question corpus runner's run logic. */
class QuestionCorpusRunTest {

    private val corpus = QuestionCorpus.load()
    private val total = corpus.cases.size
    private val recordedAt = Instant.parse("2026-10-05T12:00:00Z")

    /** One scripted extractor step: what to return and how long the call "took". */
    private data class Step(val result: QuestionExtractionResult, val latencyMs: Long)

    /**
     * Fake extractor on a fake monotonic clock. Each call consumes the next scripted step for its
     * case (found by its question text) or the default answer, advancing the clock by its latency.
     */
    private inner class FakeExtractor(
        private val script: Map<String, List<Step>> = emptyMap(),
        private val defaultLatencyMs: Long = 4_000,
    ) : QuestionExtractor {
        var nanos = 0L
        val calls = mutableListOf<String>()
        private val consumed = mutableMapOf<String, Int>()
        private val idByQuestion = corpus.cases.associate { it.question to it.id }

        override val provenance = InterpreterProvenance("fake-question-1", "q2", 1)

        override suspend fun extract(questionText: String): QuestionExtractionResult {
            calls += questionText
            val id = idByQuestion.getValue(questionText)
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

    private fun runner(extractor: FakeExtractor, delays: MutableList<Long>) = QuestionCorpusRun(
        extractor = extractor,
        corpus = corpus,
        deviceModel = "Test Phone",
        nanoTime = { extractor.nanos },
        now = { recordedAt },
        delay = { delays += it },
    )

    @Test
    fun questionsAreUniqueSoTheFakeCanTellCasesApart() {
        assertEquals(total, corpus.cases.map { it.question }.toSet().size)
    }

    @Test
    fun everyCaseIsAskedOnceInCorpusOrderWithOnlyItsQuestionText() = runTest {
        val extractor = FakeExtractor()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = runner(extractor, mutableListOf()).run { done, n -> progress += done to n }

        assertEquals(corpus.cases.map { it.question }, extractor.calls)
        assertEquals(corpus.cases.map { it.id }, result.recording.entries.map { it.caseId })
        assertEquals((0..total).map { it to total }, progress)
        assertEquals(total, result.total)
        assertEquals(total, result.answered)
        assertEquals(0, result.failed)
        assertEquals(0, result.busyRetries)
        assertEquals(total * 4_000L, result.totalLatencyMs)
        assertEquals(4_000L, result.medianLatencyMs)
    }

    @Test
    fun entriesMapAnswersAndFailures() = runTest {
        val failedId = corpus.cases[3].id
        val extractor = FakeExtractor(
            mapOf(failedId to listOf(Step(QuestionExtractionResult.Failure(InterpreterFailureKind.MALFORMED), 4_500))),
        )
        val result = runner(extractor, mutableListOf()).run { _, _ -> }

        val first = result.recording.entries[0]
        assertEquals(RecordedQuestion("subject ${first.caseId}", "action", "today", QuestionKind.COUNT), first.answer)
        assertNull(first.failureKind)
        assertEquals(4_000L, first.latencyMs)

        val failed = result.recording.entries[3]
        assertEquals(failedId, failed.caseId)
        assertNull(failed.answer)
        assertEquals(InterpreterFailureKind.MALFORMED, failed.failureKind)
        assertEquals(4_500L, failed.latencyMs)
        assertEquals(total - 1, result.answered)
        assertEquals(1, result.failed)
    }

    @Test
    fun fastThrottledRefusalsAreRetriedWithTheTagRunBackoffThenRecorded() = runTest {
        val id = corpus.cases[0].id
        val busy = Step(QuestionExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), 50)
        val other = Step(QuestionExtractionResult.Failure(InterpreterFailureKind.OTHER), 10)
        val extractor = FakeExtractor(mapOf(id to listOf(busy, other, Step(answer(id), 5_000))))
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertEquals(TagCorpusRun.BUSY_BACKOFF_MS.take(2), delays)
        assertEquals(2, result.busyRetries)
        assertEquals(total + 2, extractor.calls.size)
        assertEquals(answer(id).candidate, result.recording.entries[0].answer?.toCandidate())
        assertEquals(5_000L, result.recording.entries[0].latencyMs)
        assertEquals("in-app runner; busy retries: 2", result.recording.notes)
    }

    @Test
    fun slowOrNonThrottleFailuresAreNotRetried() = runTest {
        val slow = corpus.cases[0].id
        val unavailable = corpus.cases[1].id
        val extractor = FakeExtractor(
            mapOf(
                slow to listOf(Step(QuestionExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), TagCorpusRun.FAST_REFUSAL_MS)),
                unavailable to listOf(Step(QuestionExtractionResult.Failure(InterpreterFailureKind.UNAVAILABLE), 5)),
            ),
        )
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertTrue(delays.isEmpty())
        assertEquals(total, extractor.calls.size)
        assertEquals(InterpreterFailureKind.RETRYABLE, result.recording.entries[0].failureKind)
        assertEquals(InterpreterFailureKind.UNAVAILABLE, result.recording.entries[1].failureKind)
    }

    @Test
    fun backoffExhaustionRecordsTheLastFailure() = runTest {
        val id = corpus.cases[0].id
        val busy = Step(QuestionExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), 20)
        val last = Step(QuestionExtractionResult.Failure(InterpreterFailureKind.OTHER), 30)
        val extractor = FakeExtractor(mapOf(id to List(TagCorpusRun.BUSY_BACKOFF_MS.size) { busy } + last))
        val delays = mutableListOf<Long>()
        val result = runner(extractor, delays).run { _, _ -> }

        assertEquals(TagCorpusRun.BUSY_BACKOFF_MS, delays)
        assertEquals(TagCorpusRun.BUSY_BACKOFF_MS.size, result.busyRetries)
        val entry = result.recording.entries[0]
        assertEquals(InterpreterFailureKind.OTHER, entry.failureKind)
        assertEquals(30L, entry.latencyMs)
    }

    @Test
    fun headerCarriesProvenanceHashSourceAndNotesAndNoQuestionText() = runTest {
        val recording = runner(FakeExtractor(), mutableListOf()).run { _, _ -> }.recording

        assertEquals(RecordingSource.DEVICE, recording.source)
        assertEquals(TagCorpusRun.MODEL_LABEL, recording.modelLabel)
        assertEquals("Test Phone", recording.deviceModel)
        assertEquals(InterpreterProvenance("fake-question-1", "q2", 1), recording.provenance)
        assertEquals(corpus.sha256, recording.questionCorpusSha256)
        assertEquals("2026-10-05T12:00:00Z", recording.recordedAt)
        assertEquals("in-app runner; busy retries: 0", recording.notes)
        val text = QuestionRecording.write(recording)
        assertEquals(recording, QuestionRecording.read(text))
        corpus.cases.forEach { assertFalse("recording holds a question", text.contains(it.question)) }
    }

    @Test
    fun cancellationPropagatesAndReturnsNothing() = runTest {
        val stallAt = corpus.cases[5].question
        val reached = CompletableDeferred<Unit>()
        val inner = FakeExtractor()
        val extractor = object : QuestionExtractor {
            override val provenance = inner.provenance
            override suspend fun extract(questionText: String): QuestionExtractionResult {
                if (questionText == stallAt) {
                    reached.complete(Unit)
                    awaitCancellation()
                }
                return inner.extract(questionText)
            }
        }
        var returned: QuestionRecordingResult? = null
        var cancelled = false
        val run = QuestionCorpusRun(extractor, corpus, "Test Phone", { inner.nanos }, { recordedAt }, { })
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
        val dir = Files.createTempDirectory("question-corpus-run").toFile()
        try {
            val target = File(File(dir, "question-corpus"), "question-device-recording.json")
            val recording = runner(FakeExtractor(), mutableListOf()).run { _, _ -> }.recording
            TagCorpusRunnerActivity.writeAtomically(recording, target)

            assertEquals(listOf("question-device-recording.json"), target.parentFile!!.list()!!.toList())
            assertEquals(recording, QuestionRecording.read(target.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The fake extractor's answer for case [id]: carries the id so tests can tell entries apart. */
private fun answer(id: String) = QuestionExtractionResult.Success(QuestionCandidate("subject $id", "action", "today", QuestionKind.COUNT))
