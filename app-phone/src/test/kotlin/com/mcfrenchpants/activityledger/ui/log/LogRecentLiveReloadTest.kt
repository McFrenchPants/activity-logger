package com.mcfrenchpants.activityledger.ui.log

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.createInMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCaptureOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Log's Recent list follows ledger changes made elsewhere (the watch receiver, the foreground
 * catch-up) while the screen is started, and the screen's own capture waits for the shared
 * processing lock so it is never interpreted at the same time as anything else.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LogRecentLiveReloadTest {

    private val zone = ZoneId.of("America/Detroit")
    private val now: Instant = ZonedDateTime.of(2026, 10, 14, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(now, zone)
    private val ledger = createInMemoryActivityRepository(ApplicationProvider.getApplicationContext<Context>(), clock)
    private val repository = RecordingLedger(ledger)
    private val extractor = ScriptedExtractor()
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val store = ViewModelStore()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val lock = Mutex()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun started(): LogViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(
                repository = repository,
                orchestrator = TaggedCaptureOrchestrator(repository, extractor, clock),
                resolution = TaggedResolutionService(repository, clock),
                correction = TaggedCorrectionService(repository, clock),
                transcriber = ScriptedTranscriber(),
                clock = clock,
                isAiReady = { true },
                zone = { zone },
                locale = { Locale.US },
                processingLock = lock,
                changes = changes,
            ) as T
        }
        return ViewModelProvider(store, factory)[LogViewModel::class.java].also {
            it.onStart()
            scheduler.advanceUntilIdle()
        }
    }

    /** A watch capture written by someone other than the Log screen. */
    private fun writeElsewhere(text: String) = runSuspend {
        ledger.createRawCapture(
            NewRawCapture(CaptureSource.WATCH_VOICE, "watch-app", now, zone, text, null, null, ProcessingState.CAPTURED),
        )
    }

    @Test
    fun recentReloadsAfterALedgerChangeWhileStarted() {
        val vm = started()
        assertTrue(vm.state.value.recent.isEmpty())

        writeElsewhere("walked the dog")
        changes.tryEmit(Unit)
        scheduler.advanceUntilIdle()

        assertEquals(listOf("walked the dog"), vm.state.value.recent.map { it.rawText })
    }

    @Test
    fun recentDoesNotWatchChangesWhileStopped() {
        val vm = started()
        vm.onStop()
        scheduler.advanceUntilIdle()

        writeElsewhere("walked the dog")
        changes.tryEmit(Unit)
        scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())

        vm.onStart()
        scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.recent.size)
    }

    @Test
    fun typedCaptureWaitsForTheSharedProcessingLock() {
        val vm = started()
        assertTrue(lock.tryLock())

        vm.onInputChange("walked the dog")
        vm.submit()
        scheduler.advanceUntilIdle()
        assertEquals(0, extractor.callCount)
        assertTrue(repository.created.isEmpty())
        assertTrue(vm.state.value.isCapturing)

        lock.unlock()
        scheduler.advanceUntilIdle()
        assertEquals(1, repository.created.size)
        assertEquals(1, extractor.callCount)
        assertFalse(vm.state.value.isCapturing)
    }
}
