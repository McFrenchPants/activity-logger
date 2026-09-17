package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * The ML Kit Structured Output schema for ONE interpretation result.
 *
 * This is the wire shape the on-device model is constrained to produce; it is not a
 * domain type and carries no guarantees. It maps one-to-one onto core-domain's
 * `InterpretationCandidate`, and [InterpretationResponseDecoder] is the only thing
 * allowed to make that crossing.
 *
 * ADR-023 containment: this class, and every ML Kit import in this repository, stays
 * inside `core-ai`. It is `public` and not `internal`, which is forced, not preferred:
 * the schema compiler generates a `public class <Name>_GeneratedProvider` in this same
 * package whose members reference the annotated type, so an `internal` @Generable class
 * fails to compile ("'public' property exposes its 'internal' type argument"). Its
 * members are therefore deliberately plain Kotlin `String?`s, and no hand-written API
 * of core-ai accepts or returns one, so the ML Kit surface still ends at this module's
 * boundary.
 *
 * Field types are limited to what ADR-023 records as supported by the alpha schema
 * compiler: String, Double, Float, Int, Long, Boolean, List<T> and nested @Generable
 * classes. There is no enum support, so every domain enum travels as a String whose
 * permitted spellings are pinned by `@Guide(enumValues = ...)`, and optional values
 * travel as a nullable String.
 *
 * Every `@Guide` description is written FOR THE MODEL, not for a developer: short,
 * imperative, and phrased in terms of the user's sentence.
 *
 * The explicit `@param:` target is required: Kotlin 2.3 warns that a bare
 * constructor-parameter annotation will also apply to the backing field in a future
 * release, and the schema compiler reads it from the value parameter.
 */
@Generable(
    description = "One interpretation of a single short sentence the user spoke or typed " +
        "about an activity.",
)
data class InterpretationResponse(
    @param:Guide(
        description = "What the sentence asks for. Use LOG_ACTIVITY when it reports " +
            "something the user did or is doing, QUERY_HISTORY when it asks about past " +
            "activity, UNSUPPORTED for anything else.",
        enumValues = ["LOG_ACTIVITY", "QUERY_HISTORY", "UNSUPPORTED"],
    )
    val operation: String?,
    @param:Guide(
        description = "How the activity in the sentence relates to the activities offered " +
            "in this prompt. Use EXISTING_ACTIVITY when exactly one offered activity fits, " +
            "NEW_ACTIVITY when none fits, AMBIGUOUS when several fit equally, UNRESOLVED " +
            "when the sentence names no activity at all.",
        enumValues = ["EXISTING_ACTIVITY", "NEW_ACTIVITY", "AMBIGUOUS", "UNRESOLVED"],
    )
    val activityResolution: String?,
    @param:Guide(
        description = "The id of the activity you matched. It must be copied exactly from " +
            "one of the ids offered in this prompt. Never invent an id and never alter " +
            "one. Leave empty if you matched no offered activity.",
    )
    val matchedActivityId: String?,
    @param:Guide(
        description = "A name for a brand-new activity, only when no offered activity fits. " +
            "Keep it short and start with a verb, for example \"mow lawn\". Leave empty " +
            "otherwise.",
    )
    val proposedCanonicalName: String?,
    @param:Guide(
        description = "Whether the sentence says the activity is finished or still " +
            "underway. Leave empty if the sentence does not say.",
        enumValues = ["COMPLETED", "IN_PROGRESS"],
    )
    val activityState: String?,
    @param:Guide(
        description = "The words the sentence uses for when it happened, copied verbatim, " +
            "for example \"yesterday morning\". Never compute or write a date, a time or a " +
            "timestamp. Leave empty if the sentence says nothing about time.",
    )
    val temporalExpression: String?,
    @param:Guide(
        description = "How sure you are of this interpretation overall. Leave empty if you " +
            "cannot say.",
        enumValues = ["HIGH", "MEDIUM", "LOW"],
    )
    val confidenceBand: String?,
)
