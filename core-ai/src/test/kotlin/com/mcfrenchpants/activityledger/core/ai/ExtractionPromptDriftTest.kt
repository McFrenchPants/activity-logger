package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Version-drift guard for the extraction prompt.
 *
 * Prompt text is product logic (AI interpretation spec section 19), so it may not change by
 * accident. This pins the exact prompt for one fixed capture, and the system instruction,
 * against [EXTRACTION_PROMPT_VERSION].
 */
class ExtractionPromptDriftTest {

    private val fixedInput = ExtractionInput(
        rawText = "Oiled the gate hinges this morning",
        capturedAt = Instant.parse("2026-09-16T00:00:00Z"),
        zoneId = ZoneId.of("America/Detroit"),
    )

    private val bumpVersion =
        "Prompt text is product logic: it is versioned, and changing it is never a silent " +
            "edit. If this change is intended, bump EXTRACTION_PROMPT_VERSION in " +
            "ExtractionPrompt.kt, update the pinned checksum(s) in this test, and re-record " +
            "the tag corpus."

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Test
    fun `extraction prompt version is the one these fixtures were pinned against`() {
        assertEquals("5", EXTRACTION_PROMPT_VERSION, bumpVersion)
    }

    @Test
    fun `per-capture extraction prompt text has not drifted`() {
        assertEquals(
            PINNED_PROMPT_SHA256,
            buildExtractionPrompt(fixedInput).sha256(),
            "Extraction prompt text changed for prompt version $EXTRACTION_PROMPT_VERSION. $bumpVersion",
        )
    }

    @Test
    fun `extraction system instruction text has not drifted`() {
        assertEquals(
            PINNED_SYSTEM_INSTRUCTION_SHA256,
            EXTRACTION_SYSTEM_INSTRUCTION.sha256(),
            "Extraction system instruction changed for prompt version $EXTRACTION_PROMPT_VERSION. $bumpVersion",
        )
    }

    private companion object {
        /** SHA-256 of `buildExtractionPrompt(fixedInput)` at extraction prompt version 5. */
        const val PINNED_PROMPT_SHA256 =
            "013b71e6be86a85b13126f8e629580f39363edb9fb91a4bb0139409d78f9830b"

        /** SHA-256 of [EXTRACTION_SYSTEM_INSTRUCTION] at extraction prompt version 5. */
        const val PINNED_SYSTEM_INSTRUCTION_SHA256 =
            "6fc64ba386308fea417cb98d03159a18600897441ddee64e4067851bba931afe"
    }
}
