package com.mcfrenchpants.activityledger.core.wearprotocol.outbox

import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxEvent
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxTransitions
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolFailureKind
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.TransitionResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec

/** Hard cap on stored records. New enqueues are refused beyond it; nothing is ever evicted. */
const val MAX_OUTBOX_RECORDS: Int = 200

sealed interface EnqueueResult {
    data object Enqueued : EnqueueResult
    data object AlreadyPresent : EnqueueResult
    data object Full : EnqueueResult

    /** Encoding was refused; carries only a payload-free kind. */
    data class Refused(val kind: ProtocolFailureKind) : EnqueueResult
}

/** Result of a state-changing call on an existing capture. Never carries capture text. */
sealed interface UpdateResult {
    /** The record now has state [to] and is kept. */
    data class Moved(val to: OutboxState) : UpdateResult

    /** The record reached a delivered state and was removed from the store. */
    data class Removed(val from: OutboxState) : UpdateResult

    /** Idempotent no-op (duplicate or late ack). */
    data object Unchanged : UpdateResult

    /** No record with that captureId. */
    data object UnknownCapture : UpdateResult

    /** The event is not legal from [from]. */
    data class Refused(val from: OutboxState) : UpdateResult
}

/**
 * The watch's durable outbox. Thread-safe: every public call is synchronized on this instance.
 * All state changes go through [OutboxTransitions.next]. Pure Kotlin/JVM; no logging.
 */
class Outbox(
    private val store: OutboxStore,
    private val clock: () -> Long,
    private val policy: OutboxPolicy = OutboxPolicy.Default,
) {
    @Synchronized
    fun enqueue(envelope: CaptureEnvelope): EnqueueResult {
        val json = when (val encoded = WireCodec.encode(envelope)) {
            is ProtocolResult.Failure -> return EnqueueResult.Refused(encoded.kind)
            is ProtocolResult.Ok -> encoded.value
        }
        if (store.get(envelope.captureId) != null) return EnqueueResult.AlreadyPresent
        if (store.listAll().size >= MAX_OUTBOX_RECORDS) return EnqueueResult.Full
        val queued = when (val t = OutboxTransitions.next(OutboxState.CREATED, OutboxEvent.Queued)) {
            is TransitionResult.Moved -> t.to
            else -> return EnqueueResult.Refused(ProtocolFailureKind.MALFORMED)
        }
        val now = clock()
        val record = OutboxRecord(envelope.captureId, json, queued, 0, now, now)
        return if (store.insertIfAbsent(record)) EnqueueResult.Enqueued else EnqueueResult.AlreadyPresent
    }

    /**
     * Oldest QUEUED or RETRYABLE record whose nextAttemptAt has passed, or a PHONE_RECEIVED
     * record whose ack-wait deadline has passed (it is resent with the same captureId).
     */
    @Synchronized
    fun nextDue(): OutboxRecord? {
        val now = clock()
        return store.listAll().firstOrNull {
            (
                it.state == OutboxState.QUEUED || it.state == OutboxState.RETRYABLE ||
                    it.state == OutboxState.PHONE_RECEIVED
                ) && it.nextAttemptAtEpochMillis <= now
        }
    }

    /** Moves the record to SENDING and sets its ack deadline (now + [OutboxPolicy.ackWaitMillis]). */
    @Synchronized
    fun markSendStarted(captureId: String): UpdateResult =
        apply(captureId, OutboxEvent.SendStarted) { it.copy(nextAttemptAtEpochMillis = ackDeadline()) }

    @Synchronized
    fun markSendFailedTransient(captureId: String): UpdateResult =
        apply(captureId, OutboxEvent.SendFailedTransient) { retryLater(it) }

    @Synchronized
    fun markSendFailedPermanent(captureId: String): UpdateResult =
        apply(captureId, OutboxEvent.SendFailedPermanent) { it }

    @Synchronized
    fun applyAck(ack: CaptureAck): UpdateResult =
        apply(ack.captureId, OutboxEvent.AckReceived(ack.status)) { rec ->
            when (ack.status) {
                AckStatus.FAILED_RETRYABLE -> retryLater(rec)
                AckStatus.RECEIVED -> rec.copy(nextAttemptAtEpochMillis = ackDeadline())
                else -> rec
            }
        }

    /** Deletes a record (used to dismiss a FAILED capture). Returns true if one was removed. */
    @Synchronized
    fun discard(captureId: String): Boolean = store.remove(captureId)

    /** Turns every record left in SENDING (process died mid-send) into RETRYABLE, due now. */
    @Synchronized
    fun recoverOnStart(): Int {
        val now = clock()
        var count = 0
        for (rec in store.listAll()) {
            if (rec.state != OutboxState.SENDING) continue
            val t = OutboxTransitions.next(rec.state, OutboxEvent.SendFailedTransient)
            if (t is TransitionResult.Moved &&
                store.replace(rec.copy(state = t.to, nextAttemptAtEpochMillis = now))
            ) {
                count++
            }
        }
        return count
    }

    /**
     * Turns every SENDING record whose ack deadline has passed into RETRYABLE with the normal
     * backoff (a lost ack must not leave it stuck). Returns how many records were recovered.
     */
    @Synchronized
    fun recoverStale(): Int {
        val now = clock()
        var count = 0
        for (rec in store.listAll()) {
            if (rec.state != OutboxState.SENDING || rec.nextAttemptAtEpochMillis > now) continue
            val t = OutboxTransitions.next(rec.state, OutboxEvent.SendFailedTransient)
            if (t is TransitionResult.Moved && store.replace(retryLater(rec).copy(state = t.to))) count++
        }
        return count
    }

    /**
     * Earliest nextAttemptAt among records the sender still has to act on (QUEUED, RETRYABLE,
     * SENDING, PHONE_RECEIVED), or null if there are none. Lets the caller schedule one wake-up.
     */
    @Synchronized
    fun earliestPendingAttemptAt(): Long? =
        store.listAll().filter {
            it.state == OutboxState.QUEUED || it.state == OutboxState.RETRYABLE ||
                it.state == OutboxState.SENDING || it.state == OutboxState.PHONE_RECEIVED
        }.minOfOrNull { it.nextAttemptAtEpochMillis }

    /** Records still awaiting delivery or phone outcome (everything except FAILED). */
    @Synchronized
    fun pendingCount(): Int = store.listAll().count { it.state != OutboxState.FAILED }

    @Synchronized
    fun failedCount(): Int = store.listAll().count { it.state == OutboxState.FAILED }

    private fun ackDeadline(): Long = clock() + policy.ackWaitMillis

    private fun retryLater(rec: OutboxRecord): OutboxRecord {
        val attempts = rec.attemptCount + 1
        return rec.copy(attemptCount = attempts, nextAttemptAtEpochMillis = clock() + policy.delayAfterAttempt(attempts))
    }

    private fun apply(captureId: String, event: OutboxEvent, adjust: (OutboxRecord) -> OutboxRecord): UpdateResult {
        val rec = store.get(captureId) ?: return UpdateResult.UnknownCapture
        return when (val t = OutboxTransitions.next(rec.state, event)) {
            is TransitionResult.Refused -> UpdateResult.Refused(rec.state)
            TransitionResult.Unchanged -> UpdateResult.Unchanged
            is TransitionResult.Moved -> {
                if (t.to == OutboxState.ACKNOWLEDGED || t.to == OutboxState.PROCESSED) {
                    store.remove(captureId)
                    UpdateResult.Removed(t.to)
                } else {
                    store.replace(adjust(rec).copy(state = t.to))
                    UpdateResult.Moved(t.to)
                }
            }
        }
    }
}
