package com.mcfrenchpants.activityledger.core.speech

/**
 * Everything a listening session can report. A closed set: a caller that handles all three
 * members handles every outcome, and the compiler says so.
 *
 * ## Shape of a session
 *
 * Zero or more [PartialTranscript]s, then exactly one terminal event -- either [FinalTranscript]
 * or [Failed] -- after which the session's flow completes. Never two terminal events, never a
 * terminal event followed by a partial, and never a session that ends with neither (except when
 * the collector itself cancels, which ends the flow with no event at all, as cancellation
 * always does in Kotlin).
 *
 * Every type named here is a plain Kotlin type. Nothing in this file, or reachable from it,
 * comes from the Android SDK: the platform recognizer lives behind [PlatformSpeechTranscriber]
 * and its types never reach a caller.
 */
sealed interface SpeechEvent {

    /**
     * The engine's current best guess, **for display only**.
     *
     * It will change, it may disappear entirely, and it is not a result. Show it to give the
     * user live feedback that they are being heard; never store it, never send it anywhere,
     * and never treat it as the answer -- a session that produces partials and then hears
     * nothing usable ends in [SpeechFailure.NOTHING_HEARD], with the partial discarded.
     */
    data class PartialTranscript(val text: String) : SpeechEvent

    /**
     * The session's one result: what the engine settled on.
     *
     * @property text the best transcript, never blank.
     * @property confidence the engine's own confidence in [text], between 0 and 1, or null when
     *   it did not report one (many on-device engines do not). Null means "unknown", never
     *   "zero", so do not substitute a number for it.
     * @property alternatives the engine's other candidate transcripts, best first, excluding
     *   [text]. Usually empty. Offered so a later step can show "did you mean"; nothing is
     *   obliged to use them.
     */
    data class FinalTranscript(
        val text: String,
        val confidence: Float?,
        val alternatives: List<String>,
    ) : SpeechEvent

    /**
     * The session ended without a result.
     *
     * Carries a [SpeechFailure] kind and nothing else -- see that type for why it cannot carry
     * anything else.
     */
    data class Failed(val failure: SpeechFailure) : SpeechEvent
}
