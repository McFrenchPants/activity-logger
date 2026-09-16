package com.mcfrenchpants.activityledger.core.domain.model

/**
 * How an interpreted activity mention was matched against the canonical activity catalogue.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class ActivityResolution {
    EXISTING_ACTIVITY,
    NEW_ACTIVITY,
    AMBIGUOUS,
    UNRESOLVED,
}
