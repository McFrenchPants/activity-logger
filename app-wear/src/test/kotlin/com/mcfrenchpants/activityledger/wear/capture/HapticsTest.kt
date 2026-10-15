package com.mcfrenchpants.activityledger.wear.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {

    @Test
    fun mapping() {
        assertEquals(HapticPattern.NONE, hapticFor(CaptureUiState.Listening("x")))
        assertEquals(HapticPattern.TICK, hapticFor(CaptureUiState.Saving))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticFor(CaptureUiState.Saved("Run")))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticFor(CaptureUiState.NeedsReview))
        assertEquals(HapticPattern.LONG_PULSE, hapticFor(CaptureUiState.Failure))
        assertEquals(HapticPattern.NONE, hapticFor(CaptureUiState.Ready))
        assertEquals(
            HapticPattern.NONE,
            hapticFor(CaptureUiState.Unavailable(UnavailableReason.NO_ENGINE)),
        )
    }

    @Test
    fun firesOnlyOnTransitionIntoState() {
        val listening = CaptureUiState.Listening(null)
        assertEquals(HapticPattern.NONE, hapticOnTransition(null, listening))
        assertEquals(HapticPattern.NONE, hapticOnTransition(listening, CaptureUiState.Listening("partial")))
        assertEquals(HapticPattern.TICK, hapticOnTransition(listening, CaptureUiState.Saving))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Saving, CaptureUiState.Saving))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticOnTransition(CaptureUiState.Saving, CaptureUiState.Saved("A")))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Saved("A"), CaptureUiState.Saved("B")))
        assertEquals(HapticPattern.LONG_PULSE, hapticOnTransition(listening, CaptureUiState.Failure))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Failure, CaptureUiState.Failure))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Failure, listening))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Saving, CaptureUiState.Ready))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Saved("A"), CaptureUiState.Ready))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Ready, listening))
    }
}
