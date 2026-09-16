package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Coarse confidence level attached to an interpretation or resolution.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class ConfidenceBand {
    HIGH,
    MEDIUM,
    LOW,
}
