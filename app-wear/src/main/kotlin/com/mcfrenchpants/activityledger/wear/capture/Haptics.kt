package com.mcfrenchpants.activityledger.wear.capture

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** UX_VISUAL_SPEC 4.6 haptic vocabulary. */
enum class HapticPattern { NONE, TICK, DOUBLE_TICK, LONG_PULSE }

/** The pattern for arriving in [state]. */
fun hapticFor(state: CaptureUiState): HapticPattern = when (state) {
    is CaptureUiState.Listening -> HapticPattern.NONE
    CaptureUiState.Queued -> HapticPattern.TICK
    is CaptureUiState.Saved -> HapticPattern.DOUBLE_TICK
    CaptureUiState.NeedsReview -> HapticPattern.DOUBLE_TICK
    CaptureUiState.Failure -> HapticPattern.LONG_PULSE
    is CaptureUiState.Unavailable -> HapticPattern.NONE
}

/**
 * Fires only on a transition into a different kind of state: partial updates, repeats of the
 * same state and Saved-to-Saved name changes play nothing.
 */
fun hapticOnTransition(previous: CaptureUiState?, current: CaptureUiState): HapticPattern =
    if (previous != null && previous::class == current::class) HapticPattern.NONE else hapticFor(current)

interface HapticPlayer {
    fun play(p: HapticPattern)
}

/** System-vibrator player. Never throws. */
class VibratorHapticPlayer(context: Context) : HapticPlayer {
    private val appContext: Context = context.applicationContext

    override fun play(p: HapticPattern) {
        try {
            val timings = when (p) {
                HapticPattern.NONE -> return
                HapticPattern.TICK -> longArrayOf(0, 30)
                HapticPattern.DOUBLE_TICK -> longArrayOf(0, 30, 80, 30)
                HapticPattern.LONG_PULSE -> longArrayOf(0, 300)
            }
            val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: appContext.getSystemService(Vibrator::class.java)
                ?: return
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (expected: Exception) {
            // Haptics are a nicety; never let them break capture.
        }
    }
}
