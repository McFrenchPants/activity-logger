package com.mcfrenchpants.activityledger.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Is one of the phone app's activities resumed right now?" -- the foreground signal the watch
 * receiver and the foreground catch-up use (ADR-029).
 *
 * Resumed, not merely started: the on-device model only answers the top foreground app, and a
 * refusal is recorded as a failed interpretation and sent to review (never retried), so work must
 * not start in the started-but-not-resumed gap.
 *
 * [onResumed] and [onPaused] are called from the main thread (activity lifecycle callbacks).
 * Android pairs them per activity instance -- a configuration change pauses the old instance
 * before the new one resumes -- so the count stays balanced; an unmatched pause is ignored rather
 * than letting the count go negative and stick in the foreground afterwards. [isForeground] may
 * be read from any thread.
 */
class ForegroundSignal {
    private var resumed = 0
    private val foreground = MutableStateFlow(false)

    /** True while at least one activity is resumed. */
    val isForeground: StateFlow<Boolean> = foreground.asStateFlow()

    /** An activity resumed. Returns true exactly on the background -> foreground transition. */
    fun onResumed(): Boolean {
        resumed++
        if (resumed != 1) return false
        foreground.value = true
        return true
    }

    /** An activity paused. The count never goes below zero. */
    fun onPaused() {
        if (resumed > 0) resumed--
        if (resumed == 0) foreground.value = false
    }
}
