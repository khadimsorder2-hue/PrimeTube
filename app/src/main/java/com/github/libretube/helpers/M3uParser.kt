package com.github.libretube.helpers

import com.github.libretube.ui.models.LiveChannel

/**
 * PrimeTube: minimal, robust M3U/M3U8 playlist parser for the Live TV tab.
 *
 * Supports the common IPTV flavour:
 *   #EXTM3U
 *   #EXTINF:-1 tvg-name="..." tvg-logo="..." group-title="...",Channel Name
 *   https://example.com/stream.m3u8
 *
 * The display name is everything after the first comma that is NOT inside
 * double quotes, so channel names containing commas keep working.
 */
object M3uParser {

    private val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    /**
     * PrimeTube: playlist noise filter - promo/telegram entries and Google Drive
     * "channels" are not real TV channels and must never appear in the grid
     * (e.g. the "জয়েন করুন টেলিগ্রামে" first entry of the playlist).
     */
    private val skipRegex = Regex(
        "telegram|join|টেলিগ্রাম|googleapis\\.com/drive",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    val content = line.substringAfter("#EXTINF:")
                    val (meta, name) = splitOnUnquotedComma(content)
                    val attrs = attrRegex.findAll(meta).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingLogo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    pendingName = (name.ifBlank { attrs["tvg-name"].orEmpty() })
                        .ifBlank { meta.substringAfterLast(',').ifBlank { "Channel" } }
                        .trim()
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = line
                    val name = pendingName
                    val isNoise = skipRegex.containsMatchIn(name.orEmpty()) ||
                        skipRegex.containsMatchIn(url)
                    if (!isNoise &&
                        (url.startsWith("http://") || url.startsWith("https://")) &&
                        !name.isNullOrBlank()
                    ) {
                        channels.add(
                            LiveChannel(
                                name = name,
                                url = url,
                                logo = pendingLogo,
                                group = pendingGroup
                            )
                        )
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
        return channels
    }

    /** Returns (partBeforeFirstUnquotedComma, partAfterIt). */
    private fun splitOnUnquotedComma(content: String): Pair<String, String> {
        var inQuotes = false
        content.forEachIndexed { index, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return content.take(index) to content.substring(index + 1)
            }
        }
        return content to ""
    }
}
