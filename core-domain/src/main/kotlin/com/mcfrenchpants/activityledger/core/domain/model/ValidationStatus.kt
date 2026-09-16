package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Outcome of validating an AI interpretation before any of it is trusted or persisted.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class ValidationStatus {
    VALID,
    INVALID,
    NEEDS_REVIEW,
}
