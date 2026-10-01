package com.mcfrenchpants.activityledger.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineAssuranceTest {

    private val yes = OfflineAssurance.ON_DEVICE_CONFIRMED
    private val no = OfflineAssurance.NOT_CONFIRMED

    @Test
    fun exactMatchIsConfirmed() = assertEquals(yes, offlineAssuranceOf(listOf("en-US"), "en-US"))

    @Test
    fun caseDifferenceIsConfirmed() =
        assertEquals(yes, offlineAssuranceOf(listOf("EN-us"), "en-US"))

    @Test
    fun installedBareLanguageSatisfiesRegionalTag() =
        assertEquals(yes, offlineAssuranceOf(listOf("en"), "en-AU"))

    @Test
    fun installedRegionDoesNotSatisfyOtherRegion() =
        assertEquals(no, offlineAssuranceOf(listOf("en-US"), "en-AU"))

    @Test
    fun noMatchIsNotConfirmed() = assertEquals(no, offlineAssuranceOf(listOf("fr-FR", "de"), "en-US"))

    @Test
    fun emptyListIsNotConfirmed() = assertEquals(no, offlineAssuranceOf(emptyList(), "en-US"))

    @Test
    fun nullTagIsNotConfirmed() = assertEquals(no, offlineAssuranceOf(listOf("en-US"), null))
}
