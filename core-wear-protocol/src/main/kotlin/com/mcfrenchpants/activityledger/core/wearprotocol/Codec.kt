package com.mcfrenchpants.activityledger.core.wearprotocol

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Why encoding or decoding was refused. Payload-free by construction: a failure is only one
 * of these constants, so it cannot carry capture text (AGENTS.md #11).
 */
enum class ProtocolFailureKind { UNSUPPORTED_VERSION, MALFORMED, BLANK_TEXT, TEXT_TOO_LARGE, INVALID_ID }

/** Total result type: never thrown. [Failure] holds only a [ProtocolFailureKind]. */
sealed interface ProtocolResult<out T> {
    data class Ok<out T>(val value: T) : ProtocolResult<T>

    class Failure(val kind: ProtocolFailureKind) : ProtocolResult<Nothing> {
        override fun equals(other: Any?): Boolean = other is Failure && other.kind == kind
        override fun hashCode(): Int = kind.hashCode()
        override fun toString(): String = "Failure($kind)"
    }
}

/** Strict, total JSON codec for the phone/watch contract. No function here throws. */
object WireCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    fun encode(envelope: CaptureEnvelope): ProtocolResult<String> {
        validate(envelope)?.let { return ProtocolResult.Failure(it) }
        return try {
            ProtocolResult.Ok(json.encodeToString(CaptureEnvelope.serializer(), envelope))
        } catch (e: Exception) {
            ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        }
    }

    fun encode(ack: CaptureAck): ProtocolResult<String> {
        if (ack.protocolVersion != PROTOCOL_VERSION) {
            return ProtocolResult.Failure(ProtocolFailureKind.UNSUPPORTED_VERSION)
        }
        if (!isValidCaptureId(ack.captureId)) return ProtocolResult.Failure(ProtocolFailureKind.INVALID_ID)
        return try {
            ProtocolResult.Ok(json.encodeToString(CaptureAck.serializer(), ack))
        } catch (e: Exception) {
            ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        }
    }

    fun decodeEnvelope(text: String): ProtocolResult<CaptureEnvelope> {
        val obj = when (val parsed = parseVersionChecked(text)) {
            is ProtocolResult.Failure -> return parsed
            is ProtocolResult.Ok -> parsed.value
        }
        val value = try {
            json.decodeFromJsonElement(CaptureEnvelope.serializer(), obj)
        } catch (e: Exception) {
            return ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        }
        validate(value)?.let { return ProtocolResult.Failure(it) }
        return ProtocolResult.Ok(value)
    }

    fun decodeAck(text: String): ProtocolResult<CaptureAck> {
        val obj = when (val parsed = parseVersionChecked(text)) {
            is ProtocolResult.Failure -> return parsed
            is ProtocolResult.Ok -> parsed.value
        }
        val value = try {
            json.decodeFromJsonElement(CaptureAck.serializer(), obj)
        } catch (e: Exception) {
            return ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        }
        if (!isValidCaptureId(value.captureId)) return ProtocolResult.Failure(ProtocolFailureKind.INVALID_ID)
        return ProtocolResult.Ok(value)
    }

    /** Parses [text] to an object and checks protocolVersion before any other field. */
    private fun parseVersionChecked(text: String): ProtocolResult<JsonObject> {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        val versionPrimitive = obj["protocolVersion"] as? JsonPrimitive
            ?: return ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        val version = try {
            versionPrimitive.int
        } catch (e: Exception) {
            return ProtocolResult.Failure(ProtocolFailureKind.MALFORMED)
        }
        if (version != PROTOCOL_VERSION) return ProtocolResult.Failure(ProtocolFailureKind.UNSUPPORTED_VERSION)
        return ProtocolResult.Ok(obj)
    }

    private fun isValidCaptureId(id: String): Boolean =
        try {
            UUID.fromString(id).toString().equals(id, ignoreCase = true)
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun validate(e: CaptureEnvelope): ProtocolFailureKind? {
        if (e.protocolVersion != PROTOCOL_VERSION) return ProtocolFailureKind.UNSUPPORTED_VERSION
        if (!isValidCaptureId(e.captureId)) return ProtocolFailureKind.INVALID_ID
        if (e.source != SOURCE_WATCH_VOICE) return ProtocolFailureKind.MALFORMED
        if (e.rawText.isBlank()) return ProtocolFailureKind.BLANK_TEXT
        if (e.rawText.length > MAX_RAW_TEXT_LENGTH) return ProtocolFailureKind.TEXT_TOO_LARGE
        val alts = e.alternatives
        if (alts != null && (alts.size > MAX_ALTERNATIVES || alts.any { it.length > MAX_RAW_TEXT_LENGTH })) {
            return ProtocolFailureKind.TEXT_TOO_LARGE
        }
        return null
    }
}
