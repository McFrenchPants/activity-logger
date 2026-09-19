package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-side (Robolectric) checks of the shared components' visible text. */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stateTagsShowTheirLabels() {
        composeRule.setContent {
            ActivityLedgerTheme {
                Column {
                    RowState.entries.forEach { StateTag(it) }
                }
            }
        }
        mapOf(
            RowState.IN_PROGRESS to "In progress",
            RowState.NEEDS_REVIEW to "Needs review",
            RowState.NOT_CATEGORIZED to "Not categorized yet",
            RowState.CAPTURE_FAILED to "Couldn't capture",
        ).forEach { (state, label) ->
            composeRule.onNodeWithTag("StateTag:${state.name}").assertIsDisplayed()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun evidenceIsCurlyQuotedAndUntruncated() {
        val words = "mowed the lawn and then ".repeat(20) + "fixed the fence"
        composeRule.setContent {
            ActivityLedgerTheme { EvidenceText(words) }
        }
        composeRule.onNodeWithText("“$words”").assertIsDisplayed()
    }

    @Test
    fun quoteEvidenceUsesCurlyQuotes() {
        assertEquals("“walked the dog”", quoteEvidence("walked the dog"))
    }
}
