package com.mcfrenchpants.activityledger.core.testing

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspend [block] synchronously on the calling thread, without a coroutines library.
 *
 * Intended for code whose fakes never truly suspend. Returns the block's value or rethrows
 * its exception. If the block suspends and has not completed by the time control returns,
 * throws [IllegalStateException] (there is no event loop to resume it).
 */
fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result -> outcome = result },
    )
    val done = outcome ?: throw IllegalStateException("runSuspend: block suspended without completing")
    return done.getOrThrow()
}
