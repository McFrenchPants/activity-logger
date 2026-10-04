package com.mcfrenchpants.activityledger.ui.components

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-side (Robolectric) checks of the shared tag picker's body and filter. */
@RunWith(AndroidJUnit4::class)
class TagPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val subjects = listOf(
        KnownTag("s1", TagKind.SUBJECT, "Hot tub", emptyList()),
        KnownTag("s2", TagKind.SUBJECT, "Furnace", listOf("HVAC")),
    )

    private val actions = listOf(
        KnownTag("a1", TagKind.ACTION, "Change filter", emptyList()),
    )

    @Test
    fun filterMatchesNamesAndAliasesCaseInsensitively() {
        assertEquals(listOf("Hot tub"), filterTags(subjects, " TUB ").map { it.displayName })
        assertEquals(listOf("Furnace"), filterTags(subjects, "hvac").map { it.displayName })
        assertEquals(subjects, filterTags(subjects, ""))
        assertEquals(emptyList<KnownTag>(), filterTags(subjects, "zzz"))
    }

    @Test
    fun choosingAnExistingTagReturnsItsId() {
        var chosen: TagChoice? = null
        composeRule.setContent {
            ActivityLedgerTheme { TagPickerContent(TagKind.SUBJECT, subjects, onChoose = { chosen = it }) }
        }
        composeRule.onNodeWithTag(TAG_PICKER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Choose a subject").assertIsDisplayed()
        composeRule.onNodeWithText("Furnace").performClick()
        assertEquals(TagChoice.Existing("s2"), chosen)
    }

    @Test
    fun searchingByAnAliasFindsTheTag() {
        composeRule.setContent {
            ActivityLedgerTheme { TagPickerContent(TagKind.SUBJECT, subjects, onChoose = {}) }
        }
        composeRule.onNodeWithText("Search subjects").performTextInput("hvac")
        composeRule.onNodeWithText("Furnace").assertIsDisplayed()
        composeRule.onNodeWithText("Hot tub").assertDoesNotExist()
    }

    @Test
    fun aNewNamePrefillsTheSearchTextAndReturnsANewChoice() {
        var chosen: TagChoice? = null
        composeRule.setContent {
            ActivityLedgerTheme { TagPickerContent(TagKind.SUBJECT, subjects, onChoose = { chosen = it }) }
        }
        composeRule.onNodeWithText("Search subjects").performTextInput("Pool")
        composeRule.onNodeWithText("No matching subjects.").assertExists()
        composeRule.onNodeWithText("New subject").performClick()
        composeRule.onNodeWithText("Create").performClick()
        assertEquals(TagChoice.New("Pool"), chosen)
    }

    @Test
    fun theActionPickerUsesActionWordingAndCanStartOnTheNewNameField() {
        var chosen: TagChoice? = null
        composeRule.setContent {
            ActivityLedgerTheme {
                TagPickerContent(TagKind.ACTION, actions, onChoose = { chosen = it }, startWithNewName = true)
            }
        }
        composeRule.onNodeWithText("Choose an action").assertIsDisplayed()
        composeRule.onNodeWithText("Action name").assertIsDisplayed()
        // An empty name cannot be created.
        composeRule.onNodeWithText("Create").performClick()
        assertNull(chosen)
        composeRule.onNodeWithText("Action name").performTextInput("Drain")
        composeRule.onNodeWithText("Create").performClick()
        assertEquals(TagChoice.New("Drain"), chosen)
    }

    @Test
    fun rowsAreClickableAndAtLeastAsTallAsATouchTarget() {
        composeRule.setContent {
            ActivityLedgerTheme { TagPickerContent(TagKind.SUBJECT, subjects, onChoose = {}) }
        }
        composeRule.onNode(hasClickAction() and hasText("Hot tub")).assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasClickAction() and hasText("New subject")).assertHeightIsAtLeast(48.dp)
    }
}
