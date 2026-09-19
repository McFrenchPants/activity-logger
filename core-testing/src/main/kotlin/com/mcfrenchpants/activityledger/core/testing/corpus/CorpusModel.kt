package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * The semantic regression corpus model (TEST_STRATEGY section 3).
 *
 * Every field without a default is REQUIRED in corpus.json, including nullable ones: a case
 * must say "activityId": null explicitly rather than leave it out, so an omission can never
 * silently mean "anything goes". Only `note`, `knownResolverGap` and descriptions are optional.
 *
 * Expected values are the correct PRODUCT behaviour, never what a particular model currently
 * answers.
 */

/** Why a case exists; used to group results. */
@Serializable
enum class CorpusCategory {
    /** A different wording of an existing activity: must match it. */
    SYNONYM,

    /** A related but distinct activity: must not be merged into its neighbour. */
    NEAR_NEIGHBOUR,

    /** Nothing equivalent in the catalog: a new, well-named activity. */
    NEW_ACTIVITY,

    /** The time wording and its deterministic resolution are the point of the case. */
    TEMPORAL,

    /** Not clear enough to log: must go to review, never auto-accept. */
    AMBIGUITY,

    /** Completed versus in progress. */
    STATE,
}

/** What the whole pipeline should end in for this capture. */
@Serializable
enum class ExpectedOutcome {
    /** Logged automatically (orchestrator `AutoAccepted`). */
    AUTO_ACCEPT,

    /** Nothing logged; the user reviews it (orchestrator `NeedsReview` or `Rejected`). */
    NEEDS_REVIEW,
}

/** One activity in a catalog fixture. [id] is fixed so recorded answers can be replayed. */
@Serializable
data class FixtureActivity(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
)

/** A named, reusable catalog of existing activities that cases refer to by name. */
@Serializable
data class CatalogFixture(
    val description: String? = null,
    val activities: List<FixtureActivity>,
)

/**
 * The correct interpretation of one case.
 *
 * @property resolution The preferred resolution.
 * @property allowedResolutions Other resolutions that are equally correct (e.g. UNRESOLVED
 *   for an AMBIGUOUS case). Never includes [resolution].
 * @property activityId Catalog id to match; set only for EXISTING_ACTIVITY.
 * @property allowedActivityIds Other existing catalog ids that are equally acceptable matches
 *   (the owner has ruled that logging under them is correct). Never includes [activityId] or a
 *   [mustNotMatch] id; non-empty only where EXISTING_ACTIVITY is acceptable and the outcome is
 *   AUTO_ACCEPT.
 * @property newActivityName Preferred new-activity name; set only for NEW_ACTIVITY.
 * @property allowedNewNames Other acceptable new names. Names are compared after
 *   `NameNormalizer.normalize`.
 * @property allowedStates Every acceptable `activityState`. A JSON null entry means "the model
 *   may leave the state empty"; only used where the outcome is review anyway, because a
 *   missing state can never be auto-accepted.
 * @property temporalExpression The user's own time words the model should copy, or null when
 *   the sentence gives no time.
 * @property allowedTemporalExpressions Other acceptable copies of the time words. A JSON null
 *   entry means leaving it empty is also correct (only where that resolves identically).
 * @property resolvedLocalDate ISO local date (in the case's zone) the time words must resolve
 *   to; set exactly when [temporalExpression] is set.
 * @property precision The precision the time words must resolve to; set exactly when
 *   [temporalExpression] is set.
 * @property mustNotMatch Catalog ids that must never be the matched activity.
 * @property outcome Whether the capture should be logged automatically or go to review.
 */
@Serializable
data class ExpectedInterpretation(
    val resolution: ActivityResolution,
    val allowedResolutions: List<ActivityResolution>,
    val activityId: String?,
    val allowedActivityIds: List<String>,
    val newActivityName: String?,
    val allowedNewNames: List<String>,
    val allowedStates: List<ActivityState?>,
    val temporalExpression: String?,
    val allowedTemporalExpressions: List<String?>,
    val resolvedLocalDate: String?,
    val precision: TimePrecision?,
    val mustNotMatch: List<String>,
    val outcome: ExpectedOutcome,
) {
    /** [resolution] plus [allowedResolutions]. */
    val acceptableResolutions: Set<ActivityResolution> get() = setOf(resolution) + allowedResolutions

    /** [activityId] (if set) plus [allowedActivityIds]: every catalog id that is a correct match. */
    val acceptableActivityIds: Set<String> get() = setOfNotNull(activityId) + allowedActivityIds

    /** [newActivityName] plus [allowedNewNames], as written; empty unless NEW_ACTIVITY. */
    val acceptableNewNames: List<String> get() = listOfNotNull(newActivityName) + allowedNewNames

    /** [temporalExpression] plus [allowedTemporalExpressions] (may contain null). */
    val acceptableTemporalExpressions: List<String?> get() = listOf(temporalExpression) + allowedTemporalExpressions

    /** [resolvedLocalDate] parsed, or null. */
    val expectedLocalDate: LocalDate? get() = resolvedLocalDate?.let(LocalDate::parse)
}

/**
 * One corpus case.
 *
 * @property id Stable kebab-case identifier; recordings and reports refer to it.
 * @property catalog Name of a [CatalogFixture] in [SemanticCorpus.catalogs].
 * @property rawText The user's sentence, verbatim.
 * @property capturedAt ISO offset date-time of the capture ("now" for the time words).
 * @property zoneId IANA zone the capture was made in.
 * @property note Optional explanation of a non-obvious expectation.
 * @property knownResolverGap Set only when the deterministic `TemporalResolver` does NOT
 *   produce [ExpectedInterpretation.resolvedLocalDate] / precision for this case; describes the
 *   difference. The expectation stays product-correct; the integrity test requires the set of
 *   gap cases to equal exactly the set of marked cases.
 */
@Serializable
data class CorpusCase(
    val id: String,
    val category: CorpusCategory,
    val catalog: String,
    val rawText: String,
    val capturedAt: String,
    val zoneId: String,
    val expected: ExpectedInterpretation,
    val note: String? = null,
    val knownResolverGap: String? = null,
) {
    /** [capturedAt] as an instant. */
    val capturedInstant: Instant get() = OffsetDateTime.parse(capturedAt).toInstant()

    /** [zoneId] parsed. */
    val zone: ZoneId get() = ZoneId.of(zoneId)
}

/** The on-disk document shape of corpus.json. */
@Serializable
internal data class CorpusDocument(
    val schemaVersion: Int,
    val description: String? = null,
    val catalogs: Map<String, CatalogFixture>,
    val cases: List<CorpusCase>,
)
