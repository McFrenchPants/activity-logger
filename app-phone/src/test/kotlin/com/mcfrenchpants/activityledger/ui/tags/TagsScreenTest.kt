package com.mcfrenchpants.activityledger.ui.tags

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Host-side (Robolectric) test of the Tags screen over a real [TagsViewModel] and the real
 * in-memory ledger. Robolectric's SDK is pinned in src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class TagsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val world = TagsWorld()

    private fun showTags(): TagsViewModel {
        val vm = TagsViewModel(world.service)
        composeRule.setContent {
            ActivityLedgerTheme {
                TagsScreen(viewModel = vm)
            }
        }
        composeRule.waitForIdle()
        return vm
    }

    private fun seed() {
        world.log("Hot tub", "Change filter")
        world.log("Hot tub", "Clean")
        world.log("Furnace", "Change filter")
    }

    @Test
    fun rowsShowNameUsageAndTheSelectorSwitchesKinds() {
        seed()
        showTags()
        composeRule.onNodeWithTag(tagsKindTag(TagKind.SUBJECT)).assertIsSelected()
        composeRule.onNodeWithText("Hot tub").assertIsDisplayed()
        composeRule.onNodeWithText("Used with 2 actions").assertIsDisplayed()
        composeRule.onNodeWithText("Used with 1 action").assertIsDisplayed()
        composeRule.onNodeWithText("Clean").assertDoesNotExist()

        composeRule.onNodeWithTag(tagsKindTag(TagKind.ACTION)).performClick()
        composeRule.onNodeWithTag(tagsKindTag(TagKind.ACTION)).assertIsSelected()
        composeRule.onNodeWithText("Clean").assertIsDisplayed()
        composeRule.onNodeWithText("Used with 2 subjects").assertIsDisplayed()
        composeRule.onNodeWithText("Hot tub").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Show subjects or actions")).assertExists()
    }

    @Test
    fun emptyStateIsFriendly() {
        showTags()
        composeRule.onNodeWithTag(TAGS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No subjects yet. They show up here as you log things.").assertIsDisplayed()
    }

    @Test
    fun renameDialogStatesAndAliasLine() {
        seed()
        showTags()
        val furnace = world.subjectId("Furnace")
        composeRule.onNodeWithTag(tagRenameTag(furnace)).performClick()
        composeRule.onNodeWithTag(RENAME_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).assert(hasText("Furnace"))
        composeRule.onNodeWithTag(RENAME_SAVE_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        composeRule.onNodeWithTag(RENAME_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Boiler")
        composeRule.onNodeWithTag(RENAME_SAVE_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RENAME_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Renamed.").assertIsDisplayed()
        composeRule.onNodeWithText("Boiler").assertIsDisplayed()
        composeRule.onNodeWithText("Also called: Furnace").assertIsDisplayed()
    }

    @Test
    fun nameInUseNamesTheOtherAndOffersMerge() {
        seed()
        showTags()
        composeRule.onNodeWithTag(tagRenameTag(world.subjectId("Furnace"))).performClick()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Hot tub")
        composeRule.onNodeWithTag(RENAME_SAVE_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Another subject is already called “Hot tub”.").assertIsDisplayed()
        composeRule.onNodeWithTag(RENAME_MERGE_INSTEAD_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MERGE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
    }

    @Test
    fun invalidNameShowsPlainMessageInTheDialog() {
        seed()
        showTags()
        composeRule.onNodeWithTag(tagRenameTag(world.subjectId("Furnace"))).performClick()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("1234")
        composeRule.onNodeWithTag(RENAME_SAVE_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RENAME_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("A name can't be only numbers. Add a word.").assertIsDisplayed()
    }

    @Test
    fun mergeChooserConfirmationAndResult() {
        world.log("Hot tub", "Change filter")
        world.log("Spa", "Change filter")
        world.log("Furnace", "Change filter")
        showTags()
        val spa = world.subjectId("Spa")
        val hotTub = world.subjectId("Hot tub")
        composeRule.onNodeWithTag(tagMergeTag(spa)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MERGE_CHOOSER_TAG).assertIsDisplayed()
        // Other tags only; no "new name" option.
        composeRule.onNodeWithTag(mergeTargetTag(spa)).assertDoesNotExist()
        composeRule.onNodeWithTag(mergeTargetTag(hotTub)).assertIsDisplayed()
        composeRule.onNode(hasText("New", substring = true) and hasText("subject", substring = true)).assertDoesNotExist()

        composeRule.onNodeWithTag(MERGE_CHOOSER_SEARCH_TAG).performTextInput("furn")
        composeRule.onNodeWithTag(mergeTargetTag(hotTub)).assertDoesNotExist()
        composeRule.onNodeWithTag(MERGE_CHOOSER_SEARCH_TAG).performTextClearance()

        composeRule.onNodeWithTag(mergeTargetTag(hotTub)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MERGE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Everything logged under “Spa” will count as “Hot tub” instead. " +
                "Your original words are kept. This can't be undone yet.",
        ).assertIsDisplayed()
        assertEquals(0, world.failing.mergeCalls)

        composeRule.onNodeWithTag(MERGE_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MERGE_CONFIRM_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Merged. 1 entry moved.").assertIsDisplayed()
        composeRule.onNodeWithText("Spa").assertDoesNotExist()
    }

    @Test
    fun cancellingTheConfirmationWritesNothing() {
        seed()
        showTags()
        composeRule.onNodeWithTag(tagMergeTag(world.subjectId("Furnace"))).performClick()
        composeRule.onNodeWithTag(mergeTargetTag(world.subjectId("Hot tub"))).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MERGE_CANCEL_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MERGE_CONFIRM_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Furnace").assertIsDisplayed()
        assertEquals(0, world.failing.mergeCalls)
    }

    @Test
    fun loadFailureShowsRetry() {
        seed()
        world.failing.failCatalog = true
        showTags()
        composeRule.onNodeWithText("Couldn't load your subjects and actions. Try again.").assertIsDisplayed()
        world.failing.failCatalog = false
        composeRule.onNodeWithTag(TAGS_RETRY_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Furnace").assertIsDisplayed()
        composeRule.onNodeWithTag(TAGS_RETRY_TAG).assertDoesNotExist()
    }

    @Test
    fun longAliasListIsCappedAtThree() {
        world.log("Hot tub", "Change filter")
        showTags()
        val id = world.subjectId("Hot tub")
        for (name in listOf("Spa", "Jacuzzi", "Whirlpool", "Pool", "Tub")) {
            composeRule.onNodeWithTag(tagRenameTag(id)).performClick()
            composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
            composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput(name)
            composeRule.onNodeWithTag(RENAME_SAVE_TAG).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNode(hasText("Also called: ", substring = true) and hasText("more", substring = true)).assertIsDisplayed()
    }
}
