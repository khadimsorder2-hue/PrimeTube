package com.github.libretube.ui.activities

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.ActivityLiveTvPlayerBinding
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.LiveTvHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.ui.adapters.LiveTVAdapter
import com.github.libretube.ui.models.LiveChannel
import com.github.libretube.ui.views.SafeLinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PrimeTube: YouTube-like player for the Live TV (IPTV) channels.
 *
 * PrimeTube rule: Live TV never plays in the background. The player is owned
 * by this activity and is fully released in [onStop], so leaving the screen
 * (home tab, app switch, screen off) always stops the stream - no service,
 * no mini bar, no notification.
 *
 * - prev/next channel, channels side panel, quality dialog (HLS tracks),
 *   fill/fit toggle, brightness/volume swipe gestures
 * - on error: retry twice, then automatically switch to the next channel
 * - without internet: overlay + no playback
 */
class LiveTVPlayerActivity : AppCompatActivity() {

    private var _binding: ActivityLiveTvPlayerBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null
    private var channels: List<LiveChannel> = emptyList()
    private var currentIndex = -1
    private var errorRetries = 0

    /** PrimeTube: every stream gets exactly one alternate-container retry. */
    private var mimeFallbackTried = false

    /** PrimeTube: consecutive automatic channel hops caused by playback errors. */
    private var autoHops = 0
    private var isFillMode = true
    private var lockedQualityHeight = Int.MAX_VALUE

    /** PrimeTube: set once a channel really started, so onResume can restart it. */
    private var everStarted = false

    private val handler = Handler(Looper.getMainLooper())
    private var queueAdapter: LiveTVAdapter? = null

    // PrimeTube: tap-to-show controls that auto-hide after 5 s
    private var controlsVisible = false
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    // PrimeTube: sleep timer state (Live TV stops when the timer fires)
    private var sleepEndTime: Long? = null
    private val sleepTickRunnable = object : Runnable {
        override fun run() {
            val end = sleepEndTime ?: return
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) {
                sleepEndTime = null
                _binding?.liveSleepChip?.text = getString(R.string.prime_sleep_chip)
                toast(R.string.prime_sleep_done)
                finish()
                return
            }
            val minutes = (remaining / 60000).toInt()
            val seconds = ((remaining / 1000) % 60).toInt()
            _binding?.liveSleepChip?.text = String.format(Locale.US, "%d:%02d", minutes, seconds)
            handler.postDelayed(this, 1000)
        }
    }

    /** PrimeTube: set after a playback error, cleared on STATE_READY. */
    private var hadPlaybackError = false

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
                hadPlaybackError = false
                _binding?.livePlayerError?.isVisible = false
                _binding?.liveNoInternet?.isGone = true
                _binding?.liveErrorDetail?.isGone = true
                syncQueueHighlight()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _binding?.let { b ->
                b.liveCenterPlay.setImageResource(
                    if (isPlaying) R.drawable.ic_pause_filled else R.drawable.ic_play_filled
                )
                b.liveCenterPlay.contentDescription = getString(
                    if (isPlaying) R.string.pause else R.string.tooltip_play
                )
            }
            if (isPlaying) {
                // playing again -> controls may hide after the timeout
                if (controlsVisible) setControlsVisible(true)
            } else {
                // paused -> keep the controls on screen (YouTube behavior)
                setControlsVisible(true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hadPlaybackError = true
            _binding?.liveErrorDetail?.apply {
                text = error.errorCodeName
                isVisible = true
            }
            // PrimeTube: many IPTV servers label their streams wrongly (HLS with
            // no .m3u8 extension or the other way around) - TV boxes are much
            // stricter about this than phones. Retry once with the other
            // container interpretation before giving up on the channel.
            if (!mimeFallbackTried) {
                mimeFallbackTried = true
                toast(getString(R.string.prime_live_retrying, 1))
                retryWithAlternativeMime()
                return
            }
            // PrimeTube: retry twice, then hop to the next channel.
            // After too many dead channels in a row, stop and show the retry
            // overlay instead of looping through the whole playlist forever.
            if (errorRetries < 2) {
                errorRetries++
                toast(getString(R.string.prime_live_retrying, errorRetries))
                handler.postDelayed({
                    if (_binding == null) return@postDelayed
                    player?.let {
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

    /** PrimeTube: Android TV / set-top-box detection for D-pad + TextureView. */
    private fun isTvDevice(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return runCatching {
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }.getOrDefault(false)
    }

    /**
     * PrimeTube: D-pad support so Live TV works on real TVs:
     * OK = show controls (play/pause when visible), up/down = switch channel,
     * menu = show controls, back = hide controls or leave.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (controlsVisible) togglePlayback() else setControlsVisible(true)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                skipChannel(+1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                skipChannel(-1)
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                setControlsVisible(true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityLiveTvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        // PrimeTube: TV devices (D-pad remotes) - TextureView renders far more
        // reliably on TV panels than SurfaceView (no blank video after resume)
        if (isTvDevice()) {
            binding.livePlayerView.setVideoTextureView(TextureView(this))
        }

        binding.liveBack.setOnClickListener { finish() }
        binding.livePrev.setOnClickListener { skipChannel(-1) }
        binding.liveNext.setOnClickListener { skipChannel(+1) }
        binding.livePlayerRetry.setOnClickListener { retryNow() }
        binding.liveNoInternetRetry.setOnClickListener { retryNow() }
        binding.liveFillChip.setOnClickListener { toggleFillMode() }
        binding.liveQualityChip.setOnClickListener { showQualityDialog() }
        binding.liveQueueChip.setOnClickListener { toggleQueuePanel() }
        binding.liveSleepChip.setOnClickListener { showSleepDialog() }
        binding.liveNumberChip.setOnClickListener { showNumberJumpDialog() }
        binding.liveQueueClose.setOnClickListener {
            binding.liveQueueRoot.isGone = true
            resetChannelSearch()
        }
        binding.liveCenterPlay.setOnClickListener { togglePlayback() }
        binding.liveControlsSink.setOnClickListener { setControlsVisible(false) }

        setupGestures()
        registerNetworkWatcher()
        loadChannelsAndStart()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * PrimeTube: activity-owned ExoPlayer with an IPTV hardened HTTP stack.
     * Cross-protocol redirects are essential (many IPTV origins redirect
     * http<->https), the browser-ish User-Agent bypasses simple UA filters.
     */
    private fun buildPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)

        return ExoPlayer.Builder(this)
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
            .also { it.addListener(playerListener) }
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
            _binding?.liveQueueChip?.text =
                getString(R.string.prime_chip_channels) + " \u00b7 " + channels.size
            if (player == null) player = buildPlayer()
            if (currentIndex !in channels.indices) currentIndex = 0
            playChannel(currentIndex)
        }
    }

    private fun playChannel(index: Int, autoHop: Boolean = false) {
        val p = player ?: return
        val channel = channels.getOrNull(index) ?: return
        currentIndex = index
        errorRetries = 0
        mimeFallbackTried = false
        if (!autoHop) autoHops = 0
        binding.liveTitle.text = channel.name
        updateHeaderLogo()

        // PrimeTube: no internet -> show the hint, but still try to play.
        // Some TV boxes report no usable network even though the stream is
        // reachable; a dead connection hits the error path instead.
        if (!NetworkHelper.isNetworkAvailable(this)) {
            binding.liveNoInternet.isVisible = true
        } else {
            binding.liveNoInternet.isGone = true
        }
        binding.livePlayerError.isGone = true

        val metadata = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setArtist(getString(R.string.prime_live_tv))
            .build()

        val builder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(metadata)
        if (channel.url.lowercase(Locale.US).contains("m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        p.setMediaItem(builder.build())
        p.prepare()
        p.play()
        everStarted = true
        // PrimeTube: remember the channel for the "recently watched" section
        LiveTvHelper.recordRecent(applicationContext, channel.name)
        syncQueueHighlight()
        setControlsVisible(true)
    }

    /**
     * PrimeTube: servers often mislabel IPTV containers - replay the channel
     * once with the opposite interpretation (HLS <-> progressive).
     */
    private fun retryWithAlternativeMime() {
        val p = player ?: return
        val channel = channels.getOrNull(currentIndex) ?: return
        val builder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setArtist(getString(R.string.prime_live_tv))
                    .build()
            )
        if (!channel.url.lowercase(Locale.US).contains("m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        p.setMediaItem(builder.build())
        p.prepare()
        p.play()
    }

    // ---------- controls overlay: nothing on screen until tapped, 5 s auto-hide ----------

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        _binding?.liveControlsRoot?.isVisible = visible
        handler.removeCallbacks(hideControlsRunnable)
        // PrimeTube: on TV the D-pad needs an anchor - focus the center play
        if (visible && isTvDevice()) {
            _binding?.liveCenterPlay?.requestFocus()
        }
        // auto-hide only while actually playing - paused keeps controls visible
        if (visible && player?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun toggleControls() = setControlsVisible(!controlsVisible)

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    // ---------- sleep timer ----------

    /** PrimeTube: Live TV sleep timer - the player closes when it fires. */
    private fun showSleepDialog() {
        val minutesList = listOf(0, 15, 30, 60, 90)
        val labels = minutesList.map { minutes ->
            if (minutes == 0) getString(R.string.prime_sleep_off)
            else getString(R.string.prime_sleep_minutes, minutes)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prime_sleep_title)
            .setItems(labels.toTypedArray()) { _, which ->
                setSleepTimer(minutesList[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepTickRunnable)
        if (minutes <= 0) {
            sleepEndTime = null
            binding.liveSleepChip.text = getString(R.string.prime_sleep_chip)
            toast(R.string.prime_sleep_off)
            return
        }
        sleepEndTime = System.currentTimeMillis() + minutes * 60_000L
        toast(getString(R.string.prime_sleep_set, minutes))
        handler.postDelayed(sleepTickRunnable, 1000)
    }

    // ---------- channel number jump (TV-style 123) ----------

    private fun showNumberJumpDialog() {
        if (channels.isEmpty()) return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - ${channels.size}"
            setSingleLine(true)
            typeface = Typeface.DEFAULT_BOLD
        }
        val wrapper = FrameLayout(this)
        wrapper.setPadding(pad, pad / 2, pad, 0)
        wrapper.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prime_channel_jump)
            .setView(wrapper)
            .setPositiveButton(R.string.prime_go) { _, _ ->
                val number = input.text.toString().toIntOrNull()
                when {
                    number == null -> Unit
                    number in 1..channels.size -> {
                        binding.liveQueueRoot.isGone = true
                        playChannel(number - 1)
                    }
                    else -> toast(R.string.prime_channel_invalid)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- auto reconnect: internet back = stream back ----------

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (_binding == null) return@post
                val p = player ?: return@post
                val stuck = hadPlaybackError || p.playbackState == Player.STATE_IDLE
                if (stuck && NetworkHelper.isNetworkAvailable(this@LiveTVPlayerActivity)) {
                    toast(R.string.prime_live_reconnected)
                    retryNow()
                }
            }
        }
    }

    private fun registerNetworkWatcher() {
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }
    }

    private fun unregisterNetworkWatcher() {
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    private fun updateHeaderLogo() {
        val logo = channels.getOrNull(currentIndex)?.logo
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
        player?.let {
            it.prepare()
            it.play()
        }
    }

    private fun showNoInternet() {
        binding.liveNoInternet.isVisible = true
        binding.livePlayerError.isGone = true
        player?.pause()
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
        binding.liveFillChip.text =
            getString(if (isFillMode) R.string.prime_chip_fill else R.string.prime_chip_fit)
        toast(if (isFillMode) R.string.prime_live_fill else R.string.prime_live_fit)
    }

    // ---------- quality ----------

    private fun showQualityDialog() {
        val p = player ?: return
        val available = p.currentTracks.groups
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
                binding.liveQualityChip.text = if (which == 0) {
                    getString(R.string.prime_live_auto)
                } else {
                    "${lockedQualityHeight} p"
                }
                p.trackSelectionParameters = p.trackSelectionParameters
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
        val appContext = applicationContext
        val adapter = LiveTVAdapter(
            onClick = { channel, _ ->
                binding.liveQueueRoot.isGone = true
                resetChannelSearch()
                // PrimeTube: the panel is sorted - resolve the real index by URL
                val index = channels.indexOfFirst { it.url == channel.url }
                if (index >= 0) playChannel(index)
            },
            onFavoriteToggle = { channel ->
                LiveTvHelper.toggleFavorite(appContext, channel.name)
                queueAdapter?.setFavorites(LiveTvHelper.getFavoriteNames(appContext))
                refreshPanelList()
            }
        )
        adapter.setFavorites(LiveTvHelper.getFavoriteNames(appContext))
        // PrimeTube: search inside the channels playlist
        binding.liveQueueSearch.doAfterTextChanged { editable ->
            applyChannelFilter(editable?.toString().orEmpty())
        }
        binding.liveQueueRecycler.layoutManager = SafeLinearLayoutManager(this)
        binding.liveQueueRecycler.adapter = adapter
        queueAdapter = adapter
        refreshPanelList()
        syncQueueHighlight()
    }

    /**
     * PrimeTube: fills the channels panel - favorites first, then recently
     * watched, then the playlist order; the search field narrows it down.
     */
    private fun refreshPanelList() {
        val appContext = applicationContext
        val sorted = LiveTvHelper.sortChannels(appContext, channels)
        val query = binding.liveQueueSearch.text?.toString()?.trim().orEmpty()
        queueAdapter?.submitList(
            if (query.isEmpty()) {
                sorted
            } else {
                sorted.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.group?.contains(query, ignoreCase = true) == true
                }
            }
        )
    }

    /** PrimeTube: filter the channel cards by name/group, blank shows all. */
    private fun applyChannelFilter(query: String) {
        refreshPanelList()
    }

    private fun resetChannelSearch() {
        if (binding.liveQueueSearch.text?.isNotEmpty() == true) {
            binding.liveQueueSearch.setText("")
        }
    }

    private fun toggleQueuePanel() {
        val root = binding.liveQueueRoot
        if (root.isVisible) {
            root.isGone = true
            resetChannelSearch()
            return
        }
        resetChannelSearch()
        refreshPanelList()
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
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // PrimeTube: a clean tap (no gesture) toggles the controls
                        val moved = abs(event.x - gestureStartX) + abs(event.y - gestureStartY)
                        if (moved < 60) {
                            toggleControls()
                            true
                        } else {
                            false
                        }
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

    // ---------- lifecycle: no background playback, ever ----------

    override fun onResume() {
        super.onResume()
        // returning to a released screen (recents/app switch): restart the
        // live stream - live TV always resumes at the live edge anyway
        if (player == null && everStarted) {
            player = buildPlayer()
            playChannel(currentIndex)
        }
    }

    override fun onStop() {
        super.onStop()
        // PrimeTube rule: leaving the player = playback stops completely
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkWatcher()
        player?.release()
        player = null
        _binding = null
    }

    companion object {
        /** PrimeTube: stop auto-hopping after this many dead channels in a row. */
        private const val MAX_AUTO_HOPS = 4

        /** PrimeTube: controls auto-hide timeout (YouTube uses ~3-5 s). */
        private const val CONTROLS_TIMEOUT_MS = 5_000L

        /** PrimeTube: browser-like UA so IPTV servers with UA filters let us through. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 PrimeTube/1.0"

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
