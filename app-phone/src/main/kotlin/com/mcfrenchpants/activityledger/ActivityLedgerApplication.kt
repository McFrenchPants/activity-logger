package com.mcfrenchpants.activityledger

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoQuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.services.TagManagementService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.pipeline.BusyRetryQuestionExtractor
import com.mcfrenchpants.activityledger.pipeline.CapturePipeline
import com.mcfrenchpants.activityledger.pipeline.ForegroundCatchUp
import com.mcfrenchpants.activityledger.pipeline.ForegroundSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The phone app's process-wide owner of its long-lived collaborators.
 *
 * It holds exactly one [CapturePipeline] -- the one place the capture pipeline is wired -- and,
 * built from that pipeline's repository and clock, exactly one [TaggedResolutionService], one
 * [TaggedCorrectionService] and one [TagManagementService]. There is no dependency-injection
 * framework (ADR-032); screens reach these through this class.
 *
 * ## Lazy
 *
 * Nothing that touches the ledger or the model is created in [onCreate]: it only registers the
 * activity-lifecycle callback behind [isInForeground]. Each member is built on first use (thread-safe `lazy`, so a
 * race still yields exactly one instance). Opening the database or creating the on-device model
 * client therefore never happens just because the process started, and host-side UI tests that
 * never touch the ledger never open it.
 *
 * ## Foreground and catch-up (ADR-029)
 *
 * [isInForeground] is true while at least one of this app's activities is resumed (a locked
 * screen, a dialog-style app on top or a stopped activity all count as background). Resumed, not
 * merely started, because the on-device model only answers the top foreground app and ADR-029's
 * evidence is with MainActivity resumed: a refusal in the started-but-not-resumed gap would be
 * recorded as a failed interpretation and sent to review, never retried. So the watch receiver
 * defers background captures, and on every background ->
 * foreground transition (including the first activity resume after launch) [foregroundCatchUp]
 * interprets them. That first transition is what first builds the catch-up -- and with it the
 * pipeline -- never the process start itself. The catch-up runs on [applicationScope], so it
 * outlives any screen. [captureProcessingLock] is the one lock every interpretation of a capture
 * (watch receiver, catch-up, Log screen) holds, so none of them interprets a capture another is
 * already interpreting.
 *
 * ## Never closed
 *
 * [CapturePipeline] is [AutoCloseable] because it owns the on-device model client. This class
 * deliberately never closes it: the pipeline lives exactly as long as the process, which is what
 * CapturePipeline's own lifetime rule asks for (one per process, shared for its whole life).
 * Android gives an Application no reliable "process ending" callback (`onTerminate` is never
 * called on devices), and when the process dies the operating system reclaims the client and the
 * database handle with it. Closing it earlier would leave every later capture without a model.
 *
 * ## No logging
 *
 * Nothing here logs anything (AGENTS.md #11).
 */
class ActivityLedgerApplication : Application() {

    /** Fed from the main thread by the lifecycle callbacks registered in [onCreate]. */
    private val foregroundSignal = ForegroundSignal()

    /** True while at least one of this app's activities is resumed. Safe to read from any thread. */
    val isInForeground: StateFlow<Boolean> = foregroundSignal.isForeground

    /** Held by every interpretation of a capture in this process; see the class KDoc. */
    val captureProcessingLock: Mutex = Mutex()

    /** Lives as long as the process; work on it is never tied to a screen. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Interprets captures stored while in the background; built on the first foreground transition. */
    private val foregroundCatchUp: ForegroundCatchUp by lazy {
        ForegroundCatchUp(
            repository = capturePipeline.repository,
            process = capturePipeline.taggedOrchestrator::process,
            isForeground = { isInForeground.value },
            lock = captureProcessingLock,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (foregroundSignal.onResumed()) foregroundCatchUp.onForeground()
                }

                override fun onActivityPaused(activity: Activity) {
                    foregroundSignal.onPaused()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** The process's single capture pipeline. See the class KDoc for why it is never closed. */
    val capturePipeline: CapturePipeline by lazy { CapturePipeline.create(this) }

    /** Resolves waiting tagged captures; shares the pipeline's repository and clock. */
    val taggedResolutionService: TaggedResolutionService by lazy {
        TaggedResolutionService(capturePipeline.repository, capturePipeline.clock)
    }

    /** Applies user corrections to tagged entries; shares the pipeline's repository and clock. */
    val taggedCorrectionService: TaggedCorrectionService by lazy {
        TaggedCorrectionService(capturePipeline.repository, capturePipeline.clock)
    }

    /** Renames, merges and lists tags; shares the pipeline's repository. */
    val tagManagementService: TagManagementService by lazy {
        TagManagementService(capturePipeline.repository)
    }

    /**
     * Answers "Ask your history" questions; shares the pipeline's repository and its on-device
     * model capability (so there is still one model client per process, never closed here, and
     * built lazily on first use) behind the same short busy retry the capture path uses.
     */
    val lookupService: LookupService by lazy {
        LookupService(
            capturePipeline.repository,
            BusyRetryQuestionExtractor(GeminiNanoQuestionExtractor(capturePipeline.capability)),
        )
    }
}
