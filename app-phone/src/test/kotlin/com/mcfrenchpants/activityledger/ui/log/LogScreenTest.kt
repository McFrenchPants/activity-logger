package com.mcfrenchpants.activityledger.ui.log

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Host-side (Robolectric) test of the Log screen, driving a real [LogViewModel] over the
 * in-memory repository and a scripted interpreter. Robolectric's SDK is pinned in
 * src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class LogScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    private val repository = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val mowLawn = repository.seedActivity("Mow lawn")
    private var openedHistory = false

    private fun showLog() {
        val vm = LogViewModel(
            repository = repository,
            orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock),
            reviewResolutionService = ReviewResolutionService(repository, clock),
            correctionService = CorrectionService(repository, clock),
            clock = clock,
            isAiReady = { false },
            zone = { zone },
            locale = { Locale.US },
        )
        composeRule.setContent {
            ActivityLedgerTheme {
                LogScreen(onOpenHistory = { openedHistory = true }, viewModel = vm)
            }
        }
    }

    private fun typeAndSubmit(text: String) {
        composeRule.onNodeWithTag(LOG_INPUT_TAG).performTextInput(text)
        composeRule.onNodeWithTag(LOG_SUBMIT_TAG).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun typingAndSubmittingShowsTheSavedCardWithUndoAndChangeActivity() {
        interpreter.enqueue(Results.existing(mowLawn))
        showLog()
        composeRule.onNodeWithText("Say what you just did.").assertIsDisplayed()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertIsDisplayed()

        typeAndSubmit("I cut the grass")

        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("✓ Mow lawn — Today, 8:00 PM").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.onNodeWithText("Change activity").assertIsDisplayed()
    }

    @Test
    fun needsReviewCardShowsTheReassuranceAndDecideLater() {
        interpreter.enqueue(Results.needsReview(mowLawn))
        showLog()

        typeAndSubmit("did the yard")

        composeRule.onNodeWithTag(NEEDS_REVIEW_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Your words are saved. Nothing was guessed.").assertIsDisplayed()
        composeRule.onNodeWithText("Which activity was this?").assertIsDisplayed()
        composeRule.onNodeWithText("Choose another activity").assertIsDisplayed()
        composeRule.onNodeWithText("Create new activity").assertIsDisplayed()
        composeRule.onNodeWithText("Decide later").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Decide later").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NEEDS_REVIEW_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun notCategorizedCardAndAiNotReadyRow() {
        interpreter.enqueue(Results.failure(InterpreterFailureKind.UNAVAILABLE))
        showLog()
        composeRule.onNodeWithText("On-device AI isn't ready. Captures are still saved.").assertIsDisplayed()

        typeAndSubmit("cut grass")

        composeRule.onNodeWithTag(NOT_CATEGORIZED_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Captured ✓ — Categorized: not yet").assertIsDisplayed()
        composeRule.onNodeWithText("Saved your words, but couldn't categorize them yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose an activity").assertIsDisplayed()
    }

    @Test
    fun allHistoryCallsBack() {
        showLog()
        composeRule.onNodeWithText("All history").performScrollTo().performClick()
        assertTrue(openedHistory)
    }
}
