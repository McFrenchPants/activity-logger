package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-side (Robolectric) checks of the shared activity picker's body. */
@RunWith(AndroidJUnit4::class)
class ActivityPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val catalog = listOf(
        CatalogActivity("a1", "Mow lawn", "mow lawn", emptyList(), null),
        CatalogActivity("a2", "Walk dog", "walk dog", emptyList(), null),
    )

    @Test
    fun searchFiltersCaseInsensitivelyByDisplayName() {
        assertEquals(listOf("Walk dog"), filterActivities(catalog, " DOG ").map { it.displayName })
        assertEquals(catalog, filterActivities(catalog, ""))
    }

    @Test
    fun choosingAnExistingActivityReturnsIt() {
        var chosen: ActivityTarget? = null
        composeRule.setContent {
            ActivityLedgerTheme { ActivityPickerContent(activities = catalog, onChoose = { chosen = it }) }
        }
        composeRule.onNodeWithText("Walk dog").performClick()
        assertEquals(ActivityTarget.Existing("a2"), chosen)
    }

    @Test
    fun newActivityPrefillsTheSearchTextAndReturnsANewTarget() {
        var chosen: ActivityTarget? = null
        composeRule.setContent {
            ActivityLedgerTheme { ActivityPickerContent(activities = catalog, onChoose = { chosen = it }) }
        }
        composeRule.onNodeWithText("Search activities").performTextInput("Trim hedge")
        composeRule.onNodeWithText("No matching activities.").assertExists()
        composeRule.onNodeWithText("New activity").performClick()
        composeRule.onNodeWithText("Create").performClick()
        assertEquals(ActivityTarget.New("Trim hedge"), chosen)
    }
}
