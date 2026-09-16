package com.mcfrenchpants.activityledger.core.domain.model

/**
 * Where an alias for a canonical activity came from.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class AliasSource {
    USER_CORRECTION,
    AI_CONFIRMED,
    SEEDED,
    MANUAL,
}
