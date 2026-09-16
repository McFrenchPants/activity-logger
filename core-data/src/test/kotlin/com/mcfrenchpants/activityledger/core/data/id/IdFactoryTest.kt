package com.mcfrenchpants.activityledger.core.data.id

import java.util.UUID
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdFactoryTest {
    private val canonical = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Test
    fun productionIdsAreCanonicalLowercaseUuidV7() {
        repeat(1_000) {
            val id = UuidV7IdFactory.newId()
            assertEquals(36, id.length, id)
            assertEquals(id.lowercase(), id)
            assertTrue(canonical.matches(id), id)
            val parsed = UUID.fromString(id)
            assertEquals(id, parsed.toString())
            assertEquals(7, parsed.version(), "version nibble of $id")
            assertEquals(2, parsed.variant(), "RFC 9562 variant of $id")
        }
    }

    @Test
    fun productionIdsSortNonDecreasingAsStrings() {
        val ids = List(10_000) { UuidV7IdFactory.newId() }
        ids.zipWithNext().forEach { (a, b) -> assertTrue(a <= b, "$a then $b") }
        assertEquals(ids.size, ids.toSet().size, "IDs must be unique")
    }

    @Test
    fun deterministicFactoryIsRepeatableAndValid() {
        val a = DeterministicIdFactory()
        val b = DeterministicIdFactory()
        val first = List(3) { a.newId() }
        assertEquals(first, List(3) { b.newId() })
        assertEquals("00000000-0000-7000-8000-000000000001", first[0])
        first.forEach {
            assertTrue(canonical.matches(it), it)
            UUID.fromString(it)
        }
        assertEquals(first.sorted(), first)
    }
}
