package com.moosa.hub

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSACacheRetentionTest {
    private data class Item(val time: Long)

    @Test
    fun expiredEntriesRemovedWithoutWipingFreshEpisodeTargets() {
        val cache = ConcurrentHashMap<String, Item>()
        cache["old"] = Item(0)
        cache["a"] = Item(9_990)
        cache["b"] = Item(9_999)
        MOOSACacheRetention.makeRoom(cache, 3, 10_000, 5_000, Item::time)
        assertFalse(cache.containsKey("old"))
        assertTrue(cache.containsKey("a"))
        assertTrue(cache.containsKey("b"))
    }

    @Test
    fun whenFullEvictsOldestRatherThanEverySubtitle() {
        val cache = ConcurrentHashMap<String, Item>()
        cache["a"] = Item(9_700)
        cache["b"] = Item(9_800)
        cache["c"] = Item(9_900)
        MOOSACacheRetention.makeRoom(cache, 3, 10_000, 1_000, Item::time)
        assertFalse(cache.containsKey("a"))
        assertTrue(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        cache["d"] = Item(10_000)
        assertEquals(3, cache.size)
    }

    @Test
    fun belowLimitRetainsCompleteFreshCache() {
        val cache = ConcurrentHashMap<String, Item>()
        cache["one"] = Item(1)
        cache["two"] = Item(2)
        MOOSACacheRetention.makeRoom(cache, 128, 2, 100, Item::time)
        assertEquals(2, cache.size)
    }
}
