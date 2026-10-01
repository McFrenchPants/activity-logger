package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Identifier for the exact EXTRACTION prompt text below, recorded as provenance with every
 * extraction (ADR-038).
 *
 * Separate from [PROMPT_VERSION] on purpose: the extraction prompt is a different prompt for a
 * different task, built beside the v3 interpretation prompt, and the two must be able to move
 * independently. It continues the numbering ("4") so a recording's `promptVersion` alone says
 * which generation of the AI step produced it.
 *
 * Prompt text is product logic (AI interpretation spec section 19): ANY material change to
 * [EXTRACTION_SYSTEM_INSTRUCTION] or to [buildExtractionPrompt]'s output must bump this value,
 * update the drift-guard fixture in `ExtractionPromptDriftTest`, and re-record the tag corpus.
 */
internal const val EXTRACTION_PROMPT_VERSION: String = "4"

/**
 * The fixed system instruction the on-device model is given for every extraction.
 *
 * Terse and numbered, like [INTERPRETATION_SYSTEM_INSTRUCTION], because Gemini Nano follows
 * short, strongly structured instructions far better than prose. Field-level wording lives in
 * [ExtractionResponse]'s `@Guide` descriptions; the per-capture prompt carries the worked
 * examples.
 */
internal const val EXTRACTION_SYSTEM_INSTRUCTION: String =
    """You pull the words out of short sentences in which the user logs real-world activities they did or are doing.

1. Extract only. You are shown no list of activities or tags: never match, invent or rename one.
2. Subject: the thing acted on, a short noun phrase in the user's own words, without articles, possessives or filler. Keep its full name.
3. Action: what was done, a short verb phrase with the verb in its plain form, keeping the words that tell it apart from similar actions.
4. Leave the subject or the action empty when the sentence does not name it; never guess one.
5. Copy the user's time wording verbatim; never compute or write a date, a time or a timestamp.
6. A duration (how long it took) is duration wording, never time wording.
7. Read past obvious speech-recognition slips, but never add anything the sentence does not say.
8. Return only the requested structure: no prose, no explanation, no extra fields."""

/** Deterministic, locale-independent rendering of the capture's local wall-clock time. */
private val EXTRACTION_LOCAL_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

/**
 * Builds the per-capture extraction prompt.
 *
 * Pure: it reads nothing but [input]. No clock, no default zone, no default locale, no
 * randomness, no I/O and no logging (AGENTS.md #11 -- prompt text and capture text are never
 * logged). The same input therefore always yields byte-identical output.
 *
 * There is deliberately no candidate, activity or tag section: the model extracts the user's
 * own words and deterministic domain logic resolves them later (ADR-038).
 *
 * The worked examples use sentences, subjects and actions that do not occur in the tag corpus
 * or corpus.json, so that measuring the prompt against those corpora is not measuring the
 * examples back (`ExtractionPromptTest` asserts it).
 */
internal fun buildExtractionPrompt(input: ExtractionInput): String {
    val localDateTime = LocalDateTime.ofInstant(input.capturedAt, input.zoneId)
    val renderedLocalDateTime = EXTRACTION_LOCAL_DATE_TIME_FORMAT.format(localDateTime)

    return buildString {
        append("## Task\n")
        append(
            "Extract the words of the one user utterance at the end of this prompt: what it " +
                "asks for, what was done, what it was done to, when, for how long, and whether " +
                "it is finished.\n",
        )
        append(
            "You are not shown any list of activities or tags, and you must not try to match " +
                "one. Fill in the requested structure only.\n",
        )
        append('\n')

        append("## Rules\n")
        append(
            "- Subject is the thing acted on: a short noun phrase in the user's own words, " +
                "with no article, no possessive and no filler (\"the deck\" -> \"deck\"; " +
                "\"my kids' bikes\" -> \"bikes\"). Keep the full name: \"coffee maker\" is not " +
                "\"coffee\", \"porch light\" is not \"light\".\n",
        )
        append(
            "- Action is what was done: a short verb phrase with the verb in its plain form " +
                "(\"vacuuming\" -> \"vacuum\", \"descaled\" -> \"descale\"). Keep the object " +
                "words that tell it apart from similar actions: \"replace bulb\" and \"replace " +
                "fuse\" are different actions. The subject is not repeated in the action.\n",
        )
        append(
            "- Leave the subject empty when the sentence names no thing acted on, and the " +
                "action empty when it says nothing that was done. Never guess either one.\n",
        )
        append(
            "- State: COMPLETED when the sentence says it is done (a past-tense report " +
                "counts); IN_PROGRESS when it says it is happening now; otherwise leave it " +
                "empty.\n",
        )
        append(
            "- Time wording is ONLY the words saying WHEN it happened (yesterday, this " +
                "afternoon, just, at 3pm, last Sunday). Copy them verbatim; never write a " +
                "date, a time or a timestamp.\n",
        )
        append(
            "- Duration wording is ONLY the words saying HOW LONG it took (\"for 45 " +
                "minutes\", \"for half an hour\", \"40 minutes\"). Copy them verbatim. A " +
                "duration is NEVER time wording.\n",
        )
        append(
            "- Operation: LOG_ACTIVITY when the user reports something they did or are doing; " +
                "QUERY_HISTORY when they ask about past activity; UNSUPPORTED for anything " +
                "else.\n",
        )
        append(
            "- The sentence may come from speech recognition. Read past an obvious misheard " +
                "word, but never add anything the sentence does not say.\n",
        )
        append('\n')

        append("### Worked examples\n")
        append(
            "- \"Pumped up the tires on the kids' bikes this afternoon.\" -> subject \"bikes\", " +
                "action \"pump tires\", COMPLETED, time wording \"this afternoon\", no duration.\n",
        )
        append(
            "- \"Replaced the bulb in the porch light.\" -> subject \"porch light\", action " +
                "\"replace bulb\", COMPLETED, no time wording.\n",
        )
        append(
            "- \"Descaled the coffee maker yesterday.\" -> subject \"coffee maker\", action " +
                "\"descale\", COMPLETED, time wording \"yesterday\", copied, never a date.\n",
        )
        append(
            "- \"I'm sealing the deck.\" -> subject \"deck\", action \"seal\", IN_PROGRESS, " +
                "no time wording.\n",
        )
        append(
            "- \"Just finished vacuuming.\" -> no subject, action \"vacuum\", COMPLETED, " +
                "time wording \"just\".\n",
        )
        append(
            "- Duration is not time: \"Practiced piano for 45 minutes this morning.\" -> " +
                "subject \"piano\", action \"practice\", time wording \"this morning\", " +
                "duration wording \"for 45 minutes\". \"Practiced piano for 45 minutes.\" has " +
                "the same duration and NO time wording.\n",
        )
        append(
            "- Speech slip: \"Eye polished the silverware.\" means \"I polished the " +
                "silverware.\" -> subject \"silverware\", action \"polish\", COMPLETED.\n",
        )
        append(
            "- Question: \"When did I last service the boiler?\" -> QUERY_HISTORY, subject " +
                "\"boiler\", action \"service\".\n",
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

        append("## User utterance\n")
        append("<<<UTTERANCE\n")
        append(input.rawText)
        append("\nUTTERANCE>>>\n")
    }
}
