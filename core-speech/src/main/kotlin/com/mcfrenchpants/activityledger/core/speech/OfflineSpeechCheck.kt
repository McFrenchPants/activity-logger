package com.mcfrenchpants.activityledger.core.speech

import android.content.Context
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** How long the platform gets to answer before the answer is taken to be "not confirmed". */
private const val CHECK_TIMEOUT_MILLIS = 3_000L

/** Whether speech for the configured language is known to be handled on the device itself. */
enum class OfflineAssurance {
    /** The platform says an on-device model for the configured language is installed. */
    ON_DEVICE_CONFIRMED,

    /** Anything else: not installed, unknown, errored, unavailable or too slow to answer. */
    NOT_CONFIRMED,
}

/**
 * Asks the platform whether an on-device speech model for the configured language is installed,
 * without starting a listening session (ADR-035).
 *
 * It exists because the ordinary recognizer used by [PlatformSpeechTranscriber.preferringOffline]
 * only *prefers* offline and can still reach a network engine. Callers that must be honest about
 * being offline ask this first. Any doubt is [OfflineAssurance.NOT_CONFIRMED].
 *
 * Nothing is logged, no network permission is used, and no transcript is involved.
 */
class OfflineSpeechCheck internal constructor(
    private val context: Context,
    private val mainThread: MainThreadRunner,
) {

    constructor(context: Context) : this(context.applicationContext, LooperMainThreadRunner())

    /**
     * Never throws except [CancellationException]. The recognizer created for the check is
     * destroyed on the main thread on every exit path.
     */
    suspend fun check(): OfflineAssurance {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return OfflineAssurance.NOT_CONFIRMED
        return try {
            withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) { queryPlatform() }
                ?: OfflineAssurance.NOT_CONFIRMED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            OfflineAssurance.NOT_CONFIRMED
        }
    }

    private suspend fun queryPlatform(): OfflineAssurance {
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        try {
            return suspendCancellableCoroutine { continuation ->
                mainThread.execute {
                    try {
                        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                            continuation.resume(OfflineAssurance.NOT_CONFIRMED)
                            return@execute
                        }
                        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                        recognizerRef.set(recognizer)
                        val tag = languageTagOf(context)
                        val callback = object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                val answer = try {
                                    offlineAssuranceOf(
                                        recognitionSupport.installedOnDeviceLanguages,
                                        tag,
                                    )
                                } catch (expected: Exception) {
                                    OfflineAssurance.NOT_CONFIRMED
                                }
                                if (continuation.isActive) continuation.resume(answer)
                            }

                            override fun onError(error: Int) {
                                if (continuation.isActive) {
                                    continuation.resume(OfflineAssurance.NOT_CONFIRMED)
                                }
                            }
                        }
                        recognizer.checkRecognitionSupport(
                            recognitionIntent(tag),
                            Executor { it.run() },
                            callback,
                        )
                    } catch (expected: Exception) {
                        if (continuation.isActive) {
                            continuation.resume(OfflineAssurance.NOT_CONFIRMED)
                        }
                    }
                }
            }
        } finally {
            // Every exit path: answer, error, timeout, cancellation. Posted to the main thread,
            // which the platform recognizer requires. If creation has not yet happened when a
            // timeout fires, destroy runs in order after it, because the runner is FIFO.
            mainThread.execute {
                val live = recognizerRef.getAndSet(null) ?: return@execute
                try {
                    live.destroy()
                } catch (expected: Exception) {
                    // Nothing more to release; the check's answer is already decided.
                }
            }
        }
    }
}

/**
 * Pure decision: is the language [languageTag] covered by [installedOnDeviceLanguages]?
 *
 * Case-insensitive. A tag is satisfied by an exact match (`en-US` by `en-US`), or by an installed
 * bare language (`en` satisfies `en-XX`). An installed regional tag does NOT satisfy a different
 * region (`en-US` does not satisfy `en-AU`) nor a bare tag. A null tag or an empty list is
 * [OfflineAssurance.NOT_CONFIRMED].
 */
internal fun offlineAssuranceOf(
    installedOnDeviceLanguages: List<String>,
    languageTag: String?,
): OfflineAssurance {
    if (languageTag.isNullOrBlank()) return OfflineAssurance.NOT_CONFIRMED
    val wanted = languageTag.trim().lowercase(Locale.ROOT)
    val bare = wanted.substringBefore('-')
    val satisfied = installedOnDeviceLanguages.any { installed ->
        val have = installed.trim().lowercase(Locale.ROOT)
        have == wanted || (!have.contains('-') && have == bare)
    }
    return if (satisfied) OfflineAssurance.ON_DEVICE_CONFIRMED else OfflineAssurance.NOT_CONFIRMED
}
