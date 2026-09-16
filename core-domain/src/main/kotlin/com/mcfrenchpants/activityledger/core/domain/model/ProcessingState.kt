package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Where a raw capture currently sits in the capture-to-persisted-activity pipeline.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class ProcessingState {
    CAPTURED,
    QUEUED_FOR_PHONE,
    TRANSCRIBED,
    INTERPRETING,
    INTERPRETED,
    PERSISTED,
    NEEDS_REVIEW,
    FAILED_RETRYABLE,
    FAILED_FINAL,
}
