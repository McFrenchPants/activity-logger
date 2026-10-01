package com.mcfrenchpants.activityledger.core.wearprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireCodecTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    private fun env(
        text: String = "I changed the furnace filter",
        captureId: String = id,
        version: Int = PROTOCOL_VERSION,
    ) = CaptureEnvelope(
        protocolVersion = version,
        captureId = captureId,
        rawText = text,
        capturedAtEpochMillis = 1_700_000_000_000,
        sourceSurface = "COMPLICATION",
        speechConfidence = 0.9,
        alternatives = listOf("I changed the furnace filters"),
    )

    private fun kind(r: ProtocolResult<*>) = (r as ProtocolResult.Failure).kind

    private fun raw(text: String, captureId: String = id) =
        "{\"protocolVersion\":1,\"captureId\":\"$captureId\",\"rawText\":\"$text\"," +
            "\"capturedAtEpochMillis\":1,\"source\":\"WATCH_VOICE\",\"sourceSurface\":\"APP\"}"

    @Test fun sourceMatchesWatchVoiceName() = assertEquals("WATCH_VOICE", SOURCE_WATCH_VOICE)

    @Test fun paths() {
        assertEquals("/capture/$id", capturePath(id))
        assertEquals("/capture-ack", CAPTURE_ACK_PATH)
    }

    @Test fun envelopeRoundTrip() {
        val e = env()
        val text = (WireCodec.encode(e) as ProtocolResult.Ok).value
        assertEquals(e, (WireCodec.decodeEnvelope(text) as ProtocolResult.Ok).value)
    }

    @Test fun envelopeRoundTripWithoutOptionals() {
        val e = env().copy(speechConfidence = null, alternatives = null)
        val text = (WireCodec.encode(e) as ProtocolResult.Ok).value
        assertFalse(text.contains("speechConfidence"))
        assertEquals(e, (WireCodec.decodeEnvelope(text) as ProtocolResult.Ok).value)
    }

    @Test fun ackRoundTrip() {
        for (s in AckStatus.entries) {
            val a = CaptureAck(
                captureId = id,
                status = s,
                canonicalActivityName = "Furnace filter",
                occurredAtEpochMillis = 5L,
                needsReview = true,
            )
            val text = (WireCodec.encode(a) as ProtocolResult.Ok).value
            assertEquals(a, (WireCodec.decodeAck(text) as ProtocolResult.Ok).value)
        }
        val bare = CaptureAck(captureId = id, status = AckStatus.RECEIVED)
        val t = (WireCodec.encode(bare) as ProtocolResult.Ok).value
        assertEquals(bare, (WireCodec.decodeAck(t) as ProtocolResult.Ok).value)
    }

    @Test fun rejectsUnsupportedVersion() {
        assertEquals(ProtocolFailureKind.UNSUPPORTED_VERSION, kind(WireCodec.encode(env(version = 2))))
        val text = raw("x").replace("\"protocolVersion\":1", "\"protocolVersion\":2")
        assertEquals(ProtocolFailureKind.UNSUPPORTED_VERSION, kind(WireCodec.decodeEnvelope(text)))
        val ack = "{\"protocolVersion\":9,\"captureId\":\"$id\",\"status\":\"SAVED\"}"
        assertEquals(ProtocolFailureKind.UNSUPPORTED_VERSION, kind(WireCodec.decodeAck(ack)))
    }

    @Test fun rejectsBlankText() {
        assertEquals(ProtocolFailureKind.BLANK_TEXT, kind(WireCodec.encode(env(text = "  \n"))))
        assertEquals(ProtocolFailureKind.BLANK_TEXT, kind(WireCodec.decodeEnvelope(raw("   "))))
    }

    @Test fun rejectsOversizeText() {
        val big = "a".repeat(MAX_RAW_TEXT_LENGTH + 1)
        assertEquals(ProtocolFailureKind.TEXT_TOO_LARGE, kind(WireCodec.encode(env(text = big))))
        assertEquals(ProtocolFailureKind.TEXT_TOO_LARGE, kind(WireCodec.decodeEnvelope(raw(big))))
        assertTrue(WireCodec.encode(env(text = "a".repeat(MAX_RAW_TEXT_LENGTH))) is ProtocolResult.Ok)
    }

    @Test fun rejectsMalformedJson() {
        val bads = listOf(
            "",
            "not json",
            "[]",
            "{",
            "{}",
            "{\"protocolVersion\":\"x\"}",
            "{\"protocolVersion\":1,\"captureId\":\"$id\"}",
        )
        for (bad in bads) {
            assertEquals(ProtocolFailureKind.MALFORMED, kind(WireCodec.decodeEnvelope(bad)), bad)
        }
        val badStatus = "{\"protocolVersion\":1,\"captureId\":\"$id\",\"status\":\"NOPE\"}"
        assertEquals(ProtocolFailureKind.MALFORMED, kind(WireCodec.decodeAck(badStatus)))
    }

    @Test fun rejectsInvalidCaptureId() {
        assertEquals(ProtocolFailureKind.INVALID_ID, kind(WireCodec.encode(env(captureId = "nope"))))
        assertEquals(ProtocolFailureKind.INVALID_ID, kind(WireCodec.decodeEnvelope(raw("x", "abc"))))
        val ack = "{\"protocolVersion\":1,\"captureId\":\"abc\",\"status\":\"SAVED\"}"
        assertEquals(ProtocolFailureKind.INVALID_ID, kind(WireCodec.decodeAck(ack)))
    }

    @Test fun failureCannotHoldPayload() {
        val f = WireCodec.encode(env(text = " ")) as ProtocolResult.Failure
        assertEquals("Failure(BLANK_TEXT)", f.toString())
    }
}
