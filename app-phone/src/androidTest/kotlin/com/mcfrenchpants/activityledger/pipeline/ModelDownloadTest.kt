package com.mcfrenchpants.activityledger.pipeline

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.core.ai.ModelDownloadProgress
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.MainActivity
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fetches the on-device model onto this device, deliberately and once.
 *
 * Downloading is a multi-gigabyte, explicitly user-initiated decision: nothing on the
 * interpretation path may make it, and no ordinary test run may cause it either. This test is
 * therefore inert unless it is asked for by name, with an instrumentation argument:
 *
 * ```
 * ./gradlew :app-phone:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mcfrenchpants.activityledger.pipeline.ModelDownloadTest \
 *   -Pandroid.testInstrumentationRunnerArguments.downloadModel=true
 * ```
 *
 * Without `downloadModel=true` it skips, so a plain `connectedDebugAndroidTest` never downloads
 * anything as a side effect. It also skips when there is nothing to do (the model is already
 * [ModelReadiness.READY]) or nothing possible ([ModelReadiness.UNSUPPORTED_DEVICE]).
 *
 * Nothing calls this test, and it calls nothing but [OnDeviceModelCapability.download], exactly
 * once, with no retry loop. It reports byte counts and, on failure, a numeric error code --
 * never any other content (AGENTS.md #11).
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadTest {

    @Test
    fun downloadsTheModelWhenExplicitlyAsked() = runBlocking {
        val asked = InstrumentationRegistry.getArguments().getString(DOWNLOAD_ARGUMENT) == "true"
        assumeTrue(
            "Skipped: no model download was requested. Pass -P" +
                "android.testInstrumentationRunnerArguments.$DOWNLOAD_ARGUMENT=true to ask for one.",
            asked,
        )

        OnDeviceModelCapability().use { capability ->
            val before = capability.readiness()
            assumeTrue(
                "Skipped: nothing to download -- this device's model readiness is already " +
                    "${ModelReadiness.READY}.",
                before != ModelReadiness.READY,
            )
            assumeTrue(
                "Skipped: nothing possible -- this device reports " +
                    "${ModelReadiness.UNSUPPORTED_DEVICE} and cannot run the model in any configuration.",
                before != ModelReadiness.UNSUPPORTED_DEVICE,
            )
            Log.i(TAG, "readinessBefore=$before")

            // The app must be the top foreground app. Google permits GenAI calls only from a
            // foreground app -- a background caller is refused with BACKGROUND_USE_BLOCKED. An
            // instrumented test puts nothing on screen by itself, and the first attempt at this
            // download sat silent for fifty minutes without emitting even a failure, so the
            // Activity is launched and held resumed for the whole collection.
            val progress = mutableListOf<ModelDownloadProgress>()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)

                // Bounded on purpose. If the flow stays silent, this reports "no events in N
                // minutes" instead of hanging, which is what made the first attempt uninformative.
                val finished = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MILLIS) {
                    // Collected exactly once. No retry, no second attempt on failure.
                    capability.download().collect { event ->
                        progress += event
                        // Numbers only, one line per event, so a timeout still leaves a trail.
                        Log.i(TAG, "event#${progress.size} ${event.describeNumerically()}")
                    }
                    true
                } ?: false
                Log.i(TAG, "flowFinished=$finished events=${progress.size}")
                assertTrue(
                    "download emitted ${progress.size} event(s) and did not finish within " +
                        "${DOWNLOAD_TIMEOUT_MILLIS / 60_000} minutes",
                    finished,
                )
            }

            // Numbers only: the size reported, how far it got, and a numeric code if it failed.
            val started = progress.filterIsInstance<ModelDownloadProgress.Started>().lastOrNull()
            val lastProgress = progress.filterIsInstance<ModelDownloadProgress.Progress>().lastOrNull()
            val failure = progress.filterIsInstance<ModelDownloadProgress.Failed>().lastOrNull()
            val completed = progress.any { it == ModelDownloadProgress.Completed }
            Log.i(
                TAG,
                "events=${progress.size} bytesToDownload=${started?.bytesToDownload ?: -1} " +
                    "bytesDownloaded=${lastProgress?.totalBytesDownloaded ?: -1} " +
                    "completed=$completed failureCode=${failure?.errorCode ?: -1}",
            )

            assertTrue(
                "download reported failure code ${failure?.errorCode}",
                failure == null,
            )
            assertTrue("download produced no events at all", progress.isNotEmpty())
            assertTrue("download did not report completion", completed)

            val after = capability.readiness()
            Log.i(TAG, "readinessAfter=$after")
            assertEquals("model should be ready after a completed download", ModelReadiness.READY, after)
        }
    }

    /** Numbers only -- never any other content (AGENTS.md #11). */
    private fun ModelDownloadProgress.describeNumerically(): String = when (this) {
        is ModelDownloadProgress.Started -> "started bytesToDownload=$bytesToDownload"
        is ModelDownloadProgress.Progress -> "progress bytesDownloaded=$totalBytesDownloaded"
        ModelDownloadProgress.Completed -> "completed"
        is ModelDownloadProgress.Failed -> "failed code=$errorCode"
    }

    private companion object {
        const val TAG = "ModelDownload"
        const val DOWNLOAD_ARGUMENT = "downloadModel"

        /**
         * How long to wait for the download flow to finish. Generous, because the model is
         * measured in gigabytes, but finite, because a silent flow must report rather than hang.
         */
        const val DOWNLOAD_TIMEOUT_MILLIS = 20L * 60L * 1000L
    }
}
