package com.mcfrenchpants.activityledger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure rules for reading one batch of transcripts: which one wins, what counts as an
 * alternative, and when a confidence score may be believed. Driven through [FakeResults], which
 * is the same shape the platform's results `Bundle` is unpacked into, so these run on a host JVM.
 */
class RecognitionResultsTest {

    // ---- picking the winner ----------------------------------------------------------------

    @Test
    fun `best transcript is the first non-blank one`() {
        val results = FakeResults(transcripts = listOf("", "   ", "walked the dog", "dog walk"))

        assertEquals("walked the dog", results.bestTranscriptOrNull())
    }

    @Test
    fun `an empty batch has no transcript`() {
        assertNull(FakeResults(transcripts = emptyList()).bestTranscriptOrNull())
    }

    @Test
    fun `a batch of only blanks has no transcript`() {
        assertNull(FakeResults(transcripts = listOf("", "  ")).bestTranscriptOrNull())
    }

    // ---- the final result ------------------------------------------------------------------

    @Test
    fun `final result takes the winner and keeps the rest as alternatives`() {
        val results = FakeResults(transcripts = listOf("walked the dog", "walk the dog", "dog"))

        val final = results.toFinalTranscript()

        assertEquals("walked the dog", final?.text)
        assertEquals(listOf("walk the dog", "dog"), final?.alternatives)
    }

    @Test
    fun `alternatives exclude the winner's own slot and every blank`() {
        val results = FakeResults(transcripts = listOf("", "coffee", "", "coffee break"))

        val final = results.toFinalTranscript()

        assertEquals("coffee", final?.text)
        assertEquals(listOf("coffee break"), final?.alternatives)
    }

    @Test
    fun `a batch with nothing usable is not a final result at all`() {
        assertNull(FakeResults(transcripts = listOf(" ", "")).toFinalTranscript())
        assertNull(FakeResults(transcripts = emptyList()).toFinalTranscript())
    }

    // ---- confidence ------------------------------------------------------------------------

    @Test
    fun `confidence is the score aligned with the winner`() {
        val results = FakeResults(
            transcripts = listOf("", "walked the dog", "dog walk"),
            confidenceScores = listOf(0.1f, 0.82f, 0.4f),
        )

        assertEquals(0.82f, results.toFinalTranscript()?.confidence)
    }

    @Test
    fun `a score of exactly zero means unknown, not certainly wrong`() {
        // Measured on a Pixel 10 Pro (2026-09-19): the platform on-device engine fills the score
        // array with 0.0 for transcripts it recognised perfectly well. Believing that would store
        // a fabricated number, which is exactly what the unknown-confidence rule exists to prevent.
        val allZero = FakeResults(
            transcripts = listOf("I just cut the grass"),
            confidenceScores = listOf(0f),
        )

        assertNull(allZero.toFinalTranscript()?.confidence)
    }

    @Test
    fun `a zero score does not stop the transcript or its alternatives being kept`() {
        val results = FakeResults(
            transcripts = listOf("add sanitizer to the hot tub", "acid sanitizer to the hot tub"),
            confidenceScores = listOf(0f, 0f),
        )

        val final = results.toFinalTranscript()

        assertEquals("add sanitizer to the hot tub", final?.text)
        assertEquals(listOf("acid sanitizer to the hot tub"), final?.alternatives)
        assertNull(final?.confidence)
    }

    @Test
    fun `no scores at all means unknown confidence`() {
        val results = FakeResults(transcripts = listOf("walked the dog"), confidenceScores = null)

        assertNull(results.toFinalTranscript()?.confidence)
    }

    @Test
    fun `misaligned scores are not guessed at`() {
        val results = FakeResults(
            transcripts = listOf("walked the dog", "dog walk"),
            confidenceScores = listOf(0.9f),
        )

        assertNull(results.toFinalTranscript()?.confidence)
    }

    @Test
    fun `a score outside zero to one is unknown rather than clamped`() {
        val negative = FakeResults(transcripts = listOf("a"), confidenceScores = listOf(-1f))
        val tooLarge = FakeResults(transcripts = listOf("a"), confidenceScores = listOf(1.5f))
        val notANumber = FakeResults(transcripts = listOf("a"), confidenceScores = listOf(Float.NaN))

        assertNull(negative.toFinalTranscript()?.confidence)
        assertNull(tooLarge.toFinalTranscript()?.confidence)
        assertNull(notANumber.toFinalTranscript()?.confidence)
    }
}
