package com.mcfrenchpants.activityledger.debugtools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-side (Robolectric) smoke test of the debug-only runner SCREEN. The activity itself is not
 * started here: its readiness check goes to the real on-device model library, which a JVM test
 * cannot run hermetically.
 */
@RunWith(AndroidJUnit4::class)
class TagCorpusRunnerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: RunnerUiState) {
        composeRule.setContent {
            ActivityLedgerTheme { TagCorpusRunnerScreen(state, onRun = {}, onShare = {}, onClose = {}) }
        }
    }

    @Test
    fun notReadyDisablesRunAndSaysSoInPlainWords() {
        show(RunnerUiState(RunnerPhase.NotReady(ModelReadiness.NOT_INSTALLED), hasSavedFile = false))
        composeRule.onNodeWithTag(TAG_RUN_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_CLOSE_BUTTON).assertIsEnabled()
        composeRule.onNodeWithText("never downloads", substring = true).assertIsDisplayed()
    }

    @Test
    fun readyEnablesRunAndOffersAnEarlierFile() {
        show(RunnerUiState(RunnerPhase.Ready, hasSavedFile = true))
        composeRule.onNodeWithTag(TAG_RUN_BUTTON).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsEnabled()
    }

    @Test
    fun runningShowsProgressAndDisablesRunAndShare() {
        show(RunnerUiState(RunnerPhase.Running(23, 72), hasSavedFile = true))
        composeRule.onNodeWithText("23 of 72").assertIsDisplayed()
        composeRule.onNodeWithText("Keep this screen open and the phone unlocked", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RUN_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun finishedShowsNumbersOnlySummary() {
        show(
            RunnerUiState(
                RunnerPhase.Finished(RunSummary(72, 70, 2, 3, elapsedMs = 545_000, medianLatencyMs = 6_200)),
                hasSavedFile = true,
            ),
        )
        composeRule.onNodeWithText("Answered 70 of 72", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("9 min 5 s", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsEnabled()
    }
}
