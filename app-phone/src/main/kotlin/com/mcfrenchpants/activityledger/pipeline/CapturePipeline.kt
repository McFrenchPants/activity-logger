package com.mcfrenchpants.activityledger.pipeline

import android.content.Context
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoActivityExtractor
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import com.mcfrenchpants.activityledger.core.data.createActivityRepository
import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import java.time.Clock

/**
 * The one place in the phone app where the capture pipeline's collaborators are wired together:
 * the ledger repository, the on-device model capability, the extractor over it, the clock and
 * the orchestrator that drives them.
 *
 * There is a single capture pipeline: the subject + action tag pipeline ([extractor],
 * [taggedOrchestrator]), used by the Log screen and the watch capture receiver alike. The older
 * single-activity pipeline was removed from the phone app (ADR-050).
 *
 * Nothing else in app-phone may construct any of these. Wiring lives here and only here, by
 * hand: this project deliberately uses no dependency-injection framework (no Hilt, Koin or
 * Dagger), because one composition function is the entire wiring need of a single-process app
 * and a container would only hide the ownership rules written below.
 *
 * ## What app-phone is allowed to know
 *
 * Everything named here is either an Android type, a `java.time` type, or a plain-Kotlin type
 * from core-domain/core-data/core-ai. No ML Kit type appears in this module at all (ADR-023),
 * and nothing here inspects, re-interprets or second-guesses model output: judging an
 * interpretation is the orchestrator's and the domain validator's job (ADR-010).
 *
 * ## Ownership and lifetime
 *
 * [capability] owns a closeable on-device model client, so this object owns one too, and
 * [close] is the only thing that releases it. Create **exactly one [CapturePipeline] per app
 * process** and share it for the process's whole life:
 *
 * - loading and warming an on-device model is expensive, and the client is built to be reused;
 * - the underlying database is likewise opened once and shared;
 * - a pipeline created per capture would leak a client nobody closes.
 *
 * A caller that does create a short-lived pipeline (an instrumented test, say) is responsible
 * for closing it; [CapturePipeline] is [AutoCloseable] so `use { }` does the right thing.
 * Closing releases the model client only -- the repository's database handle is process-wide
 * and outlives any single pipeline.
 *
 * ## Nothing downloads by itself
 *
 * Creating a pipeline does not check readiness, warm anything up, or fetch a model. Ask
 * [OnDeviceModelCapability.readiness] before expecting extraction to work, and never call
 * [OnDeviceModelCapability.download] except because the person using the app asked for it.
 *
 * ## No logging
 *
 * This class logs nothing, ever. Capture text, prompt text and model output must not reach any
 * log (AGENTS.md #11).
 */
class CapturePipeline private constructor(
    /** The ledger. Reads and writes every capture, interpretation and occurrence. */
    val repository: LedgerRepository,
    /** The process's on-device model client. Closed by [close]; see the lifetime note above. */
    val capability: OnDeviceModelCapability,
    /** The clock every timestamp in the pipeline is read from. */
    val clock: Clock,
    /**
     * The extractor over the same [capability], exposed chiefly for
     * [GeminiNanoActivityExtractor.warmUp]. The tagged orchestrator already holds its own
     * reference (wrapped in a busy retry); callers should not invoke `extract` directly.
     */
    val extractor: GeminiNanoActivityExtractor,
    /** Drives one capture to exactly one persisted subject + action outcome. */
    val taggedOrchestrator: TaggedCaptureOrchestrator,
) : AutoCloseable {

    /**
     * Releases the on-device model client. Idempotent. After this, extraction reports
     * [ModelReadiness.CHECK_FAILED] rather than running.
     */
    override fun close() {
        capability.close()
    }

    companion object {
        /**
         * Builds the real pipeline.
         *
         * The orchestrator's extractor is the Gemini extractor wrapped in a [BusyRetryExtractor],
         * so a capture made while the model is busy waits briefly and is tried again (at most
         * twice) instead of failing; the Gemini extractor itself still makes one model call per
         * attempt (ADR-030 and its amendment).
         *
         * @param context any context; the repository takes the application context itself.
         * @param clock the pipeline's source of time; defaults to the system clock.
         * @param extractorDecorator an observation seam, applied to the extractor the tagged
         *   orchestrator is given -- outside the busy retry, so it observes the whole wait. It
         *   exists so a test can measure how long a call took without wiring a second pipeline
         *   of its own, and it is the identity function in production. A decorator may observe
         *   timing only: it must not read, alter, log or re-interpret anything passing through it.
         */
        fun create(
            context: Context,
            clock: Clock = Clock.systemDefaultZone(),
            extractorDecorator: (ActivityExtractor) -> ActivityExtractor = { it },
        ): CapturePipeline {
            val repository = createActivityRepository(context, clock)
            val capability = OnDeviceModelCapability()
            val extractor = GeminiNanoActivityExtractor(capability)
            val taggedOrchestrator = TaggedCaptureOrchestrator(
                repository = repository,
                extractor = extractorDecorator(BusyRetryExtractor(extractor)),
                clock = clock,
            )
            return CapturePipeline(repository, capability, clock, extractor, taggedOrchestrator)
        }
    }
}
