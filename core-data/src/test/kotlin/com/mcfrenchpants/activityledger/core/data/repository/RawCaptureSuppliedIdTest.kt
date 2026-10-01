package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** Raw-capture creation with a caller-supplied id (the watch captureId). Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class RawCaptureSuppliedIdTest {

    private class SteppingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: RoomActivityRepository
    private val clock = SteppingClock(Instant.ofEpochMilli(1_000_000L))
    private val ids = DeterministicIdFactory(next = 0x20_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, ids, clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun capture(text: String = "synthetic words", id: String? = null) = NewRawCapture(
        source = CaptureSource.WATCH_VOICE,
        sourceSurface = null,
        capturedAt = Instant.ofEpochMilli(500_000L),
        zoneId = ZoneId.of("UTC"),
        rawText = text,
        speechConfidence = null,
        speechAlternativesJson = null,
        processingState = ProcessingState.CAPTURED,
        id = id,
    )

    private fun rowCount(): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM raw_captures").use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    @Test
    fun suppliedIdBecomesRowId() = runBlocking {
        val id = repository.createRawCapture(capture(id = "watch-capture-1"))
        assertEquals("watch-capture-1", id)
        assertNotNull(db.rawCaptureDao().getById("watch-capture-1"))
        assertEquals(1, rowCount())
    }

    @Test
    fun repeatWithSameIdWritesNothingEvenWithDifferentText() = runBlocking {
        repository.createRawCapture(capture("first words", id = "c-1"))
        val before = db.rawCaptureDao().getById("c-1")!!
        clock.now = Instant.ofEpochMilli(9_000_000L)

        val again = repository.createRawCapture(capture("other words", id = "c-1"))

        assertEquals("c-1", again)
        assertEquals(1, rowCount())
        val after = db.rawCaptureDao().getById("c-1")!!
        assertEquals("first words", after.rawText)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.processingState, after.processingState)
        assertEquals(before, after)
    }

    @Test
    fun concurrentSameIdCreatesYieldOneRowAndNoException() = runBlocking {
        val concurrent = RoomActivityRepository(db, ids, clock, Dispatchers.IO)
        val results = (1..8).map { n ->
            async(Dispatchers.IO) { concurrent.createRawCapture(capture("words $n", id = "c-race")) }
        }.awaitAll()
        assertEquals(List(8) { "c-race" }, results)
        assertEquals(1, rowCount())
    }

    @Test
    fun blankSuppliedIdIsRefusedWithoutEchoingText() = runBlocking {
        val secret = "synthetic secret words"
        val e = assertFailsWith<IllegalArgumentException> {
            repository.createRawCapture(capture(secret, id = "  "))
        }
        assertFalse(e.message.orEmpty().contains(secret))
        assertEquals(0, rowCount())
    }

    @Test
    fun nullIdStillGeneratesDistinctIds() = runBlocking {
        val a = repository.createRawCapture(capture())
        val b = repository.createRawCapture(capture())
        assertNotEquals(a, b)
        assertEquals(2, rowCount())
    }
}
