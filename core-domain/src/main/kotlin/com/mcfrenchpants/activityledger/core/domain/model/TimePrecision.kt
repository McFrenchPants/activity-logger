package com.mcfrenchpants.activityledger.core.domain.model

/**
 * How precisely an activity's occurrence time is known.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class TimePrecision {
    EXACT,
    APPROXIMATE,
    DATE_ONLY,
    INFERRED_NOW,
}
