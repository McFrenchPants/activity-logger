package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Lifecycle status of a subject or action tag in the tag catalogue.
 *
 * A MERGED tag points at the tag it was merged into and is no longer offered for
 * matching; its history still references it.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class TagStatus {
    ACTIVE,
    MERGED,
}
