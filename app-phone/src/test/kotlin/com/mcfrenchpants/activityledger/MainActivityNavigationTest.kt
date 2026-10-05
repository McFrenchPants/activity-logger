package com.mcfrenchpants.activityledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.ui.explore.EXPLORE_SCREEN_TAG
import com.mcfrenchpants.activityledger.ui.history.HISTORY_SCREEN_TAG
import com.mcfrenchpants.activityledger.ui.log.LOG_SCREEN_TAG
import com.mcfrenchpants.activityledger.ui.tags.TAGS_SCREEN_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-side (Robolectric, no device) test of the app shell's navigation: Log is the start
 * destination, the navigation bar switches to History, and system Back returns to Log.
 * Robolectric's SDK is pinned in src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val logTab get() = composeRule.onNode(hasText("Log") and isSelectable())
    private val historyTab get() = composeRule.onNode(hasText("History") and isSelectable())
    private val tagsTab get() = composeRule.onNode(hasText("Tags") and isSelectable())

    @Test
    fun startsOnLogWithAllFourLabelsVisible() {
        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertIsDisplayed()
        logTab.assertIsDisplayed().assertIsSelected()
        historyTab.assertIsDisplayed().assertIsNotSelected()
        tagsTab.assertIsDisplayed().assertIsNotSelected()
        composeRule.onNode(hasText("Explore") and isSelectable()).assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun tagsTabShowsTagsAndBackReturnsToLog() {
        tagsTab.performClick()
        composeRule.onNodeWithTag(TAGS_SCREEN_TAG).assertIsDisplayed()
        tagsTab.assertIsSelected()
        historyTab.assertIsNotSelected()
        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertDoesNotExist()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertIsDisplayed()
        logTab.assertIsSelected()
    }

    @Test
    fun historyTabShowsHistoryAndBackReturnsToLog() {
        historyTab.performClick()
        composeRule.onNodeWithTag(HISTORY_SCREEN_TAG).assertIsDisplayed()
        historyTab.assertIsSelected()
        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertDoesNotExist()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertIsDisplayed()
        logTab.assertIsSelected()
        composeRule.onNodeWithTag(HISTORY_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun exploreTabIsTheFourthTabAndOpensExplore() {
        val exploreTab = composeRule.onNode(hasText("Explore") and isSelectable())
        exploreTab.assertIsDisplayed().assertIsNotSelected()
        composeRule.onNode(hasText("Ask") and isSelectable()).assertDoesNotExist()
        exploreTab.performClick()
        composeRule.onNodeWithTag(EXPLORE_SCREEN_TAG).assertIsDisplayed()
        exploreTab.assertIsSelected()
        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertDoesNotExist()
        // Order: Log, History, Tags, Explore (left to right).
        val lefts = listOf("Log", "History", "Tags", "Explore").map {
            composeRule.onNode(hasText(it) and isSelectable()).fetchSemanticsNode().boundsInRoot.left
        }
        assert(lefts == lefts.sorted()) { "tabs are not in the order Log, History, Tags, Explore: $lefts" }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOG_SCREEN_TAG).assertIsDisplayed()
    }
}
