package com.mcfrenchpants.activityledger.core.speech

import android.content.Context
import android.speech.SpeechRecognizer

/**
 * What a device can do about speech, without doing any of it.
 *
 * [SpeechCapability.check] answers two questions -- is there a local engine at all, and which
 * language would it be asked for -- and that is the whole of it. It starts no recognizer, opens
 * no microphone, captures no audio and triggers no download, so it is safe to call from a
 * settings screen or before drawing a microphone button.
 *
 * @property onDeviceRecognitionAvailable whether this device reports a usable on-device
 *   recognition engine. False means voice capture should be offered as unavailable rather than
 *   attempted; there is no network recognizer to fall back to (ADR-025). It is the platform's
 *   own claim, not a guarantee -- a device can still refuse at session time, which arrives as
 *   [SpeechFailure.NO_ON_DEVICE_ENGINE].
 * @property languageTag the BCP-47 tag the recognizer would be asked for, or null when it cannot
 *   be determined cheaply. This is the device's configured language rather than an interrogation
 *   of the engine: asking the engine what it supports means starting a support check, which is
 *   exactly the kind of work this must not do.
 */
data class SpeechAvailability(
    val onDeviceRecognitionAvailable: Boolean,
    val languageTag: String?,
)

/** Reports [SpeechAvailability] for a device. Cheap, synchronous and side-effect free. */
class SpeechCapability(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Looks at the device and reports what it can do.
     *
     * Never throws for an underlying failure: a device that cannot answer is reported as having
     * no engine, which is also how it would behave. Nothing about a failure is retained.
     */
    fun check(): SpeechAvailability {
        val available = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } catch (expected: Exception) {
            // "I could not find out" and "there is none" lead to the same offer to the user.
            false
        }
        return SpeechAvailability(
            onDeviceRecognitionAvailable = available,
            languageTag = languageTagOf(appContext),
        )
    }
}
