package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput

/**
 * The ONE place that decides what an extractor is shown for a tag corpus case. Every recorder
 * (phone, stand-in) and any later replay call [forCase], so they agree exactly on the input.
 *
 * The extractor sees the sentence, the capture instant and the zone -- and nothing from the
 * case's catalog fixture: extraction is never shown existing tags (ADR-038). Resolving the
 * extracted words against the fixture is the deterministic step that comes after.
 */
object TagCorpusExtractionInput {

    /** Builds the extractor input for [case]. */
    fun forCase(case: TagCorpusCase): ExtractionInput = ExtractionInput(
        rawText = case.rawText,
        capturedAt = case.capturedInstant,
        zoneId = case.zone,
    )
}
