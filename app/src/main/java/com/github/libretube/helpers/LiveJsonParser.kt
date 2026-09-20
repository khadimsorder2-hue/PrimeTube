package com.github.libretube.helpers

import com.github.libretube.ui.models.LiveChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * PrimeTube: flexible JSON playlist parser for the Live TV sources.
 *
 * Accepts the common JSON channel-list shapes:
 *   [ { "name": "...", "url": "...", "logo": "...", "group": "..." }, ... ]
 *   { "channels": [ ... ] } / { "data": [ ... ] } / { "items": [ ... ] }
 *
 * Key aliases are matched case-insensitively so feeds from different
 * providers parse without configuration.
 */
object LiveJsonParser {

    private val NAME_KEYS = listOf("name", "title", "channel", "channel_name", "channelname", "tvg-name", "display_name")
    private val URL_KEYS = listOf("url", "stream", "src", "link", "uri", "stream_url", "streamurl", "video_url")
    private val LOGO_KEYS = listOf("logo", "image", "icon", "tvg-logo", "tvglogo", "thumbnail", "poster")
    private val GROUP_KEYS = listOf("group", "category", "group-title", "grouptitle", "group_title", "genre")
    private val ARRAY_KEYS = listOf("channels", "data", "items", "streams", "result", "results", "list")

    fun parse(text: String): List<LiveChannel> {
        return runCatching { parseRoot(text.trim()) }.getOrDefault(emptyList())
    }

    private fun parseRoot(text: String): List<LiveChannel> {
        if (text.isEmpty()) return emptyList()

        val array: JSONArray = when {
            text.startsWith("[") -> JSONArray(text)
            else -> {
                val obj = JSONObject(text)
                ARRAY_KEYS.firstNotNullOfOrNull { obj.optJSONArray(it) } ?: return emptyList()
            }
        }

        val channels = mutableListOf<LiveChannel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = firstNonBlank(item, URL_KEYS)
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val name = firstNonBlank(item, NAME_KEYS).ifBlank { "Channel" }
            val logo = firstNonBlank(item, LOGO_KEYS).ifBlank { null }
            val group = firstNonBlank(item, GROUP_KEYS).ifBlank { null }
            channels.add(LiveChannel(name = name, url = url, logo = logo, group = group))
        }
        return channels
    }

    private fun firstNonBlank(item: JSONObject, keys: List<String>): String {
        for (key in keys) {
            for (candidate in listOf(key, key.lowercase(), key.uppercase())) {
                if (!item.has(candidate)) continue
                val value = runCatching { item.optString(candidate) }.getOrDefault("")
                if (value.isNotBlank()) return value.trim()
            }
        }
        return ""
    }
}
