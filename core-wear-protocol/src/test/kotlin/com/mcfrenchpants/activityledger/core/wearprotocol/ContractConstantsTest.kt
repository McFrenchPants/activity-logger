package com.mcfrenchpants.activityledger.core.wearprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractConstantsTest {
    @Test
    fun dataItemKeysAndPrefixAreStable() {
        assertEquals("envelope", CAPTURE_DATA_KEY)
        assertEquals("attempt", CAPTURE_ATTEMPT_KEY)
        assertEquals("/capture/", CAPTURE_PATH_PREFIX_WITH_SLASH)
        assertEquals("$CAPTURE_PATH_PREFIX/", CAPTURE_PATH_PREFIX_WITH_SLASH)
        assertTrue(capturePath("abc").startsWith(CAPTURE_PATH_PREFIX_WITH_SLASH))
    }

    @Test
    fun ackPathDoesNotMatchTheCapturePrefix() {
        assertFalse(CAPTURE_ACK_PATH.startsWith(CAPTURE_PATH_PREFIX_WITH_SLASH))
    }
}
