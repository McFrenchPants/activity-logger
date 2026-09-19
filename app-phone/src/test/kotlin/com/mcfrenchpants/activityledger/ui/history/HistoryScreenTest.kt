package com.mcfrenchpants.activityledger.ui.history

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.log.Results
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Host-side (Robolectric) test of the History screen over a real [HistoryViewModel] and the
 * in-memory repository. Robolectric's SDK is pinned in src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    private val repository = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock)
    private val mowLawn = repository.seedActivity("Mow lawn")

    private fun capture(text: String, result: InterpretationResult): String = runSuspend {
        clock.advance(Duration.ofMinutes(1))
        val id = repository.createRawCapture(
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
        interpreter.enqueue(result)
        orchestrator.process(id)
        id
    }

    private fun showHistory() {
        val vm = HistoryViewModel(
            repository = repository,
            reviewResolutionService = ReviewResolutionService(repository, clock),
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

    @Test
    fun emptyHistoryShowsTheNothingLoggedText() {
        showHistory()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertIsDisplayed()
        chip(HistoryFilter.ALL).assertIsSelected()
    }

    @Test
    fun chipsSwitchTheList() {
        capture("cut the grass", Results.existing(mowLawn))
        capture("did the yard", Results.needsReview(mowLawn))
        showHistory()
        row("cut the grass").assertIsDisplayed()
        row("did the yard").assertIsDisplayed()

        chip(HistoryFilter.NEEDS_REVIEW).performClick()
        composeRule.waitForIdle()
        chip(HistoryFilter.NEEDS_REVIEW).assertIsSelected()
        chip(HistoryFilter.ALL).assertIsNotSelected()
        row("did the yard").assertIsDisplayed()
        row("cut the grass").assertDoesNotExist()

        chip(HistoryFilter.NOT_CATEGORIZED).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nothing is waiting to be categorized.").assertIsDisplayed()
        row("did the yard").assertDoesNotExist()
    }

    @Test
    fun onlyAwaitingRowsAreButtonsAndTappingOneOpensTheSheet() {
        capture("cut the grass", Results.existing(mowLawn))
        capture("did the yard", Results.needsReview(mowLawn))
        showHistory()

        row("cut the grass").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        row("did the yard")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("has action label 'Choose an activity'") {
                    it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Choose an activity"
                },
            )

        row("did the yard").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RESOLUTION_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Your words are saved. Nothing was guessed.").assertIsDisplayed()
        composeRule.onNodeWithText("Which activity was this?").assertIsDisplayed()
        composeRule.onNodeWithText("Choose another activity").assertIsDisplayed()
        composeRule.onNodeWithText("Create new activity").assertIsDisplayed()
        composeRule.onNodeWithText("Decide later").assertIsDisplayed()

        composeRule.onNodeWithText("Decide later").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RESOLUTION_SHEET_TAG).assertDoesNotExist()
    }
}
