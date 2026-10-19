package com.moosa.hub

import java.util.concurrent.ConcurrentHashMap

/**
 * Keep lookup caches bounded on Fire TV without invalidating every entry
 * at once. Only touched when a cache approaches its configured limit.
 */
internal object MOOSACacheRetention {
    fun <K : Any, V : Any> makeRoom(
        cache: ConcurrentHashMap<K, V>,
        maxEntries: Int,
        now: Long,
        ttlMs: Long,
        createdAt: (V) -> Long
    ) {
        if (cache.size < maxEntries) return
        cache.entries.forEach { entry ->
            val age = now - createdAt(entry.value)
            if (age < 0 || age >= ttlMs) cache.remove(entry.key, entry.value)
        }
        val overflow = (cache.size - maxEntries + 1).coerceAtLeast(0)
        if (overflow > 0) {
            cache.entries.sortedBy { createdAt(it.value) }
                .take(overflow)
                .forEach { entry -> cache.remove(entry.key, entry.value) }
        }
    }
}
