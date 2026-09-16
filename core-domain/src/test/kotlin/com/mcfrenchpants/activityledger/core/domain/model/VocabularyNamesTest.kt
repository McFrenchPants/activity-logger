package com.mcfrenchpants.activityledger.core.domain.model

import kotlin.enums.EnumEntries
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-name test: enum constant names are persisted as text, so this pins each
 * vocabulary's exact name list. A rename, removal or addition must fail here and be
 * updated deliberately (with a data migration where stored values are affected).
 * Ordinals are intentionally not asserted; they are never persisted.
 */
class VocabularyNamesTest {
    private fun names(entries: EnumEntries<*>): List<String> = entries.map { it.name }

    @Test
    fun `ActivityState names are pinned`() {
        assertEquals(listOf("COMPLETED", "IN_PROGRESS"), names(ActivityState.entries))
    }

    @Test
    fun `ActivityResolution names are pinned`() {
        assertEquals(
            listOf("EXISTING_ACTIVITY", "NEW_ACTIVITY", "AMBIGUOUS", "UNRESOLVED"),
            names(ActivityResolution.entries),
        )
    }

    @Test
    fun `ProcessingState names are pinned`() {
        assertEquals(
            listOf(
                "CAPTURED",
                "QUEUED_FOR_PHONE",
                "TRANSCRIBED",
                "INTERPRETING",
                "INTERPRETED",
                "PERSISTED",
                "NEEDS_REVIEW",
                "FAILED_RETRYABLE",
                "FAILED_FINAL",
            ),
            names(ProcessingState.entries),
        )
    }

    @Test
    fun `TimePrecision names are pinned`() {
        assertEquals(
            listOf("EXACT", "APPROXIMATE", "DATE_ONLY", "INFERRED_NOW"),
            names(TimePrecision.entries),
        )
    }

    @Test
    fun `VisibilityStatus names are pinned`() {
        assertEquals(listOf("ACTIVE", "HIDDEN"), names(VisibilityStatus.entries))
    }

    @Test
    fun `CanonicalActivityStatus names are pinned`() {
        assertEquals(listOf("ACTIVE", "MERGED", "ARCHIVED"), names(CanonicalActivityStatus.entries))
    }

    @Test
    fun `AliasSource names are pinned`() {
        assertEquals(
            listOf("USER_CORRECTION", "AI_CONFIRMED", "SEEDED", "MANUAL"),
            names(AliasSource.entries),
        )
    }

    @Test
    fun `CorrectionSource names are pinned`() {
        assertEquals(listOf("USER", "REINTERPRETATION"), names(CorrectionSource.entries))
    }

    @Test
    fun `CaptureSource names are pinned`() {
        assertEquals(listOf("PHONE_VOICE", "PHONE_TEXT", "WATCH_VOICE"), names(CaptureSource.entries))
    }

    @Test
    fun `InterpretationOperation names are pinned`() {
        assertEquals(
            listOf("LOG_ACTIVITY", "QUERY_HISTORY", "UNSUPPORTED"),
            names(InterpretationOperation.entries),
        )
    }

    @Test
    fun `ValidationStatus names are pinned`() {
        assertEquals(listOf("VALID", "INVALID", "NEEDS_REVIEW"), names(ValidationStatus.entries))
    }

    @Test
    fun `ConfidenceBand names are pinned`() {
        assertEquals(listOf("HIGH", "MEDIUM", "LOW"), names(ConfidenceBand.entries))
    }
}
