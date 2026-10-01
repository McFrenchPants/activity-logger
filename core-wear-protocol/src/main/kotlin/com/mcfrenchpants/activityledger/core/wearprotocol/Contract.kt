package com.mcfrenchpants.activityledger.core.wearprotocol

import kotlinx.serialization.Serializable

/** Current wire protocol version. Bump on any incompatible change. */
const val PROTOCOL_VERSION: Int = 1

/**
 * Value of [CaptureEnvelope.source] for watch captures. Must equal the name of core-domain's
 * `CaptureSource.WATCH_VOICE`; this module cannot depend on core-domain, so a phone-side
 * module that maps it should pin the equality with a test.
 */
const val SOURCE_WATCH_VOICE: String = "WATCH_VOICE"

/** Data Layer DataItem path prefix; one item per capture at `/capture/<captureId>`. */
const val CAPTURE_PATH_PREFIX: String = "/capture"

/** [CAPTURE_PATH_PREFIX] plus a trailing slash, for manifest/path-prefix matching of `/capture/<id>`. */
const val CAPTURE_PATH_PREFIX_WITH_SLASH: String = "/capture/"

/** DataItem key holding the WireCodec-encoded [CaptureEnvelope] JSON (a String). */
const val CAPTURE_DATA_KEY: String = "envelope"

/**
 * DataItem key holding a Long the watch changes on every (re)send. The Data Layer only fires
 * `onDataChanged` on the phone when an item's content actually changes, so an identical resend
 * would be silently dropped; a changing attempt value forces delivery. The phone ignores the value.
 */
const val CAPTURE_ATTEMPT_KEY: String = "attempt"

/** Data Layer message path the phone uses to send a [CaptureAck] to the watch. */
const val CAPTURE_ACK_PATH: String = "/capture-ack"

/**
 * Maximum length of rawText, in UTF-16 chars. A spoken note is a sentence or two; 4096 is far
 * above any realistic dictation yet keeps one DataItem well under the Data Layer size limit.
 * Also applied to each entry of [CaptureEnvelope.alternatives], which is capped at
 * [MAX_ALTERNATIVES] entries.
 */
const val MAX_RAW_TEXT_LENGTH: Int = 4096

/** Maximum number of speech-recognition alternatives carried in one envelope. */
const val MAX_ALTERNATIVES: Int = 5

/** DataItem path for [captureId]: `/capture/<captureId>`. */
fun capturePath(captureId: String): String = "$CAPTURE_PATH_PREFIX/$captureId"

/** One raw capture sent from the watch to the phone. */
@Serializable
data class CaptureEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val captureId: String,
    val rawText: String,
    val capturedAtEpochMillis: Long,
    val source: String = SOURCE_WATCH_VOICE,
    val sourceSurface: String,
    val speechConfidence: Double? = null,
    val alternatives: List<String>? = null,
)

/** Phone-to-watch outcome status for a capture. */
@Serializable
enum class AckStatus { RECEIVED, SAVED, NEEDS_REVIEW, FAILED_RETRYABLE, FAILED_FINAL }

/** Phone acknowledgement of one capture. Carries no raw text. */
@Serializable
data class CaptureAck(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val captureId: String,
    val status: AckStatus,
    val canonicalActivityName: String? = null,
    val occurredAtEpochMillis: Long? = null,
    val needsReview: Boolean = false,
)
