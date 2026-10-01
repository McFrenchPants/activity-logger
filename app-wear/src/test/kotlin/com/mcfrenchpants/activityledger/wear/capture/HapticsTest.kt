package com.mcfrenchpants.activityledger.wear.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {

    @Test
    fun mapping() {
        assertEquals(HapticPattern.NONE, hapticFor(CaptureUiState.Listening("x")))
        assertEquals(HapticPattern.TICK, hapticFor(CaptureUiState.Queued))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticFor(CaptureUiState.Saved("Run")))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticFor(CaptureUiState.NeedsReview))
        assertEquals(HapticPattern.LONG_PULSE, hapticFor(CaptureUiState.Failure))
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
        assertEquals(HapticPattern.TICK, hapticOnTransition(listening, CaptureUiState.Queued))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Queued, CaptureUiState.Queued))
        assertEquals(HapticPattern.DOUBLE_TICK, hapticOnTransition(CaptureUiState.Queued, CaptureUiState.Saved("A")))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Saved("A"), CaptureUiState.Saved("B")))
        assertEquals(HapticPattern.LONG_PULSE, hapticOnTransition(listening, CaptureUiState.Failure))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Failure, CaptureUiState.Failure))
        assertEquals(HapticPattern.NONE, hapticOnTransition(CaptureUiState.Failure, listening))
    }
}
