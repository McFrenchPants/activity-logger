package com.mcfrenchpants.activityledger.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Host-side (Robolectric) checks of the Saved card and the Check card, driven from fixed state. */
@RunWith(AndroidJUnit4::class)
class LogCardsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val furnace = KnownTag("s1", TagKind.SUBJECT, "Furnace", listOf("HVAC"))
    private val boiler = KnownTag("s2", TagKind.SUBJECT, "Boiler", emptyList())

    private var card: ResultCard by mutableStateOf(saved(durationSeconds = null))
    private val events = mutableListOf<String>()

    private fun saved(durationSeconds: Long?) = ResultCard.Saved(
        captureId = "c1",
        rawText = "words",
        occurrenceId = "o1",
        subjectName = "Hot tub",
        actionName = "Change filter",
        durationSeconds = durationSeconds,
        time = "just now",
    )

    private fun check(
        subject: CheckSide = CheckSide(TagKind.SUBJECT),
        action: CheckSide = CheckSide(TagKind.ACTION),
        durationSeconds: Long? = null,
    ) = ResultCard.Check("c1", "words", subject, action, durationSeconds = durationSeconds)

    private fun chosen(kind: TagKind, name: String, assumed: Boolean = false, new: Boolean = false) = CheckSide(
        kind = kind,
        chosen = ChosenTag(if (new) TagChoice.New(name) else TagChoice.Existing("id-$name"), name),
        assumed = assumed,
    )

    private fun show(initial: ResultCard) {
        card = initial
        composeRule.setContent {
            ActivityLedgerTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (val shown = card) {
                        is ResultCard.Saved -> SavedCard(
                            card = shown,
                            enabled = true,
                            onUndo = { events += "undo" },
                            onChangeSubject = { events += "changeSubject" },
                            onChangeAction = { events += "changeAction" },
                            onTouched = {},
                        )
                        is ResultCard.Check -> CheckCard(
                            card = shown,
                            enabled = true,
                            onChooseCandidate = { kind, id -> events += "candidate:$kind:$id" },
                            onKeepMine = { events += "keepMine:$it" },
                            onPick = { events += "pick:$it" },
                            onSave = { events += "save" },
                            onDecideLater = { events += "later" },
                            onTouched = {},
                        )
                        ResultCard.RecognitionFailed -> Unit
                    }
                }
            }
        }
    }

    // ---- Saved card ----------------------------------------------------------------------------

    @Test
    fun savedCardWithADurationShowsSubjectActionDurationAndTime() {
        show(saved(durationSeconds = 1_800))
        composeRule.onNodeWithText("✓ Hot tub · Change filter — 30 min — just now").assertIsDisplayed()
        composeRule.onNodeWithText("“words”").assertIsDisplayed()
    }

    @Test
    fun savedCardWithoutADurationLeavesTheDurationPartOut() {
        show(saved(durationSeconds = null))
        composeRule.onNodeWithText("✓ Hot tub · Change filter — just now").assertIsDisplayed()
    }

    @Test
    fun savedCardButtonsReportTheirTapsAndAreTouchTargetSized() {
        show(saved(durationSeconds = null))
        composeRule.onNodeWithText("Undo").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(SAVED_CHANGE_SUBJECT_TAG).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(SAVED_CHANGE_ACTION_TAG).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("undo", "changeSubject", "changeAction"), events)
    }

    @Test
    fun savedCardAnnouncesItselfPolitelyWithTheNamesAndDuration() {
        show(saved(durationSeconds = 5_400))
        val node = composeRule.onNodeWithTag(SAVED_CARD_TAG).fetchSemanticsNode()
        assertTrue(node.config.getOrNull(SemanticsProperties.LiveRegion) != null, "must be a live region")
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("Saved. Hot tub, Change filter, 1 h 30 min, just now. Undo available.", description)
    }

    // ---- Check card ----------------------------------------------------------------------------

    @Test
    fun aNearSideOffersItsCandidatesAndKeepMineAndNothingIsChosenYet() {
        show(
            check(
                subject = CheckSide(
                    kind = TagKind.SUBJECT,
                    words = "hvac",
                    candidates = listOf(furnace, boiler),
                    keepMine = "Hvac",
                ),
                action = chosen(TagKind.ACTION, "Change filter"),
            ),
        )

        composeRule.onNodeWithText("Your words are saved. Nothing was guessed.").assertIsDisplayed()
        composeRule.onNodeWithText("Did you mean one of these?").assertIsDisplayed()
        composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, "s1")).assertIsDisplayed()
        composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, "s2")).assertIsDisplayed()
        composeRule.onNodeWithText("Keep mine: Hvac").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing chosen yet").assertIsDisplayed()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, "s1")).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(checkKeepMineTag(TagKind.SUBJECT)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("candidate:SUBJECT:s1", "keepMine:SUBJECT"), events)
    }

    @Test
    fun aChosenCandidateIsMarkedAndAnnouncedAsChosenAndSaveBecomesAvailable() {
        show(
            check(
                subject = CheckSide(
                    kind = TagKind.SUBJECT,
                    candidates = listOf(furnace),
                    keepMine = "Hvac",
                    chosen = ChosenTag(TagChoice.Existing("s1"), "Furnace"),
                ),
                action = chosen(TagKind.ACTION, "Change filter"),
            ),
        )

        composeRule.onNodeWithText("✓ Furnace").assertIsDisplayed()
        val state = composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, "s1")).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Chosen", state)
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun preChosenSidesShowTheirNamesWithAChangeButtonAndNoOptions() {
        show(
            check(
                subject = chosen(TagKind.SUBJECT, "Hot tub", assumed = true),
                action = chosen(TagKind.ACTION, "Drain", new = true),
                durationSeconds = 600,
            ),
        )

        composeRule.onNodeWithText("Hot tub").assertIsDisplayed()
        composeRule.onNodeWithText("Assumed from your usual entries").assertIsDisplayed()
        composeRule.onNodeWithText("Drain").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        composeRule.onNodeWithText("Duration: 10 min").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Did you mean one of these?").assertDoesNotExist()
        composeRule.onNodeWithTag(checkPickTag(TagKind.SUBJECT)).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(checkPickTag(TagKind.ACTION)).performClick()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsEnabled()
        assertEquals(listOf("pick:SUBJECT", "pick:ACTION"), events)
    }

    @Test
    fun blankSidesOfferChooseButtonsAndSaveStaysOff() {
        show(check())

        composeRule.onNodeWithText("Choose subject").assertIsDisplayed()
        composeRule.onNodeWithText("Choose action").assertIsDisplayed()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(checkPickTag(TagKind.SUBJECT)).performClick()
        composeRule.onNodeWithTag(checkPickTag(TagKind.ACTION)).performClick()
        composeRule.onNodeWithText("Decide later").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("pick:SUBJECT", "pick:ACTION", "later"), events)
    }

    @Test
    fun oneSideChosenIsNotEnoughToSave() {
        show(check(subject = chosen(TagKind.SUBJECT, "Hot tub")))
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun checkCardAnnouncesItselfPolitely() {
        show(check())
        val node = composeRule.onNodeWithTag(CHECK_CARD_TAG).fetchSemanticsNode()
        assertTrue(node.config.getOrNull(SemanticsProperties.LiveRegion) != null, "must be a live region")
        assertEquals(
            "Check this. Your words are saved. Nothing was guessed. Choose a subject and an action, then save.",
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )
    }
}
