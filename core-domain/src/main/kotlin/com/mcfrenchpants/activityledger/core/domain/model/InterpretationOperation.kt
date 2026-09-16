package com.mcfrenchpants.activityledger.core.domain.model

/**
 * The kind of request an interpretation determined a capture to be.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class InterpretationOperation {
    LOG_ACTIVITY,
    QUERY_HISTORY,
    UNSUPPORTED,
}
