package com.github.libretube.helpers

import android.content.Context
import com.github.libretube.ui.models.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PrimeTube: fetches and caches the Live TV (IPTV) playlist.
 *
 * - the playlist is cached in the app cache dir so the channels grid opens
 *   instantly and also works offline after the first successful load
 * - if the network fetch fails, the last known-good playlist is used
 */
object LiveTvHelper {

    const val PLAYLIST_URL =
        "https://raw.githubusercontent.com/eishakilei-bd08/soha/main/t.m3u"
    private const val CACHE_FILE = "primetube_live_tv.m3u"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchChannels(
        context: Context,
        forceRefresh: Boolean = false
    ): List<LiveChannel> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, CACHE_FILE)

        if (!forceRefresh && cache.isFile && cache.length() > 0) {
            runCatching { M3uParser.parse(cache.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@withContext it }
        }

        val text = runCatching {
            client.newCall(
                Request.Builder().url(PLAYLIST_URL).build()
            ).execute().use { response -> response.body?.string().orEmpty() }
        }.getOrNull().orEmpty()

        val channels = M3uParser.parse(text)
        if (channels.isNotEmpty()) {
            runCatching { cache.writeText(text) }
        } else if (cache.isFile) {
            // fall back to the last known-good playlist
            runCatching { M3uParser.parse(cache.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@withContext it }
        }
        channels
    }
}
