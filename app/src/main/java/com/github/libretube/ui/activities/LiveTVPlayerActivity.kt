package com.github.libretube.ui.activities

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
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
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
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

    /** PrimeTube: the open channel-number keypad, dismissed with the activity. */
    private var numberKeypadDialog: Dialog? = null

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

    // PrimeTube: live-smoothness watchdog - a stream that buffers too long is
    // nudged back to the live edge instead of freezing forever
    private var bufferingSince = -1L
    private var bufferingRecoveries = 0
    private var lastReadyAt = 0L
    private val bufferingWatchdog = object : Runnable {
        override fun run() {
            val p = player
            if (_binding != null && p != null &&
                bufferingSince > 0 &&
                p.playbackState == Player.STATE_BUFFERING &&
                p.playWhenReady
            ) {
                val stuckMs = System.currentTimeMillis() - bufferingSince
                if (stuckMs > STUCK_BUFFER_MS) {
                    // a channel that became READY long ago deserves more
                    // recovery attempts than one that never stabilized
                    if (lastReadyAt > 0 &&
                        System.currentTimeMillis() - lastReadyAt > RECOVERY_RESET_MS
                    ) {
                        bufferingRecoveries = 0
                    }
                    bufferingRecoveries++
                    when {
                        bufferingRecoveries <= 2 -> {
                            // light recovery: jump back to the live edge
                            toast(R.string.prime_live_recovering)
                            bufferingSince = System.currentTimeMillis()
                            p.seekToDefaultPosition()
                            p.prepare()
                            p.play()
                        }

                        bufferingRecoveries <= 4 -> {
                            // hard recovery: reload the same channel
                            toast(R.string.prime_live_recovering)
                            bufferingSince = System.currentTimeMillis()
                            playChannel(currentIndex)
                        }

                        else -> {
                            // give up on this channel like on a playback error
                            bufferingRecoveries = 0
                            bufferingSince = -1L
                            toast(R.string.prime_live_retry_next)
                            skipChannel(+1, autoHop = true)
                        }
                    }
                }
            }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    // gesture state
    private var gestureActive = false
    private var gestureBrightness = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var baseBrightness = 0.5f
    private var baseVolume = 0f

    /**
     * PrimeTube: the buffering ring shows ONLY while the player is really
     * buffering with playback requested. Any other state - READY, paused,
     * error, idle - hides it again. Nothing ever spins permanently.
     */
    private fun syncLiveBuffering() {
        val chip = _binding?.liveBuffering ?: return
        val p = player
        val shouldShow =
            p != null &&
                p.playbackState == Player.STATE_BUFFERING &&
                p.playWhenReady
        chip.animate().cancel()
        if (shouldShow) {
            chip.isVisible = true
            chip.animate().alpha(1f).setDuration(180).start()
        } else if (chip.isVisible || chip.alpha > 0f) {
            chip.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction {
                    chip.visibility = View.INVISIBLE
                    chip.alpha = 0f
                }
                .start()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    errorRetries = 0
                    autoHops = 0
                    hadPlaybackError = false
                    bufferingSince = -1L
                    lastReadyAt = System.currentTimeMillis()
                    _binding?.livePlayerError?.isVisible = false
                    _binding?.liveNoInternet?.isGone = true
                    _binding?.liveErrorDetail?.isGone = true
                    syncQueueHighlight()
                    // PrimeTube: never stay paused silently - if the user asked
                    // for playback and we are READY, make sure it really plays
                    player?.let { p ->
                        if (p.playWhenReady && !p.isPlaying) p.play()
                    }
                }

                Player.STATE_BUFFERING -> {
                    // PrimeTube: stamp the start so the watchdog can act when
                    // the buffer never recovers on its own
                    if (bufferingSince <= 0) bufferingSince = System.currentTimeMillis()
                    syncLiveBuffering()
                }

                else -> {
                    // hide the buffering ring again
                    syncLiveBuffering()
                }
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
            // PrimeTube: the buffering ring must NEVER show for a paused player
            syncLiveBuffering()
            if (isPlaying) {
                // playing again -> controls may hide after the timeout
                if (controlsVisible) setControlsVisible(true)
            } else {
                // PrimeTube: only a REAL pause (user or system) keeps the
                // controls pinned - buffering must not pop the overlay up
                val reallyPaused = player?.playWhenReady == false
                if (reallyPaused) setControlsVisible(true)
            }
            // PrimeTube: keep the PiP auto-enter flag in sync with playback -
            // swiping home while PAUSED must never fling a frozen frame into PiP
            if (isPipAvailable && _binding != null) {
                runCatching {
                    PictureInPictureCompat.setPictureInPictureParams(
                        this@LiveTVPlayerActivity,
                        primePipParams()
                    )
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            // PrimeTube: keep the PiP window matching the stream aspect ratio so
            // resizing stays smooth and the picture never gets letterboxed
            if (isPipAvailable && _binding != null) {
                runCatching {
                    PictureInPictureCompat.setPictureInPictureParams(
                        this@LiveTVPlayerActivity,
                        primePipParams()
                    )
                }
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

        binding.liveBack.setOnClickListener { finish() }
        binding.livePrev.setOnClickListener { skipChannel(-1) }
        binding.liveNext.setOnClickListener { skipChannel(+1) }
        binding.livePlayerRetry.setOnClickListener { retryNow() }
        binding.liveNoInternetRetry.setOnClickListener { retryNow() }
        binding.liveFillChip.setOnClickListener { toggleFillMode() }
        binding.liveQualityChip.setOnClickListener { showQualityDialog() }
        binding.liveQueueChip.setOnClickListener { toggleQueuePanel() }
        binding.liveSleepChip.setOnClickListener { showSleepDialog() }
        // PrimeTube: the 123 channel-number keypad is fully disabled by
        // request - no chip, no input path
        binding.liveQueueClose.setOnClickListener {
            binding.liveQueueRoot.isGone = true
            resetChannelSearch()
        }
        binding.liveCenterPlay.setOnClickListener { togglePlayback() }

        setupGestures()
        registerNetworkWatcher()
        handler.post(bufferingWatchdog)
        loadChannelsAndStart()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // PrimeTube: draw into the camera cutout area too - fill mode must
        // cover the whole display with no reserved notch space
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
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

        // PrimeTube: IPTV-tuned buffering - a big min/max buffer plus a much
        // larger rebuffer threshold keeps choppy live streams from starting
        // and stalling every 1-2 seconds
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 50_000,
                /* maxBufferMs = */ 90_000,
                /* bufferForPlaybackMs = */ 3_500,
                /* bufferForPlaybackAfterRebufferMs = */ 8_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60_000, /* retainBackBufferFromKeyframe = */ true)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // PrimeTube: ignore audio focus losses - a notification or
                // another app must never pause the live stream
                /* handleAudioFocus = */ false
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
            if (player == null) {
                player = buildPlayer()
            }
            // PrimeTube: THE critical wiring - the ExoPlayer must be attached
            // to the PlayerView or the video surface stays black forever
            binding.livePlayerView.player = player
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
        bufferingSince = -1L
        bufferingRecoveries = 0
        lastReadyAt = 0L
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

    // ---------- channel number jump (premium TV-style keypad) ----------

    private fun showNumberJumpDialog() {
        if (channels.isEmpty()) return
        val view = layoutInflater.inflate(R.layout.dialog_number_keypad, null)
        val display = view.findViewById<TextView>(R.id.keypadDisplay)
        val maxNumber = channels.size
        val digits = StringBuilder()

        fun updateDisplay() {
            display.text = if (digits.isEmpty()) {
                getString(R.string.prime_keypad_empty)
            } else {
                digits.toString()
            }
        }

        fun go() {
            val number = digits.toString().toIntOrNull()
            when {
                number == null -> Unit
                number in 1..maxNumber -> {
                    numberKeypadDialog?.dismiss()
                    numberKeypadDialog = null
                    binding.liveQueueRoot.isGone = true
                    playChannel(number - 1)
                }
                else -> toast(R.string.prime_channel_invalid)
            }
        }

        /** PrimeTube: jump as soon as no longer number can start with these digits. */
        fun maybeAutoGo() {
            val number = digits.toString().toIntOrNull() ?: return
            if (number in 1..maxNumber && number * 10 > maxNumber) go()
        }

        fun bindDigit(id: Int, digit: Int) {
            view.findViewById<View>(id).setOnClickListener {
                if (digits.length < 4) {
                    digits.append(digit)
                    updateDisplay()
                    maybeAutoGo()
                }
            }
        }

        bindDigit(R.id.keypad1, 1)
        bindDigit(R.id.keypad2, 2)
        bindDigit(R.id.keypad3, 3)
        bindDigit(R.id.keypad4, 4)
        bindDigit(R.id.keypad5, 5)
        bindDigit(R.id.keypad6, 6)
        bindDigit(R.id.keypad7, 7)
        bindDigit(R.id.keypad8, 8)
        bindDigit(R.id.keypad9, 9)
        bindDigit(R.id.keypad0, 0)

        view.findViewById<View>(R.id.keypadBackspace).setOnClickListener {
            if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
            updateDisplay()
        }
        view.findViewById<View>(R.id.keypadGo).setOnClickListener { go() }

        // PrimeTube: borderless floating keypad - no dialog chrome, just the
        // premium dark card. TV: D-pad focus starts on the middle key (5).
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        numberKeypadDialog = dialog
        dialog.show()
        view.findViewById<View>(R.id.keypad5)?.requestFocus()
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

    /**
     * PrimeTube: ONE shared gesture handler for the whole video area. It is
     * attached BOTH to the player view and to the controls click-sink, so a
     * vertical swipe adjusts brightness (left half) or volume (right half)
     * whether the control overlay is visible or not - and a clean tap still
     * toggles the controls.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // CRITICAL: media3's PlayerView.setUseController(false) calls
        // setClickable(false) in its constructor, which silently OVERRIDES the
        // layout's android:clickable="true". The touch listener below now
        // consumes ACTION_DOWN regardless, but keep the clickability restored
        // here as well for correct tap/pressed-state behaviour.
        binding.livePlayerView.isClickable = true

        val gestureListener = { v: View, event: MotionEvent ->
            handleGestureTouch(v, event)
        }
        binding.livePlayerView.setOnTouchListener(gestureListener)
        binding.liveControlsSink.setOnTouchListener(gestureListener)
    }

    private fun handleGestureTouch(v: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                // PrimeTube: CONSUME the down event unconditionally. This
                // guarantees the whole MOVE stream reaches this listener even
                // if media3 re-applies setClickable(false) at any point -
                // a non-consuming DOWN was the root cause of dead gestures.
                true
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
                    if (controlsVisible) {
                        // the gesture starts while the control overlay is open
                        // (swipe began on the click sink) - close it so the
                        // video area is clean while adjusting
                        setControlsVisible(false)
                    }
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
                } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(event.x - gestureStartX) + abs(event.y - gestureStartY) < 60
                ) {
                    // PrimeTube: a clean tap (no gesture). On the video area it
                    // toggles the controls; on the sink it closes them.
                    if (v.id == R.id.livePlayerView) {
                        toggleControls()
                        true
                    } else if (v.id == R.id.liveControlsSink) {
                        setControlsVisible(false)
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

    // ---------- PrimeTube: picture-in-picture for Live TV ----------

    private val isPipAvailable: Boolean
        get() = PictureInPictureCompat.isPictureInPictureAvailable(this)

    /**
     * PrimeTube: a clean Live TV PiP window - just the video, aspect ratio
     * matched to the stream, auto-enter while playing. The transition zooms
     * from the player's on-screen rect for a smooth YouTube-like animation.
     */
    private fun primePipParams(): PictureInPictureParamsCompat {
        val builder = PictureInPictureParamsCompat.Builder()
            .setAutoEnterEnabled(player?.isPlaying == true)
            // PrimeTube: seamless resize OFF - resizing content while the
            // window is still animating is what made PiP feel jumpy
            .setSeamlessResizeEnabled(false)
        val videoSize = player?.videoSize
        if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
            builder.setAspectRatio(videoSize)
        } else {
            builder.setAspectRatio(Rational(16, 9))
        }
        // PrimeTube: zoom the transition from the actual video rect
        runCatching {
            val view = _binding?.livePlayerView ?: return@runCatching
            if (!view.isShown || view.width == 0 || view.height == 0) return@runCatching
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            builder.setSourceRectHint(Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height))
        }
        return builder.build()
    }

    private fun enterPipIfPlaying() {
        if (!isPipAvailable || player?.isPlaying != true) return
        runCatching {
            PictureInPictureCompat.enterPictureInPictureMode(this, primePipParams())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // PrimeTube: leaving the app while a channel is playing -> keep
        // watching in the floating PiP window
        enterPipIfPlaying()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (_binding == null) return
        if (isInPictureInPictureMode) {
            // clean floating window: no overlays, no panels, no keypad
            setControlsVisible(false)
            numberKeypadDialog?.dismiss()
            _binding?.liveQueueRoot?.isGone = true
            _binding?.liveGesturePill?.isGone = true
        } else if (_binding != null && player != null) {
            // back in fullscreen (the window was expanded) - show the controls
            // again briefly; a dismissed window just finishes the activity
            setControlsVisible(true)
        }
    }

    // ---------- lifecycle: no background playback, ever ----------

    override fun onResume() {
        super.onResume()
        // returning to a released screen (recents/app switch): restart the
        // live stream - live TV always resumes at the live edge anyway
        if (player == null && everStarted) {
            player = buildPlayer()
            binding.livePlayerView.player = player
            playChannel(currentIndex)
        }
    }

    override fun onStop() {
        super.onStop()
        // PrimeTube rule: leaving the player = playback stops completely
        binding.livePlayerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        numberKeypadDialog?.dismiss()
        numberKeypadDialog = null
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkWatcher()
        player?.release()
        player = null
        _binding = null
    }

    companion object {
        /** PrimeTube: stop auto-hopping after this many dead channels in a row. */
        private const val MAX_AUTO_HOPS = 4

        /** PrimeTube: buffering longer than this triggers the watchdog. */
        private const val STUCK_BUFFER_MS = 8_000L

        /** PrimeTube: watchdog poll interval. */
        private const val WATCHDOG_TICK_MS = 2_000L

        /** PrimeTube: a channel READY for this long gets its recovery budget back. */
        private const val RECOVERY_RESET_MS = 20_000L

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
