package com.mcfrenchpants.activityledger.ui.ask

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupService
import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.log.ScriptedTranscriber
import com.mcfrenchpants.activityledger.ui.log.seedTags
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-side tests of [AskViewModel] over the REAL ledger (in-memory Room), the real lookup
 * service, a scripted question extractor and a scripted transcriber.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val tags = WriteRefusingTags(ledger)
    private val extractor = ScriptedQuestionExtractor()
    private val transcriber = ScriptedTranscriber()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()

    private val question = "When did I last mow the lawn?"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): AskViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AskViewModel(LookupService(tags, extractor), transcriber) as T
        }
        return ViewModelProvider(store, factory)[AskViewModel::class.java]
    }

    private fun AskViewModel.say(text: String) {
        onInputChange(text)
        ask()
        scheduler.runCurrent()
    }

    private fun AskViewModel.listen(vararg events: SpeechEvent) {
        transcriber.willEmit(*events)
        startListening()
        scheduler.runCurrent()
    }

    private fun final(text: String) = SpeechEvent.FinalTranscript(text, null, emptyList())

    private fun logMowing(times: Int) {
        repeat(times) {
            clock.advance(Duration.ofHours(1))
            logEntry(ledger, clock, zone, "Lawn", "Mow")
        }
    }

    @Test
    fun `a question about a logged entry is answered with the newest entry first`() {
        logMowing(2)
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.say(question)

        val turn = vm.state.value.thread.single()
        assertEquals(question, turn.question)
        val outcome = assertIs<AskOutcome.Answered>(turn.outcome)
        val matches = outcome.result.matches
        assertEquals(2, matches.size)
        assertTrue(matches[0].entry.occurredAt > matches[1].entry.occurredAt)
        assertFalse(vm.state.value.isAsking)
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun `a statement is not a question and never reaches the extractor`() {
        logMowing(1)
        val vm = viewModel()

        vm.say("I mowed the lawn")

        assertEquals(AskOutcome.NotAQuestion, vm.state.value.thread.single().outcome)
        assertEquals(0, extractor.received.size)
    }

    @Test
    fun `an empty ledger gives not enough history`() {
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.say(question)

        assertEquals(AskOutcome.NotEnoughHistory, vm.state.value.thread.single().outcome)
    }

    @Test
    fun `a question about something never logged gives not enough history`() {
        seedTags(ledger, clock, zone, "Hot tub", "Clean")
        logMowing(1)
        extractor.answers("garage", "paint")
        val vm = viewModel()

        vm.say("When did I last paint the garage?")

        assertEquals(AskOutcome.NotEnoughHistory, vm.state.value.thread.single().outcome)
    }

    @Test
    fun `each failure kind maps to its outcome`() {
        val expected = mapOf(
            InterpreterFailureKind.UNAVAILABLE to AskOutcome.AiUnavailable,
            InterpreterFailureKind.RETRYABLE to AskOutcome.TryAgainLater,
            InterpreterFailureKind.MALFORMED to AskOutcome.TryAgainLater,
            InterpreterFailureKind.OTHER to AskOutcome.TryAgainLater,
        )
        for ((kind, outcome) in expected) {
            extractor.fails(kind)
            val vm = viewModel()
            vm.say(question)
            assertEquals(outcome, vm.state.value.thread.single().outcome, "kind $kind")
            assertFalse(vm.state.value.isAsking)
            store.clear()
        }
    }

    @Test
    fun `blank input is ignored`() {
        val vm = viewModel()

        vm.say("   ")

        assertTrue(vm.state.value.thread.isEmpty())
        assertEquals(0, extractor.received.size)
        assertFalse(vm.state.value.isAsking)
    }

    @Test
    fun `the question is trimmed and shown pending until answered`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        extractor.holdAnswers()
        val vm = viewModel()

        vm.say("  $question  ")

        val pending = vm.state.value.thread.single()
        assertEquals(question, pending.question)
        assertNull(pending.outcome)
        assertTrue(vm.state.value.isAsking)
        assertEquals("", vm.state.value.input)

        extractor.release()
        scheduler.runCurrent()
        assertEquals(AskOutcome.TryAgainLater, vm.state.value.thread.single().outcome)
        assertFalse(vm.state.value.isAsking)
    }

    @Test
    fun `a second question while one is pending is ignored and its text stays in the field`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        extractor.holdAnswers()
        val vm = viewModel()
        vm.say(question)

        vm.say("How often do I mow the lawn?")

        assertEquals(1, vm.state.value.thread.size)
        assertEquals("How often do I mow the lawn?", vm.state.value.input)
        extractor.release()
        scheduler.runCurrent()
        assertEquals(1, extractor.received.size)
    }

    @Test
    fun `thread keeps order and ids are unique`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        val vm = viewModel()

        vm.say("When did I mow?")
        vm.say("When did I wash the car?")
        vm.say("What did I cook?")

        val thread = vm.state.value.thread
        assertEquals(listOf("When did I mow?", "When did I wash the car?", "What did I cook?"), thread.map { it.question })
        assertEquals(3, thread.map { it.id }.toSet().size)
        assertTrue(thread.all { it.outcome != null })
    }

    @Test
    fun `a spoken final transcript is put in the field and asked at once`() {
        logMowing(1)
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.listen(SpeechEvent.PartialTranscript("when did"), final(question))

        val state = vm.state.value
        assertEquals(question, state.thread.single().question)
        assertIs<AskOutcome.Answered>(state.thread.single().outcome)
        assertFalse(state.isListening)
        assertEquals("", state.partialTranscript)
    }

    @Test
    fun `partial transcript is shown while listening and stop ends the session`() {
        val vm = viewModel()

        vm.listen(SpeechEvent.PartialTranscript("when did I"))

        assertTrue(vm.state.value.isListening)
        assertEquals("when did I", vm.state.value.partialTranscript)

        vm.stopListening()
        scheduler.runCurrent()
        assertFalse(vm.state.value.isListening)
        assertEquals("", vm.state.value.partialTranscript)
        assertFalse(transcriber.isOpen)
    }

    @Test
    fun `a blank spoken transcript is not asked`() {
        val vm = viewModel()

        vm.listen(final("   "))

        assertTrue(vm.state.value.thread.isEmpty())
        assertEquals(0, extractor.received.size)
    }

    @Test
    fun `late events from a finished session change nothing`() {
        val vm = viewModel()
        vm.listen(SpeechEvent.PartialTranscript("when"))
        vm.stopListening()

        transcriber.emitNow(final(question))
        scheduler.runCurrent()

        assertTrue(vm.state.value.thread.isEmpty())
        assertEquals("", vm.state.value.input)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `speech failures show plain messages`() {
        val expected = mapOf(
            SpeechFailure.NOTHING_HEARD to R.string.ask_voice_nothing_heard,
            SpeechFailure.ENGINE_ERROR to R.string.ask_voice_nothing_heard,
            SpeechFailure.PERMISSION_MISSING to R.string.ask_mic_permission_denied,
            SpeechFailure.NO_ON_DEVICE_ENGINE to R.string.ask_voice_unavailable,
            SpeechFailure.RECOGNIZER_BUSY to R.string.log_voice_busy,
        )
        for ((failure, text) in expected) {
            val vm = viewModel()
            vm.listen(SpeechEvent.Failed(failure))
            assertEquals(UserMessage(text), vm.state.value.message, "failure $failure")
            assertFalse(vm.state.value.isListening)
            store.clear()
        }
    }

    @Test
    fun `a cancelled session shows no message`() {
        val vm = viewModel()

        vm.listen(SpeechEvent.Failed(SpeechFailure.CANCELLED))

        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `microphone permission denied and blocked show messages and dismiss clears them`() {
        val vm = viewModel()

        vm.onMicrophonePermissionDenied()
        assertEquals(UserMessage(R.string.ask_mic_permission_denied), vm.state.value.message)
        vm.dismissMessage()
        assertNull(vm.state.value.message)

        vm.onMicrophonePermissionBlocked()
        assertEquals(UserMessage(R.string.ask_mic_permission_blocked), vm.state.value.message)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `asking stops listening first`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        val vm = viewModel()
        vm.listen(SpeechEvent.PartialTranscript("x"))
        vm.onInputChange(question)

        vm.ask()
        scheduler.runCurrent()

        assertFalse(vm.state.value.isListening)
        assertFalse(transcriber.isOpen)
    }

    @Test
    fun `clear empties the thread and a late answer after clear is dropped`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        extractor.holdAnswers()
        val vm = viewModel()
        vm.say(question)
        vm.onMicrophonePermissionDenied()

        vm.clear()
        extractor.release()
        scheduler.runCurrent()

        val state = vm.state.value
        assertTrue(state.thread.isEmpty())
        assertNull(state.message)
        assertFalse(state.isAsking)
        assertFalse(state.isListening)
    }

    @Test
    fun `clear stops listening and a new question can be asked afterwards`() {
        extractor.fails(InterpreterFailureKind.OTHER)
        val vm = viewModel()
        vm.listen(SpeechEvent.PartialTranscript("x"))

        vm.clear()
        scheduler.runCurrent()
        assertFalse(transcriber.isOpen)
        vm.say(question)

        assertEquals(1, vm.state.value.thread.size)
    }

    @Test
    fun `clearing the view model closes the microphone`() {
        val vm = viewModel()
        vm.listen(SpeechEvent.PartialTranscript("x"))
        assertTrue(transcriber.isOpen)

        store.clear()
        scheduler.runCurrent()

        assertFalse(transcriber.isOpen)
    }

    @Test
    fun `asking never writes to the ledger`() {
        logMowing(2)
        val before = lookupEntriesOf(ledger)
        val historyBefore = runSuspend { ledger.loadHistory() }
        extractor.answers("lawn", "mow")
        val vm = viewModel()

        vm.say(question)
        vm.say("I did something")
        vm.listen(final("What did I do last?"))
        vm.clear()

        assertTrue(tags.writeAttempts.isEmpty(), "writes attempted: ${tags.writeAttempts}")
        assertEquals(before, lookupEntriesOf(ledger))
        assertEquals(historyBefore, runSuspend { ledger.loadHistory() })
        assertNotNull(before.firstOrNull())
    }
}
