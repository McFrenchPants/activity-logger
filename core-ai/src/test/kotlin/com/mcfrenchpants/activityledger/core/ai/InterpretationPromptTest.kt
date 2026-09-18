package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shape, content and purity of the per-capture prompt and the fixed system instruction. */
class InterpretationPromptTest {

    // region fixtures

    private val detroit = ZoneId.of("America/Detroit")

    /** 2026-09-15T20:00-04:00, i.e. 8pm local in America/Detroit. */
    private val capturedAt: Instant = Instant.parse("2026-09-16T00:00:00Z")

    private fun input(
        rawText: String = "I cut the grass",
        candidates: List<CandidateActivity> = listOf(
            CandidateActivity("act-mow", "Mow lawn", listOf("cut the grass", "mowing")),
            CandidateActivity("act-edge", "Edge lawn", emptyList()),
        ),
    ) = InterpretationInput(
        rawText = rawText,
        capturedAt = capturedAt,
        zoneId = detroit,
        candidates = candidates,
    )

    private val headings = listOf(
        "## Task",
        "## Rules",
        "## Current context",
        "## Candidate activities",
        "## User utterance",
    )

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun assertSectionsWellFormed(prompt: String) {
        val indices = headings.map { heading ->
            assertEquals(1, prompt.occurrencesOf(heading), "heading appears once: $heading")
            prompt.indexOf(heading)
        }
        assertEquals(indices.sorted(), indices, "headings appear in the required order")
    }

    private companion object {
        /** Distinctive opening of the instruction used only when candidates were offered. */
        const val COPY_AN_ID = "Copy matchedActivityId from exactly one of the ids listed above"

        /** Distinctive opening of the instruction used only when the list is empty. */
        const val GIVE_NO_ID = "Give no matchedActivityId at all"
    }

    // endregion

    // region determinism

    @Test
    fun `same input builds byte-identical prompts`() {
        val single = input()

        assertEquals(buildInterpretationPrompt(single), buildInterpretationPrompt(single))
    }

    @Test
    fun `separately constructed but equal inputs build identical prompts`() {
        assertEquals(buildInterpretationPrompt(input()), buildInterpretationPrompt(input()))
    }

    // endregion

    // region sections

    @Test
    fun `all five sections are present once and in order`() {
        assertSectionsWellFormed(buildInterpretationPrompt(input()))
    }

    @Test
    fun `no-candidate capture is still well formed and marks the list as empty`() {
        val prompt = buildInterpretationPrompt(input(candidates = emptyList()))

        assertSectionsWellFormed(prompt)
        val section = prompt.substringAfter("## Candidate activities").substringBefore("## User")
        assertTrue(section.contains("(none)"), "empty candidate list renders a none marker")
        assertTrue(
            section.contains("No existing activity is offered"),
            "the none marker explains itself",
        )
    }

    // endregion

    // region candidates

    @Test
    fun `every candidate renders id display name and aliases in the given order`() {
        val prompt = buildInterpretationPrompt(input())

        val mow = prompt.indexOf("- id: act-mow | name: Mow lawn | aliases: cut the grass, mowing")
        val edge = prompt.indexOf("- id: act-edge | name: Edge lawn | aliases: (none)")
        assertTrue(mow > 0, "candidate with aliases renders")
        assertTrue(edge > 0, "candidate without aliases renders a consistent none marker")
        assertTrue(mow < edge, "candidates keep the order they were given in")
    }

    @Test
    fun `candidates differing only in case both appear`() {
        val prompt = buildInterpretationPrompt(
            input(
                candidates = listOf(
                    CandidateActivity("act-a", "Mow lawn", emptyList()),
                    CandidateActivity("act-b", "mow Lawn", emptyList()),
                ),
            ),
        )

        assertTrue(prompt.contains("- id: act-a | name: Mow lawn | aliases: (none)"))
        assertTrue(prompt.contains("- id: act-b | name: mow Lawn | aliases: (none)"))
    }

    @Test
    fun `the candidate section forbids inventing ids`() {
        val prompt = buildInterpretationPrompt(input())

        assertTrue(prompt.contains("Copy matchedActivityId from exactly one of the ids listed above"))
        assertTrue(prompt.contains("Never invent an id"))
    }

    @Test
    fun `the id instruction matches whether or not candidates were offered`() {
        val withCandidates = buildInterpretationPrompt(input())
        val withoutCandidates = buildInterpretationPrompt(input(candidates = emptyList()))

        assertTrue(
            withCandidates.contains(COPY_AN_ID),
            "an offered list asks the model to copy one of the ids",
        )
        assertTrue(
            !withCandidates.contains(GIVE_NO_ID),
            "an offered list does not also tell the model to give no id",
        )
        assertTrue(
            withoutCandidates.contains(GIVE_NO_ID),
            "an empty list tells the model to give no id at all",
        )
        assertTrue(
            withoutCandidates.contains("NEW_ACTIVITY") && withoutCandidates.contains("UNRESOLVED"),
            "an empty list names the only two resolutions available",
        )
        assertTrue(
            !withoutCandidates.contains(COPY_AN_ID),
            "an empty list never asks the model to copy one of zero ids",
        )
    }

    // endregion

    // region utterance

    @Test
    fun `raw utterance appears exactly once and character-identical`() {
        val raw = "  mowed  the lawn, then edged it;\nfinished at the café  "

        val prompt = buildInterpretationPrompt(input(rawText = raw))

        assertTrue(prompt.contains(raw), "raw text survives unmodified")
        assertEquals(1, prompt.occurrencesOf(raw), "raw text appears exactly once")
    }

    // endregion

    // region current context

    @Test
    fun `current context renders local date-time and zone deterministically`() {
        val prompt = buildInterpretationPrompt(input())

        assertTrue(
            prompt.contains("Capture local date and time: 2026-09-15T20:00:00"),
            "local wall-clock time is rendered for the capture's zone",
        )
        assertTrue(prompt.contains("Time zone: America/Detroit"))
    }

    @Test
    fun `current context forbids the model computing a date itself`() {
        val prompt = buildInterpretationPrompt(input())

        assertTrue(prompt.contains("Do not compute a date yourself"))
    }

    // endregion

    // region few-shot examples

    @Test
    fun `few-shot example covers a synonym match`() {
        assertTrue(buildInterpretationPrompt(input()).contains("\"I cut the grass\" -> match Mow lawn"))
    }

    @Test
    fun `few-shot example covers near-neighbour rejection`() {
        assertTrue(
            buildInterpretationPrompt(input()).contains("\"edged the lawn\" is not Mow lawn"),
        )
    }

    @Test
    fun `few-shot example covers a new activity`() {
        assertTrue(
            buildInterpretationPrompt(input()).contains("new activity named \"Flush water heater\""),
        )
    }

    @Test
    fun `few-shot example covers an ambiguous utterance and its outcome`() {
        assertTrue(
            buildInterpretationPrompt(input()).contains(
                "- Ambiguous: \"Worked on the yard\", \"Did the thing by the furnace\" -> " +
                    "ambiguous. Do not force a confident match.",
            ),
            "the ambiguous example must still teach that the answer is ambiguous",
        )
    }

    @Test
    fun `few-shot example covers completed versus in progress`() {
        assertTrue(
            buildInterpretationPrompt(input()).contains("\"Just finished mowing\" is completed"),
        )
    }

    @Test
    fun `few-shot example covers a relative time phrase and its outcome`() {
        assertTrue(
            buildInterpretationPrompt(input()).contains(
                "- Relative time: \"Changed the furnace filter yesterday\" -> time wording " +
                    "\"yesterday\", copied verbatim, never converted to a date.",
            ),
            "the relative-time example must still teach verbatim copying, not date conversion",
        )
    }

    // endregion

    // region system instruction principles

    @Test
    fun `system instruction states all nine interpretation principles`() {
        val instruction = INTERPRETATION_SYSTEM_INSTRUCTION

        assertTrue(instruction.contains("logs real-world activities"), "1: real-world activity log")
        assertTrue(instruction.contains("Prefer an existing offered activity"), "2: prefer existing")
        assertTrue(instruction.contains("merely related"), "3: do not merge related")
        assertTrue(instruction.contains("concise and verb-first"), "4: naming rule")
        assertTrue(instruction.contains("mowing is not edging"), "5: preserve distinctions")
        assertTrue(instruction.contains("never fabricate a precise timestamp"), "6: temporal wording")
        assertTrue(instruction.contains("Return only the requested structure"), "7: structure only")
        assertTrue(instruction.contains("Never invent an id"), "8: no invented ids")
        assertTrue(
            instruction.contains("ambiguous or unresolved instead of guessing"),
            "9: refuse to guess",
        )
    }

    // endregion
}
