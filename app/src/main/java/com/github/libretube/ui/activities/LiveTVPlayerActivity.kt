package com.github.libretube.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.libretube.R
import com.github.libretube.databinding.ActivityLiveTvPlayerBinding
import com.github.libretube.helpers.ImageHelper

/**
 * PrimeTube: dedicated, low-memory full-screen player for the Live TV (IPTV)
 * channels. Kept completely separate from the YouTube player so that IPTV
 * streams (HLS / progressive) can never break normal video playback.
 */
class LiveTVPlayerActivity : AppCompatActivity() {

    private var _binding: ActivityLiveTvPlayerBinding? = null
    private val binding get() = _binding!!
    private var exoPlayer: ExoPlayer? = null
    private var streamUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityLiveTvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        binding.liveTitle.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        intent.getStringExtra(EXTRA_LOGO)?.let {
            ImageHelper.loadImage(it, binding.liveLogo)
        }
        streamUrl = intent.getStringExtra(EXTRA_URL)

        binding.liveBack.setOnClickListener { finish() }
        binding.livePlayerRetry.setOnClickListener {
            binding.livePlayerError.visibility = android.view.View.GONE
            preparePlayer()
        }

        preparePlayer()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun preparePlayer() {
        val url = streamUrl ?: return finish()
        binding.livePlayerError.visibility = android.view.View.GONE

        val player = exoPlayer ?: ExoPlayer.Builder(this).build().also {
            exoPlayer = it
            binding.livePlayerView.player = it
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(playerListener)
        }

        val builder = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@LiveTVPlayerActivity,
                R.string.prime_live_player_error,
                Toast.LENGTH_SHORT
            ).show()
            _binding?.livePlayerError?.visibility = android.view.View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        _binding?.livePlayerView?.player = null
        _binding = null
    }

    companion object {
        const val EXTRA_NAME = "live_tv_name"
        const val EXTRA_URL = "live_tv_url"
        const val EXTRA_LOGO = "live_tv_logo"

        fun start(context: Context, name: String, url: String, logo: String?) {
            context.startActivity(
                Intent(context, LiveTVPlayerActivity::class.java)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_LOGO, logo)
            )
        }
    }
}
