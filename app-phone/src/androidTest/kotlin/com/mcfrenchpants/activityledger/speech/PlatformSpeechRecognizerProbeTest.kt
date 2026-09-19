package com.mcfrenchpants.activityledger.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-off hardware measurement on the PHONE, NOT a regression gate. The watch twin of this
 * test lives in app-wear/src/androidTest (tag `WearSpeechProbe`); this one tags its output
 * `PhoneSpeechProbe` so the two runs are never confused in logcat.
 *
 * Why it exists: the watch run answered `isOnDeviceRecognitionAvailable=false` and
 * `createOnDeviceSpeechRecognizer(...)` threw `UnsupportedOperationException`, even though
 * Google's recognizer was installed and registered as the default recognition service. The
 * phone's assumption that on-device recognition works rests on the same kind of package /
 * property inspection that just proved wrong, so it has to be measured on real hardware
 * before any core-speech work depends on it.
 *
 * It reports:
 *
 *  1. [SpeechRecognizer.isRecognitionAvailable]
 *  2. [SpeechRecognizer.isOnDeviceRecognitionAvailable]
 *  3. whether `createOnDeviceSpeechRecognizer(...)` + `startListening(...)` reaches
 *     [RecognitionListener.onReadyForSpeech] within a bounded wait, or else which
 *     `SpeechRecognizer.ERROR_*` code (or exception class) it fails with
 *  4. the recognition-service components the platform would resolve, by name only.
 *
 * It deliberately does NOT transcribe: an automated run cannot supply audio, so the session
 * is cancelled the moment the engine reports it is ready. Nothing but booleans, integer
 * error codes and component names is ever logged or asserted on (AGENTS.md #11).
 *
 * "Unavailable" is a valid answer, so the test PASSES in that case too; only a missing
 * callback (bounded wait, never a hang) fails it.
 *
 * Run it by hand, with RECORD_AUDIO granted out of band:
 *
 * ```
 * adb shell pm grant com.mcfrenchpants.activityledger android.permission.RECORD_AUDIO
 * ./gradlew :app-phone:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.mcfrenchpants.activityledger.speech.PlatformSpeechRecognizerProbeTest
 * adb logcat -d -s PhoneSpeechProbe
 * ```
 *
 * If app-phone does not yet declare RECORD_AUDIO in its manifest the grant will be rejected
 * and question 3 will answer ERROR_9(INSUFFICIENT_PERMISSIONS) or a SecurityException --
 * still a real measurement of questions 1 and 2, but re-run it once the permission is
 * declared before treating question 3 as settled.
 */
@RunWith(AndroidJUnit4::class)
class PlatformSpeechRecognizerProbeTest {

    @Test
    fun reportPlatformSpeechRecognizerAvailability() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext

        // Questions 1 + 2: static availability, as the platform reports it.
        val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        // Which service(s) would actually serve a recognition request. Component names only.
        val components = resolveRecognitionServiceComponents(context)

        // Question 3: does an on-device recognizer actually start?
        val startOutcome = probeOnDeviceStartListening(context)

        val report = buildString {
            appendLine("$TAG RESULT isRecognitionAvailable=$recognitionAvailable")
            appendLine("$TAG RESULT isOnDeviceRecognitionAvailable=$onDeviceRecognitionAvailable")
            appendLine("$TAG RESULT onDeviceStartListening=$startOutcome")
            appendLine("$TAG RESULT recognitionServiceComponents=$components")
        }
        report.trim().lines().forEach { Log.i(TAG, it.removePrefix("$TAG ")) }

        // Also push it into the instrumentation stream so `am instrument -r` shows it.
        instrumentation.sendStatus(
            INSTRUMENTATION_STATUS_IN_PROGRESS,
            Bundle().apply { putString("stream", "\n$report") },
        )

        // Always-true assertion whose message carries the findings: an "unavailable" or
        // "ERROR_*" answer is a measurement, not a build failure. The only real failures
        // are an exception escaping above or NO_CALLBACK from the bounded wait below.
        assertTrue(
            "\n$report",
            startOutcome != OUTCOME_NO_CALLBACK,
        )
    }

    /**
     * Creates an on-device recognizer and starts a session, entirely on the main thread
     * (the platform [SpeechRecognizer] requires it), waiting at most [READY_TIMEOUT_SECONDS]
     * for the first callback. Returns a short outcome token, never any recognized text.
     *
     * The recognizer is destroyed in a `finally` on every exit path: ready, error, timeout
     * and exception.
     */
    private fun probeOnDeviceStartListening(context: Context): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstCallback = CountDownLatch(1)
        val outcome = AtomicReference(OUTCOME_NO_CALLBACK)
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val creationFailure = AtomicReference<String?>(null)

        try {
            instrumentation.runOnMainSync {
                try {
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizerRef.set(recognizer)
                    recognizer.setRecognitionListener(
                        ProbeListener(outcome, firstCallback),
                    )
                    recognizer.startListening(recognitionIntent())
                } catch (t: Throwable) {
                    creationFailure.set("THREW_${t.javaClass.simpleName}")
                    firstCallback.countDown()
                }
            }

            // Bounded: the test reports NO_CALLBACK rather than hanging.
            firstCallback.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return creationFailure.get() ?: outcome.get()
        } finally {
            val recognizer = recognizerRef.getAndSet(null)
            if (recognizer != null) {
                try {
                    instrumentation.runOnMainSync {
                        try {
                            // Stop immediately: this probe must never capture speech.
                            recognizer.cancel()
                        } finally {
                            recognizer.destroy()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "teardown threw ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun resolveRecognitionServiceComponents(context: Context): List<String> =
        context.packageManager
            .queryIntentServices(Intent(RECOGNITION_SERVICE_ACTION), 0)
            .mapNotNull { it.serviceInfo }
            .map { "${it.packageName}/${it.name}" }
            .sorted()

    /**
     * Records only the first callback, as a token. Result callbacks are answered with a
     * fixed token and never read: no transcript or partial text is touched.
     */
    private class ProbeListener(
        private val outcome: AtomicReference<String>,
        private val firstCallback: CountDownLatch,
    ) : RecognitionListener {

        private fun record(token: String) {
            if (outcome.compareAndSet(OUTCOME_NO_CALLBACK, token)) {
                firstCallback.countDown()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = record(OUTCOME_READY)

        override fun onError(error: Int) = record("ERROR_$error(${errorName(error)})")

        override fun onResults(results: Bundle?) = record(OUTCOME_RESULTS_IGNORED)

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "PhoneSpeechProbe"
        const val RECOGNITION_SERVICE_ACTION = "android.speech.RecognitionService"
        const val READY_TIMEOUT_SECONDS = 20L

        /** `Instrumentation.sendStatus` code for "still running" progress output. */
        const val INSTRUMENTATION_STATUS_IN_PROGRESS = 2

        const val OUTCOME_NO_CALLBACK = "NO_CALLBACK"
        const val OUTCOME_READY = "READY_FOR_SPEECH"
        const val OUTCOME_RESULTS_IGNORED = "RESULTS_BEFORE_READY_IGNORED"

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            else -> "UNKNOWN"
        }
    }
}
