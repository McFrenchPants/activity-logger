package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Lifecycle status of a canonical activity in the catalogue.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class CanonicalActivityStatus {
    ACTIVE,
    MERGED,
    ARCHIVED,
}
