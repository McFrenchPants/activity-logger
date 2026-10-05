package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * The ML Kit Structured Output schema for ONE question extraction (prompt version q2, schema version 2, ADR-052).
 *
 * The wire shape the on-device model is constrained to produce; not a domain type, and it
 * carries no guarantees. It maps one-to-one onto core-domain's `QuestionCandidate`, and
 * [QuestionResponseDecoder] is the only thing allowed to make that crossing.
 *
 * ADR-023 containment: this class, and every ML Kit import in this repository, stays inside
 * `core-ai`. It is `public` because the schema compiler generates a public provider in this
 * package that references it (see [ExtractionResponse]); its members are plain `String?`s and no
 * hand-written API of core-ai accepts or returns one.
 *
 * The alpha schema compiler has no enum support (ADR-023), so the question kind travels as a
 * nullable String whose permitted spellings are pinned by `@Guide(enumValues = ...)`, exactly as
 * [ExtractionResponse] does for its enums. The decoder maps it onto core-domain's `QuestionKind`.
 *
 * Every `@Guide` description is written FOR THE MODEL. The explicit `@param:` target is required
 * for the same reason as in [ExtractionResponse].
 *
 * Bump `QUESTION_SCHEMA_VERSION` when this class's shape changes.
 */
@Generable(
    description = "The words pulled out of a single short question the user asked about their " +
        "own past activities.",
)
data class QuestionResponse(
    @param:Guide(
        description = "The thing the question is about, as a short noun phrase in the user's " +
            "own words, with no article or possessive, for example \"furnace filter\". Keep " +
            "its full name. Leave empty if the question names no thing.",
    )
    val subject: String?,
    @param:Guide(
        description = "What was done, as a short verb phrase with the verb in its plain " +
            "form, for example \"change oil\". Keep the words that tell it apart from " +
            "similar actions. Leave empty if the question says nothing that was done.",
    )
    val action: String?,
    @param:Guide(
        description = "The words of the question that say when, copied exactly as written, " +
            "for example \"in August\", \"last month\", \"this year\" or \"since June\". " +
            "Leave empty if the question says nothing about when. Never turn them into a date.",
    )
    val dateWindow: String?,
    @param:Guide(
        description = "What the question asks for. Use LAST_TIME when it asks when something " +
            "was last done or whether it was ever done, COUNT when it asks how many times, " +
            "HOW_OFTEN when it asks how often or how regularly, LIST when it asks what was " +
            "done. Leave empty if unclear.",
        enumValues = ["LAST_TIME", "COUNT", "HOW_OFTEN", "LIST"],
    )
    val kind: String?,
)
