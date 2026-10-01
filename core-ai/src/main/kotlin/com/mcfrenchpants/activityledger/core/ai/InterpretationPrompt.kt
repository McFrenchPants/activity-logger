package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Identifier for the exact prompt text below, recorded as provenance with every
 * interpretation.
 *
 * Prompt text is product logic (AI interpretation spec section 19): ANY material change to
 * [INTERPRETATION_SYSTEM_INSTRUCTION] or to [buildInterpretationPrompt]'s output must bump
 * this value, update the drift-guard fixture in `InterpretationPromptDriftTest`, and re-run
 * the semantic regression corpus.
 */
internal const val PROMPT_VERSION: String = "3"

/**
 * The fixed system instruction the on-device model is given for every capture.
 *
 * Deliberately terse and numbered: Gemini Nano follows short, strongly structured,
 * example-driven instructions far better than prose. Field-level wording lives in
 * [InterpretationResponse]'s `@Guide` descriptions, which the model already receives with
 * the schema; this instruction states the nine interpretation principles and does not
 * restate the field docs.
 */
internal const val INTERPRETATION_SYSTEM_INSTRUCTION: String =
    """You interpret short sentences in which the user logs real-world activities they did or are doing.

1. Prefer an existing offered activity whenever it means the same thing as the sentence.
2. Never merge activities that are merely related; a false merge corrupts the user's history.
3. Propose a new canonical name only when no offered activity is equivalent; make it concise and verb-first.
4. Preserve real distinctions: mowing is not edging.
5. Extract the user's own time wording; never fabricate a precise timestamp.
6. Return only the requested structure: no prose, no explanation, no extra fields.
7. Never invent an id; copy an offered id exactly or leave it empty.
8. When the meaning is not clear enough, answer ambiguous or unresolved instead of guessing."""

/** Deterministic, locale-independent rendering of the capture's local wall-clock time. */
private val LOCAL_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

/** Marker used when a section would otherwise be empty. */
private const val NONE_MARKER = "(none)"

/**
 * Builds the per-capture prompt.
 *
 * Pure: it reads nothing but [input]. No clock, no default zone, no default locale, no
 * randomness, no I/O and no logging (AGENTS.md #11 — prompt text and capture text are never
 * logged). The same input therefore always yields byte-identical output.
 *
 * Candidates are rendered exactly as given, in the order given: shortlisting and ranking are
 * core-domain's job, not this module's.
 */
internal fun buildInterpretationPrompt(input: InterpretationInput): String {
    val localDateTime = LocalDateTime.ofInstant(input.capturedAt, input.zoneId)
    val renderedLocalDateTime = LOCAL_DATE_TIME_FORMAT.format(localDateTime)

    return buildString {
        append("## Task\n")
        append(
            "Interpret the one user utterance at the end of this prompt: decide what it asks " +
                "for, which activity it refers to, and how it describes the time.\n",
        )
        append("Fill in the requested structure only.\n")
        append('\n')

        append("## Rules\n")
        append(
            "- Match an offered candidate activity only when the meaning is genuinely the " +
                "same; otherwise do not match it.\n",
        )
        append(
            "- Related is not the same. Keep distinct activities distinct even when they " +
                "share a place, a tool or a chore session.\n",
        )
        append(
            "- Name a new activity only when no offered activity is equivalent. A name is " +
                "concise, verb-first, one single concept, with no date, no time, no " +
                "completion status and no filler.\n",
        )
        append("  GOOD: \"Flush water heater\", \"Clean dryer vent\".\n")
        append("  BAD: \"I flushed the water heater today\", \"Water heater stuff\".\n")
        append(
            "- Copy the user's time wording; never write a date, a time or a timestamp. Time " +
                "wording is ONLY words saying WHEN the activity happened (yesterday, this " +
                "morning, at 3pm, just now, last Tuesday). A duration or amount (\"for 30 " +
                "minutes\", \"about 40 minutes\") is NOT time wording: leave the time wording " +
                "empty for it.\n",
        )
        append('\n')
        append("### Worked examples\n")
        append("- Synonym: \"I cut the grass\" -> match Mow lawn. So does \"mowed\".\n")
        append(
            "- Synonym: \"put a new HVAC filter in\" -> match Replace furnace filter.\n",
        )
        append(
            "- Near neighbour, do NOT match: \"edged the lawn\" is not Mow lawn; \"raked " +
                "leaves\" is not Blow leaves; \"washed the car\" is not Wax car.\n",
        )
        append(
            "- New activity: \"Flushed the water heater\" with no equivalent candidate -> " +
                "new activity named \"Flush water heater\".\n",
        )
        append(
            "- Ambiguous: \"Worked on the yard\", \"Did the thing by the furnace\" -> " +
                "ambiguous. Do not force a confident match.\n",
        )
        append(
            "- Completed vs in progress: \"Just finished mowing\" is completed; \"I'm " +
                "mowing now\" is in progress; say nothing when the sentence does not say. " +
                "\"Just finished mowing\" has time wording \"just now\".\n",
        )
        append(
            "- Duration is not time: \"I just walked the dogs for about 30 minutes\" -> time " +
                "wording \"just now\"; the duration \"for about 30 minutes\" is ignored.\n",
        )
        append(
            "- Relative time: \"Changed the furnace filter yesterday\" -> time wording " +
                "\"yesterday\", copied verbatim, never converted to a date.\n",
        )
        append('\n')

        append("## Current context\n")
        append("Capture local date and time: $renderedLocalDateTime\n")
        append("Time zone: ${input.zoneId.id}\n")
        append(
            "This is here only so you can understand relative wording such as \"yesterday\" " +
                "or \"this morning\". Do not compute a date yourself and do not put a date, " +
                "time or timestamp in your answer.\n",
        )
        append('\n')

        append("## Candidate activities\n")
        if (input.candidates.isEmpty()) {
            append("$NONE_MARKER No existing activity is offered for this capture.\n")
            append(
                "Give no matchedActivityId at all: leave it empty, because there is no id to " +
                    "copy and you must never invent one. Answer NEW_ACTIVITY when the " +
                    "sentence names an activity clearly enough, otherwise UNRESOLVED; never " +
                    "report a matched activity.\n",
            )
        } else {
            input.candidates.forEach { candidate ->
                val aliases =
                    if (candidate.aliases.isEmpty()) {
                        NONE_MARKER
                    } else {
                        candidate.aliases.joinToString(separator = ", ")
                    }
                append("- id: ${candidate.id} | name: ${candidate.displayName} | aliases: $aliases\n")
            }
            append(
                "Copy matchedActivityId from exactly one of the ids listed above, character " +
                    "for character. Never invent an id and never name an activity that is not " +
                    "in this list.\n",
            )
        }
        append('\n')

        append("## User utterance\n")
        append("<<<UTTERANCE\n")
        append(input.rawText)
        append("\nUTTERANCE>>>\n")
    }
}
