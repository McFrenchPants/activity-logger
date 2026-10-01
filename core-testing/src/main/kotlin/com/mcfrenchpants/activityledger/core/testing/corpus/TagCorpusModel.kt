package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * The tag corpus model (subject + action redesign, TG1).
 *
 * A separate test set from corpus.json: every capture is described by a SUBJECT tag (what it
 * was done to) and an ACTION tag (what was done), plus an optional duration, and a policy
 * outcome (AUTO_SAVE / CONFIRM / NEEDS_REVIEW).
 *
 * Every field without a default is REQUIRED in tag-corpus.json, including nullable ones: a case
 * must say "existingId": null explicitly rather than leave it out, so an omission can never
 * silently mean "anything goes". Only `note`, `knownResolverGap`, `sourceCaseId` and
 * descriptions are optional.
 *
 * Expected values are the correct PRODUCT behaviour, never what a particular model or resolver
 * currently answers.
 */

/** Where a tag corpus case comes from. */
@Serializable
enum class TagCaseGroup {
    /** The tag reading of one corpus.json case (same sentence, context and time expectations). */
    PORTED,

    /** A real watch transcription the owner provided, speech errors kept verbatim. */
    REAL_ENTRY,

    /** A synthetic sibling of a real entry that reuses its catalog's subjects or actions. */
    SIBLING,

    /** A real-entry sentence against an empty catalog: every tag must be new. */
    EMPTY_START,
}

/** What the decision policy should end in for this capture. */
@Serializable
enum class TagOutcome {
    /** Logged silently, with no question. */
    AUTO_SAVE,

    /** A quick "did you mean X?" card; nothing is saved silently. */
    CONFIRM,

    /** Nothing logged; the user reviews it. */
    NEEDS_REVIEW,
}

/**
 * One subject or action tag in a catalog fixture.
 *
 * @property id Fixed kebab-case id (prefixed `subj-` or `act-`) so recorded answers can be replayed.
 * @property displayName The tag's name as the user sees it.
 * @property aliases Other names that are the same tag.
 */
@Serializable
data class FixtureTag(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
)

/**
 * A known subject + action combination (what the old model called an activity). Used for
 * inferring a missing subject or action and for lookup.
 *
 * @property subjectId Id of a subject in the same fixture.
 * @property actionId Id of an action in the same fixture.
 * @property displayName The combination's name as the user knows it.
 */
@Serializable
data class FixturePair(
    val subjectId: String,
    val actionId: String,
    val displayName: String,
)

/**
 * A named, reusable tag catalog that cases refer to by name.
 *
 * @property description Optional explanation of the fixture.
 * @property subjects Existing subject tags.
 * @property actions Existing action tags.
 * @property pairs Known subject + action combinations.
 */
@Serializable
data class TagCatalogFixture(
    val description: String? = null,
    val subjects: List<FixtureTag>,
    val actions: List<FixtureTag>,
    val pairs: List<FixturePair>,
)

/**
 * The correct reading of one tag (the subject or the action) of a case.
 *
 * Exactly one of [existingId] / [newName] is set, except that both may be null when
 * [mayBeEmpty] is true or the case's outcome is NEEDS_REVIEW.
 *
 * @property existingId The existing tag the words must resolve to; creating a new tag instead
 *   would be a duplicate.
 * @property allowedExistingIds Other existing tag ids that are equally acceptable. Never
 *   includes [existingId] or a [mustNotMatch] id. May accompany [newName] where reusing an
 *   existing tag is also correct.
 * @property newName Preferred name of a new tag; set only when nothing suitable exists.
 * @property allowedNewNames Other acceptable new names. Names are compared case-insensitively
 *   after trimming.
 * @property mayBeEmpty True when leaving this tag empty is also correct (e.g. a subject the
 *   sentence omits but a known pair implies).
 * @property mustNotMatch Existing tag ids (of the same kind) that must never be chosen; pins
 *   the wrong tags the real entries were filed under.
 */
@Serializable
data class ExpectedTag(
    val existingId: String?,
    val allowedExistingIds: List<String>,
    val newName: String?,
    val allowedNewNames: List<String>,
    val mayBeEmpty: Boolean,
    val mustNotMatch: List<String>,
) {
    /** [existingId] (if set) plus [allowedExistingIds]: every existing id that is a correct match. */
    val acceptableExistingIds: Set<String> get() = setOfNotNull(existingId) + allowedExistingIds

    /** [newName] plus [allowedNewNames], as written. */
    val acceptableNewNames: List<String> get() = listOfNotNull(newName) + allowedNewNames

    /** True when [name] equals an acceptable new name, case-insensitively after trimming. */
    fun acceptsNewName(name: String): Boolean =
        acceptableNewNames.any { it.trim().equals(name.trim(), ignoreCase = true) }
}

/**
 * The correct interpretation of one tag corpus case.
 *
 * @property subject Expected subject tag.
 * @property action Expected action tag.
 * @property allowedStates Every acceptable `activityState`. A JSON null entry means "may be left
 *   empty"; only used where the outcome is review anyway.
 * @property temporalExpression The user's own time words, or null when the sentence gives no time.
 * @property allowedTemporalExpressions Other acceptable copies of the time words. A JSON null
 *   entry means leaving it empty is also correct (only where that resolves identically).
 * @property resolvedLocalDate ISO local date (in the case's zone) the time words must resolve
 *   to; set exactly when [temporalExpression] is set.
 * @property precision The precision the time words must resolve to; set exactly when
 *   [temporalExpression] is set.
 * @property durationExpression The user's own duration words (e.g. "for half an hour"), or
 *   null when no duration is stated. A duration is never a time.
 * @property allowedDurationExpressions Other acceptable copies of the duration words.
 * @property durationMinutes The duration in minutes; set (and positive) exactly when
 *   [durationExpression] is set.
 * @property outcome The preferred policy outcome.
 * @property allowedOutcomes Other acceptable outcomes. Never includes [outcome].
 */
@Serializable
data class ExpectedTagInterpretation(
    val subject: ExpectedTag,
    val action: ExpectedTag,
    val allowedStates: List<ActivityState?>,
    val temporalExpression: String?,
    val allowedTemporalExpressions: List<String?>,
    val resolvedLocalDate: String?,
    val precision: TimePrecision?,
    val durationExpression: String?,
    val allowedDurationExpressions: List<String?>,
    val durationMinutes: Int?,
    val outcome: TagOutcome,
    val allowedOutcomes: List<TagOutcome>,
) {
    /** [outcome] plus [allowedOutcomes]. */
    val acceptableOutcomes: Set<TagOutcome> get() = setOf(outcome) + allowedOutcomes

    /** [temporalExpression] plus [allowedTemporalExpressions] (may contain null). */
    val acceptableTemporalExpressions: List<String?> get() = listOf(temporalExpression) + allowedTemporalExpressions

    /** [durationExpression] plus [allowedDurationExpressions] (may contain null). */
    val acceptableDurationExpressions: List<String?> get() = listOf(durationExpression) + allowedDurationExpressions

    /** [resolvedLocalDate] parsed, or null. */
    val expectedLocalDate: LocalDate? get() = resolvedLocalDate?.let(LocalDate::parse)
}

/**
 * One tag corpus case.
 *
 * @property id Stable kebab-case identifier; future recordings and reports refer to it.
 * @property group Where the case comes from.
 * @property category Why the case exists (reuses the corpus.json categories).
 * @property catalog Name of a [TagCatalogFixture] in [TagCorpus.catalogs].
 * @property rawText The user's sentence, verbatim (speech errors kept for real entries).
 * @property capturedAt ISO offset date-time of the capture ("now" for the time words).
 * @property zoneId IANA zone the capture was made in.
 * @property expected The product-correct interpretation.
 * @property note Optional explanation of a non-obvious expectation.
 * @property knownResolverGap Set only when the deterministic `TemporalResolver` does NOT produce
 *   the expected date / precision; describes the difference.
 * @property sourceCaseId The corpus.json case id for [TagCaseGroup.PORTED] cases (required
 *   there), null for every other group.
 */
@Serializable
data class TagCorpusCase(
    val id: String,
    val group: TagCaseGroup,
    val category: CorpusCategory,
    val catalog: String,
    val rawText: String,
    val capturedAt: String,
    val zoneId: String,
    val expected: ExpectedTagInterpretation,
    val note: String? = null,
    val knownResolverGap: String? = null,
    val sourceCaseId: String? = null,
) {
    /** [capturedAt] as an instant. */
    val capturedInstant: Instant get() = OffsetDateTime.parse(capturedAt).toInstant()

    /** [zoneId] parsed. */
    val zone: ZoneId get() = ZoneId.of(zoneId)
}

/** The on-disk document shape of tag-corpus.json. */
@Serializable
internal data class TagCorpusDocument(
    val schemaVersion: Int,
    val description: String? = null,
    val catalogs: Map<String, TagCatalogFixture>,
    val cases: List<TagCorpusCase>,
)
