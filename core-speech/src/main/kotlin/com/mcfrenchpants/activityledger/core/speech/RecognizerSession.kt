package com.mcfrenchpants.activityledger.core.speech

/**
 * The seam between the session logic in [PlatformSpeechTranscriber] and the actual platform
 * recognizer.
 *
 * It exists so the interesting rules -- exactly one terminal event, partials never promoted to
 * results, always cancel then destroy -- can be driven from a host JVM test with no device and
 * no microphone. The production side of the seam is a thin wrapper around
 * `android.speech.SpeechRecognizer` that contains no decisions of its own.
 *
 * ## Threading
 *
 * Every member of [RecognizerHandle], and [RecognizerFactory.create], is called on the main
 * thread and nowhere else: the platform recognizer requires it. Callbacks arrive on the main
 * thread too, because that is where the platform delivers them.
 */
internal interface RecognizerFactory {

    /**
     * Creates a recognizer, or returns null when this device has no on-device engine.
     *
     * Null is the expected, non-exceptional "no engine here" answer. It is separate from
     * throwing because the platform factory does both: it can report unavailability up front,
     * and it can also throw `UnsupportedOperationException` on a device that claims to have a
     * recognition service but cannot give an on-device one (observed on a OnePlus Watch 3
     * running Wear OS 5). Both end up as [SpeechFailure.NO_ON_DEVICE_ENGINE].
     */
    fun create(): RecognizerHandle?
}

/** One live recognizer. Created, started, cancelled and destroyed on the main thread only. */
internal interface RecognizerHandle {

    /** Attaches [callbacks] and begins listening. */
    fun start(callbacks: RecognizerCallbacks)

    /** Stops listening. Always called before [destroy], on every exit path. */
    fun cancel()

    /** Releases the engine. Always called, exactly once, on every exit path. */
    fun destroy()
}

/**
 * What a recognizer tells the session about. A deliberately smaller set than the platform's own
 * listener: the events that do not change the outcome (ready for speech, sound levels, buffers
 * of audio, end of speech) are dropped at the adapter and never reach any logic here. In
 * particular the raw audio buffer callback is discarded without being read, so no audio bytes
 * exist anywhere on this side of the seam.
 */
internal interface RecognizerCallbacks {

    /** A live, display-only guess. May arrive any number of times, including zero. */
    fun onPartialTranscripts(results: RecognitionResults)

    /** The session's final batch. May legitimately be empty, meaning nothing was recognised. */
    fun onFinalTranscripts(results: RecognitionResults)

    /** The session failed, with one of `SpeechRecognizer`'s `ERROR_*` codes. */
    fun onError(errorCode: Int)
}
