package com.mcfrenchpants.activityledger.ui.explore

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.ui.history.EDIT_DONE_TAG
import com.mcfrenchpants.activityledger.ui.history.EDIT_SHEET_TAG
import com.mcfrenchpants.activityledger.ui.history.HistoryViewModel
import com.mcfrenchpants.activityledger.ui.log.ScriptedTranscriber
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-side (Robolectric) tests of the stateful [ExploreScreen] over the REAL in-memory ledger:
 * the shared History Edit sheet, the microphone permission flow and system Back.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class ExploreScreenIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val tags = WriteRefusingExploreTags(ledger)
    private val transcriber = ScriptedTranscriber()

    private fun exploreViewModel() = ExploreViewModel(
        repository = tags,
        lookup = LookupService(tags, ExploreQuestionExtractor()),
        transcriber = transcriber,
        clock = clock,
        zoneProvider = { zone },
        firstDayOfWeekProvider = { DayOfWeek.MONDAY },
        computeDispatcher = Dispatchers.Unconfined,
    )

    private fun historyViewModel() = HistoryViewModel(
        repository = ledger,
        resolution = TaggedResolutionService(ledger, clock),
        correction = TaggedCorrectionService(ledger, clock),
        clock = clock,
        locale = { Locale.US },
    )

    private fun registryAnswering(granted: Boolean) = object : ActivityResultRegistry() {
        @Suppress("UNCHECKED_CAST")
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            dispatchResult(requestCode, granted as O)
        }
    }

    private fun showScreen(
        explore: ExploreViewModel = exploreViewModel(),
        history: HistoryViewModel = historyViewModel(),
        permissionGranted: Boolean = true,
        canAskAgain: Boolean = true,
    ): ExploreViewModel {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registryAnswering(permissionGranted)
        }
        composeRule.setContent {
            ActivityLedgerTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ExploreScreen(viewModel = explore, historyViewModel = history, canAskAgain = { canAskAgain })
                }
            }
        }
        composeRule.waitForIdle()
        return explore
    }

    @Test
    fun `a tagged entry row opens the History edit sheet and closing it recounts`() {
        clock.currentInstant = ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone).toInstant()
        logExploreEntry(ledger, clock, zone, "Lawn", "Mow")
        clock.currentInstant = now
        val explore = showScreen()
        composeRule.waitUntil(5_000) { explore.state.value.summary?.entriesInPeriod == 1 }

        composeRule.onNodeWithTag(exploreViewTag(ExploreView.ENTRIES)).performClick()
        val row = hasContentDescription("Your words: Mow Lawn", substring = true)
        composeRule.onNodeWithTag(EXPLORE_SCREEN_TAG).performScrollToNode(row)
        composeRule.onNode(row).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EDIT_SHEET_TAG).assertIsDisplayed()

        val loadsBefore = tags.exploreLoads
        composeRule.onNodeWithTag(EDIT_DONE_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EDIT_SHEET_TAG).assertDoesNotExist()
        composeRule.waitUntil(5_000) { tags.exploreLoads > loadsBefore }
        assertTrue(tags.writeAttempts.isEmpty(), "the Explore view model must not write")
    }

    @Test
    fun `granting the microphone permission starts listening and a second tap stops it`() {
        val explore = showScreen(permissionGranted = true)
        composeRule.onNodeWithTag(EXPLORE_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EXPLORE_LISTENING_TAG).assertIsDisplayed()
        assertTrue(explore.state.value.isListening)

        composeRule.onNodeWithTag(EXPLORE_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EXPLORE_LISTENING_TAG).assertDoesNotExist()
    }

    @Test
    fun `refusing the permission says so in plain words`() {
        showScreen(permissionGranted = false, canAskAgain = true)
        composeRule.onNodeWithTag(EXPLORE_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Asking by voice needs the microphone.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `refusing the permission for good shows the settings message`() {
        showScreen(permissionGranted = false, canAskAgain = false)
        composeRule.onNodeWithTag(EXPLORE_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("switched off for this app in Settings", substring = true).assertIsDisplayed()
    }

    @Test
    fun `system Back calls back only while there are earlier filters`() {
        var state by mutableStateOf(ExploreUiState(canGoBack = true))
        var backs = 0
        composeRule.setContent {
            ActivityLedgerTheme {
                ExploreContent(state = state, callbacks = ExploreCallbacks(onBack = { backs++ }), locale = Locale.US)
            }
        }
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, backs)

        state = state.copy(canGoBack = false)
        composeRule.waitForIdle()
        val enabled = composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks()
        assertEquals(false, enabled)
    }
}
