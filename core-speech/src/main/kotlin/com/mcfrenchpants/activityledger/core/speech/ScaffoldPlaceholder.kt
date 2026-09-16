package com.mcfrenchpants.activityledger.core.speech

import android.content.Context
import android.speech.SpeechRecognizer

/**
 * SCAFFOLD PLACEHOLDER -- no product meaning.
 *
 * This module will wrap the platform on-device recognizer per ADR-024, but no
 * transcription, audio capture or recognizer lifecycle exists yet, and no
 * transcriber abstraction is declared here: that interface is real design owned by
 * the speech step, and inventing a shape for it in a scaffold would set a
 * precedent that step has to live with.
 *
 * What is here is one capability probe, which is genuinely part of ADR-024's
 * reasoning: from API 33 the on-device recognizer path fails explicitly rather than
 * silently falling back to a network recognizer, and a caller has to be able to ask
 * whether a local engine exists at all.
 *
 * Nothing here records, stores, logs or transmits any utterance. It must stay that
 * way: this module sits closest to raw user speech.
 */
object ScaffoldPlaceholder {

    /** Returns a fixed marker string. Carries no product meaning. */
    fun marker(): String = "core-speech scaffold placeholder"

    /**
     * Reports whether this device has an on-device speech recognition service
     * available. Placeholder-level capability detection only -- it starts no
     * recognizer and processes no audio.
     */
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}
