package com.mcfrenchpants.activityledger.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * How many candidate transcripts the engine is asked for. Small on purpose: the extra candidates
 * only ever feed a "did you mean" affordance, and a long list costs the engine work for nothing.
 */
private const val MAX_RESULT_COUNT = 3

/**
 * The one implementation of [SpeechTranscriber] that touches the Android SDK: the platform
 * on-device recognizer, obtained through
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] (ADR-024).
 *
 * ## Why the platform recognizer
 *
 * ADR-024 chose it over ML Kit's `genai-speech-recognition`, which is alpha and whose better
 * mode runs only on the newest Pixels, while the watch and the fallback phone need the platform
 * engine regardless. This module therefore declares no ML Kit dependency at all, and under
 * ADR-023 no module but core-ai may name one.
 *
 * ## Offline, always
 *
 * The session asks for the on-device recognizer *and* sets `EXTRA_PREFER_OFFLINE`, so speech is
 * never handed to a network service. This module declares no network permission and there is no
 * cloud fallback path to add one for (ADR-025).
 *
 * ## The session contract, and how it is kept
 *
 * One collection is one session. Partials are forwarded as they arrive, then exactly one
 * terminal event is emitted and the flow completes -- enforced by a single compare-and-set, so
 * a late callback after a result, or an error delivered on top of a result, cannot produce a
 * second terminal event. A partial is never promoted: if the engine finishes with nothing
 * usable, the session ends as [SpeechFailure.NOTHING_HEARD] and the last partial is dropped.
 *
 * The recognizer is cancelled and then destroyed in `awaitClose`, which runs on every exit path
 * there is -- a normal finish, a failure, and cancellation of the collector -- and every one of
 * those calls, like the creation and the start, is posted to the main thread, which the platform
 * recognizer requires.
 *
 * ## Nothing is logged, ever
 *
 * There is no logging call anywhere in this module. A transcript exists only inside a
 * [SpeechEvent] handed to the collector; it cannot reach a failure, because [SpeechFailure] is
 * an enum with no payload (AGENTS.md #11). Audio never exists on this side of the API at all:
 * the platform's audio-buffer callback is ignored without being read.
 */
class PlatformSpeechTranscriber internal constructor(
    private val recognizerFactory: RecognizerFactory,
    private val mainThread: MainThreadRunner,
) : SpeechTranscriber {

    /** Production constructor. Holds the application context, never an Activity. */
    constructor(context: Context) : this(
        PlatformRecognizerFactory(context.applicationContext),
        LooperMainThreadRunner(),
    )

    companion object {

        /**
         * A transcriber backed by the ORDINARY platform recognizer
         * ([SpeechRecognizer.createSpeechRecognizer]) with `EXTRA_PREFER_OFFLINE` set (ADR-035).
         *
         * It exists because the watch has no `createOnDeviceSpeechRecognizer()` (the
         * constructor above reports [SpeechFailure.NO_ON_DEVICE_ENGINE] there), yet has an
         * ordinary recognition service. `EXTRA_PREFER_OFFLINE` is only a hint: this recognizer
         * may use a network engine, which is acceptable (ADR-036). The session rules are exactly
         * those of the on-device path; only the way the recognizer is obtained differs.
         */
        fun preferringOffline(context: Context): PlatformSpeechTranscriber =
            PlatformSpeechTranscriber(
                OrdinaryRecognizerFactory(context.applicationContext),
                LooperMainThreadRunner(),
            )
    }

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        // The live recognizer, if one was created. Read by the teardown below, which may run
        // on a different thread than the one that set it.
        val handle = AtomicReference<RecognizerHandle?>(null)

        // Guards the "exactly one terminal event" rule. The platform is free to deliver an
        // error after a result, or two errors; only the first one through this gate is emitted.
        val terminated = AtomicBoolean(false)

        fun emitTerminal(event: SpeechEvent) {
            if (terminated.compareAndSet(false, true)) {
                trySend(event)
                close()
            }
        }

        val callbacks = object : RecognizerCallbacks {

            override fun onPartialTranscripts(results: RecognitionResults) {
                // A partial that arrives after the session has settled is stale, and emitting it
                // would break the "terminal event is last" rule.
                if (terminated.get()) return
                results.bestTranscriptOrNull()?.let { trySend(SpeechEvent.PartialTranscript(it)) }
            }

            override fun onFinalTranscripts(results: RecognitionResults) {
                // An empty or all-blank final batch is the engine saying it heard nothing usable
                // -- which is a failure, NOT an invitation to fall back on the last partial.
                emitTerminal(
                    results.toFinalTranscript() ?: SpeechEvent.Failed(SpeechFailure.NOTHING_HEARD),
                )
            }

            override fun onError(errorCode: Int) {
                emitTerminal(SpeechEvent.Failed(speechFailureOf(errorCode)))
            }
        }

        mainThread.execute {
            val created = try {
                recognizerFactory.create()
            } catch (unsupported: UnsupportedOperationException) {
                // A real case, not defensive padding: a probe on a OnePlus Watch 3 (Wear OS 5)
                // showed the factory throwing this even though the device has a registered
                // recognition service. Letting it propagate would crash the collector for what
                // is simply "this watch cannot do it".
                emitTerminal(SpeechEvent.Failed(SpeechFailure.NO_ON_DEVICE_ENGINE))
                return@execute
            } catch (security: SecurityException) {
                // Some engines refuse at creation time when the microphone permission is missing
                // rather than reporting ERROR_INSUFFICIENT_PERMISSIONS later.
                emitTerminal(SpeechEvent.Failed(SpeechFailure.PERMISSION_MISSING))
                return@execute
            } catch (expected: Exception) {
                // Deliberately broad, and deliberately not rethrown: this interface promises a
                // failure event rather than a throw. Errors are NOT caught -- a broken process
                // is not an unusable session. Nothing about `expected` is retained or reported.
                emitTerminal(SpeechEvent.Failed(SpeechFailure.ENGINE_ERROR))
                return@execute
            }

            if (created == null) {
                emitTerminal(SpeechEvent.Failed(SpeechFailure.NO_ON_DEVICE_ENGINE))
                return@execute
            }

            handle.set(created)

            try {
                created.start(callbacks)
            } catch (expected: Exception) {
                // The recognizer exists, so the teardown below still owns it; only the session
                // failed to start.
                emitTerminal(SpeechEvent.Failed(SpeechFailure.ENGINE_ERROR))
            }
        }

        awaitClose {
            // The single release path, reached however the session ended -- result, failure, or
            // the collector walking away. cancel() first so the engine stops listening even if
            // destroy() is slow to take effect; both are attempted even if cancel() throws.
            mainThread.execute {
                val live = handle.getAndSet(null) ?: return@execute
                try {
                    live.cancel()
                } finally {
                    live.destroy()
                }
            }
        }
    }.buffer(Channel.UNLIMITED, BufferOverflow.SUSPEND)
    // Unlimited on purpose: callbacks arrive on the main thread and cannot block waiting for a
    // collector, and a dropped event here would mean a lost *terminal* event and a flow that
    // never completes. The buffer holds a handful of short strings for the length of one
    // session.
}

/**
 * Maps one of `SpeechRecognizer`'s `ERROR_*` codes to the kind a caller can act on. The numeric
 * code is used here and then discarded; it is never reported, stored or logged.
 *
 * `ERROR_CLIENT` is read as [SpeechFailure.CANCELLED] rather than as a generic engine error: the
 * platform reports it when the client itself ends the session, which is what happens when the
 * user backs out of listening, and telling someone their engine failed when they cancelled would
 * be wrong.
 */
internal fun speechFailureOf(errorCode: Int): SpeechFailure = when (errorCode) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.PERMISSION_MISSING

    // Silence and "could not match that" are the same thing to the person who just spoke.
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> SpeechFailure.NOTHING_HEARD

    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure.RECOGNIZER_BUSY

    // The engine cannot serve this device or this language locally, and no download or network
    // fallback will be attempted to fix that.
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    -> SpeechFailure.NO_ON_DEVICE_ENGINE

    SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.CANCELLED

    // Everything else, including codes added by a future platform version.
    else -> SpeechFailure.ENGINE_ERROR
}

/** Creates real platform recognizers. Holds no state beyond the application context. */
private class PlatformRecognizerFactory(private val context: Context) : RecognizerFactory {

    override fun create(): RecognizerHandle? {
        // Cheap, synchronous and starts nothing. Asking first turns the common "this device has
        // no local engine" case into a clean answer instead of an exception.
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null

        // May throw UnsupportedOperationException on a device whose recognition service cannot
        // serve an on-device request; the caller maps that.
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return PlatformRecognizerHandle(recognizer, languageTagOf(context))
    }
}

/**
 * Creates ordinary (not on-device) platform recognizers, for devices such as the watch that have
 * no on-device factory (ADR-035). The recognizer may use a network engine (ADR-036).
 */
internal class OrdinaryRecognizerFactory(private val context: Context) : RecognizerFactory {

    override fun create(): RecognizerHandle? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        return PlatformRecognizerHandle(recognizer, languageTagOf(context))
    }
}

/** The thin wrapper over one platform recognizer. Contains no decisions. */
internal class PlatformRecognizerHandle(
    private val recognizer: SpeechRecognizer,
    private val languageTag: String?,
) : RecognizerHandle {

    override fun start(callbacks: RecognizerCallbacks) {
        recognizer.setRecognitionListener(PlatformRecognitionListener(callbacks))
        recognizer.startListening(recognitionIntent(languageTag))
    }

    override fun cancel() = recognizer.cancel()

    override fun destroy() = recognizer.destroy()
}

/** The one request shape this module ever sends to a recognizer. */
internal fun recognitionIntent(languageTag: String?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Free-form dictation: people describe what they did in ordinary sentences, not in
        // commands from a fixed grammar.
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // Belt and braces with createOnDeviceSpeechRecognizer: speech must not go to a server.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULT_COUNT)
        languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
    }

/**
 * Translates the platform listener into [RecognizerCallbacks]. Every callback that cannot change
 * the outcome is dropped here -- including `onBufferReceived`, whose audio is ignored without
 * being read, so raw audio never exists on this side of the seam.
 */
private class PlatformRecognitionListener(
    private val callbacks: RecognizerCallbacks,
) : RecognitionListener {

    override fun onPartialResults(partialResults: Bundle?) {
        callbacks.onPartialTranscripts(BundleRecognitionResults(partialResults))
    }

    override fun onResults(results: Bundle?) {
        callbacks.onFinalTranscripts(BundleRecognitionResults(results))
    }

    override fun onError(error: Int) {
        callbacks.onError(error)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

/**
 * The only place a results `Bundle` is read. It unpacks the two keys the session cares about and
 * makes no decision about them; every rule lives in the pure [RecognitionResults] helpers.
 */
private class BundleRecognitionResults(bundle: Bundle?) : RecognitionResults {

    override val transcripts: List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    override val confidenceScores: List<Float>? =
        bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
}

/**
 * The language the recognizer is asked for: the device's own configured language. Null when it
 * cannot be read, in which case the engine picks its default.
 */
internal fun languageTagOf(context: Context): String? = try {
    context.resources.configuration.locales
        .takeIf { !it.isEmpty }
        ?.get(0)
        ?.toLanguageTag()
} catch (expected: Exception) {
    null
}
