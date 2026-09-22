package com.github.libretube.helpers

import com.github.libretube.api.obj.Streams
import java.util.concurrent.ConcurrentHashMap

/**
 * PrimeTube: tiny cache for fully loaded stream-info objects.
 *
 * While a video plays, the next video of the queue is preloaded in the
 * background. An instant cache hit here makes switching videos (next/prev/
 * autoplay/audio mode) start immediately instead of waiting for a fresh
 * extraction round-trip, which is by far the slowest step of playback.
 *
 * Entries expire quickly because stream URLs are short-lived, and the cache
 * holds at most [MAX_ENTRIES] objects to keep the memory footprint minimal.
 */
object PrimeStreamCache {
    private const val MAX_ENTRIES = 2
    private const val TTL_MS = 10 * 60 * 1000L

    private data class Entry(val streams: Streams, val storedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(videoId: String): Streams? {
        val entry = cache[videoId] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            cache.remove(videoId)
            return null
        }
        return entry.streams
    }

    @Synchronized
    fun put(videoId: String, streams: Streams) {
        // keep the cache tiny - drop the oldest entry when full
        if (cache.size >= MAX_ENTRIES) {
            cache.entries.minByOrNull { it.value.storedAt }?.let { cache.remove(it.key) }
        }
        cache[videoId] = Entry(streams, System.currentTimeMillis())
    }
}
