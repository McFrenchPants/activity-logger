package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Interprets captures that were stored while the app was in the background (ADR-029).
 *
 * The platform refuses on-device model calls unless the app is the top foreground app, so a
 * watch capture that arrives while the phone app is not on screen is stored raw and left
 * CAPTURED (see [com.mcfrenchpants.activityledger.wear.WatchCaptureReceiver]). Each time the app
 * comes to the foreground, [onForeground] asks for one sweep: every capture that has no
 * occurrence and is CAPTURED or FAILED_RETRYABLE goes through [process] (the tagged
 * orchestrator, behind the pipeline's busy retry), oldest first, one at a time.
 *
 * Rules it keeps:
 * - Captures in NEEDS_REVIEW or FAILED_FINAL are never re-processed (that would re-ask a model
 *   answer, ADR-030), and neither is one with an occurrence.
 * - Every [process] call happens under [lock], the same lock the watch receiver and the Log
 *   screen hold while they create and interpret a capture, and the capture's state is re-read
 *   under that lock first. So a capture is never processed twice at once, and one the Log screen
 *   or the receiver has just handled is skipped.
 * - [isForeground] is checked immediately before every [process] call; the sweep stops as soon
 *   as the app has left the foreground. The next foreground transition starts a new sweep.
 * - Requests are conflated: a request made while a sweep runs schedules exactly one more sweep
 *   after it, so a capture deferred mid-sweep is still picked up.
 * - The sweep runs on [scope] (application-scoped in production), so leaving a screen does not
 *   cancel it. A failing read or process call is swallowed; the capture keeps its state.
 * - Raw captures are only read here, never rewritten. Nothing is logged (AGENTS.md #11).
 */
class ForegroundCatchUp(
    private val repository: ActivityRepository,
    private val process: suspend (captureId: String) -> TaggedProcessingOutcome,
    private val isForeground: () -> Boolean,
    private val lock: Mutex,
    scope: CoroutineScope,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    sweep()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Exception) {
                    // Nothing changes for a capture that could not be handled; the next sweep retries.
                }
            }
        }
    }

    /** The app came to the foreground: schedule a sweep (at most one queued behind a running one). */
    fun onForeground() {
        requests.trySend(Unit)
    }

    /** One pass over the unprocessed captures, oldest first. Returns early once in the background. */
    internal suspend fun sweep() {
        if (!isForeground()) return
        val pending = repository.loadHistory()
            .filter { it.occurrence == null && it.processingState in UNPROCESSED }
            .sortedWith(compareBy({ it.capturedAt }, { it.captureId }))
            .map { it.captureId }
        for (captureId in pending) {
            lock.withLock {
                val current = try {
                    repository.getCapture(captureId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Exception) {
                    null
                }
                val stillPending = current != null && !current.hasOccurrence && current.processingState in UNPROCESSED
                if (stillPending) {
                    if (!isForeground()) return
                    try {
                        process(captureId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (expected: Exception) {
                        // The capture keeps its state; a later sweep may try it again.
                    }
                }
            }
        }
    }

    private companion object {
        val UNPROCESSED = setOf(ProcessingState.CAPTURED, ProcessingState.FAILED_RETRYABLE)
    }
}
