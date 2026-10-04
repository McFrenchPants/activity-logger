package com.mcfrenchpants.activityledger.ui.ask

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupEntry
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupMatch
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupResult
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupTier
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.ui.log.ScriptedTranscriber
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import com.mcfrenchpants.activityledger.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Host-side (Robolectric) tests of the Ask screen: [AskContent] on scripted state, plus [AskScreen] for voice. */
@RunWith(AndroidJUnit4::class)
class AskScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()

    private var state by mutableStateOf(AskUiState())
    private val asked = mutableListOf<String>()
    private var cleared = 0
    private var mics = 0
    private var dismissed = 0

    private fun show(initial: AskUiState = AskUiState()) {
        state = initial
        composeRule.setContent {
            ActivityLedgerTheme {
                AskContent(
                    state = state,
                    onInputChange = { state = state.copy(input = it) },
                    onAsk = { asked += state.input },
                    onClear = { cleared++ },
                    onDismissMessage = { dismissed++ },
                    onMicrophone = { mics++ },
                    zone = zone,
                    now = now,
                )
            }
        }
    }

    private fun entry(subject: String, action: String, hoursAgo: Long, duration: Long? = null) = LookupEntry(
        occurrenceId = "o-$subject-$action-$hoursAgo",
        subjectId = "s-$subject",
        subjectName = subject,
        actionId = "a-$action",
        actionName = action,
        occurredAt = now.minusSeconds(hoursAgo * 3600),
        durationSeconds = duration,
    )

    private fun match(e: LookupEntry, tier: LookupTier = LookupTier.BOTH, exact: Boolean = true) =
        LookupMatch(e, tier, exact)

    private fun answered(vararg matches: LookupMatch) =
        AskOutcome.Answered(LookupResult(matches.toList()))

    private fun turnWith(outcome: AskOutcome?, question: String = "When did I last mow?") =
        AskUiState(thread = listOf(AskTurn(1, question, outcome)))

    @Test
    fun `an empty thread shows the invitation and no Clear button`() {
        show()
        composeRule.onNodeWithTag(ASK_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Ask when you last did something", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ASK_CLEAR_TAG).assertDoesNotExist()
    }

    @Test
    fun `typing then tapping Send asks with the text`() {
        show()
        composeRule.onNodeWithTag(ASK_INPUT_TAG).performTextInput("When did I last mow?")
        composeRule.onNodeWithTag(ASK_SEND_TAG).assertIsEnabled().performClick()
        assertEquals(listOf("When did I last mow?"), asked)
    }

    @Test
    fun `Send is disabled for blank input and while an answer is pending`() {
        show()
        composeRule.onNodeWithTag(ASK_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(ASK_INPUT_TAG).performTextInput("   ")
        composeRule.onNodeWithTag(ASK_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(ASK_INPUT_TAG).performTextInput("x")
        composeRule.onNodeWithTag(ASK_SEND_TAG).assertIsEnabled()
        state = state.copy(isAsking = true)
        composeRule.onNodeWithTag(ASK_SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the question is shown as a bubble above its answer`() {
        show(turnWith(AskOutcome.NotEnoughHistory, "When did I last fly a kite?"))
        composeRule.onNodeWithText("When did I last fly a kite?").assertIsDisplayed()
        composeRule.onNodeWithText("There isn't enough history yet to answer that.").assertIsDisplayed()
    }

    @Test
    fun `an answer with one match states the fact first with its duration`() {
        show(turnWith(answered(match(entry("Lawn", "Mow", hoursAgo = 3, duration = 3600)))))
        composeRule.onNodeWithText("Last logged: Lawn · Mow").assertIsDisplayed()
        composeRule.onNodeWithText("Today, 5:00 PM").assertIsDisplayed()
        composeRule.onNodeWithText("Lasted 1 h").assertIsDisplayed()
        composeRule.onNodeWithText("Before that:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Other matches").assertDoesNotExist()
        composeRule.onNodeWithText("Closest match:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an answer with a previous occurrence states it and the gap in plain words`() {
        val newest = match(entry("Lawn", "Mow", hoursAgo = 2))
        val older = match(entry("Lawn", "Mow", hoursAgo = 2 + 24 * 9 + 5))
        show(turnWith(answered(newest, older)))
        composeRule.onNodeWithText("Before that: Sep 6, 1:00 PM").assertIsDisplayed()
        composeRule.onNodeWithText("Gap between the two: 9 days").assertIsDisplayed()
    }

    @Test
    fun `a gap under a day is shown in hours`() {
        show(turnWith(answered(match(entry("Lawn", "Mow", 1)), match(entry("Lawn", "Mow", 1 + 6)))))
        composeRule.onNodeWithText("Gap between the two: 6 hours").assertIsDisplayed()
    }

    @Test
    fun `several matches list the others under Other matches, at most five`() {
        val top = match(entry("Lawn", "Mow", 1))
        val others = (1..7).map { match(entry("Subject$it", "Action$it", 10L + it), LookupTier.SUBJECT_ONLY) }
        show(turnWith(answered(top, *others.toTypedArray())))
        composeRule.onNodeWithText("Other matches").assertIsDisplayed()
        composeRule.onNodeWithText("Subject1 · Action1").assertExists()
        composeRule.onNodeWithText("Subject5 · Action5").assertExists()
        composeRule.onNodeWithText("Subject6 · Action6").assertDoesNotExist()
    }

    @Test
    fun `a match that is not exact shows the closest-match note`() {
        show(turnWith(answered(match(entry("Lawn", "Mow", 1), exact = false))))
        composeRule.onNodeWithText(
            "Closest match: your question did not match a logged item exactly.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an exact partial-tier match shows the partly-match note`() {
        show(turnWith(answered(match(entry("Lawn", "Mow", 1), tier = LookupTier.ACTION_ONLY))))
        composeRule.onNodeWithText(
            "Closest match: showing entries that only partly match your question.",
        ).assertIsDisplayed()
    }

    @Test
    fun `not enough history uses the specified sentence`() {
        show(turnWith(AskOutcome.NotEnoughHistory))
        composeRule.onNodeWithText("There isn't enough history yet to answer that.").assertIsDisplayed()
    }

    @Test
    fun `a statement points to the Log tab and offers no logging`() {
        show(turnWith(AskOutcome.NotAQuestion))
        composeRule.onNodeWithText("That sounds like something you did, not a question.").assertIsDisplayed()
        composeRule.onNodeWithText("To log it, use the Log tab.").assertIsDisplayed()
        composeRule.onNodeWithText("Log it", substring = true).assertDoesNotExist()
    }

    @Test
    fun `AI unavailable and try again later show their copy`() {
        show(turnWith(AskOutcome.AiUnavailable))
        composeRule.onNodeWithText(
            "This phone can't read questions on its own yet, so Ask isn't available here.",
        ).assertIsDisplayed()
        state = turnWith(AskOutcome.TryAgainLater)
        composeRule.onNodeWithText("I couldn't read that question just now. Try again in a moment.")
            .assertIsDisplayed()
    }

    @Test
    fun `a pending turn shows a described progress indicator`() {
        show(turnWith(outcome = null).copy(isAsking = true))
        composeRule.onNodeWithTag(ASK_PENDING_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Looking through your history").assertIsDisplayed()
    }

    @Test
    fun `Clear appears only with a thread and calls clear`() {
        show(turnWith(AskOutcome.NotEnoughHistory))
        composeRule.onNodeWithTag(ASK_CLEAR_TAG).assertIsDisplayed().performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `the microphone toggles its icon and shows the listening state`() {
        show()
        composeRule.onNodeWithContentDescription("Ask by voice").assertIsDisplayed().performClick()
        assertEquals(1, mics)
        composeRule.onNodeWithTag(ASK_LISTENING_TAG).assertDoesNotExist()

        state = state.copy(isListening = true, partialTranscript = "when did I")
        composeRule.onNodeWithTag(ASK_LISTENING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ASK_PARTIAL_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    @Test
    fun `a message is shown and can be dismissed`() {
        show(AskUiState(message = UserMessage(R.string.ask_voice_nothing_heard)))
        composeRule.onNodeWithText("I didn't catch that. Try again, or type your question.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    // ---- The stateful screen: microphone permission and listening ------------------------

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

    private fun showScreen(permissionGranted: Boolean, canAskAgain: Boolean): AskViewModel {
        val clock = MutableClock(now, zone)
        val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
        val viewModel = AskViewModel(LookupService(WriteRefusingTags(ledger), ScriptedQuestionExtractor()), ScriptedTranscriber())
        val registry = registryAnswering(permissionGranted)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        composeRule.setContent {
            ActivityLedgerTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AskScreen(viewModel = viewModel, canAskAgain = { canAskAgain })
                }
            }
        }
        return viewModel
    }

    @Test
    fun `granting the microphone permission starts listening and a second tap stops it`() {
        val viewModel = showScreen(permissionGranted = true, canAskAgain = true)
        composeRule.onNodeWithTag(ASK_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ASK_LISTENING_TAG).assertIsDisplayed()
        assertTrue(viewModel.state.value.isListening)

        composeRule.onNodeWithTag(ASK_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ASK_LISTENING_TAG).assertDoesNotExist()
    }

    @Test
    fun `refusing the permission says so in plain words`() {
        showScreen(permissionGranted = false, canAskAgain = true)
        composeRule.onNodeWithTag(ASK_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Asking by voice needs the microphone.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `refusing the permission for good shows the settings message`() {
        showScreen(permissionGranted = false, canAskAgain = false)
        composeRule.onNodeWithTag(ASK_MIC_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("switched off for this app in Settings", substring = true).assertIsDisplayed()
    }
}
