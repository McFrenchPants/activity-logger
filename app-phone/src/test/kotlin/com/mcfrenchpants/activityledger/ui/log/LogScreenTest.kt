package com.mcfrenchpants.activityledger.ui.log

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host-side (Robolectric) test of the Log screen, driving a real [LogViewModel] over the real
 * in-memory ledger and a scripted extractor. Robolectric's SDK is pinned in
 * src/test/resources/robolectric.properties.
 */
@RunWith(AndroidJUnit4::class)
class LogScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    private val repository = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val extractor = ScriptedExtractor()
    private var openedHistory = false
    private lateinit var viewModel: LogViewModel

    private fun showLog() {
        viewModel = LogViewModel(
            repository = repository,
            orchestrator = TaggedCaptureOrchestrator(repository, extractor, clock),
            resolution = TaggedResolutionService(repository, clock),
            correction = TaggedCorrectionService(repository, clock),
            transcriber = ScriptedTranscriber(),
            clock = clock,
            isAiReady = { false },
            zone = { zone },
            locale = { Locale.US },
        )
        composeRule.setContent {
            ActivityLedgerTheme {
                LogScreen(onOpenHistory = { openedHistory = true }, viewModel = viewModel)
            }
        }
    }

    private fun typeAndSubmit(text: String) {
        composeRule.onNodeWithTag(LOG_INPUT_TAG).performTextInput(text)
        composeRule.onNodeWithTag(LOG_SUBMIT_TAG).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun typingAndSubmittingShowsTheSavedCardWithDurationUndoAndBothChangeButtons() {
        extractor.on("Changed the filter for 30 minutes", Extracted.log("Hot tub", "Change filter", duration = "30 minutes"))
        showLog()
        composeRule.onNodeWithText("Say what you just did.").assertIsDisplayed()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertIsDisplayed()

        typeAndSubmit("Changed the filter for 30 minutes")

        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("✓ Hot tub · Change filter — 30 min — Today, 8:00 PM").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.onNodeWithText("Change subject").assertIsDisplayed()
        composeRule.onNodeWithText("Change action").assertIsDisplayed()
    }

    @Test
    fun theSavedTitleReadsWellWithoutADuration() {
        extractor.on("Fed the dog", Extracted.log("Dog", "Feed"))
        showLog()

        typeAndSubmit("Fed the dog")

        composeRule.onNodeWithText("✓ Dog · Feed — Today, 8:00 PM").assertIsDisplayed()
    }

    @Test
    fun aCloseMatchShowsTheCheckCardAndSaveWaitsForAChoice() {
        seedTags(repository, clock, zone, "Furnace", "Change filter")
        extractor.on("hvac filter done", Extracted.log("HVAC", "Change filter"))
        showLog()

        typeAndSubmit("hvac filter done")

        composeRule.onNodeWithTag(CHECK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Your words are saved. Nothing was guessed.").assertIsDisplayed()
        composeRule.onNodeWithText("Furnace").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(checkKeepMineTag(TagKind.SUBJECT)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsNotEnabled()

        val furnace = (viewModel.state.value.card as ResultCard.Check).subject.candidates.single().id
        composeRule.onNodeWithTag(checkCandidateTag(TagKind.SUBJECT, furnace)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHECK_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("✓ Furnace · Change filter — Today, 8:00 PM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aBlankSideOffersAChooseButtonThatOpensThePickerForThatKind() {
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
        showLog()

        typeAndSubmit("cut grass")

        composeRule.onNodeWithTag(CHECK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Choose action").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choose subject").performScrollTo().performClick()
        composeRule.waitForIdle()
        val picker = assertNotNull(viewModel.state.value.picker)
        assertEquals(TagKind.SUBJECT, picker.kind)
    }

    @Test
    fun decideLaterDismissesTheCheckCard() {
        extractor.fallback = Extracted.failure(InterpreterFailureKind.UNAVAILABLE)
        showLog()
        composeRule.onNodeWithText("On-device AI isn't ready. Captures are still saved.").assertIsDisplayed()

        typeAndSubmit("cut grass")
        composeRule.onNodeWithText("Decide later").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Decide later").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHECK_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun allHistoryCallsBack() {
        showLog()
        composeRule.onNodeWithText("All history").performScrollTo().performClick()
        assertTrue(openedHistory)
    }
}
