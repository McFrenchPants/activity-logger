package com.mcfrenchpants.activityledger.core.domain.model

/**
 * What originated a correction to previously persisted interpretation data.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class CorrectionSource {
    USER,
    REINTERPRETATION,
}
