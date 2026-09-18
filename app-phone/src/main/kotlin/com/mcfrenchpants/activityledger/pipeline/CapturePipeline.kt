package com.mcfrenchpants.activityledger.pipeline

import android.content.Context
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoActivityInterpreter
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import com.mcfrenchpants.activityledger.core.data.createActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import java.time.Clock

/**
 * The one place in the phone app where the capture pipeline's collaborators are wired together:
 * the ledger repository, the on-device model capability, the interpreter over it, the clock and
 * the orchestrator that drives them.
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
 * [OnDeviceModelCapability.readiness] before expecting interpretation to work, and never call
 * [OnDeviceModelCapability.download] except because the person using the app asked for it.
 *
 * ## No logging
 *
 * This class logs nothing, ever. Capture text, prompt text and model output must not reach any
 * log (AGENTS.md #11).
 */
class CapturePipeline private constructor(
    /** The ledger. Reads and writes every capture, interpretation and occurrence. */
    val repository: ActivityRepository,
    /** The process's on-device model client. Closed by [close]; see the lifetime note above. */
    val capability: OnDeviceModelCapability,
    /**
     * The interpreter, exposed for the things only it can do -- chiefly
     * [GeminiNanoActivityInterpreter.warmUp] and a readiness-aware caller. The orchestrator
     * already holds its own reference; callers should not invoke `interpret` directly.
     */
    val interpreter: GeminiNanoActivityInterpreter,
    /** The clock every timestamp in the pipeline is read from. */
    val clock: Clock,
    /** Drives one capture from stored raw text to exactly one persisted outcome. */
    val orchestrator: CaptureInterpretationOrchestrator,
) : AutoCloseable {

    /**
     * Releases the on-device model client. Idempotent. After this, interpretation reports
     * [ModelReadiness.CHECK_FAILED] rather than running.
     */
    override fun close() {
        capability.close()
    }

    companion object {
        /**
         * Builds the real pipeline.
         *
         * @param context any context; the repository takes the application context itself.
         * @param clock the pipeline's source of time; defaults to the system clock.
         * @param interpreterDecorator an observation seam, applied to the interpreter the
         *   orchestrator is given. It exists so a test can measure how long a call took without
         *   wiring a second pipeline of its own, and it is the identity function in production.
         *   A decorator may observe timing only: it must not read, alter, log or re-interpret
         *   anything passing through it.
         */
        fun create(
            context: Context,
            clock: Clock = Clock.systemDefaultZone(),
            interpreterDecorator: (ActivityInterpreter) -> ActivityInterpreter = { it },
        ): CapturePipeline {
            val repository = createActivityRepository(context, clock)
            val capability = OnDeviceModelCapability()
            val interpreter = GeminiNanoActivityInterpreter(capability)
            val orchestrator = CaptureInterpretationOrchestrator(
                repository = repository,
                interpreter = interpreterDecorator(interpreter),
                clock = clock,
            )
            return CapturePipeline(repository, capability, interpreter, clock, orchestrator)
        }
    }
}
