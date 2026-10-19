package com.moosa.hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MOOSAHomePageCacheTest {
    private val firstPage = """{"results":[{"id":1}]}"""
    private val updatedPage = """{"results":[{"id":2}]}"""

    @Test
    fun freshHomeShelfAvoidsRepeatNetworkRequests() = runBlocking {
        val clock = AtomicLong(1_000L)
        val cache = MOOSAHomePageCache(scope = this, now = clock::get)
        val calls = AtomicInteger(0)

        assertEquals(firstPage, cache.get("movies") {
            calls.incrementAndGet()
            firstPage
        })
        assertEquals(firstPage, cache.get("movies") {
            calls.incrementAndGet()
            updatedPage
        })
        assertEquals(1, calls.get())
    }

    @Test
    fun staleShelfReturnsImmediatelyAndRefreshesInBackground() = runBlocking {
        val clock = AtomicLong(1_000L)
        val cache = MOOSAHomePageCache(scope = this, now = clock::get)
        assertEquals(firstPage, cache.get("movies") { firstPage })

        clock.addAndGet(MOOSAHomeLoadPolicy.FRESH_HOME_CACHE_MS + 1L)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val displayed = withTimeout(1_000L) {
            cache.get("movies") {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                updatedPage
            }
        }
        assertEquals(firstPage, displayed)
        withTimeout(2_000L) { refreshStarted.await() }
        // Other reads while a refresh is running also remain immediate.
        assertEquals(firstPage, cache.get("movies") {
            throw IllegalStateException("a second refresh must not start")
        })
        releaseRefresh.complete(Unit)
        withTimeout(2_000L) {
            while (cache.get("movies") { updatedPage } != updatedPage) {
                delay(10L)
            }
        }
    }

    @Test
    fun concurrentColdLoadsShareOneNetworkFetch() = runBlocking {
        val cache = MOOSAHomePageCache(scope = this)
        val calls = AtomicInteger(0)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val first = async {
            cache.get("series") {
                calls.incrementAndGet()
                fetchStarted.complete(Unit)
                releaseFetch.await()
                firstPage
            }
        }
        withTimeout(2_000L) { fetchStarted.await() }
        val second = async {
            cache.get("series") {
                calls.incrementAndGet()
                updatedPage
            }
        }
        releaseFetch.complete(Unit)
        assertEquals(firstPage, withTimeout(2_000L) { first.await() })
        assertEquals(firstPage, withTimeout(2_000L) { second.await() })
        assertEquals(1, calls.get())
    }

    @Test
    fun invalidTmdbPayloadNeverReplacesCachedHome() = runBlocking {
        val clock = AtomicLong(1_000L)
        val cache = MOOSAHomePageCache(scope = this, now = clock::get)
        assertEquals(firstPage, cache.get("movies") { firstPage })
        clock.addAndGet(MOOSAHomeLoadPolicy.FRESH_HOME_CACHE_MS + 1L)
        assertEquals(firstPage, cache.get("movies") { """{"status_message":"rate limit"}""" })
        // A failed refresh must not blank previously valid posters.
        assertEquals(firstPage, cache.get("movies") { """{"status_message":"rate limit"}""" })
    }
}
