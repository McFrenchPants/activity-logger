package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Whether a record is shown to the user or hidden (soft-removed) from normal views.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class VisibilityStatus {
    ACTIVE,
    HIDDEN,
}
