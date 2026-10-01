package com.mcfrenchpants.activityledger.core.wearprotocol

/** Watch-side lifecycle of one capture (WATCH_SPEC section 4). */
enum class OutboxState { CREATED, QUEUED, SENDING, PHONE_RECEIVED, PROCESSED, ACKNOWLEDGED, RETRYABLE, FAILED }

/** Things that can happen to an outbox entry. */
sealed interface OutboxEvent {
    data object Queued : OutboxEvent
    data object SendStarted : OutboxEvent
    data object SendFailedTransient : OutboxEvent
    data object SendFailedPermanent : OutboxEvent
    data class AckReceived(val status: AckStatus) : OutboxEvent
}

/** Result of [OutboxTransitions.next]. */
sealed interface TransitionResult {
    /** The entry legally moved to [to]. */
    data class Moved(val to: OutboxState) : TransitionResult

    /** Idempotent no-op: a duplicate or late ack. Not an error. */
    data object Unchanged : TransitionResult

    /** The event is not legal from [from]. */
    data class Refused(val from: OutboxState, val event: OutboxEvent) : TransitionResult
}

/**
 * Pure outbox transition function.
 *
 * Legal edges: CREATED -queued-> QUEUED; QUEUED/RETRYABLE -sendStarted-> SENDING;
 * SENDING -transient failure-> RETRYABLE; QUEUED/SENDING/RETRYABLE -permanent failure-> FAILED.
 *
 * Ack status mapping (valid from SENDING, RETRYABLE, PHONE_RECEIVED, PROCESSED; an ack in
 * CREATED or QUEUED is refused because nothing was sent):
 * - RECEIVED -> PHONE_RECEIVED (phone has the capture, outcome unknown)
 * - NEEDS_REVIEW -> PROCESSED (phone interpreted it but wants user review; a later SAVED completes it)
 * - SAVED -> ACKNOWLEDGED (terminal success)
 * - FAILED_RETRYABLE -> RETRYABLE
 * - FAILED_FINAL -> FAILED (terminal)
 *
 * Any ack in a terminal state (ACKNOWLEDGED, FAILED) is [TransitionResult.Unchanged]. An ack
 * that would move the entry backwards (RECEIVED after PROCESSED) or repeats the current
 * target state is also [TransitionResult.Unchanged].
 */
object OutboxTransitions {
    fun next(state: OutboxState, event: OutboxEvent): TransitionResult {
        val refused = TransitionResult.Refused(state, event)
        return when (event) {
            OutboxEvent.Queued ->
                if (state == OutboxState.CREATED) moved(OutboxState.QUEUED) else refused
            OutboxEvent.SendStarted ->
                if (state == OutboxState.QUEUED || state == OutboxState.RETRYABLE) {
                    moved(OutboxState.SENDING)
                } else {
                    refused
                }
            OutboxEvent.SendFailedTransient ->
                if (state == OutboxState.SENDING) moved(OutboxState.RETRYABLE) else refused
            OutboxEvent.SendFailedPermanent ->
                when (state) {
                    OutboxState.QUEUED, OutboxState.SENDING, OutboxState.RETRYABLE -> moved(OutboxState.FAILED)
                    else -> refused
                }
            is OutboxEvent.AckReceived -> ack(state, event, refused)
        }
    }

    private fun moved(to: OutboxState) = TransitionResult.Moved(to)

    private fun ack(state: OutboxState, event: OutboxEvent.AckReceived, refused: TransitionResult): TransitionResult {
        when (state) {
            OutboxState.ACKNOWLEDGED, OutboxState.FAILED -> return TransitionResult.Unchanged
            OutboxState.CREATED, OutboxState.QUEUED -> return refused
            else -> Unit
        }
        val target = when (event.status) {
            AckStatus.RECEIVED -> OutboxState.PHONE_RECEIVED
            AckStatus.NEEDS_REVIEW -> OutboxState.PROCESSED
            AckStatus.SAVED -> OutboxState.ACKNOWLEDGED
            AckStatus.FAILED_RETRYABLE -> OutboxState.RETRYABLE
            AckStatus.FAILED_FINAL -> OutboxState.FAILED
        }
        if (target == state) return TransitionResult.Unchanged
        if (target == OutboxState.PHONE_RECEIVED && state == OutboxState.PROCESSED) return TransitionResult.Unchanged
        return moved(target)
    }
}
