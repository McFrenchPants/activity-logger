package com.mcfrenchpants.activityledger.core.speech

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole `SpeechRecognizer.ERROR_*` to [SpeechFailure] mapping, asserted against the
 * platform's own constants rather than against copied numbers, so a renumbering in a future
 * Android release cannot quietly pass.
 *
 * These constants are compile-time `static final int`s, so they are inlined into this test's
 * bytecode and no Android runtime is needed to read them.
 */
class SpeechFailureMappingTest {

    @Test
    fun `missing microphone permission is reported as such`() {
        assertEquals(
            SpeechFailure.PERMISSION_MISSING,
            speechFailureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun `no match and speech timeout both mean nothing was heard`() {
        assertEquals(SpeechFailure.NOTHING_HEARD, speechFailureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(
            SpeechFailure.NOTHING_HEARD,
            speechFailureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun `a busy engine is reported as busy`() {
        assertEquals(
            SpeechFailure.RECOGNIZER_BUSY,
            speechFailureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
        )
    }

    @Test
    fun `support and language problems mean there is no local engine for this device`() {
        assertEquals(
            SpeechFailure.NO_ON_DEVICE_ENGINE,
            speechFailureOf(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT),
        )
        assertEquals(
            SpeechFailure.NO_ON_DEVICE_ENGINE,
            speechFailureOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            SpeechFailure.NO_ON_DEVICE_ENGINE,
            speechFailureOf(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
    }

    @Test
    fun `a client-ended session is a cancellation, not an engine failure`() {
        assertEquals(SpeechFailure.CANCELLED, speechFailureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun `every other known code falls through to a generic engine error`() {
        val generic = listOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        )

        generic.forEach { code ->
            assertEquals(SpeechFailure.ENGINE_ERROR, speechFailureOf(code), "code $code")
        }
    }

    @Test
    fun `an unrecognised code is a generic engine error rather than a guess`() {
        assertEquals(SpeechFailure.ENGINE_ERROR, speechFailureOf(Int.MAX_VALUE))
        assertEquals(SpeechFailure.ENGINE_ERROR, speechFailureOf(-1))
    }
}
