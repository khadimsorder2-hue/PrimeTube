package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginStart
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.CustomExoPlayerViewTemplateBinding
import com.github.libretube.databinding.DoubleTapOverlayBinding
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.PlayerGestureControlsViewBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.round
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BrightnessHelper
import com.github.libretube.helpers.GeminiSubtitleHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.obj.VideoResolution
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.controllers.FullscreenGestureAnimationController
import com.github.libretube.ui.extensions.toggleSystemBars
import com.github.libretube.ui.interfaces.CustomPlayerCallback
import com.github.libretube.ui.interfaces.PlayerGestureOptions
import com.github.libretube.ui.interfaces.PlayerOptions
import com.github.libretube.ui.listeners.PlayerGestureController
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.adapters.PlayingQueueAdapter
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.views.SafeLinearLayoutManager
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.StatsSheet
import com.github.libretube.ui.tools.SleepTimer
import com.github.libretube.util.PlayingQueue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CustomExoPlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : PlayerView(context, attributeSet), PlayerOptions, PlayerGestureOptions {
    @Suppress("LeakingThis")
    val binding = ExoStyledPlayerControlViewBinding.bind(this)
    val backgroundBinding = CustomExoPlayerViewTemplateBinding.bind(this)

    /**
     * Objects for player tap and swipe gesture
     */
    private val gestureViewBinding: PlayerGestureControlsViewBinding get() = backgroundBinding.playerGestureControlsView.binding
    private val doubleTapOverlayBinding: DoubleTapOverlayBinding get() = backgroundBinding.doubleTapOverlay.binding

    private var playerGestureController: PlayerGestureController
    private var brightnessHelper: BrightnessHelper
    private var audioHelper: AudioHelper
    private lateinit var chaptersViewModel: ChaptersViewModel
    private lateinit var seekBarListener: TimeBar.OnScrubListener
    private var fullscreenGestureAnimationController: FullscreenGestureAnimationController
    private var chaptersBottomSheet: ChaptersBottomSheet? = null
    private var scrubbingTimeBar = false

    /**
     * Objects from the parent fragment
     */

    private val runnableHandler = Handler(Looper.getMainLooper())
    private var isPlayerLocked: Boolean = false

    // PrimeTube: true while a remote/D-pad focus move happened inside the
    // controller - used to keep the auto-hide countdown alive on TVs
    private var isTvDevice = checkIsTvDevice()

    private var resizeModePref: Int
        set(value) {
            PreferenceHelper.putInt(
                PreferenceKeys.PLAYER_RESIZE_MODE,
                value
            )
        }
        // PrimeTube: default to FILL (like the YouTube app's "Fill screen") so fullscreen
        // videos use the whole display without black bars, unless the user chose otherwise
        get() = PreferenceHelper.getInt(
            PreferenceKeys.PLAYER_RESIZE_MODE,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.resize_mode_fit,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.resize_mode_zoom,
        AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.resize_mode_fill
    )

    private val activity get() = context as BaseActivity

    private val supportFragmentManager
        get() = activity.supportFragmentManager

    /**
     * Playback speed that has been set before the fast forward mode
     * has been triggered by a long press.
     */
    private var rememberedPlaybackSpeed: Float? = null

    // PrimeTube: free-form subtitles - pinch to resize, double tap to reset.
    // Offset/scale are persisted in the settings.
    private var subtitleOffsetFraction = PlayerHelper.primeSubtitleOffset
    private var subtitleTextScale = PlayerHelper.primeSubtitleScale
    private var subtitleGestureTaken = false
    private val subtitleScaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!detector.isInProgress) return false
                subtitleTextScale = (subtitleTextScale * detector.scaleFactor).coerceIn(0.5f, 3f)
                applySubtitleTextSize()
                return true
            }
        }
    )

    // PrimeTube: AI (Gemini) Bangla subtitle overlay - our own TextView so
    // the translated cues share the free-form behavior of the captions
    private var aiCues: List<GeminiSubtitleHelper.AiCue> = emptyList()
    private val aiSubtitleView: TextView = TextView(context).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.WHITE)
        setShadowLayer(6f, 0f, 2f, 0xB3000000.toInt())
        setPadding(48f.dpToPx(), 8f.dpToPx(), 48f.dpToPx(), 12f.dpToPx())
        visibility = View.GONE
    }

    private fun toggleController(show: Boolean = !isControllerFullyVisible) {
        if (show) showController() else hideController()
    }

    /** PrimeTube: public read access for the fragments. */
    fun isPrimeControllerFullyVisible(): Boolean = isControllerFullyVisible

    /**
     * PrimeTube: TV remote support - show the controls and move the focus
     * onto the play/pause button so the whole control bar can be navigated
     * with the D-pad of a TV remote.
     */
    private fun showTvControls() {
        showController()
        if (isTvDevice) focusControllerForTv()
    }

    /**
     * PrimeTube: make every clickable control focusable and put the focus on
     * the play/pause button - remote D-pad navigation starts from there.
     */
    private fun focusControllerForTv() {
        val queue = ArrayDeque<View>()
        queue.add(binding.root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view.isClickable && !view.isFocusable) view.isFocusable = true
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) queue.add(group.getChildAt(i))
            }
        }
        binding.playPauseBTN.requestFocus()
    }

    /** PrimeTube: whether the view lives inside the player control bar. */
    private fun isViewInsideController(view: View): Boolean {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent === binding.root) return true
            parent = parent.parent
        }
        return false
    }

    /** PrimeTube: Android TV / set-top box detection. */
    private fun checkIsTvDevice(): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }.getOrDefault(false)
    }

    /**
     * PrimeTube: pre-dispatch hook for the activity - guarantees the remote's
     * OK / up / down always opens the control bar, even if some other focused
     * view in the hierarchy would swallow the key.
     */
    fun handleTvKeyPreDispatch(keyCode: Int): Boolean {
        if (!isTvDevice || isControllerFullyVisible) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showTvControls()
                true
            }
            else -> false
        }
    }

    private var playerViewModel: PlayerViewModel? = null
    private var commonPlayerViewModel: CommonPlayerViewModel? = null
    private var viewLifecycleOwner: LifecycleOwner? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The window that needs to be addressed for showing and hiding the system bars
     * If null, the activity's default/main window will be used
     */
    var currentWindow: Window? = null

    private var selectedResolution: Int? = null
    var sponsorBlockAutoSkip = true
        private set

    private var selectedAudioLanguageAndRoleFlags: Pair<String?, @C.RoleFlags Int>? = null
    private lateinit var playerCallback: CustomPlayerCallback


    // if null, it's been set to automatic
    private var fullscreenResolution: Int? = null

    // the resolution to use when the video is not played in fullscreen
    // if null, use same quality as fullscreen
    private var noFullscreenResolution: Int? = null

    /**
     * PrimeTube: PiP controls wiring - a slim always-visible bottom bar with
     * a drag-seek slider plus ONE small headphone button at the very
     * bottom-right corner (clear of the system's close button at the top).
     */
    private var primePipMode = false
    private var primePipSeekDragging = false

    /**
     * PrimeTube: invoked when the PiP headphone button is tapped - the player
     * fragment leaves the PiP window and keeps the audio running in background.
     */
    var onPrimePipAudioClick: (() -> Unit)? = null

    private val primePipTicker = object : Runnable {
        override fun run() {
            if (primePipMode) {
                syncPrimePipProgress()
                postDelayed(this, 500)
            }
        }
    }

    private fun setupPrimePipProgress() {
        backgroundBinding.primePipAudioBtn.setOnClickListener {
            onPrimePipAudioClick?.invoke()
        }
        backgroundBinding.primePipSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player?.duration ?: 0L
                    if (duration > 0) {
                        backgroundBinding.primePipTime.text =
                            primeMsToTime(value.toLong()) + " / " + primeMsToTime(duration)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    primePipSeekDragging = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    primePipSeekDragging = false
                    seekBar?.progress?.let { player?.seekTo(it.toLong()) }
                    syncPrimePipProgress()
                }
            }
        )
    }

    /**
     * PrimeTube: enables/disables the clean PiP overlay. Called by the player
     * fragment whenever picture-in-picture mode changes.
     */
    fun setPrimePipMode(enabled: Boolean) {
        if (primePipMode == enabled) return
        primePipMode = enabled
        if (enabled) {
            // PrimeTube: belt & braces - make sure the regular controller can
            // never leak into the PiP window (it carries the settings gear),
            // even if the fragment's own disable call raced this one
            runCatching {
                setUseController(false)
                hideController()
            }
            // PrimeTube: the progress bar is ALWAYS visible in PiP - the user
            // can drag-seek without ever tapping the window (tapping would
            // summon the system's PiP chrome with its close button)
            syncPrimePipProgress()
            backgroundBinding.primePipProgressRoot.isVisible = true
            // PrimeTube: the single small headphone button - audio-only background.
            // ALWAYS visible, at the very bottom-right corner (never overlaps
            // the system's PiP close button, which sits at the top).
            backgroundBinding.primePipAudioBtn.isVisible = true
            post(primePipTicker)
        } else {
            removeCallbacks(primePipTicker)
            primePipSeekDragging = false
            backgroundBinding.primePipProgressRoot.isGone = true
            backgroundBinding.primePipAudioBtn.isGone = true
            runCatching { setUseController(true) }
        }
    }

    private fun syncPrimePipProgress() {
        val p = player ?: return
        val duration = p.duration
        val seek = backgroundBinding.primePipSeek
        val time = backgroundBinding.primePipTime
        val hasDuration = duration > 0
        seek.isVisible = hasDuration
        if (hasDuration) {
            seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (!primePipSeekDragging) {
                seek.progress = p.currentPosition.coerceIn(0, duration)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        time.text = if (hasDuration) {
            primeMsToTime(p.currentPosition) + " / " + primeMsToTime(duration)
        } else {
            // live stream - no duration, no slider, just a small LIVE badge
            "LIVE"
        }
    }

    private fun primeMsToTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    init {
        // PrimeTube: brightness must follow the window the player is currently
        // shown in (the fullscreen dialog in fullscreen, the activity window
        // otherwise) - otherwise the brightness gesture changes an INVISIBLE
        // window and looks completely dead
        brightnessHelper = BrightnessHelper(activity) { currentWindow ?: activity.window }
        playerGestureController = PlayerGestureController(activity, this)

        // PrimeTube: the AI subtitle overlay lives inside the content frame so
        // it stays with the video (under the controls, over the surface)
        backgroundBinding.exoContentFrame.addView(
            aiSubtitleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )

        audioHelper = AudioHelper(context)

        // PrimeTube: wire the clean PiP progress slider (auto-hides, can seek)
        setupPrimePipProgress()

        // PrimeTube: on TVs the 2 s auto-hide must not fight remote focus
        // navigation - restart the countdown whenever the focus moves between
        // the player controls
        if (isTvDevice) {
            viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isViewInsideController(newFocus) &&
                    isControllerFullyVisible
                ) {
                    cancelHideControllerTask()
                    enqueueHideControllerTask()
                }
            }
        }

        fullscreenGestureAnimationController = FullscreenGestureAnimationController(
            playerView = this,
            videoFrameView = backgroundBinding.exoContentFrame,
            onSwipeUpCompleted = {
                if (!isFullscreen()) playerCallback.toggleFullscreen()
            },
            onSwipeDownCompleted = {
                if (isFullscreen()) playerCallback.toggleFullscreen()
            }
        )
    }

    fun initialize(
        chaptersViewModel: ChaptersViewModel,
        commonPlayerViewModel: CommonPlayerViewModel,
        playerViewModel: PlayerViewModel,
        viewLifecycleOwner: LifecycleOwner,
        playerCallback: CustomPlayerCallback,
        player: Player,
    ) {
        this.chaptersViewModel = chaptersViewModel
        this.playerViewModel = playerViewModel
        this.commonPlayerViewModel = commonPlayerViewModel
        this.viewLifecycleOwner = viewLifecycleOwner
        this.playerCallback = playerCallback
        super.player = player

        // PrimeTube: fresh video - no leftover A-B loop
        resetAbRepeat()

        initializeGestureProgress()

        initRewindAndForward()
        applyCaptionsStyle()
        initializeAdvancedOptions()
        initializeTopBarControls()
        initializePrimeControls()

        setupKeyboardFocus()

        // don't let the player view hide its controls automatically
        controllerShowTimeoutMs = -1
        // don't let the player view show its controls automatically
        controllerAutoShow = false

        binding.fullscreen.setOnClickListener { playerCallback.toggleFullscreen() }

        resizeMode = resizeModePref

        // prevent the controls from disappearing while scrubbing the time bar
        if (!::seekBarListener.isInitialized) {
            seekBarListener = object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) {
                    cancelHideControllerTask()
                }

                override fun onScrubMove(timeBar: TimeBar, position: Long) {
                    cancelHideControllerTask()

                    setCurrentChapterName(forceUpdate = true, enqueueNew = false)
                    scrubbingTimeBar = true
                }

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    enqueueHideControllerTask()

                    setCurrentChapterName(forceUpdate = true, enqueueNew = false)
                    scrubbingTimeBar = false
                }
            }
            binding.exoProgress.addSeekBarListener(seekBarListener)
        }

        // restore the duration type from the previous session
        updateDisplayedDurationType()

        binding.duration.setOnClickListener {
            updateDisplayedDurationType(true)
        }
        binding.timeLeft.setOnClickListener {
            updateDisplayedDurationType(false)
        }
        binding.position.setOnClickListener {
            if (playerCallback.isVideoLive()) player.let { it.seekTo(it.duration) }
        }

        activity.supportFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            findViewTreeLifecycleOwner() ?: activity
        ) { _, bundle ->
            player.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        // enable the chapters dialog in the player
        binding.chapterName.setOnClickListener {
            val sheet = chaptersBottomSheet ?: ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to player.duration.div(1000)
                    )
                }
                .also {
                    chaptersBottomSheet = it
                }

            if (sheet.isVisible) {
                sheet.dismiss()
            } else {
                sheet.show(activity.supportFragmentManager)
            }
        }

        supportFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            findViewTreeLifecycleOwner() ?: activity
        ) { _, args ->
            (player as? MediaController)?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }

        // PrimeTube: the queue (playlist) button in the player top bar -
        // in fullscreen it opens the YouTube-style side panel, otherwise the sheet
        binding.queueToggle.setOnClickListener {
            val isFullscreen = commonPlayerViewModel.isFullscreen.value == true
            if (isFullscreen) {
                toggleQueuePanel()
            } else {
                PlayingQueueSheet().show(supportFragmentManager, null)
            }
        }

        updateMarginsByFullscreenMode()

        commonPlayerViewModel.isFullscreen.observe(viewLifecycleOwner) { isFullscreen ->
            updateTopBarMargin()

            val fullscreenDrawable =
                if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            binding.fullscreen.setImageResource(fullscreenDrawable)

            binding.exoTitle.isInvisible = !isFullscreen

            // PrimeTube: close the queue side panel when leaving fullscreen
            if (!isFullscreen) hideQueuePanel()

            updateResolution(isFullscreen)
        }

        syncQueueButtons()

        binding.playPauseBTN.setOnClickListener {
            player.togglePlayPauseState()
        }

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                super.onEvents(player, events)
                this@CustomExoPlayerView.onPlaybackEvents(player, events)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                keepScreenOn = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                // PrimeTube: an A-B loop never survives the end of a video
                if (playbackState == Player.STATE_ENDED) resetAbRepeat()
            }
        })

        binding.playPauseBTN.setImageResource(
            PlayerHelper.getPlayPauseActionIcon(player)
        )

        binding.exoProgress.setPlayer(player)

        if (player.isPlaying) keepScreenOn = true

        updateCurrentPosition()
    }

    /**
     * @see CustomExoPlayerView.initialize
     * @see CustomExoPlayerView.detachPlayer
     */
    @Deprecated("Use `initialize()` instead to attach `Player` and use `detachPlayer()` to detach it")
    override fun setPlayer(player: Player?) {
        super.setPlayer(player)
    }

    fun detachPlayer(){
        super.setPlayer(null)
    }

    private fun syncQueueButtons() {
        if (!PlayerHelper.skipButtonsEnabled) return

        // toggle the visibility of next and prev buttons based on queue and whether the player view is locked
        binding.skipPrev.isInvisible = !PlayingQueue.hasPrev() || isPlayerLocked
        binding.skipNext.isInvisible = !PlayingQueue.hasNext() || isPlayerLocked

        handler.postDelayed(this::syncQueueButtons, 100)
    }

    /**
     * Update the displayed duration of the video
     */
    private fun updateDisplayedDuration() {
        if (playerCallback.isVideoLive()) return

        val duration = player?.duration?.div(1000) ?: return
        if (duration < 0) return

        val durationWithoutSegments = duration - playerViewModel?.segments?.value.orEmpty().sumOf {
            val (start, end) = it.segmentStartAndEnd
            end.toDouble() - start.toDouble()
        }.toLong()
        val durationString = DateUtils.formatElapsedTime(duration)

        binding.duration.text = if (durationWithoutSegments < duration) {
            "$durationString (${DateUtils.formatElapsedTime(durationWithoutSegments)})"
        } else {
            durationString
        }
    }

    /**
     * Set the name of the video chapter in the [CustomExoPlayerView]
     * @param forceUpdate Update the current chapter name no matter if the seek bar is scrubbed
     * @param enqueueNew set a timeout to automatically repeat this function again in 100ms
     */
    fun setCurrentChapterName(forceUpdate: Boolean = false, enqueueNew: Boolean = true) {
        val player = player ?: return
        val chapters = chaptersViewModel.chapters

        binding.chapterName.isInvisible = chapters.isEmpty()

        // the following logic to set the chapter title can be skipped if no chapters are available
        if (chapters.isEmpty()) return

        // call the function again in 100ms
        if (enqueueNew) postDelayed(this::setCurrentChapterName, 100)

        // if the user is scrubbing the time bar, don't update
        if (scrubbingTimeBar && !forceUpdate) return

        val currentIndex = PlayerHelper.getCurrentChapterIndex(player.currentPosition, chapters)
        val newChapterName = currentIndex?.let { chapters[it].title.trim() }.orEmpty()

        chaptersViewModel.currentChapterIndex.updateIfChanged(currentIndex ?: -1)

        // change the chapter name textView text to the chapterName
        if (newChapterName != binding.chapterName.text) {
            binding.chapterName.text = newChapterName
        }
    }

    /**
     * focus the player view so that all keyboard events will be moved here
     */
    private fun setupKeyboardFocus() {
        isFocusable = true
        isFocusableInTouchMode = true
        // workaround (possibly a no-op?): we don't directly focus the player via
        // requestFocus() because that leads the focus to be moved back to the search bar
        // once exiting fullscreen
        activity.window.decorView.requestFocus()
    }

    fun toggleSystemBars(showBars: Boolean) {
        getWindow().toggleSystemBars(
            types = if (showBars) {
                WindowHelper.getGestureControlledBars(context)
            } else {
                WindowInsetsCompat.Type.systemBars()
            },
            showBars = showBars
        )
    }

    private fun updateDisplayedDurationType(showTimeLeft: Boolean? = null) {
        var shouldShowTimeLeft = showTimeLeft ?: PreferenceHelper
            .getBoolean(PreferenceKeys.SHOW_TIME_LEFT, false)
        // always show the time left only if it's a livestream
        if (playerCallback.isVideoLive()) shouldShowTimeLeft = true
        if (showTimeLeft != null) {
            // save whether to show time left or duration for next session
            PreferenceHelper.putBoolean(PreferenceKeys.SHOW_TIME_LEFT, shouldShowTimeLeft)
        }
        binding.timeLeft.isVisible = shouldShowTimeLeft
        binding.duration.isGone = shouldShowTimeLeft
    }

    private fun enqueueHideControllerTask() {
        runnableHandler.postDelayed(AUTO_HIDE_CONTROLLER_DELAY, HIDE_CONTROLLER_TOKEN) {
            hideController()
        }
    }

    private fun cancelHideControllerTask() {
        runnableHandler.removeCallbacksAndMessages(HIDE_CONTROLLER_TOKEN)
    }

    override fun hideController() {
        // remove the callback to hide the controller
        cancelHideControllerTask()
        super.hideController()
        backgroundBinding.exoControlsBackground.animate()
            .alpha(0f)
            .setDuration(500)
            .start()

        if (isFullscreen()) {
            toggleSystemBars(false)
        }
    }

    override fun showController() {
        // remove the previous callback from the queue to prevent a flashing behavior
        cancelHideControllerTask()
        // automatically hide the controller after 2 seconds
        enqueueHideControllerTask()
        super.showController()
        backgroundBinding.exoControlsBackground.animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        if (isFullscreen() && !isPlayerLocked) {
            toggleSystemBars(true)
        }
    }

    fun showControllerPermanently() {
        // remove the previous callback from the queue to prevent a flashing behavior
        cancelHideControllerTask()
        super.showController()
    }

    private fun initRewindAndForward() {
        val seekIncrementText = (PlayerHelper.seekIncrement / 1000).toString()
        listOf(
            doubleTapOverlayBinding.rewindLayout.rewindTV,
            doubleTapOverlayBinding.forwardLayout.forwardTV,
            binding.seekButtonForward.forwardTV,
            binding.seekButtonRewind.rewindTV
        ).forEach {
            it.text = seekIncrementText
        }
        binding.seekButtonForward.forwardBTN.setOnClickListener {
            player?.seekBy(PlayerHelper.seekIncrement)
        }
        binding.seekButtonRewind.rewindBTN.setOnClickListener {
            player?.seekBy(-PlayerHelper.seekIncrement)
        }

        if (!PlayerHelper.doubleTapToSeek) {
            binding.seekButtonForward.forwardBTN.isVisible = !isPlayerLocked
            binding.seekButtonRewind.rewindBTN.isVisible = !isPlayerLocked
        }
    }

    private fun initializeAdvancedOptions() {
        binding.toggleOptions.setOnClickListener {
            val items = getOptionsMenuItems()
            val bottomSheetFragment = BaseBottomSheet().setItems(items, null)
            bottomSheetFragment.show(supportFragmentManager, null)
        }
    }

    /**
     * PrimeTube: direct quality / autoplay / speed controls in the player top bar.
     * All handlers are guarded so a failure can never crash playback.
     */
    private fun initializeTopBarControls() {
        binding.autoplayToggle.setOnClickListener {
            runCatching {
                PlayerHelper.autoPlayEnabled = !PlayerHelper.autoPlayEnabled
                updateAutoplayState()
            }
        }
        binding.qualityToggle.setOnClickListener {
            runCatching { onQualityClicked() }
        }
        binding.speedTop.setOnClickListener {
            runCatching { onPlaybackSpeedClicked() }
        }
        updateAutoplayState()
    }

    // PrimeTube: haptic feedback shared by the premium player controls
    private fun View.primeHaptic() {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * PrimeTube: the premium control row in the bottom bar - A-B repeat loop,
     * screenshot and the screen lock, all with haptic feedback.
     */
    private fun initializePrimeControls() {
        binding.abToggle.setOnClickListener {
            it.primeHaptic()
            cycleAbRepeat()
        }
        binding.screenshotToggle.setOnClickListener {
            it.primeHaptic()
            captureScreenshot()
        }
        binding.lockToggle.setOnClickListener {
            it.primeHaptic()
            setControlsLocked(!isPlayerLocked)
        }
        binding.playPauseBTN.setOnClickListener {
            it.primeHaptic()
            player?.togglePlayPauseState()
        }
    }

    // ---------------- screen lock ----------------

    private var lockIndicator: ImageView? = null

    /**
     * PrimeTube: YouTube-style screen lock. While locked the controls can
     * never come up - only the small lock pill stays visible; tapping it
     * unlocks again.
     */
    fun setControlsLocked(locked: Boolean) {
        isPlayerLocked = locked
        playerGestureController.areControlsLocked = locked
        ensureLockIndicator()
        lockIndicator?.isVisible = locked
        binding.lockToggle.setImageResource(
            if (locked) R.drawable.ic_locked else R.drawable.ic_unlocked
        )
        if (locked) {
            hideController()
            toast(context.getString(R.string.prime_lock_on))
        } else {
            toast(context.getString(R.string.prime_lock_off))
            if (isControllerFullyVisible) enqueueHideControllerTask()
        }
    }

    private fun ensureLockIndicator() {
        if (lockIndicator != null) return
        val pad = (resources.displayMetrics.density * 10).toInt()
        val margin = (resources.displayMetrics.density * 16).toInt()
        val view = ImageView(context).apply {
            setImageResource(R.drawable.ic_locked)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.prime_gesture_pill)
            setPadding(pad, pad, pad, pad)
            isVisible = false
            elevation = resources.displayMetrics.density * 24
            contentDescription = context.getString(R.string.prime_lock_controls)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START
            ).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener {
                it.primeHaptic()
                setControlsLocked(false)
            }
        }
        addView(view)
        lockIndicator = view
    }

    // ---------------- A-B repeat ----------------

    private var abPointA = -1L
    private var abPointB = -1L

    /**
     * PrimeTube: A-B loop in three taps - first tap marks the start,
     * second the end (loop starts), third tap cancels.
     */
    private fun cycleAbRepeat() {
        val p = player ?: return
        when {
            abPointA < 0 -> {
                abPointA = p.currentPosition
                abPointB = -1L
                binding.abToggle.alpha = 0.55f
                toast(context.getString(R.string.prime_ab_a_set, DateUtils.formatElapsedTime(abPointA / 1000)))
            }

            abPointB < 0 -> {
                val end = p.currentPosition
                if (end - abPointA < 1000) {
                    toast(R.string.prime_ab_too_short)
                } else {
                    abPointB = end
                    binding.abToggle.alpha = 1f
                    toast(
                        context.getString(
                            R.string.prime_ab_loop_on,
                            DateUtils.formatElapsedTime(abPointA / 1000),
                            DateUtils.formatElapsedTime(abPointB / 1000)
                        )
                    )
                    startAbLoopPolling()
                }
            }

            else -> {
                resetAbRepeat()
                toast(R.string.prime_ab_reset)
            }
        }
    }

    private fun startAbLoopPolling() {
        runnableHandler.postDelayed(400, AB_REPEAT_TOKEN) { checkAbLoop() }
    }

    private fun checkAbLoop() {
        val p = player
        if (p == null || abPointA < 0 || abPointB <= abPointA) {
            resetAbRepeat()
            return
        }
        if (p.currentPosition >= abPointB) p.seekTo(abPointA)
        startAbLoopPolling()
    }

    private fun resetAbRepeat() {
        runnableHandler.removeCallbacksAndMessages(AB_REPEAT_TOKEN)
        abPointA = -1L
        abPointB = -1L
        binding.abToggle.alpha = 1f
    }

    // ---------------- screenshot ----------------

    /**
     * PrimeTube: capture the current video frame with [PixelCopy] (works
     * for SurfaceView content) and store it in Pictures/PrimeTube.
     */
    private fun captureScreenshot() {
        val activity = context as? BaseActivity ?: return
        if (player == null || width <= 0 || height <= 0) return
        runCatching {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                activity.window,
                rect,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        saveScreenshotAsync(bitmap)
                    } else {
                        toast(R.string.prime_screenshot_failed)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure { toast(R.string.prime_screenshot_failed) }
    }

    private fun saveScreenshotAsync(bitmap: Bitmap) {
        Thread {
            val name = runCatching { saveBitmapToGallery(bitmap) }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (name != null) {
                    toast(context.getString(R.string.prime_screenshot_saved, name))
                } else {
                    toast(R.string.prime_screenshot_failed)
                }
            }
        }.start()
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): String {
        val name = "PrimeTube_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PrimeTube")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gallery insert failed")
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            } ?: throw IOException("output stream failed")
            return context.getString(R.string.prime_screenshot_folder)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PrimeTube")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("mkdirs failed")
            val file = File(dir, name)
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            return file.absolutePath
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoplayState() {
        runCatching {
            val enabled = PlayerHelper.autoPlayEnabled
            binding.autoplayToggle.alpha = if (enabled) 1f else 0.4f
        }
    }

    fun getOptionsMenuItems(): List<BottomSheetItem> = listOf(
        BottomSheetItem(
            context.getString(R.string.repeat_mode),
            R.drawable.ic_repeat,
            {
                when (PlayingQueue.repeatMode) {
                    Player.REPEAT_MODE_OFF -> context.getString(R.string.repeat_mode_none)
                    Player.REPEAT_MODE_ONE -> context.getString(R.string.repeat_mode_current)
                    Player.REPEAT_MODE_ALL -> context.getString(R.string.repeat_mode_all)
                    else -> throw IllegalArgumentException()
                }
            }
        ) {
            onRepeatModeClicked()
        },
        BottomSheetItem(
            context.getString(R.string.player_resize_mode),
            R.drawable.ic_aspect_ratio,
            {
                resizeModes.find { it.first == resizeMode }?.second?.let {
                    context.getString(it)
                }
            }
        ) {
            onResizeModeClicked()
        },
        BottomSheetItem(
            context.getString(R.string.playback_speed),
            R.drawable.ic_speed,
            {
                "${player?.playbackParameters?.speed?.round(2)}x"
            }
        ) {
            onPlaybackSpeedClicked()
        },
        BottomSheetItem(
            context.getString(R.string.sleep_timer),
            R.drawable.ic_sleep,
            {
                if (SleepTimer.timeLeftMillis > 0) {
                    val minutesLeft =
                        ceil(SleepTimer.timeLeftMillis.toDouble() / DateUtils.MINUTE_IN_MILLIS).toInt()
                    context.resources.getQuantityString(
                        R.plurals.minutes_left,
                        minutesLeft,
                        minutesLeft
                    )
                } else {
                    context.getString(R.string.disabled)
                }
            }
        ) {
            onSleepTimerClicked()
        },
        BottomSheetItem(
            context.getString(R.string.quality),
            R.drawable.ic_hd,
            this::getCurrentResolutionSummary
        ) {
            onQualityClicked()
        },
        BottomSheetItem(
            context.getString(R.string.audio_track),
            R.drawable.ic_audio,
            this::getCurrentAudioTrackTitle
        ) {
            onAudioStreamClicked()
        },
        BottomSheetItem(
            context.getString(R.string.captions),
            R.drawable.ic_caption,
            {
                player?.let { PlayerHelper.getCurrentPlayedCaptionFormat(it)?.language }
                    ?: context.getString(R.string.none)
            }
        ) {
            onCaptionsClicked()
        },
        BottomSheetItem(
            context.getString(R.string.stats_for_nerds),
            R.drawable.ic_info
        ) {
            onStatsClicked()
        }
    )

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun getCurrentResolutionSummary(): String {
        val currentQuality = player?.videoSize?.height ?: 0
        var summary = "${currentQuality}p"
        if (selectedResolution == null) {
            summary += " - ${context.getString(R.string.auto)}"
        } else if ((selectedResolution ?: 0) > currentQuality) {
            summary += " - ${context.getString(R.string.resolution_limited)}"
        }
        return summary
    }

    private fun getCurrentAudioTrackTitle(): String {
        if (player == null) {
            return context.getString(R.string.unknown_or_no_audio)
        }

        // The player reference should be not changed between the null check
        // and its access, so a non-null assertion should be safe here
        val selectedAudioLanguagesAndRoleFlags =
            PlayerHelper.getAudioLanguagesAndRoleFlagsFromTrackGroups(
                player!!.currentTracks.groups,
                true
            )

        if (selectedAudioLanguagesAndRoleFlags.isEmpty()) {
            return context.getString(R.string.unknown_or_no_audio)
        }

        // At most one audio track should be selected regardless of audio
        // format or quality
        val firstSelectedAudioFormat = selectedAudioLanguagesAndRoleFlags[0]

        if (selectedAudioLanguagesAndRoleFlags.size == 1 &&
            firstSelectedAudioFormat.first == null &&
            !PlayerHelper.haveAudioTrackRoleFlagSet(
                firstSelectedAudioFormat.second
            )
        ) {
            // Regardless of audio format or quality, if there is only one
            // audio stream which has no language and no role flags, it
            // should mean that there is only a single audio track which
            // has no language or track type set in the video played
            // Consider it as the default audio track (or unknown)
            return context.getString(R.string.default_or_unknown_audio_track)
        }


        return PlayerHelper.getAudioTrackNameFromFormat(
            context,
            firstSelectedAudioFormat,
        )
    }


    private fun rewind() {
        player?.seekBy(-PlayerHelper.seekIncrement)

        // show the rewind button
        doubleTapOverlayBinding.apply {
            animateSeeking(
                rewindLayout.rewindBTN,
                rewindLayout.rewindIV,
                rewindLayout.rewindTV,
                true
            )

            // start callback to hide the button
            runnableHandler.removeCallbacksAndMessages(HIDE_REWIND_BUTTON_TOKEN)
            runnableHandler.postDelayed(700, HIDE_REWIND_BUTTON_TOKEN) {
                rewindLayout.rewindBTN.isGone = true
            }
        }
    }

    private fun forward() {
        player?.seekBy(PlayerHelper.seekIncrement)

        // show the forward button
        doubleTapOverlayBinding.apply {
            animateSeeking(
                forwardLayout.forwardBTN,
                forwardLayout.forwardIV,
                forwardLayout.forwardTV,
                false
            )

            // start callback to hide the button
            runnableHandler.removeCallbacksAndMessages(HIDE_FORWARD_BUTTON_TOKEN)
            runnableHandler.postDelayed(700, HIDE_FORWARD_BUTTON_TOKEN) {
                forwardLayout.forwardBTN.isGone = true
            }
        }
    }

    private fun animateSeeking(
        container: FrameLayout,
        imageView: ImageView,
        textView: TextView,
        isRewind: Boolean
    ) {
        container.isVisible = true
        // the direction of the action
        val direction = if (isRewind) -1 else 1

        // clear previous animation
        imageView.animate()
            .rotation(0F)
            .setDuration(0)
            .start()

        textView.animate()
            .translationX(0f)
            .setDuration(0)
            .start()

        // start the rotate animation of the drawable
        imageView.animate()
            .rotation(direction * 30F)
            .setDuration(ANIMATION_DURATION)
            .withEndAction {
                // reset the animation when finished
                imageView.animate()
                    .rotation(0F)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            }
            .start()

        // animate the text view to move outside the image view
        textView.animate()
            .translationX(direction * 100f)
            .setDuration((ANIMATION_DURATION * 1.5).toLong())
            .withEndAction {
                // move the text back into the button
                runnableHandler.postDelayed(100) {
                    textView.animate()
                        .setDuration(ANIMATION_DURATION / 2)
                        .translationX(0f)
                        .start()
                }
            }
    }

    private fun initializeGestureProgress() {
        gestureViewBinding.brightnessProgressBar.let { bar ->
            bar.progress = (brightnessHelper.savedWindowBrightness * bar.max).toInt().coerceIn(0, bar.max)
        }
        gestureViewBinding.volumeProgressBar.let { bar ->
            bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
        }
    }

    private fun updateBrightness(distance: Float) {
        gestureViewBinding.brightnessControlView.isVisible = true
        val bar = gestureViewBinding.brightnessProgressBar

        if (bar.progress == 0) {
            // If brightness progress goes to below 0, set to system brightness
            if (distance <= 0) {
                brightnessHelper.resetToSystemBrightness()
                gestureViewBinding.brightnessImageView.setImageResource(
                    R.drawable.ic_brightness_auto
                )
                gestureViewBinding.brightnessTextView.text = resources.getString(R.string.auto)
                return
            }
            gestureViewBinding.brightnessImageView.setImageResource(R.drawable.ic_brightness)
        }

        bar.incrementProgressBy(distance.toInt())
        gestureViewBinding.brightnessTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
        brightnessHelper.windowBrightness = bar.progress.toFloat() / bar.max
    }

    private fun updateVolume(distance: Float) {
        val bar = gestureViewBinding.volumeProgressBar
        gestureViewBinding.volumeControlView.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
            }
        }

        if (bar.progress == 0) {
            gestureViewBinding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt())
        audioHelper.deviceVolume = bar.progress.toFloat() / bar.max

        gestureViewBinding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    override fun onPlaybackSpeedClicked() {
        (player as? MediaController)?.let {
            PlaybackOptionsSheet(it).show(supportFragmentManager)
        }
    }

    override fun onResizeModeClicked() {
        // switching between original aspect ratio (black bars) and zoomed to fill device screen
        BaseBottomSheet()
            .setSimpleItems(
                resizeModes.map { context.getString(it.second) },
                preselectedItem = resizeModes.first { it.first == resizeMode }.second.let {
                    context.getString(it)
                }
            ) { index ->
                resizeMode = resizeModes[index].first
            }
            .show(supportFragmentManager)
    }

    override fun setResizeMode(resizeMode: Int) {
        super.setResizeMode(resizeMode)
        // automatically remember the resize mode for the next session
        resizeModePref = resizeMode
    }

    override fun onRepeatModeClicked() {
        // repeat mode options dialog
        BaseBottomSheet()
            .setSimpleItems(
                PlayerHelper.repeatModes.map { context.getString(it.second) },
                preselectedItem = PlayerHelper.repeatModes
                    .firstOrNull { it.first == PlayingQueue.repeatMode }
                    ?.second?.let {
                        context.getString(it)
                    }
            ) { index ->
                PlayingQueue.repeatMode = PlayerHelper.repeatModes[index].first
            }
            .show(supportFragmentManager)
    }

    override fun onSleepTimerClicked() {
        SleepTimerSheet().show(supportFragmentManager)
    }

    override fun onCaptionsClicked() {
        val player = player ?: return

        val captions = PlayerHelper.getCaptionTracks(player)
            // put normal tracks before auto-generated tracks
            .sortedBy { it.roleFlags == PlayerHelper.ROLE_FLAG_AUTO_GEN_SUBTITLE }
            .associateWith {
                val displayName = Locale.forLanguageTag(it.language.orEmpty())
                    .getDisplayLanguage(Locale.getDefault())

                if (it.roleFlags == PlayerHelper.ROLE_FLAG_AUTO_GEN_SUBTITLE) {
                    "$displayName (${context.getString(R.string.auto_generated)})"
                } else {
                    displayName
                }
            }

        val currentSubtitle = PlayerHelper.getCurrentPlayedCaptionFormat(player)
        BaseBottomSheet()
            .setSimpleItems(
                listOf(context.getString(R.string.none)) + captions.values.toList(),
                preselectedItem = captions.entries.firstOrNull { (track, _) ->
                    track == currentSubtitle
                }?.value ?: context.getString(R.string.none)
            ) { index ->
                val captionsFormat =
                    captions.keys.toList().getOrNull(index - 1)

                updateCurrentSubtitle(captionsFormat?.id)
                playerViewModel?.currentCaptionId = captionsFormat?.id
            }
            .show(supportFragmentManager)
    }

    fun updateCurrentSubtitle(trackId: String?) {
        val player = player as? MediaController ?: return

        player.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_CAPTION_TRACK.name to trackId
            )
        )
    }

    /**
     * Get all available player resolutions
     */
    private fun getAvailableResolutions(): List<VideoResolution> {
        val player = player ?: return emptyList()

        val resolutions = player.currentTracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).map {
                    group.getTrackFormat(it).height
                }
            }
            .filter { it > 0 }
            .map { VideoResolution("${it}p", it) }
            .toSortedSet(compareByDescending { it.resolution })

        resolutions.add(VideoResolution(context.getString(R.string.auto_quality), Int.MAX_VALUE))
        return resolutions.toList()
    }

    override fun onQualityClicked() {
        // get the available resolutions
        val resolutions = getAvailableResolutions()

        // Dialog for quality selection
        BaseBottomSheet()
            .setSimpleItems(
                resolutions.map(VideoResolution::name),
                preselectedItem = resolutions.firstOrNull {
                    it.resolution == selectedResolution
                }?.name ?: context.getString(R.string.auto_quality)
            ) { which ->
                val newResolution = resolutions[which].resolution
                setPlayerResolution(newResolution, true)

                // save the selected resolution to update on fullscreen change
                if (noFullscreenResolution != null && isFullscreen()) {
                    noFullscreenResolution = newResolution
                } else {
                    fullscreenResolution = newResolution
                }
            }
            .show(supportFragmentManager)
    }

    fun setToDefaultResolution() {
        fullscreenResolution = PlayerHelper.getDefaultResolution(context, true)
        noFullscreenResolution = PlayerHelper.getDefaultResolution(context, false)
        updateResolution(isFullscreen())
    }

    private fun updateResolution(isFullscreen: Boolean) {
        if (!isFullscreen && noFullscreenResolution != null) {
            setPlayerResolution(noFullscreenResolution!!)
        } else if (fullscreenResolution != null) {
            setPlayerResolution(fullscreenResolution!!)
        } else {
            setPlayerResolution(Int.MAX_VALUE)
        }
    }

    fun setPlayerResolution(resolution: Int, isSelectedByUser: Boolean = false) {
        val player = player as? MediaController ?: return

        val transformedResolution =
            if (!isSelectedByUser && playerCallback.isVideoShort()) {
                ceil(resolution * 16.0 / 9.0).toInt()
            } else {
                resolution
            }

        player.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_RESOLUTION.name to transformedResolution
            )
        )

        selectedResolution = resolution
    }

    override fun onAudioStreamClicked() {
        val player = player as? MediaController ?: return
        val context = context ?: return

        val audioLanguagesAndRoleFlags = PlayerHelper.getAudioLanguagesAndRoleFlagsFromTrackGroups(
            player.currentTracks.groups,
            false
        )
        val baseBottomSheet = BaseBottomSheet()

        if (audioLanguagesAndRoleFlags.isEmpty() || (audioLanguagesAndRoleFlags.size == 1 &&
                    audioLanguagesAndRoleFlags[0].first == null &&
                    !PlayerHelper.haveAudioTrackRoleFlagSet(
                        audioLanguagesAndRoleFlags[0].second
                    ))
        ) {
            // Regardless of audio format or quality, if there is only one audio stream which has
            // no language and no role flags, it should mean that there is only a single audio
            // track which has no language or track type set in the video played
            // Consider it as the default audio track (or unknown)
            baseBottomSheet.setSimpleItems(
                listOf(context.getString(R.string.default_or_unknown_audio_track)),
                preselectedItem = context.getString(R.string.default_or_unknown_audio_track),
                listener = null
            )
        } else {
            val sortedAudioTracks = audioLanguagesAndRoleFlags
                // audio tracks have only a single flag set
                // ordered by main, dubbed, audio descriptive
                .sortedBy { it.second }

            baseBottomSheet.setSimpleItems(
                sortedAudioTracks
                .map {
                    PlayerHelper.getAudioTrackNameFromFormat(context, it)
                },
                preselectedItem = getCurrentAudioTrackTitle(),
            ) { index ->
                val selectedAudioFormat = sortedAudioTracks[index]
                player.sendCustomCommand(
                    AbstractPlayerService.runPlayerActionCommand, bundleOf(
                        PlayerCommand.SET_AUDIO_ROLE_FLAGS.name to selectedAudioFormat.second
                    )
                )
                player.sendCustomCommand(
                    AbstractPlayerService.runPlayerActionCommand, bundleOf(
                        PlayerCommand.SET_AUDIO_LANGUAGE.name to selectedAudioFormat.first
                    )
                )
                selectedAudioLanguageAndRoleFlags = selectedAudioFormat
            }
        }

        baseBottomSheet.show(supportFragmentManager)
    }

    override fun onStatsClicked() {
        val player = player ?: return

        val videoStats =
            PlayerHelper.getVideoStats(player.currentTracks, playerCallback.getVideoId())
        StatsSheet()
            .apply { arguments = bundleOf(IntentData.videoStats to videoStats) }
            .show(supportFragmentManager)
    }

    fun isFullscreen() = commonPlayerViewModel?.isFullscreen?.value ?: false

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)

        updateMarginsByFullscreenMode()
    }

    /**
     * PrimeTube: YouTube-style queue side panel. Transparent, with a close
     * button and at most ~25% of the player width so the video stays visible.
     */
    private fun toggleQueuePanel() {
        val root = binding.queuePanelRoot
        if (root.isVisible) {
            hideQueuePanel()
            return
        }

        if (queuePanelAdapter == null) {
            val adapter = PlayingQueueAdapter { videoId ->
                (player as? MediaController)?.navigateVideo(videoId)
                queuePanelAdapter?.refresh()
            }
            binding.queuePanelRecycler.layoutManager = SafeLinearLayoutManager(context)
            binding.queuePanelRecycler.adapter = adapter
            binding.queuePanelClose.setOnClickListener { hideQueuePanel() }
            // PrimeTube: playlist search like YouTube
            binding.queuePanelSearch.doAfterTextChanged { editable ->
                queuePanelAdapter?.setFilter(editable?.toString().orEmpty())
            }
            queuePanelAdapter = adapter
        }

        // at most ~25% of the player width (small floor for usability)
        val playerWidth = width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val minWidth = (resources.displayMetrics.density * 140).toInt()
        binding.queuePanel.layoutParams = binding.queuePanel.layoutParams.apply {
            width = maxOf((playerWidth * 0.25f).toInt(), minWidth)
        }

        queuePanelAdapter?.refresh()
        root.isVisible = true
    }

    /** PrimeTube: hides the queue side panel and resets the playlist search. */
    private fun hideQueuePanel() {
        binding.queuePanelRoot.isGone = true
        if (binding.queuePanelSearch.hasFocus()) binding.queuePanelSearch.clearFocus()
        if (binding.queuePanelSearch.text?.isNotEmpty() == true) {
            binding.queuePanelSearch.setText("")
            queuePanelAdapter?.setFilter("")
        }
    }

    /** PrimeTube: state holder of the queue side panel adapter */
    private var queuePanelAdapter: PlayingQueueAdapter? = null

    /**
     * Updates the margins according to the current orientation and fullscreen mode
     */
    fun updateMarginsByFullscreenMode() {
        // add a larger bottom margin to the time bar in landscape mode
        binding.exoProgress.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = (if (isFullscreen()) 20f else 0f).dpToPx()
        }

        updateTopBarMargin()

        // don't add extra padding if there's no cutout and no margin set that would need to be undone
        if (!activity.hasCutout && binding.topBar.marginStart == LANDSCAPE_MARGIN_HORIZONTAL_NONE) return

        // add a margin to the top and the bottom bar in landscape mode for notches
        val isForcedLandscape =
            activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val isInLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin =
            if (isFullscreen() && (isInLandscape || isForcedLandscape)) LANDSCAPE_MARGIN_HORIZONTAL else LANDSCAPE_MARGIN_HORIZONTAL_NONE

        listOf(binding.topBar, binding.bottomBar).forEach {
            it.updateLayoutParams<MarginLayoutParams> {
                marginStart = horizontalMargin
                marginEnd = horizontalMargin
            }
        }

        binding.fullscreen.layoutParams =
            (binding.fullscreen.layoutParams as MarginLayoutParams).apply {
                if (isFullscreen()) {
                    // Add extra bottom margin in fullscreen mode
                    bottomMargin =
                        resources.getDimensionPixelSize(R.dimen.fullscreen_button_margin_bottom)
                    marginEnd =
                        resources.getDimensionPixelSize(R.dimen.fullscreen_button_margin_end)
                } else {
                    // Reset to default margin
                    bottomMargin =
                        resources.getDimensionPixelSize(R.dimen.normal_button_margin_bottom)
                    marginEnd = resources.getDimensionPixelSize(R.dimen.normal_button_margin_end)
                }
            }
    }

    /**
     * Load the captions style according to the users preferences
     */
    private fun applyCaptionsStyle() {
        val transparent = PlayerHelper.primeTransparentSubtitles
        val captionStyle = if (transparent) {
            // PrimeTube: YouTube-like clean captions - white text with a soft
            // shadow, nothing drawn behind it (fully transparent background)
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                0xB3000000.toInt(),
                null
            )
        } else {
            PlayerHelper.getCaptionStyle(context)
        }
        subtitleView?.apply {
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(
                Cue.TEXT_SIZE_TYPE_ABSOLUTE,
                PlayerHelper.captionsTextSize * subtitleTextScale
            )
            if (PlayerHelper.useRichCaptionRendering && !transparent) {
                setViewType(SubtitleView.VIEW_TYPE_WEB)
            }
            setApplyEmbeddedStyles(!transparent && captionStyle == CaptionStyleCompat.DEFAULT)
            setStyle(captionStyle)
            // PrimeTube: subtitles sit at the very bottom by default
            setBottomPaddingFraction(SUBTITLE_BOTTOM_FRACTION)
        }
        applySubtitleTextSize()
        applySubtitleTransform()
    }

    /** PrimeTube: re-apply the user subtitle offset after size changes. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySubtitleTransform()
    }

    private fun applySubtitleTransform() {
        val height = height.takeIf { it > 0 } ?: return
        val translation = subtitleOffsetFraction * height
        subtitleView?.translationY = translation
        aiSubtitleView.translationY = translation
    }

    private fun applySubtitleTextSize() {
        subtitleView?.setFixedTextSize(
            Cue.TEXT_SIZE_TYPE_ABSOLUTE,
            PlayerHelper.captionsTextSize * subtitleTextScale
        )
    }

    private fun persistSubtitle() {
        PreferenceHelper.putString(
            PreferenceKeys.PRIME_SUBTITLE_OFFSET,
            subtitleOffsetFraction.toString()
        )
        PreferenceHelper.putString(
            PreferenceKeys.PRIME_SUBTITLE_SCALE,
            subtitleTextScale.toString()
        )
    }

    /**
     * PrimeTube: free-form subtitle gestures. ONLY a two-finger pinch on the
     * visible caption area resizes the subtitles; every single-finger touch
     * stays with the player gestures so brightness/volume always work.
     */
    private fun handleSubtitleTouch(event: MotionEvent): Boolean {
        val subtitleView = subtitleView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount < 2) return false
                if (subtitleView.childCount == 0 || subtitleView.visibility != View.VISIBLE) {
                    return false
                }
                val rect = RectF()
                computeSubtitleBounds(rect)
                if (!rect.contains(event.getX(0), event.getY(0))) return false

                // PrimeTube: ONLY a two-finger pinch on the captions takes the
                // gesture over from the player - single-finger touches always
                // stay with the brightness/volume/fullscreen gestures.
                sendCancelToGestureController(event)
                subtitleGestureTaken = true
                subtitleScaleDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!subtitleGestureTaken) return false
                subtitleScaleDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTaken = subtitleGestureTaken
                subtitleScaleDetector.onTouchEvent(event)
                subtitleGestureTaken = false
                if (!wasTaken) return false
                persistSubtitle()
                return true
            }
        }
        return false
    }

    /** PrimeTube: end the tap/gesture detection cleanly when subtitles take over. */
    private fun sendCancelToGestureController(event: MotionEvent) {
        MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            MotionEvent.ACTION_CANCEL,
            event.x,
            event.y,
            event.metaState
        ).also { cancelEvent ->
            playerGestureController.onTouchEvent(cancelEvent)
            cancelEvent.recycle()
        }
    }

    /** PrimeTube: bounds of the drawn caption text in this view's coordinates. */
    private fun computeSubtitleBounds(out: RectF) {
        val subtitleView = subtitleView ?: return out.set(0f, 0f, 0f, 0f)
        val viewLocation = IntArray(2)
        val playerLocation = IntArray(2)
        subtitleView.getLocationOnScreen(viewLocation)
        getLocationOnScreen(playerLocation)
        val left = (viewLocation[0] - playerLocation[0]).toFloat()
        val top = (viewLocation[1] - playerLocation[1]).toFloat()

        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until subtitleView.childCount) {
            val child = subtitleView.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            l = minOf(l, left + child.left)
            t = minOf(t, top + child.top)
            r = maxOf(r, left + child.right)
            b = maxOf(b, top + child.bottom)
        }
        if (r > l && b > t) out.set(l, t, r, b) else out.set(left, top, left + subtitleView.width, top + subtitleView.height)
    }

    /**
     * Set the current position text (e.g. "10:00 - 17:37"). This does not set the timebar
     * progress, ExoPlayer handles that automatically.
     */
    @SuppressLint("SetTextI18n")
    private fun updateCurrentPosition() {
        val position = player?.currentPosition?.div(1000) ?: 0
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET }?.div(1000) ?: 0
        val timeLeft = duration - position

        binding.position.text =
            if (playerCallback.isVideoLive()) context.getString(R.string.live) else DateUtils.formatElapsedTime(
                position
            )
        binding.timeLeft.text = "-${DateUtils.formatElapsedTime(timeLeft)}"

        // PrimeTube: keep the AI (Bangla) subtitle overlay in sync
        updateAiSubtitle()

        runnableHandler.postDelayed(100, UPDATE_POSITION_TOKEN, this::updateCurrentPosition)
    }

    /** PrimeTube: pick and show the AI subtitle cue for the current position. */
    private fun updateAiSubtitle() {
        if (aiCues.isEmpty()) return
        val position = player?.currentPosition ?: return
        val cue = aiCues.firstOrNull { position >= it.startMs && position < it.endMs }
        if (cue != null) {
            if (aiSubtitleView.text.toString() != cue.text) aiSubtitleView.text = cue.text
            if (!aiSubtitleView.isVisible) {
                aiSubtitleView.isVisible = true
                applySubtitleTransform()
            }
        } else if (aiSubtitleView.isVisible) {
            aiSubtitleView.isVisible = false
        }
    }

    /**
     * PrimeTube: render AI translated (Bangla) subtitle cues. The ExoPlayer
     * caption track rendering is disabled while the AI overlay is active so
     * the two never run in parallel.
     */
    fun setAiSubtitleCues(cues: List<GeminiSubtitleHelper.AiCue>) {
        aiCues = cues
        aiSubtitleView.isVisible = false
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    /** PrimeTube: back to the original captions. */
    fun clearAiSubtitles() {
        aiCues = emptyList()
        aiSubtitleView.isVisible = false
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
    }

    /**
     * Add extra margin to the top bar to not overlap the status bar.
     */
    fun updateTopBarMargin() {
        binding.topBar.updateLayoutParams<MarginLayoutParams> {
            topMargin = (if (isFullscreen()) 18f else 0f).dpToPx()
        }
    }

    override fun onSingleTap(areControlsLocked: Boolean) {
        // PrimeTube: in PiP mode there is nothing to toggle - the progress bar
        // and headphone button are always visible, and the tap only opens the
        // SYSTEM PiP chrome (which no app can suppress)
        if (primePipMode) return
        if (areControlsLocked) {
            // PrimeTube: locked - pulse the lock pill, never reveal the controls
            lockIndicator?.animate()
                ?.scaleX(1.2f)?.scaleY(1.2f)
                ?.setDuration(120)
                ?.withEndAction {
                    lockIndicator?.animate()?.scaleX(1f)?.scaleY(1f)
                        ?.setDuration(120)?.start()
                }
                ?.start()
            return
        }
        toggleController()
    }

    override fun onDoubleTapCenterScreen() {
        player?.togglePlayPauseState()
    }

    override fun onDoubleTapLeftScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        rewind()
    }

    override fun onDoubleTapRightScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        forward()
    }

    override fun onSwipeLeftScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) {
            if (PlayerHelper.fullscreenGesturesEnabled) onSwipeCenterScreen(distanceY, positionY)
            return
        }

        if (isControllerFullyVisible) hideController()
        updateBrightness(distanceY)
    }

    override fun onSwipeRightScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) {
            if (PlayerHelper.fullscreenGesturesEnabled) onSwipeCenterScreen(distanceY, positionY)
            return
        }

        if (isControllerFullyVisible) hideController()
        updateVolume(distanceY)
    }

    override fun onSwipeCenterScreen(distanceY: Float, positionY: Float) {
        if (!PlayerHelper.fullscreenGesturesEnabled) return
        fullscreenGestureAnimationController.onSwipe(distanceY, positionY)
    }

    override fun onSwipeEnd() {
        fullscreenGestureAnimationController.onSwipeEnd()
        gestureViewBinding.brightnessControlView.isGone = true
        gestureViewBinding.volumeControlView.isGone = true
    }

    override fun onZoom() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
        }
    }

    override fun onMinimize() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_FRACTION)
    }

    override fun onLongPress() {
        if (!PlayerHelper.longPressFastForward) return

        backgroundBinding.fastForwardView.isVisible = true
        val player = player ?: return

        // backup current playback speed in order to restore it
        // after the fast forward action is done
        rememberedPlaybackSpeed = player.playbackParameters.speed

        // PrimeTube: hold to play at exactly 2x speed, like the YouTube app
        player.playbackParameters = PlaybackParameters(2f, player.playbackParameters.pitch)
    }

    override fun onLongPressEnd() {
        if (!PlayerHelper.longPressFastForward) return

        backgroundBinding.fastForwardView.isGone = true

        val player = player ?: return
        rememberedPlaybackSpeed?.let {
            player.playbackParameters = PlaybackParameters(it, player.playbackParameters.pitch)
        }
        rememberedPlaybackSpeed = null
    }

    override fun onFullscreenChange(isFullscreen: Boolean) {
        if (isFullscreen) {
            if (PlayerHelper.swipeGestureEnabled) {
                brightnessHelper.restoreSavedBrightness()
            }
            subtitleView?.setFixedTextSize(
                Cue.TEXT_SIZE_TYPE_ABSOLUTE,
                PlayerHelper.captionsTextSize * 1.5f * subtitleTextScale
            )
            if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
            }
        } else {
            if (PlayerHelper.swipeGestureEnabled) {
                brightnessHelper.resetToSystemBrightness()
            }
            subtitleView?.setFixedTextSize(
                Cue.TEXT_SIZE_TYPE_ABSOLUTE,
                PlayerHelper.captionsTextSize * subtitleTextScale
            )
            subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_FRACTION)
        }

        updateMarginsByFullscreenMode()
    }

    /**
     * Listen for all child touch events
     */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        // when a control is clicked, restart the countdown to hide the controller
        if (isControllerFullyVisible) {
            cancelHideControllerTask()
            enqueueHideControllerTask()
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        if (!useController) return false

        // PrimeTube: a TWO-FINGER pinch on the visible captions resizes them.
        // A single finger is ALWAYS left to the player gestures - the old
        // single-finger subtitle drag used to swallow brightness/volume swipes
        // that started over the caption area.
        if (handleSubtitleTouch(event)) return true

        return playerGestureController.onTouchEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.togglePlayPauseState()
            }

            // PrimeTube: TV remote - OK opens the control bar and puts the
            // focus on play/pause; when the bar is already open it toggles
            // playback like on the YouTube TV app
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isControllerFullyVisible) {
                    player?.togglePlayPauseState()
                } else {
                    showTvControls()
                }
            }

            // PrimeTube: TV remote - any up/down movement reveals the bar
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isControllerFullyVisible) showTvControls() else return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                forward()
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                rewind()
            }

            // PrimeTube: full media key support on TVs and keyboards
            KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()

            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                PlayingQueue.getNext()?.let { (player as? MediaController)?.navigateVideo(it) }
            }

            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                PlayingQueue.getPrev()?.let { (player as? MediaController)?.navigateVideo(it) }
            }

            KeyEvent.KEYCODE_F -> {
                playerCallback.toggleFullscreen()
            }

            else -> return false
        }

        return true
    }

    override fun getViewMeasures(): Pair<Int, Int> {
        return width to height
    }

    var alreadySetDefaultSubtitle: Boolean = false
    fun onPlaybackEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            binding.playPauseBTN.setImageResource(
                PlayerHelper.getPlayPauseActionIcon(player)
            )

            // keep screen on if the video is playing
            keepScreenOn = player.isPlaying == true
        }

        if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
            // if the video is not starting automatically, show the controller
            if (!PlayerHelper.playAutomatically) showControllerPermanently()
            // PrimeTube: like YouTube - autoplaying videos reveal the controls
            // for a moment too, so the user always sees the controls work
            else showController()
        }

        if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME) && !alreadySetDefaultSubtitle) {
            // only set the default subtitle at the start of the playback session
            alreadySetDefaultSubtitle = true

            // set default caption language from preferences if caption language is available
            val captions = PlayerHelper.getCaptionTracks(player)
            val defaultLangCaption =
                captions.firstOrNull { it.language == PlayerHelper.defaultSubtitleCode }

            updateCurrentSubtitle(defaultLangCaption?.id)

            // if the video is live, the remaining time is displayed instead of duration
            updateDisplayedDurationType()
        }
        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
            // new video started
            alreadySetDefaultSubtitle = false
        }

        updateDisplayedDuration()
    }

    fun getWindow(): Window = currentWindow ?: activity.window

    /**
     * PrimeTube: re-apply the saved gesture brightness to whichever window the
     * player currently lives in. Called right after the fullscreen dialog
     * window swap, because the fullscreen-change callback fires BEFORE the
     * player view is actually moved into the new window.
     */
    fun primeSyncBrightnessToCurrentWindow() {
        if (PlayerHelper.swipeGestureEnabled) brightnessHelper.restoreSavedBrightness()
    }

    /**
     * PrimeTube: give the system brightness back to the (new) activity window
     * after the player left the fullscreen dialog window.
     */
    fun primeResetBrightnessToSystemWindow() {
        if (PlayerHelper.swipeGestureEnabled) brightnessHelper.resetToSystemBrightness()
    }

    companion object {
        private const val HIDE_CONTROLLER_TOKEN = "hideController"
        private const val HIDE_FORWARD_BUTTON_TOKEN = "hideForwardButton"
        private const val HIDE_REWIND_BUTTON_TOKEN = "hideRewindButton"
        private const val UPDATE_POSITION_TOKEN = "updatePosition"

        /** PrimeTube: handler token of the A-B repeat polling loop. */
        private const val AB_REPEAT_TOKEN = "primeAbRepeat"

        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.158f

        /** PrimeTube: default subtitle position - hugging the very bottom. */
        private const val SUBTITLE_BOTTOM_FRACTION = 0.012f
        private const val ANIMATION_DURATION = 100L
        private const val AUTO_HIDE_CONTROLLER_DELAY = 2000L
        private val LANDSCAPE_MARGIN_HORIZONTAL = 20f.dpToPx()
        private val LANDSCAPE_MARGIN_HORIZONTAL_NONE = 0f.dpToPx()
    }
}
