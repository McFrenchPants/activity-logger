package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * The ML Kit Structured Output schema for ONE extraction result (prompt version 4, ADR-038).
 *
 * This is the wire shape the on-device model is constrained to produce; it is not a domain type
 * and carries no guarantees. It maps one-to-one onto core-domain's `ExtractionCandidate`, and
 * [ExtractionResponseDecoder] is the only thing allowed to make that crossing.
 *
 * There is deliberately no confidence field: the model's self-reported confidence is no longer
 * used for any decision, and every extra field costs on-device latency.
 *
 * ADR-023 containment: this class, and every ML Kit import in this repository, stays inside
 * `core-ai`. It is `public` and not `internal`, which is forced, not preferred: the schema
 * compiler generates a `public class <Name>_GeneratedProvider` in this same package whose
 * members reference the annotated type, so an `internal` @Generable class fails to compile. Its
 * members are therefore deliberately plain Kotlin `String?`s, and no hand-written API of core-ai
 * accepts or returns one, so the ML Kit surface still ends at this module's boundary.
 *
 * Field types are limited to what ADR-023 records as supported by the alpha schema compiler.
 * There is no enum support, so every domain enum travels as a String whose permitted spellings
 * are pinned by `@Guide(enumValues = ...)`, and optional values travel as a nullable String.
 *
 * Every `@Guide` description is written FOR THE MODEL, not for a developer: short, imperative,
 * and phrased in terms of the user's sentence.
 *
 * The explicit `@param:` target is required: Kotlin 2.3 warns that a bare constructor-parameter
 * annotation will also apply to the backing field in a future release, and the schema compiler
 * reads it from the value parameter.
 *
 * Bump [EXTRACTION_SCHEMA_VERSION] when this class's shape or permitted spellings change.
 */
@Generable(
    description = "The words pulled out of a single short sentence the user spoke or typed " +
        "about an activity.",
)
data class ExtractionResponse(
    @param:Guide(
        description = "What the sentence asks for. Use LOG_ACTIVITY when it reports " +
            "something the user did or is doing, QUERY_HISTORY when it asks about past " +
            "activity, UNSUPPORTED for anything else.",
        enumValues = ["LOG_ACTIVITY", "QUERY_HISTORY", "UNSUPPORTED"],
    )
    val operation: String?,
    @param:Guide(
        description = "The thing acted on, as a short noun phrase in the user's own words, " +
            "with no article or possessive, for example \"coffee maker\". Keep its full name. " +
            "Leave empty if the sentence names no thing.",
    )
    val subject: String?,
    @param:Guide(
        description = "What was done, as a short verb phrase with the verb in its plain " +
            "form, for example \"replace bulb\". Keep the words that tell it apart from " +
            "similar actions. Leave empty if the sentence says nothing that was done.",
    )
    val action: String?,
    @param:Guide(
        description = "Whether the sentence says the activity is finished or still " +
            "underway. Leave empty if the sentence does not say.",
        enumValues = ["COMPLETED", "IN_PROGRESS"],
    )
    val activityState: String?,
    @param:Guide(
        description = "The words the sentence uses for WHEN it happened, copied verbatim, " +
            "for example \"yesterday morning\". Never a duration. Never compute or write a " +
            "date, a time or a timestamp. Leave empty if the sentence says nothing about when.",
    )
    val temporalExpression: String?,
    @param:Guide(
        description = "The words the sentence uses for HOW LONG it took, copied verbatim, " +
            "for example \"for 45 minutes\". Leave empty if the sentence gives no duration.",
    )
    val durationExpression: String?,
)
