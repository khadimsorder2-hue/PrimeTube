package com.github.libretube.ui.activities

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.ActivityLiveTvPlayerBinding
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.LiveTvHelper
import com.github.libretube.helpers.LiveTvState
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.ui.adapters.LiveTVAdapter
import com.github.libretube.ui.models.LiveChannel
import com.github.libretube.ui.views.SafeLinearLayoutManager
import com.github.libretube.services.LiveTvPlaybackService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PrimeTube: YouTube-like player for the Live TV (IPTV) channels.
 *
 * - playback runs in [com.github.libretube.services.LiveTvPlaybackService] so
 *   leaving the activity keeps the channel playing (mini bar in MainActivity)
 * - prev/next channel, playlist side panel, quality dialog (HLS tracks),
 *   fill/fit toggle, brightness/volume swipe gestures
 * - on error: retry twice, then automatically switch to the next channel
 * - without internet: overlay + no playback
 */
class LiveTVPlayerActivity : AppCompatActivity() {

    private var _binding: ActivityLiveTvPlayerBinding? = null
    private val binding get() = _binding!!

    private var controller: MediaController? = null
    private var channels: List<LiveChannel> = emptyList()
    private var currentIndex = -1
    private var errorRetries = 0

    /** PrimeTube: consecutive automatic channel hops caused by playback errors. */
    private var autoHops = 0
    private var isFillMode = true
    private var lockedQualityHeight = Int.MAX_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private var queueAdapter: LiveTVAdapter? = null

    // gesture state
    private var gestureActive = false
    private var gestureBrightness = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var baseBrightness = 0.5f
    private var baseVolume = 0f

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                errorRetries = 0
                autoHops = 0
                _binding?.livePlayerError?.isVisible = false
                syncQueueHighlight()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // PrimeTube: retry twice, then hop to the next channel.
            // After too many dead channels in a row, stop and show the retry
            // overlay instead of looping through the whole playlist forever.
            if (errorRetries < 2) {
                errorRetries++
                toast(getString(R.string.prime_live_retrying, errorRetries))
                handler.postDelayed({
                    if (_binding == null) return@postDelayed
                    controller?.let {
                        it.prepare()
                        it.play()
                    }
                }, 1200)
            } else if (autoHops < MAX_AUTO_HOPS) {
                autoHops++
                toast(R.string.prime_live_retry_next)
                handler.postDelayed({ skipChannel(+1, autoHop = true) }, 800)
            } else {
                autoHops = 0
                _binding?.livePlayerError?.isVisible = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityLiveTvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        binding.liveBack.setOnClickListener { finish() }
        binding.livePrev.setOnClickListener { skipChannel(-1) }
        binding.liveNext.setOnClickListener { skipChannel(+1) }
        binding.livePlayerRetry.setOnClickListener { retryNow() }
        binding.liveNoInternetRetry.setOnClickListener { retryNow() }
        binding.liveFill.setOnClickListener { toggleFillMode() }
        binding.liveQuality.setOnClickListener { showQualityDialog() }
        binding.liveQueue.setOnClickListener { toggleQueuePanel() }
        binding.liveQueueClose.setOnClickListener { binding.liveQueueRoot.isGone = true }

        setupGestures()
        loadChannelsAndStart()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun loadChannelsAndStart() {
        val startName = intent.getStringExtra(EXTRA_NAME)
        val startUrl = intent.getStringExtra(EXTRA_URL)
        val startLogo = intent.getStringExtra(EXTRA_LOGO)
        val startIndex = intent.getIntExtra(EXTRA_INDEX, -1)

        // instant fallback list from the click, replaced by the full cached list
        if (startUrl != null) {
            channels = listOf(LiveChannel(startName.orEmpty(), startUrl, startLogo))
            currentIndex = 0
        }

        setupQueuePanel()

        lifecycleScope.launchWhenCreated {
            val cached = runCatching {
                LiveTvHelper.fetchChannels(applicationContext)
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                channels = cached
                currentIndex = startIndex.takeIf { it in cached.indices }
                    ?: cached.indexOfFirst { it.url == startUrl }.takeIf { it >= 0 }
                    ?: 0
            }
            connectToService()
        }
    }

    private fun connectToService() {
        if (controller != null) {
            startOrResume()
            return
        }
        BackgroundHelper.startMediaService(
            applicationContext,
            LiveTvPlaybackService::class.java
        ) { c ->
            // PrimeTube fix: the callback of startMediaService runs on a
            // background executor. Touching views from there crashes with
            // CalledFromWrongThreadException, so everything is marshalled
            // to the main thread first.
            handler.post {
                val b = _binding ?: return@post
                this.controller = c
                c.addListener(playerListener)

                // PrimeTube fix (the big one): the controller was never
                // attached to the PlayerView, so no video ever rendered and
                // the controls stayed dead. This line makes the picture and
                // the YouTube-like UI appear.
                b.livePlayerView.player = c
                b.livePlayerView.controllerShowTimeoutMs = 3500
                startOrResume()
            }
        }
    }

    /** Continue the already playing session (mini bar -> full player) or start the clicked channel. */
    private fun startOrResume() {
        val c = controller ?: return
        val current = c.currentMediaItem
        if (current != null && c.playbackState != Player.STATE_IDLE &&
            c.mediaMetadata.extras?.getInt(EXTRA_INDEX, -1) != -1
        ) {
            // resume UI sync with the session that is already playing
            currentIndex = c.mediaMetadata.extras?.getInt(EXTRA_INDEX, currentIndex) ?: currentIndex
            binding.liveTitle.text = c.mediaMetadata.title
            updateHeaderLogo()
            syncQueueHighlight()
            return
        }
        if (currentIndex !in channels.indices) currentIndex = 0
        playChannel(currentIndex)
    }

    private fun playChannel(index: Int, autoHop: Boolean = false) {
        val c = controller ?: return
        val channel = channels.getOrNull(index) ?: return
        currentIndex = index
        errorRetries = 0
        if (!autoHop) autoHops = 0
        binding.liveTitle.text = channel.name
        updateHeaderLogo()

        // PrimeTube: keep the mini bar state in sync
        LiveTvState.channelName = channel.name
        LiveTvState.channelLogo = channel.logo

        // PrimeTube: without internet there is no play
        if (!NetworkHelper.isNetworkAvailable(this)) {
            showNoInternet()
            return
        }
        binding.liveNoInternet.isGone = true
        binding.livePlayerError.isGone = true

        val extras = Bundle().apply {
            putInt(EXTRA_INDEX, index)
            putString(EXTRA_LOGO, channel.logo)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setArtist(getString(R.string.prime_live_tv))
            .setExtras(extras)
            .build()

        val builder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(metadata)
        if (channel.url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)

        c.setMediaItem(builder.build())
        c.prepare()
        c.play()
        syncQueueHighlight()
    }

    private fun updateHeaderLogo() {
        val logo = channels.getOrNull(currentIndex)?.logo
            ?: controller?.mediaMetadata?.extras?.getString(EXTRA_LOGO)
        if (logo != null) ImageHelper.loadImage(logo, binding.liveLogo)
    }

    private fun skipChannel(dir: Int, autoHop: Boolean = false) {
        if (channels.isEmpty()) return
        val target = (if (currentIndex < 0) 0 else currentIndex + dir).mod(channels.size)
        playChannel(target, autoHop)
    }

    private fun retryNow() {
        if (!NetworkHelper.isNetworkAvailable(this)) {
            showNoInternet()
            return
        }
        binding.liveNoInternet.isGone = true
        binding.livePlayerError.isGone = true
        controller?.let {
            it.prepare()
            it.play()
        }
    }

    private fun showNoInternet() {
        binding.liveNoInternet.isVisible = true
        binding.livePlayerError.isGone = true
        controller?.pause()
        toast(R.string.prime_live_no_internet)
    }

    // ---------- fill / fit ----------

    private fun toggleFillMode() {
        isFillMode = !isFillMode
        binding.livePlayerView.resizeMode = if (isFillMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        toast(if (isFillMode) R.string.prime_live_fill else R.string.prime_live_fit)
    }

    // ---------- quality ----------

    private fun showQualityDialog() {
        val c = controller ?: return
        val available = c.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        if (available.isEmpty()) {
            toast(R.string.prime_live_no_quality)
            return
        }

        val options = mutableListOf(getString(R.string.prime_live_auto)).apply {
            addAll(available.map { "$it p" })
        }
        val checked = if (lockedQualityHeight == Int.MAX_VALUE) 0
        else available.indexOfFirst { it == lockedQualityHeight }.coerceAtLeast(0) + 1

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                lockedQualityHeight = if (which == 0) Int.MAX_VALUE else available[which - 1]
                c.trackSelectionParameters = c.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, lockedQualityHeight)
                    .build()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- channels side panel ----------

    private fun setupQueuePanel() {
        val adapter = LiveTVAdapter { _, index ->
            binding.liveQueueRoot.isGone = true
            playChannel(index)
        }
        binding.liveQueueRecycler.layoutManager = SafeLinearLayoutManager(this)
        binding.liveQueueRecycler.adapter = adapter
        queueAdapter = adapter
        adapter.submitList(channels)
        syncQueueHighlight()
    }

    private fun toggleQueuePanel() {
        val root = binding.liveQueueRoot
        if (root.isVisible) {
            root.isGone = true
            return
        }
        queueAdapter?.submitList(channels)
        syncQueueHighlight()

        // PrimeTube: same 25% transparent corner panel as the YouTube player
        val playerWidth = window.decorView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val minWidth = (resources.displayMetrics.density * 150).toInt()
        binding.liveQueuePanel.layoutParams = binding.liveQueuePanel.layoutParams.apply {
            width = maxOf((playerWidth * 0.25f).toInt(), minWidth)
        }
        root.isVisible = true

        val current = currentIndex
        if (current >= 0) {
            binding.liveQueueRecycler.post {
                binding.liveQueueRecycler.scrollToPosition(current)
            }
        }
    }

    private fun syncQueueHighlight() {
        queueAdapter?.setCurrentUrl(channels.getOrNull(currentIndex)?.url)
    }

    // ---------- brightness / volume gestures ----------

    private fun setupGestures() {
        binding.livePlayerView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = gestureStartY - event.y
                    if (!gestureActive && abs(dy) > 55 &&
                        abs(dy) > abs(event.x - gestureStartX) * 1.4f
                    ) {
                        gestureActive = true
                        gestureBrightness = event.x < v.width / 2f
                        baseBrightness = window.attributes.screenBrightness
                            .takeIf { it >= 0 } ?: 0.5f
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        baseVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    }
                    if (gestureActive) {
                        val ratio = dy / v.height
                        if (gestureBrightness) applyBrightness(ratio) else applyVolume(ratio)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureActive) {
                        gestureActive = false
                        handler.postDelayed({ _binding?.liveGesturePill?.isGone = true }, 700)
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun applyBrightness(ratio: Float) {
        val value = (baseBrightness + ratio).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = value }
        showGesturePill(getString(R.string.prime_gesture_brightness, (value * 100).toInt()))
    }

    private fun applyVolume(ratio: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (baseVolume + ratio * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        showGesturePill(getString(R.string.prime_gesture_volume, target * 100 / max))
    }

    private fun showGesturePill(text: String) {
        binding.liveGesturePill.text = text
        binding.liveGesturePill.isVisible = true
        binding.liveGesturePill.gravity = Gravity.CENTER
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        // PrimeTube: only the connection is closed - the channel keeps
        // playing in LiveTvPlaybackService (mini bar in MainActivity).
        controller?.release()
        controller = null
        _binding = null
    }

    companion object {
        /** PrimeTube: stop auto-hopping after this many dead channels in a row. */
        private const val MAX_AUTO_HOPS = 4

        const val EXTRA_NAME = "live_tv_name"
        const val EXTRA_URL = "live_tv_url"
        const val EXTRA_LOGO = "live_tv_logo"
        const val EXTRA_INDEX = "live_tv_index"

        fun start(context: Context, index: Int, name: String, url: String, logo: String?) {
            context.startActivity(
                Intent(context, LiveTVPlayerActivity::class.java)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_LOGO, logo)
            )
        }
    }
}
