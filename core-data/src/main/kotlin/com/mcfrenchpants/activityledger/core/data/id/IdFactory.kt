package com.mcfrenchpants.activityledger.core.data.id

/**
 * Source of primary-key identifiers for core-data rows.
 *
 * Injected rather than called statically so tests can substitute a deterministic
 * implementation.
 */
internal fun interface IdFactory {
    /** Returns a new identifier as canonical lowercase 36-character UUID text. */
    fun newId(): String
}
