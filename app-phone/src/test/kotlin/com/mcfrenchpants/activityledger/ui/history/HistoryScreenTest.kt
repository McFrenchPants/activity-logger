package com.mcfrenchpants.activityledger.ui.history

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
import com.mcfrenchpants.activityledger.ui.log.CHECK_CARD_TAG
import com.mcfrenchpants.activityledger.ui.log.CHECK_DECIDE_LATER_TAG
import com.mcfrenchpants.activityledger.ui.log.CHECK_SAVE_TAG
import com.mcfrenchpants.activityledger.ui.log.Extracted
import com.mcfrenchpants.activityledger.ui.log.ScriptedExtractor
import com.mcfrenchpants.activityledger.ui.log.checkCandidateTag
import com.mcfrenchpants.activityledger.ui.log.seedTags
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Host-side (Robolectric) test of the History screen over a real [HistoryViewModel] and the real
 * in-memory ledger. Robolectric's SDK is pinned in src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val extractor = ScriptedExtractor()
    private val orchestrator = TaggedCaptureOrchestrator(ledger, extractor, clock)

    init {
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
    }

    private val tubWords = "Changed the hot tub filter for 30 minutes"
    private val hvacWords = "The hvac filter needs changing, done 20 minutes"
    private val unclearWords = "did a thing for the house"

    private fun capture(text: String): String = runSuspend {
        clock.advance(Duration.ofMinutes(1))
        val id = ledger.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = "log_typed",
                capturedAt = clock.instant(),
                zoneId = zone,
                rawText = text,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        orchestrator.process(id)
        id
    }

    private fun savedTubEntry(): String {
        extractor.on(tubWords, Extracted.log("Hot tub", "Change filter", duration = "30 minutes"))
        return capture(tubWords)
    }

    private fun closeMatchCapture(): String {
        seedTags(ledger, clock, zone, "Furnace", "Change filter")
        extractor.on(hvacWords, Extracted.log("HVAC", "Change filter", duration = "20 minutes"))
        return capture(hvacWords)
    }

    private fun showHistory() {
        val vm = HistoryViewModel(
            repository = ledger,
            resolution = TaggedResolutionService(ledger, clock),
            correction = TaggedCorrectionService(ledger, clock),
            clock = clock,
            locale = { Locale.US },
        )
        composeRule.setContent {
            ActivityLedgerTheme {
                HistoryScreen(viewModel = vm)
            }
        }
        composeRule.waitForIdle()
    }

    private fun row(words: String) =
        composeRule.onNode(hasContentDescription("Your words: $words", substring = true))

    private fun chip(filter: HistoryFilter) = composeRule.onNodeWithTag(historyFilterTag(filter))

    private fun hasClickLabel(label: String) = SemanticsMatcher("has action label '$label'") {
        it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == label
    }

    @Test
    fun emptyHistoryShowsTheNothingLoggedText() {
        showHistory()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertIsDisplayed()
        chip(HistoryFilter.ALL).assertIsSelected()
    }

    @Test
    fun aTaggedRowShowsSubjectActionAndDurationAndIsReadAsOneSentence() {
        savedTubEntry()
        showHistory()

        composeRule.onNodeWithText("Hot tub · Change filter", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("30 min", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Hot tub, Change filter, 30 min", substring = true)).assertIsDisplayed()
        row(tubWords)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasClickLabel("Edit this entry"))
    }

    @Test
    fun anOldStyleRowKeepsItsNameAndIsNotClickable() {
        val old = HistoryRowModel(
            captureId = "c1",
            activityName = "Mow lawn",
            time = "just now",
            state = null,
            rawText = "cut the grass",
        )
        composeRule.setContent {
            ActivityLedgerTheme {
                HistoryContent(
                    state = HistoryUiState(allRows = listOf(old), loaded = true),
                    onSelectFilter = {},
                    onOpenCheck = {},
                    onOpenEdit = {},
                )
            }
        }

        composeRule.onNodeWithText("Mow lawn", useUnmergedTree = true).assertIsDisplayed()
        row("cut the grass").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun chipsSwitchTheList() {
        savedTubEntry()
        capture(unclearWords)
        showHistory()
        row(tubWords).assertIsDisplayed()
        row(unclearWords).assertIsDisplayed()

        chip(HistoryFilter.NOT_CATEGORIZED).performClick()
        composeRule.waitForIdle()
        chip(HistoryFilter.NOT_CATEGORIZED).assertIsSelected()
        chip(HistoryFilter.ALL).assertIsNotSelected()
        row(unclearWords).assertIsDisplayed()
        row(tubWords).assertDoesNotExist()

        chip(HistoryFilter.NEEDS_REVIEW).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nothing needs review.").assertIsDisplayed()
        row(unclearWords).assertDoesNotExist()
    }

    @Test
    fun aWaitingRowOpensTheCheckCardAndDecideLaterClosesIt() {
        savedTubEntry()
        closeMatchCapture()
        showHistory()

        row(hvacWords)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasClickLabel("Check this entry"))
        row(hvacWords).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHECK_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CHECK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Check this").assertIsDisplayed()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag(CHECK_DECIDE_LATER_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHECK_SHEET_TAG).assertDoesNotExist()
        row(hvacWords).assertIsDisplayed()
    }

    @Test
    fun choosingTheCandidateAndSavingTurnsTheWaitingRowIntoASavedOne() {
        closeMatchCapture()
        showHistory()
        row(hvacWords).performClick()
        composeRule.waitForIdle()

        val furnace = runSuspend { ledger.loadTagCatalog() }.subjects.single { it.displayName == "Furnace" }
        composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, furnace.id)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHECK_SHEET_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Furnace · Change filter", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("20 min", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aSavedRowOpensTheEditSheetAndRemoveHidesIt() {
        savedTubEntry()
        showHistory()

        row(tubWords).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Edit entry").assertIsDisplayed()
        composeRule.onNodeWithTag(EDIT_CHANGE_SUBJECT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EDIT_CHANGE_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Change subject").assertIsDisplayed()
        composeRule.onNodeWithText("Change action").assertIsDisplayed()
        composeRule.onNodeWithText("This hides the entry from your history. Your words stay saved.").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(EDIT_REMOVE_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_SHEET_TAG).assertDoesNotExist()
        row(tubWords).assertDoesNotExist()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertIsDisplayed()
    }

    @Test
    fun theEditSheetDoneButtonClosesItWithoutChanges() {
        savedTubEntry()
        showHistory()
        row(tubWords).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_DONE_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_SHEET_TAG).assertDoesNotExist()
        row(tubWords).assertIsDisplayed()
    }
}
