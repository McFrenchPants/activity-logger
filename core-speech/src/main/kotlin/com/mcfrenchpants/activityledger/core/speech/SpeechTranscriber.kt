package com.mcfrenchpants.activityledger.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Turns spoken words into text, one explicit session at a time.
 *
 * ## What a session is
 *
 * [listen] returns a **cold** flow: nothing is created, no microphone is opened and no engine is
 * started until someone collects it, and collecting it a second time starts a second, separate
 * session. Cancelling the collection ends the session and releases the engine. There is no
 * continuous, background or hotword listening anywhere behind this interface, and there must
 * never be: a session exists only while a collector is actively waiting on it.
 *
 * The event sequence is described by [SpeechEvent]: live partials for display, then exactly one
 * terminal event, then completion.
 *
 * ## What it is not
 *
 * It produces text and hands it back. It does not persist anything, does not know what a capture
 * is, and does not interpret what was said -- the phone's own pipeline decides what to do with
 * the words. It also never exposes audio: no implementation may hand out audio bytes, write
 * audio to disk, or keep audio past the engine's own session.
 *
 * ## No Android in sight
 *
 * Neither this interface nor any type in its signatures names an Android type, so callers,
 * tests and future non-Android implementations are not tied to the platform recognizer.
 * [PlatformSpeechTranscriber] is the one implementation that touches the Android SDK.
 */
interface SpeechTranscriber {

    /**
     * Starts one listening session when collected.
     *
     * Reports every failure as a [SpeechEvent.Failed] rather than throwing, including a device
     * with no on-device engine and a missing microphone permission; the only thing that ends
     * this flow exceptionally is cancellation of the collecting coroutine, which propagates as
     * usual.
     */
    fun listen(): Flow<SpeechEvent>
}
