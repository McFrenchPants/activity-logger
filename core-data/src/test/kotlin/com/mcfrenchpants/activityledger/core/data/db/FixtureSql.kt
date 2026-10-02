package com.mcfrenchpants.activityledger.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity

/**
 * Test-only raw SQL inserts. They bypass the production data-access API on
 * purpose, so tests can create states that API deliberately cannot (a dangling
 * foreign key, a HIDDEN occurrence, a hand-made correction row). Enums are
 * written as their constant name, exactly as EnumConverters stores them.
 */
internal fun SupportSQLiteDatabase.insertRow(table: String, values: Map<String, Any?>) {
    val columns = values.keys.joinToString(", ")
    val placeholders = values.keys.joinToString(", ") { "?" }
    val args = values.values.map { if (it is Enum<*>) it.name else it }.toTypedArray()
    execSQL("INSERT INTO $table ($columns) VALUES ($placeholders)", args)
}

internal fun SupportSQLiteDatabase.insertRawCapture(row: RawCaptureEntity) = insertRow(
    "raw_captures",
    mapOf(
        "id" to row.id, "source" to row.source, "source_surface" to row.sourceSurface,
        "captured_at" to row.capturedAt, "captured_zone_id" to row.capturedZoneId, "raw_text" to row.rawText,
        "speech_confidence" to row.speechConfidence, "speech_alternatives_json" to row.speechAlternativesJson,
        "processing_state" to row.processingState, "created_at" to row.createdAt, "updated_at" to row.updatedAt,
    ),
)

internal fun SupportSQLiteDatabase.insertCanonicalActivity(row: CanonicalActivityEntity) = insertRow(
    "canonical_activities",
    mapOf(
        "id" to row.id, "display_name" to row.displayName, "normalized_name" to row.normalizedName,
        "status" to row.status, "created_at" to row.createdAt, "updated_at" to row.updatedAt,
        "merged_into_activity_id" to row.mergedIntoActivityId,
        "subject_id" to row.subjectId, "action_id" to row.actionId,
    ),
)

internal fun SupportSQLiteDatabase.insertActivityAlias(row: ActivityAliasEntity) = insertRow(
    "activity_aliases",
    mapOf(
        "id" to row.id, "canonical_activity_id" to row.canonicalActivityId, "alias_text" to row.aliasText,
        "normalized_alias" to row.normalizedAlias, "source" to row.source, "confidence" to row.confidence,
        "created_at" to row.createdAt,
    ),
)

internal fun SupportSQLiteDatabase.insertInterpretation(row: InterpretationEntity) = insertRow(
    "interpretations",
    mapOf(
        "id" to row.id, "raw_capture_id" to row.rawCaptureId, "created_at" to row.createdAt,
        "interpreter_version" to row.interpreterVersion, "prompt_version" to row.promptVersion,
        "schema_version" to row.schemaVersion, "operation" to row.operation,
        "activity_resolution" to row.activityResolution, "matched_activity_id" to row.matchedActivityId,
        "proposed_canonical_name" to row.proposedCanonicalName, "activity_state" to row.activityState,
        "temporal_expression" to row.temporalExpression, "resolved_occurred_at" to row.resolvedOccurredAt,
        "time_precision" to row.timePrecision, "model_confidence_band" to row.modelConfidenceBand,
        "candidate_context_hash" to row.candidateContextHash, "structured_result_json" to row.structuredResultJson,
        "validation_status" to row.validationStatus, "validation_reason" to row.validationReason,
    ),
)

internal fun SupportSQLiteDatabase.insertActivityOccurrence(row: ActivityOccurrenceEntity) = insertRow(
    "activity_occurrences",
    mapOf(
        "id" to row.id, "canonical_activity_id" to row.canonicalActivityId, "raw_capture_id" to row.rawCaptureId,
        "effective_interpretation_id" to row.effectiveInterpretationId, "captured_at" to row.capturedAt,
        "occurred_at" to row.occurredAt, "time_precision" to row.timePrecision,
        "activity_state" to row.activityState, "visibility_status" to row.visibilityStatus,
        "created_at" to row.createdAt, "updated_at" to row.updatedAt,
    ),
)

internal fun SupportSQLiteDatabase.insertCorrection(row: CorrectionEntity) = insertRow(
    "corrections",
    mapOf(
        "id" to row.id, "occurrence_id" to row.occurrenceId, "created_at" to row.createdAt,
        "source" to row.source, "reason" to row.reason,
        "previous_canonical_activity_id" to row.previousCanonicalActivityId,
        "new_canonical_activity_id" to row.newCanonicalActivityId,
        "previous_occurred_at" to row.previousOccurredAt, "new_occurred_at" to row.newOccurredAt,
        "previous_time_precision" to row.previousTimePrecision, "new_time_precision" to row.newTimePrecision,
        "previous_activity_state" to row.previousActivityState, "new_activity_state" to row.newActivityState,
        "previous_effective_interpretation_id" to row.previousEffectiveInterpretationId,
        "new_effective_interpretation_id" to row.newEffectiveInterpretationId,
    ),
)

/** Number of rows in [table] matching [where] (a trusted, test-authored SQL fragment). */
internal fun SupportSQLiteDatabase.count(table: String, where: String = "1", vararg args: Any?): Int =
    query("SELECT COUNT(*) FROM $table WHERE $where", args).use { c ->
        check(c.moveToFirst())
        c.getInt(0)
    }
