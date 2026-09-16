package com.mcfrenchpants.activityledger.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SCAFFOLD PLACEHOLDER TEST -- no product meaning.
 *
 * Proves only that this module's unit-test source set compiles and runs. Delete it
 * with the placeholders it covers.
 */
class ScaffoldPlaceholderTest {

    @Test
    fun `marker is the scaffold marker`() {
        assertEquals("core-ai scaffold placeholder", ScaffoldPlaceholder.marker())
    }

    @Test
    fun `scaffold reports no capability`() {
        assertFalse(ScaffoldPlaceholder.isScaffoldCapabilityAvailable())
    }

    @Test
    fun `generable placeholder holds its value`() {
        assertEquals("x", ScaffoldPlaceholderGenerable("x").placeholder)
    }
}
