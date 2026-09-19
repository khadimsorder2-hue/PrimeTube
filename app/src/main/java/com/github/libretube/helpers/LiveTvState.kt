package com.github.libretube.helpers

/**
 * PrimeTube: lightweight in-process state of the Live TV playback service.
 * The app runs everything in a single process, so a plain object is enough
 * to let MainActivity know whether a live channel is playing in background
 * and which one it is (for the mini bar).
 */
object LiveTvState {
    @Volatile
    var isActive: Boolean = false

    @Volatile
    var channelName: String? = null

    @Volatile
    var channelLogo: String? = null
}
