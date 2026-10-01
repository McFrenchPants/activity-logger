package com.mcfrenchpants.activityledger.core.wearprotocol.outbox

/**
 * Retry backoff. After the Nth failed attempt the next try waits:
 * 1 -> 5 s, 2 -> 30 s, 3 -> 2 min, 4 -> 10 min, 5 -> 30 min, 6 and later -> 1 h (cap).
 *
 * There is deliberately no attempt limit: a transient failure never becomes permanent because of
 * how many times it happened, so a capture is never dropped for being slow to deliver.
 */
open class OutboxPolicy {
    open fun delayAfterAttempt(attemptCount: Int): Long {
        val index = (attemptCount - 1).coerceIn(0, DELAYS_MILLIS.size - 1)
        return DELAYS_MILLIS[index]
    }

    companion object {
        const val SECOND: Long = 1_000L
        const val MINUTE: Long = 60 * SECOND
        const val HOUR: Long = 60 * MINUTE

        /** Delay table; the last entry is the cap. */
        val DELAYS_MILLIS: List<Long> =
            listOf(5 * SECOND, 30 * SECOND, 2 * MINUTE, 10 * MINUTE, 30 * MINUTE, HOUR)

        val Default: OutboxPolicy = OutboxPolicy()
    }
}
