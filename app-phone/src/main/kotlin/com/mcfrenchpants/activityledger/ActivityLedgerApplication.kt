package com.mcfrenchpants.activityledger

import android.app.Application
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoQuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.services.TagManagementService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.pipeline.BusyRetryQuestionExtractor
import com.mcfrenchpants.activityledger.pipeline.CapturePipeline

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
 * Nothing is created in [onCreate]. Each member is built on first use (thread-safe `lazy`, so a
 * race still yields exactly one instance). Opening the database or creating the on-device model
 * client therefore never happens just because the process started, and host-side UI tests that
 * never touch the ledger never open it.
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
