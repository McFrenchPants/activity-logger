package com.mcfrenchpants.activityledger.wear.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where the drain loop asks for its next deferred wake-up. */
interface RetryScheduling {
    /** Arms a single wake-up at [timeMillis] (epoch millis), replacing any earlier one; null cancels it. */
    fun scheduleAt(timeMillis: Long?)
}

/**
 * Runs [drain] one pass at a time. A [wake] during a pass is remembered and causes exactly one more
 * pass afterwards; passes never overlap. After every pass the returned time goes to [scheduler].
 * Never logs; a failing pass is swallowed (the next wake or alarm retries).
 */
class DrainLoop(
    private val scope: CoroutineScope,
    private val drain: suspend () -> Long?,
    private val scheduler: RetryScheduling,
) {
    private val lock = Any()
    private var running = false
    private var pending = false
    private var current: Job? = null

    /** Requests a pass. The returned job completes when the loop that covers this request goes idle. */
    fun wake(): Job = synchronized(lock) {
        val existing = current
        if (running && existing != null) {
            pending = true
            return existing
        }
        running = true
        pending = false
        val job = scope.launch { loop() }
        current = job
        job
    }

    private suspend fun loop() {
        try {
            while (true) {
                try {
                    scheduler.scheduleAt(drain())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    // Swallowed: the next wake or alarm tries again.
                }
                synchronized(lock) {
                    if (pending) {
                        pending = false
                    } else {
                        running = false
                        return
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (current === kotlin.coroutines.coroutineContext[Job]) running = false
            }
        }
    }
}
