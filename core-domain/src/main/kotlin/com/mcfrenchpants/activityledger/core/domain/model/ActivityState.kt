package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Whether a logged activity occurrence has finished or is still underway.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class ActivityState {
    COMPLETED,
    IN_PROGRESS,
}
