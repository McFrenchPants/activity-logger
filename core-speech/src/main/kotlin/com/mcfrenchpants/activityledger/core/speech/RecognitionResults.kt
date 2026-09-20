package com.mcfrenchpants.activityledger.core.speech

/**
 * A read-only view of one batch of transcripts the engine handed back.
 *
 * This is the pure seam in front of the platform's results `Bundle`. Reading a `Bundle` needs a
 * device; deciding which transcript wins, whether a confidence score is usable and what counts
 * as "nothing heard" does not -- and that decision is the part worth testing. So the `Bundle` is
 * unpacked in one tiny Android-only adapter (see `PlatformSpeechTranscriber`) and every rule
 * below is applied to this interface instead, on a host JVM.
 *
 * Internal on purpose: it is shaped by the platform recognizer's output and is not part of what
 * this module offers its callers.
 */
internal interface RecognitionResults {

    /**
     * The engine's candidate transcripts, best first, exactly as it supplied them. May be empty,
     * and individual entries may be blank.
     */
    val transcripts: List<String>

    /**
     * The engine's confidence in each entry of [transcripts], positionally aligned, or null when
     * it reported none. Entries may be negative, which the platform uses to mean "not scored".
     */
    val confidenceScores: List<Float>?
}

/**
 * The best transcript that actually contains something, or null when the batch is empty or is
 * nothing but blanks.
 *
 * Null is the "nothing heard" answer, and the only one: the caller never has to distinguish an
 * absent list from a list of empty strings.
 */
internal fun RecognitionResults.bestTranscriptOrNull(): String? =
    transcripts.firstOrNull { it.isNotBlank() }

/**
 * The whole result of a finished session, or null if it contained no usable words.
 *
 * The rules, in one place:
 *
 * - the winner is the first non-blank transcript, not simply the first one (some engines pad the
 *   list with empty entries);
 * - alternatives are the *other* non-blank transcripts, in the engine's own order, with the
 *   winner removed once -- an engine that repeats itself does not get to offer the answer back
 *   as an alternative to itself;
 * - the confidence is taken from the score aligned with the winner, and only when the score list
 *   is the same length as the transcript list (otherwise the alignment is a guess) and the value
 *   is a real number in 0..1. Anything else becomes null, meaning "unknown". A made-up number
 *   would be worse than no number, because a later step could weigh it.
 * - **a score of exactly 0 is treated as "not reported", not as "certainly wrong."** Measured on
 *   a Pixel 10 Pro (2026-09-19): the platform on-device engine returns a score array of the right
 *   length with every entry 0.0, for transcripts it clearly did recognise well. It does not report
 *   confidence for on-device recognition and emits 0.0 as filler. Storing that 0.0 would write
 *   exactly the fabricated number the rule above exists to prevent -- it would just have been
 *   fabricated by the engine rather than by us. The cost of the rule is that a genuine 0.0 is
 *   read as unknown, which is harmless: a transcript the engine has zero confidence in carries
 *   no information a caller could act on anyway.
 */
internal fun RecognitionResults.toFinalTranscript(): SpeechEvent.FinalTranscript? {
    val winnerIndex = transcripts.indexOfFirst { it.isNotBlank() }
    if (winnerIndex < 0) return null
    val winner = transcripts[winnerIndex]

    val alternatives = transcripts
        .filterIndexed { index, text -> index != winnerIndex && text.isNotBlank() }

    return SpeechEvent.FinalTranscript(
        text = winner,
        confidence = confidenceAt(winnerIndex),
        alternatives = alternatives,
    )
}

/** The usable confidence for one position, or null. See [toFinalTranscript] for the rules. */
private fun RecognitionResults.confidenceAt(index: Int): Float? {
    val scores = confidenceScores ?: return null
    if (scores.size != transcripts.size) return null
    val score = scores[index]
    if (!score.isFinite() || score !in 0f..1f) return null
    // Exactly 0 means "the engine did not report one" -- see toFinalTranscript's rules.
    return if (score == 0f) null else score
}
