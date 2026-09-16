package com.mcfrenchpants.activityledger.core.domain.model

/**
 * The device and input method a raw capture was recorded with.
 *
 * Constant names are persisted as text: renaming or removing a constant is a data migration.
 */
enum class CaptureSource {
    PHONE_VOICE,
    PHONE_TEXT,
    WATCH_VOICE,
}
