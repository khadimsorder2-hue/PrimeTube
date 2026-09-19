package com.github.libretube.services

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

        // PrimeTube: IPTV hardened HTTP stack.
        // - cross-protocol redirects (http<->https) are essential: plenty of
        //   IPTV servers redirect and ExoPlayer refuses them by default
        // - a browser-ish User-Agent avoids simple UA filters on some servers
        // - longer timeouts keep flaky CDN origins from dying mid-buffer
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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

    companion object {
        /** PrimeTube: browser-like UA so IPTV servers with UA filters let us through. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 PrimeTube/1.0"
    }
}
