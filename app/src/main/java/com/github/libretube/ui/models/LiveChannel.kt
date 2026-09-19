package com.github.libretube.ui.models

/**
 * PrimeTube: a single channel of the Live TV (IPTV) playlist.
 */
data class LiveChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null
)
