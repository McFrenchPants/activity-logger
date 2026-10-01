package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.InMemoryOutboxStore
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.MAX_OUTBOX_RECORDS
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.OutboxRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutboxCaptureSinkFullTest {
    private fun id(n: Int) = java.util.UUID(0L, n.toLong()).toString()

    private fun filled(failedIndexes: Set<Int>): Pair<InMemoryOutboxStore, Outbox> {
        val store = InMemoryOutboxStore()
        for (i in 1..MAX_OUTBOX_RECORDS) {
            val state = if (i in failedIndexes) OutboxState.FAILED else OutboxState.QUEUED
            store.insertIfAbsent(OutboxRecord(id(i), "{}", state, 0, 0L, i.toLong()))
        }
        return store to Outbox(store, { 1_000_000L })
    }

    private fun sink(outbox: Outbox, wakes: IntArray) =
        OutboxCaptureSink(outbox, { id(9999) }, { 1_000_000L }, onEnqueued = { wakes[0]++ })

    @Test
    fun fullWithFailedRecordDiscardsOnlyTheOldestFailed() = runTest {
        val (store, outbox) = filled(setOf(5, 9))
        val wakes = IntArray(1)
        val result = sink(outbox, wakes).enqueue(WatchTranscript("hello", 0.9f, emptyList()))
        assertEquals(id(9999), result)
        assertNull(store.get(id(5)))
        assertNotNull(store.get(id(9)))
        assertNotNull(store.get(id(9999)))
        assertEquals(1, wakes[0])
        assertEquals(MAX_OUTBOX_RECORDS, store.listAll().size)
    }

    @Test
    fun fullWithNoFailedRecordThrowsAndDiscardsNothing() = runTest {
        val (store, outbox) = filled(emptySet())
        val wakes = IntArray(1)
        try {
            sink(outbox, wakes).enqueue(WatchTranscript("SECRET-words", 0.9f, emptyList()))
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertFalse(e.message.orEmpty().contains("SECRET"))
        }
        assertEquals(MAX_OUTBOX_RECORDS, store.listAll().size)
        assertTrue(store.listAll().all { it.state == OutboxState.QUEUED })
        assertEquals(0, wakes[0])
    }
}
