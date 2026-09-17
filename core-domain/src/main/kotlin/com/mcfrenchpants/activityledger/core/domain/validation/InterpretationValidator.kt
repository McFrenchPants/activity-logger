package com.mcfrenchpants.activityledger.core.domain.validation

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution

/**
 * Everything the validator needs to judge one interpreter answer.
 *
 * @property candidate The untrusted structured answer.
 * @property suppliedCandidates The shortlist actually shown to the interpreter.
 * @property catalog The full active catalog (used for duplicate-name detection).
 * @property temporal Deterministic resolution of the candidate's time phrase.
 * @property speechConfidence Speech recognizer confidence, or null when unknown / typed input.
 */
data class ValidationInput(
    val candidate: InterpretationCandidate,
    val suppliedCandidates: List<CandidateActivity>,
    val catalog: List<CatalogActivity>,
    val temporal: TemporalResolution,
    val speechConfidence: Double?,
)

/** The only three things that can happen to an interpretation. There is no "save but flag" tier. */
enum class ValidationOutcome(val validationStatus: ValidationStatus) {
    /** Safe to record automatically. */
    AUTO_ACCEPT(ValidationStatus.VALID),

    /** Nothing is logged; the user must review. */
    NEEDS_REVIEW(ValidationStatus.NEEDS_REVIEW),

    /** Structurally unacceptable answer; nothing is logged. */
    REJECT(ValidationStatus.INVALID),
}

/**
 * Machine-readable reasons an interpretation was not auto-accepted.
 *
 * The constant NAMES are persisted as text (interpretations.validation_reason). Renaming or
 * removing a constant is a data change and needs a migration.
 *
 * @property rejects True if this reason forces [ValidationOutcome.REJECT]; false if it only
 *   requires [ValidationOutcome.NEEDS_REVIEW].
 */
enum class ValidationReason(val rejects: Boolean) {
    OPERATION_UNSUPPORTED(true),
    OPERATION_IS_QUERY(true),
    EXISTING_WITHOUT_ACTIVITY_ID(true),
    EXISTING_ACTIVITY_NOT_SUPPLIED(true),
    EXISTING_WITH_PROPOSED_NAME(true),
    NEW_WITHOUT_NAME(true),
    NEW_WITH_ACTIVITY_ID(true),
    UNMATCHED_WITH_ACTIVITY_FIELDS(true),
    STATE_MISSING(true),

    ACTIVITY_AMBIGUOUS(false),
    ACTIVITY_UNRESOLVED(false),
    CONFIDENCE_NOT_HIGH(false),
    CONFIDENCE_MISSING(false),
    TIME_IN_FUTURE(false),
    TIME_UNRESOLVABLE(false),
    NEW_NAME_INVALID(false),
    NEW_NAME_DUPLICATES_EXISTING(false),
    SPEECH_CONFIDENCE_LOW(false),
    ;

    /** True if this reason only requires review. */
    val needsReview: Boolean get() = !rejects
}

/**
 * The validator's verdict. Contains only enum values, never raw text or names.
 *
 * @property reasonCodes Reason names sorted alphabetically and comma-joined, or null when there
 *   are none; this is the value persisted as interpretations.validation_reason.
 */
data class ValidationDecision(
    val outcome: ValidationOutcome,
    val validationStatus: ValidationStatus,
    val reasons: Set<ValidationReason>,
) {
    val reasonCodes: String?
        get() = if (reasons.isEmpty()) null else reasons.map { it.name }.sorted().joinToString(",")
}

/**
 * Decides whether an interpreter answer may be saved automatically, must wait for review, or is
 * rejected. Pure and deterministic: no clock, no logging, never throws for candidate content.
 */
class InterpretationValidator(val minimumSpeechConfidence: Double = DEFAULT_MINIMUM_SPEECH_CONFIDENCE) {

    init {
        require(minimumSpeechConfidence in 0.0..1.0) { "minimumSpeechConfidence must be within 0..1" }
    }

    fun validate(input: ValidationInput): ValidationDecision {
        val c = input.candidate
        when (c.operation) {
            InterpretationOperation.UNSUPPORTED -> return decide(setOf(ValidationReason.OPERATION_UNSUPPORTED))
            InterpretationOperation.QUERY_HISTORY -> return decide(setOf(ValidationReason.OPERATION_IS_QUERY))
            InterpretationOperation.LOG_ACTIVITY -> Unit
        }

        val reasons = mutableSetOf<ValidationReason>()
        val matchedId = c.matchedActivityId
        val name = c.proposedCanonicalName

        when (c.activityResolution) {
            ActivityResolution.EXISTING_ACTIVITY -> {
                if (matchedId.isNullOrBlank()) {
                    reasons += ValidationReason.EXISTING_WITHOUT_ACTIVITY_ID
                } else if (input.suppliedCandidates.none { it.id == matchedId }) {
                    reasons += ValidationReason.EXISTING_ACTIVITY_NOT_SUPPLIED
                }
                if (!name.isNullOrBlank()) reasons += ValidationReason.EXISTING_WITH_PROPOSED_NAME
            }
            ActivityResolution.NEW_ACTIVITY -> {
                if (!matchedId.isNullOrBlank()) reasons += ValidationReason.NEW_WITH_ACTIVITY_ID
                if (name.isNullOrBlank()) {
                    reasons += ValidationReason.NEW_WITHOUT_NAME
                } else {
                    if (NewActivityNameCheck.check(name) is NewActivityNameCheck.Result.Invalid) {
                        reasons += ValidationReason.NEW_NAME_INVALID
                    }
                    val normalized = NameNormalizer.normalize(name)
                    val duplicate = input.catalog.any { entry ->
                        entry.normalizedName == normalized || entry.normalizedAliases.any { it == normalized }
                    }
                    if (duplicate) reasons += ValidationReason.NEW_NAME_DUPLICATES_EXISTING
                }
            }
            ActivityResolution.AMBIGUOUS, ActivityResolution.UNRESOLVED -> {
                reasons += if (c.activityResolution == ActivityResolution.AMBIGUOUS) {
                    ValidationReason.ACTIVITY_AMBIGUOUS
                } else {
                    ValidationReason.ACTIVITY_UNRESOLVED
                }
                if (!matchedId.isNullOrBlank() || !name.isNullOrBlank()) {
                    reasons += ValidationReason.UNMATCHED_WITH_ACTIVITY_FIELDS
                }
            }
        }

        if (c.activityState == null) reasons += ValidationReason.STATE_MISSING

        when (c.confidenceBand) {
            null -> reasons += ValidationReason.CONFIDENCE_MISSING
            ConfidenceBand.MEDIUM, ConfidenceBand.LOW -> reasons += ValidationReason.CONFIDENCE_NOT_HIGH
            ConfidenceBand.HIGH -> Unit
        }

        when (input.temporal) {
            TemporalResolution.Future -> reasons += ValidationReason.TIME_IN_FUTURE
            TemporalResolution.Unresolvable -> reasons += ValidationReason.TIME_UNRESOLVABLE
            is TemporalResolution.Resolved -> Unit
        }

        val speech = input.speechConfidence
        // Written as !(>=) so a NaN confidence is treated as low rather than silently trusted;
        // a value outside 0..1 means a broken recognizer and is not trusted either.
        if (speech != null && (!(speech >= minimumSpeechConfidence) || speech > 1.0)) {
            reasons += ValidationReason.SPEECH_CONFIDENCE_LOW
        }

        return decide(reasons)
    }

    private fun decide(reasons: Set<ValidationReason>): ValidationDecision {
        val outcome = when {
            reasons.any { it.rejects } -> ValidationOutcome.REJECT
            reasons.isNotEmpty() -> ValidationOutcome.NEEDS_REVIEW
            else -> ValidationOutcome.AUTO_ACCEPT
        }
        return ValidationDecision(outcome, outcome.validationStatus, reasons.toSet())
    }

    companion object {
        const val DEFAULT_MINIMUM_SPEECH_CONFIDENCE: Double = 0.5
    }
}
