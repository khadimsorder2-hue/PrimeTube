package com.github.libretube.helpers

import android.content.Context
import android.net.Uri
import com.github.libretube.ui.models.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * PrimeTube: Live TV (IPTV) source + playlist manager.
 *
 * Sources:
 * - the user can add multiple playlist sources: an M3U link, an M3U8 link,
 *   a JSON link or a local playlist FILE picked from the device (SAF)
 * - the active source is switchable at any time, every source keeps its own
 *   cache file so switching is instant and works offline after first load
 * - a fresh install has the built-in default M3U playlist
 *
 * Playlist formats:
 * - M3U / M3U8 text playlists  -> [M3uParser]
 * - JSON channel lists         -> [LiveJsonParser]
 */
object LiveTvHelper {

    const val DEFAULT_SOURCE_URL =
        "https://raw.githubusercontent.com/eishakilei-bd08/soha/main/t.m3u"
    const val DEFAULT_SOURCE_ID = "default"
    const val DEFAULT_SOURCE_NAME = "Default playlist"

    const val TYPE_M3U = "m3u"
    const val TYPE_M3U8 = "m3u8"
    const val TYPE_JSON = "json"
    const val TYPE_FILE = "file"

    private const val PREFS = "primetube_live_tv"
    private const val KEY_SOURCES = "sources"
    private const val KEY_ACTIVE = "active"
    private const val CACHE_PREFIX = "primetube_live_"

    /** PrimeTube: one user-configured Live TV playlist source. */
    data class LiveTvSource(
        val id: String,
        val name: String,
        val type: String,
        val uri: String
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- sources ----------------

    /**
     * All configured sources. Fresh installs only have the built-in default
     * M3U playlist (not persisted - persisted as soon as the user adds one).
     */
    fun getSources(context: Context): List<LiveTvSource> {
        val stored = runCatching {
            val array = JSONArray(prefs(context).getString(KEY_SOURCES, "[]").orEmpty())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                LiveTvSource(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    type = obj.optString("type", TYPE_M3U),
                    uri = obj.optString("uri")
                )
            }.filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        }.getOrDefault(emptyList())

        return if (stored.isEmpty()) {
            listOf(LiveTvSource(DEFAULT_SOURCE_ID, DEFAULT_SOURCE_NAME, TYPE_M3U, DEFAULT_SOURCE_URL))
        } else {
            stored
        }
    }

    /** Adds a source and activates it. Returns false when the input is invalid. */
    fun addSource(context: Context, name: String, type: String, uri: String): Boolean {
        val cleanName = name.trim().ifBlank {
            when (type) {
                TYPE_M3U -> "M3U playlist"
                TYPE_M3U8 -> "M3U8 playlist"
                TYPE_JSON -> "JSON playlist"
                else -> "Local playlist"
            }
        }
        val cleanUri = uri.trim()
        if (cleanUri.isEmpty()) return false
        if (type != TYPE_FILE && !cleanUri.startsWith("http://") && !cleanUri.startsWith("https://")) {
            return false
        }

        val sources = getSources(context).toMutableList()
        sources.removeAll { it.id == DEFAULT_SOURCE_ID && it.uri == DEFAULT_SOURCE_URL && sources.size > 1 }
        val source = LiveTvSource(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = cleanName,
            type = type,
            uri = cleanUri
        )
        sources.add(source)

        val array = JSONArray()
        sources.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("type", it.type)
                    .put("uri", it.uri)
            )
        }
        prefs(context).edit()
            .putString(KEY_SOURCES, array.toString())
            .putString(KEY_ACTIVE, source.id)
            .apply()
        return true
    }

    /** Removes a source (the default playlist can never be removed). */
    fun removeSource(context: Context, id: String) {
        if (id == DEFAULT_SOURCE_ID) return
        val sources = getSources(context).filter { it.id != id }
        if (sources.isEmpty()) return

        val array = JSONArray()
        sources.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("type", it.type)
                    .put("uri", it.uri)
            )
        }
        val editor = prefs(context).edit().putString(KEY_SOURCES, array.toString())
        if (getActiveSource(context).id == id) {
            editor.putString(KEY_ACTIVE, sources.first().id)
            cacheFile(context, id).delete()
        }
        editor.apply()
    }

    /** Switches the active playlist source ("toggle" between sources). */
    fun setActiveSource(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun getActiveSource(context: Context): LiveTvSource {
        val activeId = prefs(context).getString(KEY_ACTIVE, null)
        val sources = getSources(context)
        return sources.firstOrNull { it.id == activeId } ?: sources.first()
    }

    // ---------------- channels ----------------

    private fun cacheFile(context: Context, sourceId: String): File {
        val safe = Integer.toHexString(sourceId.hashCode())
        return File(context.cacheDir, "$CACHE_PREFIX$safe.dat")
    }

    private fun parseFor(type: String, text: String): List<LiveChannel> = when (type) {
        TYPE_JSON -> LiveJsonParser.parse(text)
        // M3U and M3U8 channel lists share the same text format
        else -> M3uParser.parse(text)
    }

    /**
     * Fetches the channels of the ACTIVE source. Cached text is used when
     * fresh loading fails, so the grid and the player always have channels.
     */
    suspend fun fetchChannels(
        context: Context,
        forceRefresh: Boolean = false
    ): List<LiveChannel> = withContext(Dispatchers.IO) {
        val source = getActiveSource(context)
        val cache = cacheFile(context, source.id)

        if (!forceRefresh && cache.isFile && cache.length() > 0) {
            runCatching { parseFor(source.type, cache.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@withContext it }
        }

        val text = loadSourceText(context, source)

        val channels = if (text.isNotBlank()) parseFor(source.type, text) else emptyList()
        if (channels.isNotEmpty()) {
            runCatching { cache.writeText(text) }
        } else if (cache.isFile) {
            // fall back to the last known-good playlist of this source
            runCatching { parseFor(source.type, cache.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@withContext it }
        }
        channels
    }

    private fun loadSourceText(context: Context, source: LiveTvSource): String {
        return runCatching {
            if (source.type == TYPE_FILE) {
                context.contentResolver
                    .openInputStream(Uri.parse(source.uri))
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            } else {
                client.newCall(
                    Request.Builder().url(source.uri).build()
                ).execute().use { response ->
                    response.body?.string().orEmpty()
                }
            }
        }.getOrDefault("")
    }
}
