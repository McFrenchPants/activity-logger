package com.mcfrenchpants.activityledger.ui.log

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-side (Robolectric) test of the Log screen's voice-capture surface: the microphone control,
 * the listening state, the recognition-failure card and every microphone-permission outcome.
 *
 * The microphone permission is never granted under Robolectric, so tapping the microphone always
 * goes through the permission request. That request is answered by a stand-in result registry
 * ([registryAnswering]), and whether the system would still offer the dialog again is driven by
 * [LogScreen]'s `canAskAgain` seam -- the two together give the three real outcomes: granted,
 * refused-but-askable, refused-for-good.
 *
 * The ViewModel's listening methods are the VC1.3 state-only stubs: no recognizer runs here.
 */
@RunWith(AndroidJUnit4::class)
class LogVoiceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val clock = MutableClock(ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant(), zone)
    private val repository = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val mowLawn = repository.seedActivity("Mow lawn")

    private lateinit var viewModel: LogViewModel

    /** Answers every permission request with [granted], without any system dialog. */
    private fun registryAnswering(granted: Boolean) = object : ActivityResultRegistry() {
        @Suppress("UNCHECKED_CAST")
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            dispatchResult(requestCode, granted as O)
        }
    }

    private fun showLog(permissionGranted: Boolean = true, canAskAgain: Boolean = true) {
        viewModel = LogViewModel(
            repository = repository,
            orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock),
            reviewResolutionService = ReviewResolutionService(repository, clock),
            correctionService = CorrectionService(repository, clock),
            clock = clock,
            isAiReady = { true },
            zone = { zone },
            locale = { Locale.US },
        )
        val registry = registryAnswering(permissionGranted)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        composeRule.setContent {
            ActivityLedgerTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    LogScreen(
                        onOpenHistory = {},
                        viewModel = viewModel,
                        canAskAgain = { canAskAgain },
                    )
                }
            }
        }
    }

    private fun tapMicrophone() {
        composeRule.onNodeWithTag(LOG_MIC_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    // ---- The microphone control ----------------------------------------------------------

    @Test
    fun microphoneSitsBesideTheTextFieldAndSubmitAndReadsLogByVoiceWhenIdle() {
        showLog()

        composeRule.onNodeWithTag(LOG_INPUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LOG_SUBMIT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LOG_MIC_TAG).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Log by voice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop listening").assertDoesNotExist()
    }

    @Test
    fun whileListeningTheMicrophoneReadsStopListening() {
        showLog(permissionGranted = true)

        tapMicrophone()

        assertTrue(viewModel.state.value.isListening)
        composeRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Log by voice").assertDoesNotExist()
    }

    @Test
    fun tappingStopReturnsToTheIdleMicrophone() {
        showLog(permissionGranted = true)
        tapMicrophone()

        tapMicrophone()

        assertFalse(viewModel.state.value.isListening)
        composeRule.onNodeWithContentDescription("Log by voice").assertIsDisplayed()
    }

    @Test
    fun theMicrophoneIsDisabledWhileCapturingAndWhileTheScreenIsStopped() {
        showContent(LogUiState(isStarted = true, isCapturing = true))
        composeRule.onNodeWithTag(LOG_MIC_TAG).assertIsNotEnabled()

        composeRule.runOnUiThread { state = LogUiState(isStarted = false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOG_MIC_TAG).assertIsNotEnabled()

        composeRule.runOnUiThread { state = LogUiState(isStarted = true) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOG_MIC_TAG).assertIsEnabled()
    }

    // ---- The listening state -------------------------------------------------------------

    @Test
    fun listeningAnnouncesItselfShowsThePartialAsEvidenceAndBlocksTypedSubmit() {
        showLog(permissionGranted = true)
        composeRule.onNodeWithTag(LOG_INPUT_TAG).performTextInput("cut the grass")

        tapMicrophone()
        composeRule.runOnUiThread { viewModel.onPartialTranscript("I cut the grass") }
        composeRule.waitForIdle()

        // Announced, not just drawn: the listening line is a polite live region.
        val listening = composeRule.onNodeWithTag(LOG_LISTENING_TAG).assertIsDisplayed()
        assertTrue(
            listening.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion) != null,
            "the listening line must be a live region",
        )
        composeRule.onNodeWithText("Listening...").assertIsDisplayed()
        // The partial is the user's words, so it is quoted in the evidence voice, like the cards.
        composeRule.onNodeWithTag(LOG_PARTIAL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("“I cut the grass”").assertIsDisplayed()
        // Typed submit is blocked while listening, even with text in the field.
        composeRule.onNodeWithTag(LOG_SUBMIT_TAG).assertIsNotEnabled()
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun whileListeningRecentIsGoneFromTheSemanticsTreeAndComesBackAfterwards() {
        showLog(permissionGranted = true)
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        composeRule.onNodeWithText("All history").performScrollTo().assertIsDisplayed()

        tapMicrophone()

        composeRule.onNodeWithText("Recent").assertDoesNotExist()
        composeRule.onNodeWithText("All history").assertDoesNotExist()
        composeRule.onNodeWithText("No activities logged yet. Type what you just did.").assertDoesNotExist()

        tapMicrophone()

        composeRule.onNodeWithText("Recent").assertIsDisplayed()
    }

    // ---- The recognition-failure card ------------------------------------------------------

    @Test
    fun theRecognitionFailureCardShowsOnlyTheApologyAndTwoWaysOn() {
        showLog(permissionGranted = true)
        tapMicrophone()

        composeRule.runOnUiThread { viewModel.showRecognitionFailure() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RECOGNITION_FAILED_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't make out any words. Nothing was saved.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onNodeWithText("Type instead").assertIsDisplayed()
        // Nothing was heard, so the card carries no capture and no words at all.
        assertTrue(viewModel.state.value.card is ResultCard.RecognitionFailed)
        assertFalse(viewModel.state.value.isListening)
    }

    @Test
    fun tryAgainStartsListeningAgain() {
        showLog(permissionGranted = true)
        tapMicrophone()
        composeRule.runOnUiThread { viewModel.showRecognitionFailure() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Try again").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(viewModel.state.value.isListening)
        composeRule.onNodeWithTag(RECOGNITION_FAILED_CARD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(LOG_LISTENING_TAG).assertIsDisplayed()
    }

    @Test
    fun typeInsteadDismissesTheCardAndLeavesTypingWorking() {
        interpreter.enqueue(Results.existing(mowLawn))
        showLog(permissionGranted = true)
        tapMicrophone()
        composeRule.runOnUiThread { viewModel.showRecognitionFailure() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Type instead").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RECOGNITION_FAILED_CARD_TAG).assertDoesNotExist()
        assertFalse(viewModel.state.value.isListening)
        typeAndSubmit("I cut the grass")
        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
    }

    // ---- Permission outcomes: none of them may be a dead end --------------------------------

    @Test
    fun refusingTheMicrophoneSaysSoInPlainWordsAndLeavesTypingAvailable() {
        interpreter.enqueue(Results.existing(mowLawn))
        showLog(permissionGranted = false, canAskAgain = true)

        tapMicrophone()

        assertFalse(viewModel.state.value.isListening)
        composeRule.onNodeWithText("Voice logging needs the microphone. You can still type what you did.")
            .assertIsDisplayed()
        typeAndSubmit("I cut the grass")
        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun refusingTheMicrophoneForGoodSaysSoOnceAndLeavesTypingAvailable() {
        interpreter.enqueue(Results.existing(mowLawn))
        showLog(permissionGranted = false, canAskAgain = false)

        tapMicrophone()

        assertFalse(viewModel.state.value.isListening)
        composeRule.onNodeWithText(
            "The microphone is switched off for this app in Settings, so voice logging can't start. " +
                "You can still type what you did.",
        ).assertIsDisplayed()
        typeAndSubmit("I cut the grass")
        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun grantingTheMicrophoneStartsListeningAndStillLeavesTypingAvailable() {
        interpreter.enqueue(Results.existing(mowLawn))
        showLog(permissionGranted = true)

        tapMicrophone()
        assertTrue(viewModel.state.value.isListening)

        // Stopping hands the screen straight back to typing.
        tapMicrophone()
        typeAndSubmit("I cut the grass")
        composeRule.onNodeWithTag(SAVED_CARD_TAG).assertIsDisplayed()
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun typeAndSubmit(text: String) {
        composeRule.onNodeWithTag(LOG_INPUT_TAG).performTextInput(text)
        composeRule.onNodeWithTag(LOG_SUBMIT_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private var state by mutableStateOf(LogUiState())

    /** Drives [LogContent] directly, for states the stubs cannot reach from the outside yet. */
    private fun showContent(initial: LogUiState) {
        state = initial
        composeRule.setContent {
            ActivityLedgerTheme {
                LogContent(
                    state = state,
                    onInputChange = {},
                    onSubmit = {},
                    onUndo = {},
                    onChangeActivity = {},
                    onSuggestion = {},
                    onChooseActivity = {},
                    onCreateActivity = {},
                    onDecideLater = {},
                    onCardTouched = {},
                    onOpenHistory = {},
                    onMicrophone = {},
                    onTryAgain = {},
                    onTypeInstead = {},
                )
            }
        }
    }
}
