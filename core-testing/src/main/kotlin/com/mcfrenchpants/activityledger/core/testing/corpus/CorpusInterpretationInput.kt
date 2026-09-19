package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelection
import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput

/**
 * Exactly what the interpreter is shown for one corpus case.
 *
 * @property selection The real [CandidateSelector]'s shortlist over [SemanticCorpus.catalogFor].
 * @property input The interpreter input built from the case and [selection].
 */
data class CorpusCaseInput(
    val selection: CandidateSelection,
    val input: InterpretationInput,
)

/**
 * The ONE place that decides what a model is shown for a corpus case. Every recorder (phone,
 * stand-in) and the JVM replay call [forCase], so they agree byte-for-byte on the shortlist,
 * its context hash and the interpreter input.
 *
 * Uses the production default [CandidateSelector] (the same one the capture orchestrator uses by
 * default) over the case's fixed-id catalog, which has no occurrence history.
 */
object CorpusInterpretationInput {

    /** Builds the selection and interpreter input for [case] of [corpus]. */
    fun forCase(corpus: SemanticCorpus, case: CorpusCase): CorpusCaseInput {
        val selection = CandidateSelector().select(corpus.catalogFor(case), case.rawText)
        val input = InterpretationInput(
            rawText = case.rawText,
            capturedAt = case.capturedInstant,
            zoneId = case.zone,
            candidates = selection.candidates,
        )
        return CorpusCaseInput(selection, input)
    }
}
