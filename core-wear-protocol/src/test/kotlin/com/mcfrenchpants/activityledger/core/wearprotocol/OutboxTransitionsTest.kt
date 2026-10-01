package com.mcfrenchpants.activityledger.core.wearprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OutboxTransitionsTest {
    private fun moved(s: OutboxState, e: OutboxEvent, to: OutboxState) =
        assertEquals(TransitionResult.Moved(to), OutboxTransitions.next(s, e), "$s $e")

    private fun refused(s: OutboxState, e: OutboxEvent) =
        assertIs<TransitionResult.Refused>(OutboxTransitions.next(s, e), "$s $e")

    private fun unchanged(s: OutboxState, e: OutboxEvent) =
        assertEquals(TransitionResult.Unchanged, OutboxTransitions.next(s, e), "$s $e")

    private fun ack(s: AckStatus) = OutboxEvent.AckReceived(s)

    @Test fun legalEdges() {
        moved(OutboxState.CREATED, OutboxEvent.Queued, OutboxState.QUEUED)
        moved(OutboxState.QUEUED, OutboxEvent.SendStarted, OutboxState.SENDING)
        moved(OutboxState.RETRYABLE, OutboxEvent.SendStarted, OutboxState.SENDING)
        moved(OutboxState.PHONE_RECEIVED, OutboxEvent.SendStarted, OutboxState.SENDING)
        moved(OutboxState.SENDING, OutboxEvent.SendFailedTransient, OutboxState.RETRYABLE)
        for (s in listOf(OutboxState.QUEUED, OutboxState.SENDING, OutboxState.RETRYABLE)) {
            moved(s, OutboxEvent.SendFailedPermanent, OutboxState.FAILED)
        }
        moved(OutboxState.SENDING, ack(AckStatus.RECEIVED), OutboxState.PHONE_RECEIVED)
        moved(OutboxState.SENDING, ack(AckStatus.SAVED), OutboxState.ACKNOWLEDGED)
        moved(OutboxState.SENDING, ack(AckStatus.NEEDS_REVIEW), OutboxState.PROCESSED)
        moved(OutboxState.SENDING, ack(AckStatus.FAILED_RETRYABLE), OutboxState.RETRYABLE)
        moved(OutboxState.SENDING, ack(AckStatus.FAILED_FINAL), OutboxState.FAILED)
        moved(OutboxState.PHONE_RECEIVED, ack(AckStatus.SAVED), OutboxState.ACKNOWLEDGED)
        moved(OutboxState.PHONE_RECEIVED, ack(AckStatus.NEEDS_REVIEW), OutboxState.PROCESSED)
        moved(OutboxState.PHONE_RECEIVED, ack(AckStatus.FAILED_FINAL), OutboxState.FAILED)
        moved(OutboxState.PHONE_RECEIVED, ack(AckStatus.FAILED_RETRYABLE), OutboxState.RETRYABLE)
        moved(OutboxState.PROCESSED, ack(AckStatus.SAVED), OutboxState.ACKNOWLEDGED)
        moved(OutboxState.PROCESSED, ack(AckStatus.FAILED_FINAL), OutboxState.FAILED)
        moved(OutboxState.RETRYABLE, ack(AckStatus.SAVED), OutboxState.ACKNOWLEDGED)
        moved(OutboxState.RETRYABLE, ack(AckStatus.RECEIVED), OutboxState.PHONE_RECEIVED)
    }

    @Test fun retryableResumesSending() =
        moved(OutboxState.RETRYABLE, OutboxEvent.SendStarted, OutboxState.SENDING)

    @Test fun illegalEdgesRefused() {
        refused(OutboxState.QUEUED, OutboxEvent.Queued)
        refused(OutboxState.SENDING, OutboxEvent.Queued)
        refused(OutboxState.ACKNOWLEDGED, OutboxEvent.Queued)
        refused(OutboxState.CREATED, OutboxEvent.SendStarted)
        refused(OutboxState.SENDING, OutboxEvent.SendStarted)
        refused(OutboxState.ACKNOWLEDGED, OutboxEvent.SendStarted)
        refused(OutboxState.FAILED, OutboxEvent.SendStarted)
        refused(OutboxState.QUEUED, OutboxEvent.SendFailedTransient)
        refused(OutboxState.RETRYABLE, OutboxEvent.SendFailedTransient)
        refused(OutboxState.CREATED, OutboxEvent.SendFailedPermanent)
        refused(OutboxState.ACKNOWLEDGED, OutboxEvent.SendFailedPermanent)
        refused(OutboxState.CREATED, ack(AckStatus.SAVED))
        refused(OutboxState.QUEUED, ack(AckStatus.SAVED))
        val r = OutboxTransitions.next(OutboxState.CREATED, OutboxEvent.SendStarted) as TransitionResult.Refused
        assertEquals(OutboxState.CREATED, r.from)
        assertEquals(OutboxEvent.SendStarted, r.event)
    }

    @Test fun duplicateAckInTerminalStateIsIdempotent() {
        for (s in listOf(OutboxState.ACKNOWLEDGED, OutboxState.FAILED)) {
            for (a in AckStatus.entries) unchanged(s, ack(a))
        }
    }

    @Test fun lateOrRepeatedAckDoesNotRegress() {
        unchanged(OutboxState.PHONE_RECEIVED, ack(AckStatus.RECEIVED))
        unchanged(OutboxState.PROCESSED, ack(AckStatus.RECEIVED))
        unchanged(OutboxState.PROCESSED, ack(AckStatus.NEEDS_REVIEW))
        unchanged(OutboxState.RETRYABLE, ack(AckStatus.FAILED_RETRYABLE))
    }
}
