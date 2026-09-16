package com.mcfrenchpants.activityledger.core.data.id

/**
 * Test-only [IdFactory] producing predictable, ascending, valid-format UUID text:
 * 00000000-0000-7000-8000-000000000001, ...-000000000002, and so on.
 * Not thread-safe; intended for single-threaded tests.
 */
internal class DeterministicIdFactory(private var next: Long = 1L) : IdFactory {
    override fun newId(): String {
        val n = next++
        require(n in 0L..0xFFFF_FFFF_FFFFL) { "DeterministicIdFactory exhausted" }
        return "00000000-0000-7000-8000-%012x".format(n)
    }
}
