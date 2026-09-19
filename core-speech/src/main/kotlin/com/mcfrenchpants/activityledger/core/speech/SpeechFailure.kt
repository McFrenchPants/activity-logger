package com.mcfrenchpants.activityledger.core.speech

/**
 * Why a listening session ended without a transcript.
 *
 * ## A kind, and nothing else
 *
 * This is an enum on purpose, and it must stay one. A listening session is the closest this
 * app ever gets to raw user speech, so the type that reports its failure is deliberately
 * incapable of carrying words: it has no message, no cause, no partial text and no field of any
 * kind (AGENTS.md #11). That is a structural guarantee rather than a habit -- there is no
 * property here for a transcript to leak through, so no future edit can accidentally put one in
 * a failure without first changing this declaration. Do not add one. If a diagnostic is ever
 * genuinely needed, add another *kind*, not a payload.
 *
 * The members below are the distinctions a caller can actually act on. Two engine errors that
 * lead to the same thing for the person using the app are the same kind here; the platform's
 * own numeric error code is mapped in core-speech and then discarded.
 */
enum class SpeechFailure {

    /**
     * The microphone permission has not been granted, so nothing was ever listened to.
     *
     * The caller's move is to ask for the permission (or send the user to settings), not to
     * retry the session.
     */
    PERMISSION_MISSING,

    /**
     * This device has no usable on-device recognition engine.
     *
     * Permanent for this device and this app version: retrying achieves nothing. Voice capture
     * should be presented as unavailable rather than as broken. Reaching for a network
     * recognizer instead is not an option (ADR-025: nothing leaves the device).
     */
    NO_ON_DEVICE_ENGINE,

    /**
     * The session ran but produced no words: silence, a timeout, or audio the engine could not
     * match to anything.
     *
     * The ordinary "I pressed the button and then said nothing" outcome, and the one place a
     * caller may sensibly offer "try again". A session that produced live partial text and then
     * ended with no final result also lands here -- a partial is display-only and is never
     * promoted to a result.
     */
    NOTHING_HEARD,

    /** The engine was already busy with another session. Worth retrying shortly. */
    RECOGNIZER_BUSY,

    /**
     * The engine failed for some other reason -- audio trouble, an internal error, or a code
     * this version of the app does not recognise.
     *
     * The deliberate catch-all. An unknown failure is reported as this rather than guessed at.
     */
    ENGINE_ERROR,

    /**
     * The session was stopped from this side before it produced a result.
     *
     * Not an error to show anyone: it is what happens when the user backs out of listening.
     */
    CANCELLED,
}
