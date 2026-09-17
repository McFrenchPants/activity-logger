package com.mcfrenchpants.activityledger.core.domain.validation

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution.AMBIGUOUS
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution.EXISTING_ACTIVITY
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution.NEW_ACTIVITY
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution.UNRESOLVED
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationOutcome.AUTO_ACCEPT
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationOutcome.NEEDS_REVIEW
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationOutcome.REJECT
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason as R
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterpretationValidatorTest {
    private val validator = InterpretationValidator()

    private val mowLawn = CatalogActivity("act-mow", "Mow lawn", "mow lawn", listOf("cut grass"), null)
    private val gutters = CatalogActivity("act-gutters", "Clean gutters", "clean gutters", emptyList(), null)
    private val filter = CatalogActivity("act-filter", "Replace furnace filter", "replace furnace filter", emptyList(), null)
    private val catalog = listOf(mowLawn, gutters, filter)
    private val supplied = catalog.map { CandidateActivity(it.id, it.displayName, it.normalizedAliases) }
    private val resolved = TemporalResolution.Resolved(Instant.parse("2026-09-16T12:00:00Z"), TimePrecision.DATE_ONLY)

    private fun existing(
        id: String? = "act-mow",
        name: String? = null,
        state: ActivityState? = ActivityState.COMPLETED,
        band: ConfidenceBand? = ConfidenceBand.HIGH,
        operation: InterpretationOperation = InterpretationOperation.LOG_ACTIVITY,
        resolution: ActivityResolution = EXISTING_ACTIVITY,
    ) = InterpretationCandidate(operation, resolution, id, name, state, "yesterday", band)

    private fun newActivity(name: String? = "Clean dryer vent", id: String? = null, band: ConfidenceBand? = ConfidenceBand.HIGH) =
        existing(id = id, name = name, band = band, resolution = NEW_ACTIVITY)

    private fun unmatched(resolution: ActivityResolution, band: ConfidenceBand? = ConfidenceBand.HIGH) =
        existing(id = null, name = null, band = band, resolution = resolution)

    private fun validate(
        candidate: InterpretationCandidate,
        temporal: TemporalResolution = resolved,
        speech: Double? = null,
        v: InterpretationValidator = validator,
        suppliedCandidates: List<CandidateActivity> = supplied,
    ) = v.validate(ValidationInput(candidate, suppliedCandidates, catalog, temporal, speech))

    private fun assertOutcome(expected: ValidationOutcome, reason: R, decision: ValidationDecision) {
        assertTrue(reason in decision.reasons, "expected $reason in ${decision.reasons}")
        assertEquals(expected, decision.outcome)
        assertEquals(expected == REJECT, reason.rejects)
    }

    // --- Each reason ---------------------------------------------------------------------------

    @Test
    fun `OPERATION_UNSUPPORTED`() =
        assertOutcome(REJECT, R.OPERATION_UNSUPPORTED, validate(existing(operation = InterpretationOperation.UNSUPPORTED)))

    @Test
    fun `OPERATION_IS_QUERY`() =
        assertOutcome(REJECT, R.OPERATION_IS_QUERY, validate(existing(operation = InterpretationOperation.QUERY_HISTORY)))

    @Test
    fun `EXISTING_WITHOUT_ACTIVITY_ID`() {
        assertOutcome(REJECT, R.EXISTING_WITHOUT_ACTIVITY_ID, validate(existing(id = null)))
        assertOutcome(REJECT, R.EXISTING_WITHOUT_ACTIVITY_ID, validate(existing(id = "  ")))
    }

    @Test
    fun `EXISTING_ACTIVITY_NOT_SUPPLIED`() {
        assertOutcome(REJECT, R.EXISTING_ACTIVITY_NOT_SUPPLIED, validate(existing(id = "act-unknown")))
        // In the catalog but not offered to the model.
        assertOutcome(
            REJECT, R.EXISTING_ACTIVITY_NOT_SUPPLIED,
            validate(existing(id = "act-mow"), suppliedCandidates = supplied.filter { it.id != "act-mow" }),
        )
    }

    @Test
    fun `EXISTING_WITH_PROPOSED_NAME`() =
        assertOutcome(REJECT, R.EXISTING_WITH_PROPOSED_NAME, validate(existing(name = "Mow lawn")))

    @Test
    fun `NEW_WITHOUT_NAME`() {
        assertOutcome(REJECT, R.NEW_WITHOUT_NAME, validate(newActivity(name = null)))
        assertOutcome(REJECT, R.NEW_WITHOUT_NAME, validate(newActivity(name = " ")))
    }

    @Test
    fun `NEW_WITH_ACTIVITY_ID`() =
        assertOutcome(REJECT, R.NEW_WITH_ACTIVITY_ID, validate(newActivity(id = "act-mow")))

    @Test
    fun `UNMATCHED_WITH_ACTIVITY_FIELDS`() {
        assertOutcome(REJECT, R.UNMATCHED_WITH_ACTIVITY_FIELDS, validate(existing(resolution = AMBIGUOUS)))
        assertOutcome(REJECT, R.UNMATCHED_WITH_ACTIVITY_FIELDS, validate(existing(id = null, name = "Yard work", resolution = UNRESOLVED)))
    }

    @Test
    fun `STATE_MISSING`() = assertOutcome(REJECT, R.STATE_MISSING, validate(existing(state = null)))

    @Test
    fun `ACTIVITY_AMBIGUOUS`() {
        val d = validate(unmatched(AMBIGUOUS))
        assertOutcome(NEEDS_REVIEW, R.ACTIVITY_AMBIGUOUS, d)
        assertEquals(setOf(R.ACTIVITY_AMBIGUOUS), d.reasons)
    }

    @Test
    fun `ACTIVITY_UNRESOLVED`() {
        val d = validate(unmatched(UNRESOLVED))
        assertOutcome(NEEDS_REVIEW, R.ACTIVITY_UNRESOLVED, d)
        assertEquals(setOf(R.ACTIVITY_UNRESOLVED), d.reasons)
    }

    @Test
    fun `CONFIDENCE_NOT_HIGH`() {
        assertOutcome(NEEDS_REVIEW, R.CONFIDENCE_NOT_HIGH, validate(existing(band = ConfidenceBand.MEDIUM)))
        assertOutcome(NEEDS_REVIEW, R.CONFIDENCE_NOT_HIGH, validate(existing(band = ConfidenceBand.LOW)))
    }

    @Test
    fun `CONFIDENCE_MISSING`() = assertOutcome(NEEDS_REVIEW, R.CONFIDENCE_MISSING, validate(existing(band = null)))

    @Test
    fun `TIME_IN_FUTURE`() =
        assertOutcome(NEEDS_REVIEW, R.TIME_IN_FUTURE, validate(existing(), temporal = TemporalResolution.Future))

    @Test
    fun `TIME_UNRESOLVABLE`() =
        assertOutcome(NEEDS_REVIEW, R.TIME_UNRESOLVABLE, validate(existing(), temporal = TemporalResolution.Unresolvable))

    @Test
    fun `NEW_NAME_INVALID`() {
        val d = validate(newActivity(name = "I mowed the lawn today"))
        assertOutcome(NEEDS_REVIEW, R.NEW_NAME_INVALID, d)
        assertEquals(setOf(R.NEW_NAME_INVALID), d.reasons)
    }

    @Test
    fun `NEW_NAME_DUPLICATES_EXISTING by normalized name and by alias`() {
        val byName = validate(newActivity(name = "Mow Lawn!"))
        assertOutcome(NEEDS_REVIEW, R.NEW_NAME_DUPLICATES_EXISTING, byName)
        assertEquals(setOf(R.NEW_NAME_DUPLICATES_EXISTING), byName.reasons)
        val byAlias = validate(newActivity(name = "Cut grass"))
        assertOutcome(NEEDS_REVIEW, R.NEW_NAME_DUPLICATES_EXISTING, byAlias)
        assertEquals(setOf(R.NEW_NAME_DUPLICATES_EXISTING), byAlias.reasons)
    }

    @Test
    fun `duplicate check uses full catalog not only supplied candidates`() {
        val d = validate(newActivity(name = "Clean gutters"), suppliedCandidates = emptyList())
        assertOutcome(NEEDS_REVIEW, R.NEW_NAME_DUPLICATES_EXISTING, d)
    }

    @Test
    fun `SPEECH_CONFIDENCE_LOW`() {
        val d = validate(existing(), speech = 0.49)
        assertOutcome(NEEDS_REVIEW, R.SPEECH_CONFIDENCE_LOW, d)
        assertEquals(setOf(R.SPEECH_CONFIDENCE_LOW), d.reasons)
    }

    @Test
    fun `every reason is covered and classified`() {
        assertEquals(18, R.entries.size)
        assertEquals(9, R.entries.count { it.rejects })
        assertEquals(9, R.entries.count { it.needsReview })
    }

    // --- AUTO_ACCEPT -------------------------------------------------------------------------

    @Test
    fun `existing supplied activity with high confidence is auto accepted`() {
        val d = validate(existing())
        assertEquals(AUTO_ACCEPT, d.outcome)
        assertEquals(ValidationStatus.VALID, d.validationStatus)
        assertTrue(d.reasons.isEmpty())
        assertNull(d.reasonCodes)
    }

    @Test
    fun `new acceptable name is auto accepted`() {
        val d = validate(newActivity(name = "Clean dryer vent"))
        assertEquals(AUTO_ACCEPT, d.outcome)
        assertEquals(ValidationStatus.VALID, d.validationStatus)
        assertNull(d.reasonCodes)
    }

    @Test
    fun `in progress state is accepted`() {
        assertEquals(AUTO_ACCEPT, validate(existing(state = ActivityState.IN_PROGRESS)).outcome)
        assertEquals(
            AUTO_ACCEPT,
            validate(existing(id = null, name = "Clean dryer vent", state = ActivityState.IN_PROGRESS, resolution = NEW_ACTIVITY)).outcome,
        )
    }

    // --- Combination and precedence ---------------------------------------------------------

    @Test
    fun `reject plus review reasons returns reject and reports all`() {
        val d = validate(existing(state = null, band = ConfidenceBand.LOW), temporal = TemporalResolution.Future, speech = 0.1)
        assertEquals(REJECT, d.outcome)
        assertEquals(ValidationStatus.INVALID, d.validationStatus)
        assertEquals(setOf(R.STATE_MISSING, R.CONFIDENCE_NOT_HIGH, R.TIME_IN_FUTURE, R.SPEECH_CONFIDENCE_LOW), d.reasons)
        assertEquals("CONFIDENCE_NOT_HIGH,SPEECH_CONFIDENCE_LOW,STATE_MISSING,TIME_IN_FUTURE", d.reasonCodes)
    }

    @Test
    fun `query with other problems reports only the operation reason`() {
        val d = validate(
            existing(state = null, band = ConfidenceBand.LOW, operation = InterpretationOperation.QUERY_HISTORY, id = null),
            temporal = TemporalResolution.Unresolvable,
            speech = 0.0,
        )
        assertEquals(REJECT, d.outcome)
        assertEquals(setOf(R.OPERATION_IS_QUERY), d.reasons)
        assertEquals("OPERATION_IS_QUERY", d.reasonCodes)
    }

    @Test
    fun `status mapping for all outcomes`() {
        assertEquals(ValidationStatus.VALID, validate(existing()).validationStatus)
        assertEquals(ValidationStatus.NEEDS_REVIEW, validate(existing(band = ConfidenceBand.MEDIUM)).validationStatus)
        assertEquals(ValidationStatus.INVALID, validate(existing(state = null)).validationStatus)
        assertEquals(ValidationStatus.VALID, AUTO_ACCEPT.validationStatus)
        assertEquals(ValidationStatus.NEEDS_REVIEW, NEEDS_REVIEW.validationStatus)
        assertEquals(ValidationStatus.INVALID, REJECT.validationStatus)
    }

    // --- Semantic regression cases ----------------------------------------------------------

    @Test
    fun `false merge edged the lawn matched to mow lawn below high goes to review`() {
        // Raw text "I edged the lawn." — model wrongly picks Mow lawn.
        for (band in listOf(ConfidenceBand.MEDIUM, ConfidenceBand.LOW)) {
            val d = validate(existing(id = "act-mow", band = band))
            assertEquals(NEEDS_REVIEW, d.outcome)
            assertEquals(setOf(R.CONFIDENCE_NOT_HIGH), d.reasons)
        }
    }

    @Test
    fun `vague inputs with ambiguous or unresolved candidates never auto accept`() {
        val inputs = listOf("Worked on the yard.", "Did the furnace thing.", "Handled the filter.", "Fixed that thing outside.")
        for (raw in inputs) {
            for (resolution in listOf(AMBIGUOUS, UNRESOLVED)) {
                for (band in listOf(ConfidenceBand.HIGH, ConfidenceBand.MEDIUM, ConfidenceBand.LOW)) {
                    val d = validate(unmatched(resolution, band))
                    assertEquals(NEEDS_REVIEW, d.outcome, "$raw / $resolution / $band")
                }
            }
        }
    }

    // --- Speech confidence ------------------------------------------------------------------

    @Test
    fun `speech confidence threshold boundaries`() {
        assertEquals(AUTO_ACCEPT, validate(existing(), speech = 0.5).outcome)
        assertEquals(AUTO_ACCEPT, validate(existing(), speech = null).outcome)
        assertTrue(R.SPEECH_CONFIDENCE_LOW in validate(existing(), speech = 0.49).reasons)
    }

    @Test
    fun `out-of-range speech confidence is not trusted`() {
        for (bad in listOf(1.5, Double.POSITIVE_INFINITY, -0.1, Double.NaN)) {
            assertTrue(R.SPEECH_CONFIDENCE_LOW in validate(existing(), speech = bad).reasons, "speech $bad")
        }
        assertEquals(AUTO_ACCEPT, validate(existing(), speech = 1.0).outcome)
        for (badThreshold in listOf(-0.1, 1.1, Double.NaN)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { InterpretationValidator(badThreshold) }
        }
    }

    @Test
    fun `custom speech threshold is respected`() {
        val strict = InterpretationValidator(minimumSpeechConfidence = 0.8)
        assertEquals(0.8, strict.minimumSpeechConfidence)
        assertTrue(R.SPEECH_CONFIDENCE_LOW in validate(existing(), speech = 0.79, v = strict).reasons)
        assertEquals(AUTO_ACCEPT, validate(existing(), speech = 0.8, v = strict).outcome)
        assertEquals(AUTO_ACCEPT, validate(existing(), speech = 0.3, v = InterpretationValidator(0.2)).outcome)
    }

    // --- Robustness -------------------------------------------------------------------------

    @Test
    fun `never throws for arbitrary candidate content`() {
        val long = "x".repeat(100_000)
        val weird = listOf(null, "", " ", "\t\n", long, "act-nowhere", " ", "!!!", "🙂")
        for (op in InterpretationOperation.entries) {
            for (res in ActivityResolution.entries) {
                for (id in weird) {
                    for (name in weird) {
                        val c = InterpretationCandidate(op, res, id, name, null, name, null)
                        val d = validate(c, temporal = TemporalResolution.Unresolvable, speech = Double.NaN)
                        assertEquals(d.outcome.validationStatus, d.validationStatus)
                        assertFalse(d.reasonCodes.orEmpty().contains("x"))
                    }
                }
            }
        }
    }
}
