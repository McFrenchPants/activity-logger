package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecision
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionPolicy
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oracle test for the deterministic tag decision policy (ADR-039).
 *
 * For each listed tag corpus case the policy is fed a HAND-WRITTEN ideal extraction -- what a
 * perfect extractor would pull out of the sentence: operation LOG_ACTIVITY, the subject and
 * action words below, nothing else -- against the case's catalog fixture. No model is involved,
 * so this checks the resolver and policy alone: given the right words, is the decision right?
 *
 * Checked per case: the outcome is acceptable; on AUTO_SAVE every exact tag is an acceptable
 * existing id (or an inferred subject the case allows to be omitted), every new tag name is an
 * acceptable new name, no tag is a mustNotMatch id, and no new tag is created where the case
 * expects an existing one (a silent duplicate).
 *
 * Failure messages name case ids and field names only, never sentence text (AGENTS.md #11).
 */
class TagPolicyOracleTest {

    private class Ideal(val subject: String?, val action: String?)

    /** Case id -> the ideal extracted words (subject, action); null = not said. */
    private val ideal: Map<String, Ideal> = linkedMapOf(
        "real-changed-furnace-filter" to Ideal("furnace", "change filter"),
        "real-changed-hot-tub-filter" to Ideal("hot tub", "change filter"),
        "real-tractor-oil-change" to Ideal("tractor", "change oil"),
        "real-lawn-mower-gas" to Ideal("lawn mower", "get gas"),
        "real-weeded-garden-half-hour" to Ideal("garden", "weed"),
        "real-durango-headlight" to Ideal("Durango", "replace headlight"),
        "real-walked-dogs-30-minutes" to Ideal("dogs", "walk"),
        "real-reboot-wifi" to Ideal("Wi-Fi", "reboot"),
        "real-cleaned-hot-tub" to Ideal("hot tub", "clean"),
        "real-mowed-lawn-40-minutes" to Ideal("lawn", "mow"),
        "real-put-dishes-away" to Ideal("dishes", "put away"),
        "real-hot-tub-sanitizer" to Ideal("hot tub", "add sanitizer"),
        "sib-drained-the-hot-tub" to Ideal("hot tub", "drain"),
        "sib-changed-filter-in-hot-tub" to Ideal("hot tub", "change filter"),
        "sib-changed-oil-in-lawn-mower" to Ideal("lawn mower", "change oil"),
        "sib-washed-the-dogs" to Ideal("dogs", "wash"),
        "sib-cleaned-the-furnace" to Ideal("furnace", "clean"),
        "sib-rebooted-the-router" to Ideal("router", "reboot"),
        "sib-weeded-the-flower-bed" to Ideal("flower bed", "weed"),
        "sib-added-chlorine-to-hot-tub" to Ideal("hot tub", "add chlorine"),
        "empty-changed-furnace-filter" to Ideal("furnace", "change filter"),
        "empty-durango-headlight" to Ideal("Durango", "replace headlight"),
        "empty-walked-dogs-30-minutes" to Ideal("dogs", "walk"),
        "empty-reboot-wifi" to Ideal("Wi-Fi", "reboot"),
        "p-mow-i-mowed-the-lawn" to Ideal("lawn", "mow"),
        "p-mow-finished-mowing" to Ideal(null, "mow"),
        "p-mow-just-mowed" to Ideal(null, "mow"),
        "p-edge-i-edged-the-lawn" to Ideal("lawn", "edge"),
        "p-edge-finished-edging" to Ideal(null, "edge"),
        "p-edge-no-edge-catalog-i-edged-the-lawn" to Ideal("lawn", "edge"),
        "p-gutters-cleaned-the-gutters" to Ideal("gutters", "clean"),
        "p-dryer-vent-cleaned-the-dryer-vent" to Ideal("dryer vent", "clean"),
        "p-dryer-emptied-the-lint-trap" to Ideal("dryer lint trap", "empty"),
        "p-leaves-raked-the-leaves" to Ideal("leaves", "rake"),
        "p-leaves-blew-leaves-off-the-driveway" to Ideal("leaves", "blow"),
        "p-car-washed-the-car" to Ideal("car", "wash"),
        "p-car-waxed-the-car" to Ideal("car", "wax"),
        "p-new-flushed-the-water-heater" to Ideal("water heater", "flush"),
        "p-new-replaced-smoke-detector-batteries" to Ideal("smoke detector", "replace batteries"),
        "p-new-empty-catalog-changed-the-furnace-filter" to Ideal("furnace", "change filter"),
        "p-new-empty-catalog-mowed-the-lawn" to Ideal("lawn", "mow"),
        "p-time-took-out-the-trash-last-night" to Ideal("trash", "take out"),
        "p-time-watered-the-garden-at-7pm" to Ideal("garden", "water"),
        "p-state-im-mowing-now" to Ideal(null, "mow"),
        "p-state-just-finished-edging-the-lawn" to Ideal("lawn", "edge"),
        "p-ambiguous-worked-on-the-yard" to Ideal("yard", "work on"),
        "p-ambiguous-did-the-furnace-thing" to Ideal("furnace thing", "do"),
        "p-ambiguous-handled-the-filter" to Ideal("filter", "handle"),
        "p-ambiguous-fixed-that-thing-outside" to Ideal("thing outside", "fix"),
    )

    /**
     * Cases the current rules cannot pass without breaking a rule the policy is required to
     * follow. Asserted to be EXACTLY the failing set, so a fix or a new failure both show up.
     * Empty today: every listed case passes with the rules as specified.
     */
    private val KNOWN_POLICY_GAPS: Set<String> = emptySet()

    private val corpus = TagCorpus.load()

    @Test
    fun `the table covers exactly the intended cases and each exists`() {
        assertEquals(49, ideal.size, "ideal extraction table size")
        val missing = ideal.keys.filter { corpus.case(it) == null }
        assertTrue(missing.isEmpty(), "cases not in the tag corpus: $missing")
        assertTrue(KNOWN_POLICY_GAPS.all { it in ideal.keys }, "known gaps must be table cases")
    }

    @Test
    fun `the policy decides every ideal extraction acceptably`() {
        val problems = linkedMapOf<String, MutableList<String>>()
        ideal.forEach { (id, words) ->
            val case = corpus.case(id) ?: return@forEach
            val fixture = corpus.catalogs[case.catalog] ?: run {
                problems.getOrPut(id) { mutableListOf() }.add("catalog fixture missing")
                return@forEach
            }
            val decision = TagDecisionPolicy.decide(
                ExtractionCandidate(
                    operation = InterpretationOperation.LOG_ACTIVITY,
                    subject = words.subject,
                    action = words.action,
                    activityState = null,
                    temporalExpression = null,
                    durationExpression = null,
                ),
                TagCorpusCatalogs.toTagCatalog(fixture),
            )
            val found = check(case, decision)
            if (found.isNotEmpty()) problems[id] = found
        }

        val failing = problems.keys.toSet()
        val report = problems.entries.joinToString("\n") { (id, p) -> "$id: ${p.joinToString("; ")}" }
        assertEquals(
            KNOWN_POLICY_GAPS,
            failing,
            "failing cases differ from KNOWN_POLICY_GAPS\n$report",
        )
    }

    private fun check(case: TagCorpusCase, decision: TagDecision): MutableList<String> {
        val problems = mutableListOf<String>()
        val expected = case.expected
        val outcome = TagOutcome.valueOf(decision.outcome.name)
        if (outcome !in expected.acceptableOutcomes) {
            problems.add("outcome $outcome not in acceptableOutcomes ${expected.acceptableOutcomes.sorted()} (reasons ${decision.reasons.sorted()})")
        }
        if (decision.outcome == TagDecisionOutcome.AUTO_SAVE) {
            checkSide("subject", decision.subject, expected.subject, inferredAllowed = decision.subjectInferred && expected.subject.mayBeEmpty, problems)
            checkSide("action", decision.action, expected.action, inferredAllowed = false, problems)
        }
        return problems
    }

    private fun checkSide(
        field: String,
        resolution: TagResolution,
        expected: ExpectedTag,
        inferredAllowed: Boolean,
        problems: MutableList<String>,
    ) {
        when (resolution) {
            is TagResolution.Exact -> {
                val id = resolution.tag.id
                if (id in expected.mustNotMatch) problems.add("$field auto-saved to mustNotMatch id $id")
                if (id !in expected.acceptableExistingIds && !inferredAllowed) {
                    problems.add("$field auto-saved to id $id, not an acceptable existing id")
                }
            }
            is TagResolution.New -> {
                if (expected.existingId != null) {
                    problems.add("$field created a new tag where existingId ${expected.existingId} is expected (silent duplicate)")
                } else if (!expected.acceptsNewName(resolution.name)) {
                    problems.add("$field new name is not an acceptable new name")
                }
            }
            TagResolution.Empty, is TagResolution.Near ->
                problems.add("$field auto-saved while ${resolution::class.simpleName}")
        }
    }
}
