package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Version-drift guard.
 *
 * Prompt text is product logic (AI interpretation spec section 19), so it may not change by
 * accident. This pins the exact prompt for one fixed capture, and the system instruction,
 * against [PROMPT_VERSION].
 */
class InterpretationPromptDriftTest {

    private val fixedInput = InterpretationInput(
        rawText = "I cut the grass this morning",
        capturedAt = Instant.parse("2026-09-16T00:00:00Z"),
        zoneId = ZoneId.of("America/Detroit"),
        candidates = listOf(
            CandidateActivity("act-mow", "Mow lawn", listOf("cut the grass", "mowing")),
            CandidateActivity("act-edge", "Edge lawn", emptyList()),
        ),
    )

    private val bumpVersion =
        "Prompt text is product logic: it is versioned, and changing it is never a silent " +
            "edit. If this change is intended, bump PROMPT_VERSION in " +
            "InterpretationPrompt.kt, update the pinned checksum(s) in this test, and run " +
            "the semantic regression corpus."

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Test
    fun `prompt version is the one these fixtures were pinned against`() {
        assertEquals("3", PROMPT_VERSION, bumpVersion)
    }

    @Test
    fun `per-capture prompt text has not drifted`() {
        assertEquals(
            PINNED_PROMPT_SHA256,
            buildInterpretationPrompt(fixedInput).sha256(),
            "Prompt text changed for prompt version $PROMPT_VERSION. $bumpVersion",
        )
    }

    @Test
    fun `prompt text for a capture with no candidates has not drifted`() {
        assertEquals(
            PINNED_NO_CANDIDATE_PROMPT_SHA256,
            buildInterpretationPrompt(fixedInput.copy(candidates = emptyList())).sha256(),
            "Empty-candidate prompt text changed for prompt version $PROMPT_VERSION. $bumpVersion",
        )
    }

    @Test
    fun `system instruction text has not drifted`() {
        assertEquals(
            PINNED_SYSTEM_INSTRUCTION_SHA256,
            INTERPRETATION_SYSTEM_INSTRUCTION.sha256(),
            "System instruction changed for prompt version $PROMPT_VERSION. $bumpVersion",
        )
    }

    private companion object {
        /** SHA-256 of `buildInterpretationPrompt(fixedInput)` at prompt version 3. */
        const val PINNED_PROMPT_SHA256 =
            "dc5b1a6742166836f5bc58855b03ebdfe789b600872538f931bff868bc931a02"

        /**
         * SHA-256 of the same capture with no candidates at all, at prompt version 3. Pinned
         * separately because the empty-candidate branch has its own instruction text, which a
         * fixture built from a populated shortlist would never exercise.
         */
        const val PINNED_NO_CANDIDATE_PROMPT_SHA256 =
            "870773f600015b181ab4969ca3f969d8e5c26faf5d70f836ed3894d95a42755e"

        /** SHA-256 of [INTERPRETATION_SYSTEM_INSTRUCTION] at prompt version 3. */
        const val PINNED_SYSTEM_INSTRUCTION_SHA256 =
            "f666dd83a5c68feedcd1567e0e8c3171fe3f9ec24b30d77881c762f9655196c5"
    }
}
