package com.mcfrenchpants.activityledger.core.data.db.migration

/**
 * Version-specific raw-SQL seeds for [MigrationHarness]. Each seed is written
 * against its version's exported schema JSON and must never be edited to follow
 * later entity changes. Synthetic text only.
 */
internal object MigrationSeeds {

    /** Every seed, keyed by start version. SchemaVersionConsistencyTest checks coverage. */
    val byStartVersion: Map<Int, MigrationSeed> by lazy { listOf(V1).associateBy { it.startVersion } }

    private fun id(n: Int): String = "00000000-0000-7000-8000-%012d".format(n)

    private const val T = 1_750_000_000_000L

    // Ids: raw captures 1xx, activities 2xx, aliases 3xx, interpretations 4xx, occurrences 5xx, corrections 6xx.
    private val RC_RUN = id(101)
    private val RC_COFFEE = id(102)
    private val RC_READ = id(103)
    private val ACT_RUNNING = id(201)
    private val ACT_COFFEE = id(202)
    private val ACT_JOGGING = id(203)
    private val ALIAS_JOG = id(301)
    private val INT_RUN_FIRST = id(401)
    private val INT_RUN_SECOND = id(402)
    private val INT_COFFEE_NEW = id(403)
    private val INT_READ = id(404)
    private val OCC_RUN = id(501)
    private val OCC_COFFEE = id(502)
    private val OCC_READ_HIDDEN = id(503)
    private val CORR_RUN = id(601)

    val V1: MigrationSeed = MigrationSeed(
        startVersion = 1,
        statements = listOf(
            // raw_captures: non-ASCII text (accents, emoji), zone ids, null and non-null optionals.
            "INSERT INTO raw_captures (id, source, source_surface, captured_at, captured_zone_id, raw_text, " +
                "speech_confidence, speech_alternatives_json, processing_state, created_at, updated_at) VALUES " +
                "('$RC_RUN', 'WATCH_VOICE', 'tile', $T, 'Europe/Paris', 'Couru près du café 🏃', 0.875, " +
                "'[\"couru pres du cafe\"]', 'PERSISTED', $T, ${T + 5})",
            "INSERT INTO raw_captures (id, source, source_surface, captured_at, captured_zone_id, raw_text, " +
                "speech_confidence, speech_alternatives_json, processing_state, created_at, updated_at) VALUES " +
                "('$RC_COFFEE', 'PHONE_TEXT', NULL, ${T + 1000}, 'America/Argentina/Buenos_Aires', " +
                "'Tomé un café con ñandú ☕', NULL, NULL, 'PERSISTED', ${T + 1000}, ${T + 1005})",
            "INSERT INTO raw_captures (id, source, source_surface, captured_at, captured_zone_id, raw_text, " +
                "speech_confidence, speech_alternatives_json, processing_state, created_at, updated_at) VALUES " +
                "('$RC_READ', 'PHONE_VOICE', 'app', ${T + 2000}, 'Asia/Tokyo', 'read a book', 0.5, NULL, " +
                "'PERSISTED', ${T + 2000}, ${T + 2005})",
            // canonical_activities: a pre-existing activity, one created by NEW_ACTIVITY, one MERGED.
            "INSERT INTO canonical_activities (id, display_name, normalized_name, status, created_at, updated_at, " +
                "merged_into_activity_id) VALUES ('$ACT_RUNNING', 'Running', 'running', 'ACTIVE', ${T - 1000}, " +
                "${T - 1000}, NULL)",
            "INSERT INTO canonical_activities (id, display_name, normalized_name, status, created_at, updated_at, " +
                "merged_into_activity_id) VALUES ('$ACT_COFFEE', 'Coffee', 'coffee', 'ACTIVE', ${T + 1005}, " +
                "${T + 1005}, NULL)",
            "INSERT INTO canonical_activities (id, display_name, normalized_name, status, created_at, updated_at, " +
                "merged_into_activity_id) VALUES ('$ACT_JOGGING', 'Jogging', 'jogging', 'MERGED', ${T - 900}, " +
                "${T + 3000}, '$ACT_RUNNING')",
            "INSERT INTO activity_aliases (id, canonical_activity_id, alias_text, normalized_alias, source, " +
                "confidence, created_at) VALUES ('$ALIAS_JOG', '$ACT_RUNNING', 'Jog', 'jog', 'USER_CORRECTION', " +
                "0.75, ${T + 3000})",
            // interpretations: two attempts for the run capture, a NEW_ACTIVITY one, and one for the hidden read.
            "INSERT INTO interpretations (id, raw_capture_id, created_at, interpreter_version, prompt_version, " +
                "schema_version, operation, activity_resolution, matched_activity_id, proposed_canonical_name, " +
                "activity_state, temporal_expression, resolved_occurred_at, time_precision, model_confidence_band, " +
                "candidate_context_hash, structured_result_json, validation_status, validation_reason) VALUES " +
                "('$INT_RUN_FIRST', '$RC_RUN', ${T + 1}, 'interp-1', 'prompt-1', 1, 'LOG_ACTIVITY', " +
                "'EXISTING_ACTIVITY', '$ACT_COFFEE', NULL, 'IN_PROGRESS', 'tout à l''heure', $T, 'APPROXIMATE', " +
                "'LOW', 'hash-a', '{\"a\":1}', 'NEEDS_REVIEW', 'low confidence')",
            "INSERT INTO interpretations (id, raw_capture_id, created_at, interpreter_version, prompt_version, " +
                "schema_version, operation, activity_resolution, matched_activity_id, proposed_canonical_name, " +
                "activity_state, temporal_expression, resolved_occurred_at, time_precision, model_confidence_band, " +
                "candidate_context_hash, structured_result_json, validation_status, validation_reason) VALUES " +
                "('$INT_RUN_SECOND', '$RC_RUN', ${T + 2}, 'interp-2', 'prompt-2', 1, 'LOG_ACTIVITY', " +
                "'EXISTING_ACTIVITY', '$ACT_RUNNING', NULL, 'COMPLETED', NULL, ${T + 60_000}, 'EXACT', 'HIGH', " +
                "NULL, NULL, 'VALID', NULL)",
            "INSERT INTO interpretations (id, raw_capture_id, created_at, interpreter_version, prompt_version, " +
                "schema_version, operation, activity_resolution, matched_activity_id, proposed_canonical_name, " +
                "activity_state, temporal_expression, resolved_occurred_at, time_precision, model_confidence_band, " +
                "candidate_context_hash, structured_result_json, validation_status, validation_reason) VALUES " +
                "('$INT_COFFEE_NEW', '$RC_COFFEE', ${T + 1001}, 'interp-2', 'prompt-2', 1, 'LOG_ACTIVITY', " +
                "'NEW_ACTIVITY', NULL, 'Coffee', 'COMPLETED', NULL, ${T + 1000}, 'INFERRED_NOW', 'MEDIUM', " +
                "NULL, NULL, 'VALID', NULL)",
            "INSERT INTO interpretations (id, raw_capture_id, created_at, interpreter_version, prompt_version, " +
                "schema_version, operation, activity_resolution, matched_activity_id, proposed_canonical_name, " +
                "activity_state, temporal_expression, resolved_occurred_at, time_precision, model_confidence_band, " +
                "candidate_context_hash, structured_result_json, validation_status, validation_reason) VALUES " +
                "('$INT_READ', '$RC_READ', ${T + 2001}, 'interp-2', 'prompt-2', 1, 'LOG_ACTIVITY', " +
                "'EXISTING_ACTIVITY', '$ACT_JOGGING', NULL, 'COMPLETED', 'yesterday', ${T - 86_400_000}, " +
                "'DATE_ONLY', 'HIGH', NULL, NULL, 'VALID', NULL)",
            // activity_occurrences: a corrected one, a NEW_ACTIVITY one, a HIDDEN one.
            "INSERT INTO activity_occurrences (id, canonical_activity_id, raw_capture_id, " +
                "effective_interpretation_id, captured_at, occurred_at, time_precision, activity_state, " +
                "visibility_status, created_at, updated_at) VALUES ('$OCC_RUN', '$ACT_RUNNING', '$RC_RUN', " +
                "'$INT_RUN_SECOND', $T, ${T + 60_000}, 'EXACT', 'COMPLETED', 'ACTIVE', ${T + 5}, ${T + 4000})",
            "INSERT INTO activity_occurrences (id, canonical_activity_id, raw_capture_id, " +
                "effective_interpretation_id, captured_at, occurred_at, time_precision, activity_state, " +
                "visibility_status, created_at, updated_at) VALUES ('$OCC_COFFEE', '$ACT_COFFEE', '$RC_COFFEE', " +
                "'$INT_COFFEE_NEW', ${T + 1000}, ${T + 1000}, 'INFERRED_NOW', 'COMPLETED', 'ACTIVE', " +
                "${T + 1005}, ${T + 1005})",
            "INSERT INTO activity_occurrences (id, canonical_activity_id, raw_capture_id, " +
                "effective_interpretation_id, captured_at, occurred_at, time_precision, activity_state, " +
                "visibility_status, created_at, updated_at) VALUES ('$OCC_READ_HIDDEN', '$ACT_JOGGING', " +
                "'$RC_READ', '$INT_READ', ${T + 2000}, ${T - 86_400_000}, 'DATE_ONLY', 'COMPLETED', 'HIDDEN', " +
                "${T + 2005}, ${T + 5000})",
            // corrections: the run occurrence moved from Coffee/approximate/in-progress to Running/exact/completed.
            "INSERT INTO corrections (id, occurrence_id, created_at, source, reason, previous_canonical_activity_id, " +
                "new_canonical_activity_id, previous_occurred_at, new_occurred_at, previous_time_precision, " +
                "new_time_precision, previous_activity_state, new_activity_state, " +
                "previous_effective_interpretation_id, new_effective_interpretation_id) VALUES ('$CORR_RUN', " +
                "'$OCC_RUN', ${T + 4000}, 'USER', 'mauvaise activité', '$ACT_COFFEE', '$ACT_RUNNING', $T, " +
                "${T + 60_000}, 'APPROXIMATE', 'EXACT', 'IN_PROGRESS', 'COMPLETED', '$INT_RUN_FIRST', " +
                "'$INT_RUN_SECOND')",
        ),
        coverage = mapOf(
            "raw capture with accented text" to "SELECT COUNT(*) FROM raw_captures WHERE raw_text LIKE '%é%'",
            "two raw captures with non-ASCII text" to
                "SELECT COUNT(*) - 1 FROM raw_captures WHERE length(CAST(raw_text AS BLOB)) > length(raw_text)",
            "raw capture with an emoji" to
                "SELECT COUNT(*) FROM raw_captures WHERE instr(raw_text, '🏃') > 0 OR instr(raw_text, '☕') > 0",
            "raw capture with a captured_zone_id" to
                "SELECT COUNT(*) FROM raw_captures WHERE captured_zone_id <> 'UTC'",
            "capture with two interpretations" to
                "SELECT COUNT(*) FROM (SELECT raw_capture_id FROM interpretations GROUP BY raw_capture_id " +
                "HAVING COUNT(*) >= 2)",
            "NEW_ACTIVITY-created activity with occurrence" to
                "SELECT COUNT(*) FROM activity_occurrences o JOIN interpretations i " +
                "ON i.id = o.effective_interpretation_id WHERE i.activity_resolution = 'NEW_ACTIVITY'",
            "existing activity used by an occurrence" to
                "SELECT COUNT(*) FROM activity_occurrences o JOIN canonical_activities a " +
                "ON a.id = o.canonical_activity_id WHERE a.created_at < o.captured_at",
            "alias" to "SELECT COUNT(*) FROM activity_aliases",
            "corrected occurrence with previous/new activity and time" to
                "SELECT COUNT(*) FROM corrections c JOIN activity_occurrences o ON o.id = c.occurrence_id " +
                "WHERE c.previous_canonical_activity_id IS NOT NULL AND c.new_canonical_activity_id IS NOT NULL " +
                "AND c.previous_occurred_at IS NOT NULL AND c.new_occurred_at IS NOT NULL",
            "HIDDEN occurrence" to "SELECT COUNT(*) FROM activity_occurrences WHERE visibility_status = 'HIDDEN'",
            "MERGED activity" to
                "SELECT COUNT(*) FROM canonical_activities WHERE status = 'MERGED' " +
                "AND merged_into_activity_id IS NOT NULL",
        ),
        rawCaptureProbeId = RC_RUN,
        occurrenceProbeId = OCC_RUN,
    )
}
