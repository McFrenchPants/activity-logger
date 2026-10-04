package com.mcfrenchpants.activityledger.wear

import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.StoredCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone's receiving end for watch captures. Pure Kotlin: no Android or Play Services types,
 * so it is fully testable on the JVM. [WatchCaptureListenerService] is the thin Android shell.
 *
 * ## Idempotent on the capture id
 *
 * The watch resends until it hears an ack, and the phone process may die at any point, so every
 * receive must be safe to repeat. The envelope's captureId is the raw capture's id: a resend
 * never creates a second raw capture or occurrence, and once an occurrence exists the
 * interpreter is never run again; the existing state is simply re-acked. A single [Mutex]
 * serialises all receives, so two concurrent deliveries of one id cannot both create or process.
 *
 * ## Zone
 *
 * The envelope carries no time zone, so the capture is stored with the phone's current zone
 * ([zone]). The watch and phone are normally in the same zone; the instant itself is exact.
 *
 * ## Privacy
 *
 * An undecodable, unsupported-version, blank or oversize envelope is dropped silently: nothing
 * is written and no ack is sent (its id cannot be trusted). Nothing here logs, and no exception
 * message or ack carries raw text (AGENTS.md #11).
 *
 * @param process runs the tag pipeline for one stored capture (production passes the tagged
 *   orchestrator's `process`). A confirm or review outcome is acked NEEDS_REVIEW: the question
 *   cannot be asked on the watch, the owner answers it on the phone (ADR-045).
 * @param sendAck delivers an encoded ack to [sendAck]'s `sourceNodeId`; failures are swallowed
 *   because the watch will resend and be re-acked.
 */
class WatchCaptureReceiver(
    private val repository: ActivityRepository,
    private val process: suspend (captureId: String) -> TaggedProcessingOutcome,
    private val sendAck: suspend (sourceNodeId: String, ackJson: String) -> Unit,
    private val zone: () -> ZoneId,
) {
    private val mutex = Mutex()

    /** Handles one delivered envelope from [sourceNodeId]. Rethrows cancellation, nothing else. */
    suspend fun receive(sourceNodeId: String, envelopeJson: String) {
        val envelope = when (val decoded = WireCodec.decodeEnvelope(envelopeJson)) {
            is ProtocolResult.Ok -> decoded.value
            is ProtocolResult.Failure -> return
        }
        mutex.withLock { handle(sourceNodeId, envelope) }
    }

    private suspend fun handle(node: String, envelope: CaptureEnvelope) {
        val id = envelope.captureId
        val existing = try {
            repository.getCapture(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ack(node, id, AckStatus.FAILED_RETRYABLE)
            return
        }

        if (existing == null) {
            try {
                repository.createRawCapture(newRawCapture(envelope))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ack(node, id, AckStatus.FAILED_RETRYABLE)
                return
            }
            ack(node, id, AckStatus.RECEIVED)
            runProcess(node, id)
            return
        }

        when {
            existing.hasOccurrence -> ack(node, id, AckStatus.SAVED)
            isUnprocessed(existing) -> runProcess(node, id)
            existing.processingState == ProcessingState.FAILED_FINAL -> ack(node, id, AckStatus.FAILED_FINAL)
            else -> ack(node, id, AckStatus.NEEDS_REVIEW, needsReview = true)
        }
    }

    private fun isUnprocessed(c: StoredCapture): Boolean =
        c.processingState == ProcessingState.CAPTURED || c.processingState == ProcessingState.FAILED_RETRYABLE

    private suspend fun runProcess(node: String, id: String) {
        val outcome = try {
            process(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ack(node, id, AckStatus.FAILED_RETRYABLE)
            return
        }
        when (outcome) {
            is TaggedProcessingOutcome.AutoSaved -> {
                var name: String? = null
                var occurredAt: Long? = null
                try {
                    val occurrence = repository.getOccurrence(outcome.occurrenceId)
                    if (occurrence != null) {
                        occurredAt = occurrence.occurredAt.toEpochMilli()
                        name = repository.getActivity(occurrence.canonicalActivityId)?.displayName
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Optional detail only; the SAVED status is what matters.
                }
                ack(node, id, AckStatus.SAVED, name, occurredAt)
            }
            TaggedProcessingOutcome.AlreadyHasOccurrence -> ack(node, id, AckStatus.SAVED)
            is TaggedProcessingOutcome.NeedsConfirm,
            is TaggedProcessingOutcome.NeedsReview,
            is TaggedProcessingOutcome.Rejected,
            // The raw capture is durably stored; the owner can review/retry on the phone, and
            // acking stops the watch resending forever.
            is TaggedProcessingOutcome.InterpreterUnavailable,
            -> ack(node, id, AckStatus.NEEDS_REVIEW, needsReview = true)
        }
    }

    private suspend fun ack(
        node: String,
        captureId: String,
        status: AckStatus,
        name: String? = null,
        occurredAtEpochMillis: Long? = null,
        needsReview: Boolean = false,
    ) {
        val json = when (
            val encoded = WireCodec.encode(
                CaptureAck(
                    captureId = captureId,
                    status = status,
                    canonicalActivityName = name,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    needsReview = needsReview,
                ),
            )
        ) {
            is ProtocolResult.Ok -> encoded.value
            is ProtocolResult.Failure -> return
        }
        try {
            sendAck(node, json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed: the watch resends and gets re-acked; the capture is already stored.
        }
    }

    private fun newRawCapture(e: CaptureEnvelope) = NewRawCapture(
        id = e.captureId,
        source = CaptureSource.WATCH_VOICE,
        sourceSurface = e.sourceSurface,
        capturedAt = Instant.ofEpochMilli(e.capturedAtEpochMillis),
        zoneId = zone(),
        rawText = e.rawText,
        speechConfidence = e.speechConfidence,
        speechAlternativesJson = e.alternatives?.let(::jsonArray),
        processingState = ProcessingState.CAPTURED,
    )

    internal companion object {
        /** Encodes [items] as a JSON array of strings (RFC 8259 escaping). */
        internal fun jsonArray(items: List<String>): String =
            items.joinToString(prefix = "[", postfix = "]", separator = ",") { s ->
                buildString {
                    append('"')
                    for (ch in s) {
                        when {
                            ch == '"' -> append("\\\"")
                            ch == '\\' -> append("\\\\")
                            ch == '\n' -> append("\\n")
                            ch == '\r' -> append("\\r")
                            ch == '\t' -> append("\\t")
                            ch < ' ' -> append("\\u%04x".format(ch.code))
                            else -> append(ch)
                        }
                    }
                    append('"')
                }
            }
    }
}
