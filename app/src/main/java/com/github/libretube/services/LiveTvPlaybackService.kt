package com.github.libretube.services

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.github.libretube.helpers.LiveTvState

/**
 * PrimeTube: background playback service for the Live TV (IPTV) player.
 *
 * Owning the ExoPlayer in a MediaSessionService decouples playback from the
 * player activity: leaving the activity keeps the channel playing (with the
 * mini bar in the main screen) exactly like the YouTube app.
 */
class LiveTvPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        LiveTvState.isActive = true

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady && player.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        LiveTvState.isActive = false
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
