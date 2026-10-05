package com.mcfrenchpants.activityledger.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import com.mcfrenchpants.activityledger.ui.explore.logExploreEntry
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [HistoryViewModel] keeps itself up to date while the screen is showing: a ledger change (a
 * [MutableSharedFlow] standing in for the database's change reports) leads, after the changes
 * settle, to a quiet reload that keeps the filter, an open sheet and messages. Real in-memory
 * ledger behind a load-counting wrapper, real services, virtual time.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLiveReloadTest {

    /** The real ledger, counting history loads; [gate] holds a load back after it has read. */
    private class CountingLedger(private val inner: LedgerRepository) : LedgerRepository by inner {
        var historyLoads = 0
        var failLoads = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun loadHistory(): List<HistoryEntry> {
            historyLoads++
            check(!failLoads) { "scripted load failure" }
            val read = inner.loadHistory()
            gate?.let { held ->
                gate = null
                held.await()
            }
            return read
        }
    }

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val repository = CountingLedger(ledger)
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun started(): HistoryViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(
                repository = repository,
                resolution = TaggedResolutionService(repository, clock),
                correction = TaggedCorrectionService(repository, clock),
                clock = clock,
                locale = { Locale.US },
                changes = changes,
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[HistoryViewModel::class.java]
        vm.onStart()
        scheduler.runCurrent()
        return vm
    }

    private val HistoryViewModel.s get() = state.value

    private fun HistoryViewModel.act(block: HistoryViewModel.() -> Unit) {
        block()
        scheduler.runCurrent()
    }

    /** A capture the background processing has not finished (it shows as waiting). */
    private fun waitingCapture(text: String = "synthetic words"): String = runSuspend {
        clock.advance(Duration.ofMinutes(1))
        ledger.createRawCapture(
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
    }

    /** A saved tagged entry; returns its capture id. */
    private fun savedEntry(subject: String, action: String): String {
        clock.advance(Duration.ofMinutes(1))
        val occurrence = logExploreEntry(ledger, clock, zone, subject, action)
        return runSuspend { ledger.loadHistory() }.single { it.occurrence?.occurrenceId == occurrence }.captureId
    }

    private fun changed() {
        check(changes.tryEmit(Unit))
        scheduler.runCurrent()
    }

    private fun pass(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun settled() = pass(SETTLE_MS + 1)

    @Test
    fun `a change while showing reloads the list after the changes settle`() {
        val vm = started()
        assertEquals(0, vm.s.allRows.size)

        val id = waitingCapture()
        changed()
        pass(SETTLE_MS - 1)
        assertEquals(1, repository.historyLoads, "nothing reloads before the changes settle")

        pass(2)
        assertEquals(2, repository.historyLoads)
        assertEquals(listOf(id), vm.s.allRows.map { it.captureId })
    }

    @Test
    fun `the filter and an open edit sheet survive a quiet reload`() {
        val entry = savedEntry("Hot tub", "Clean")
        val vm = started()
        vm.act { selectFilter(HistoryFilter.NOT_CATEGORIZED) }
        vm.act { openEdit(entry) }
        val edit = assertNotNull(vm.s.edit)

        val waiting = waitingCapture()
        changed()
        settled()

        assertEquals(HistoryFilter.NOT_CATEGORIZED, vm.s.filter)
        assertEquals(edit, vm.s.edit)
        assertEquals(listOf(waiting), vm.s.rows.map { it.captureId })
        assertEquals(2, vm.s.allRows.size)
    }

    @Test
    fun `an open check card and its draft survive a quiet reload`() {
        val waiting = waitingCapture()
        val vm = started()
        vm.act { openCheck(waiting) }
        val card = assertNotNull(vm.s.check)

        savedEntry("Lawn", "Mow")
        changed()
        settled()

        assertEquals(card, vm.s.check)
        assertEquals(2, vm.s.allRows.size)
    }

    @Test
    fun `after onStop changes are not handled`() {
        val vm = started()

        vm.onStop()
        waitingCapture()
        changed()
        settled()

        assertEquals(1, repository.historyLoads)
        assertEquals(0, vm.s.allRows.size)

        vm.act { onStart() }
        assertEquals(1, vm.s.allRows.size)
        waitingCapture()
        changed()
        settled()
        assertEquals(2, vm.s.allRows.size, "watching again after onStart")
    }

    @Test
    fun `a burst of changes causes one reload`() {
        val vm = started()

        repeat(5) {
            waitingCapture()
            changed()
            pass(100)
        }
        assertEquals(1, repository.historyLoads)
        settled()

        assertEquals(2, repository.historyLoads)
        assertEquals(5, vm.s.allRows.size)
    }

    @Test
    fun `a quiet reload that finishes after a newer load does not overwrite it`() {
        val vm = started()
        repository.gate = CompletableDeferred()
        val held = repository.gate

        waitingCapture()
        changed()
        settled()
        assertEquals(0, vm.s.allRows.size, "the quiet load read one capture and is held back")

        waitingCapture()
        vm.act { onStart() }
        assertEquals(2, vm.s.allRows.size)

        assertNotNull(held).complete(Unit)
        scheduler.runCurrent()
        assertEquals(2, vm.s.allRows.size, "the older result is dropped")
    }

    @Test
    fun `a failed quiet reload keeps the list`() {
        waitingCapture()
        val vm = started()
        val rows = vm.s.allRows

        repository.failLoads = true
        changed()
        settled()

        assertEquals(rows, vm.s.allRows)
        assertEquals(UserMessage(R.string.history_not_loaded), vm.s.message)

        repository.failLoads = false
        changed()
        settled()
        assertNull(vm.s.message)
    }

    @Test
    fun `a failed quiet reload says nothing while a sheet is open`() {
        val entry = savedEntry("Hot tub", "Clean")
        val vm = started()
        vm.act { openEdit(entry) }

        repository.failLoads = true
        changed()
        settled()

        assertNull(vm.s.message)
        assertNotNull(vm.s.edit)
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}
