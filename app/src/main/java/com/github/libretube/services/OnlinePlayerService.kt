package com.github.libretube.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getSubtitleRoleFlags
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.util.DeArrowUtil
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YoutubeHlsPlaylistParser
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class OnlinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false

    // PlaylistId/ChannelId for autoplay
    private var playlistId: String? = null
    private var channelId: String? = null
    private var startTimestampSeconds: Long? = null

    /**
     * The response that gets when called the Api.
     */
    private var streams: Streams? = null

    /**
     * PrimeTube: YouTube's official DASH manifest (with all URLs routed through the instance
     * proxy), fetched as a fallback when the instance caps the available streams below 1440p.
     * This makes 1440p/2160p (4K) playback possible even on instances that only report
     * streams up to 1080p. Null when not needed or when fetching failed.
     */
    private var officialDashManifest: String? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /*
    Current job that's loading a new video (the value is null if no video is loading at the moment).
     */
    private var fetchVideoInfoJob: Job? = null

    /**
     * PrimeTube: "source error" recovery - stream URLs (proxied googlevideo links)
     * die frequently. Instead of falling into STATE_IDLE (which destroys the whole
     * service and kills playback), re-fetch fresh streams and resume at the last
     * position - twice, with a short backoff.
     */
    private var sourceErrorRetries = 0
    private var sourceErrorRecovery = false

    /**
     * PrimeTube: root-level "source error" prevention - when one playback pipeline
     * keeps failing, the recovery switches to a DIFFERENT pipeline instead of
     * retrying the same one forever:
     * 0 = automatic (SABR for regular videos, HLS for live),
     * 1 = plain DASH manifest (SABR skipped),
     * 2 = direct HLS playlist.
     * A successful READY state resets this back to automatic.
     */
    private var primeSourceEscalation = 0

    /**
     * PrimeTube: quality-pick verification. Some sources (SABR) advertise
     * every quality in their track list but quietly keep delivering a lower
     * rendition. After the user picks a quality, a short delayed check makes
     * sure the picked height is REALLY playing - and escalates the source
     * (official DASH) when it is not.
     */
    private var primeRequestedQualityHeight: Int? = null
    private var primeQualityVerifyTask: Runnable? = null
    private var primeQualityVerifyAttempts = 0
    private var primeQualityEscalatedFor: Pair<String, Int>? = null
    private var primeQualityEscalatedAt = 0L

    /**
     * PrimeTube: buffering watchdog - a video stuck in STATE_BUFFERING for too
     * long is brought back to life instead of freezing forever: first a light
     * re-buffer at the current position, then a full stream re-fetch (the
     * proven source-error recovery path).
     */
    private var bufferStuckSince = -1L
    private var bufferRecoveries = 0
    private val bufferWatchdog = object : Runnable {
        override fun run() {
            val p = exoPlayer
            if (p != null && !isTransitioning &&
                bufferStuckSince > 0 &&
                p.playbackState == Player.STATE_BUFFERING &&
                p.playWhenReady
            ) {
                val stuckMs = System.currentTimeMillis() - bufferStuckSince
                if (stuckMs > BUFFER_STUCK_MS && isVideoIdReady()) {
                    bufferRecoveries++
                    bufferStuckSince = System.currentTimeMillis()
                    when {
                        bufferRecoveries <= 2 -> {
                            // light recovery: re-buffer at the current position
                            toastFromMainThread(getString(R.string.prime_buffer_recover))
                            p.seekTo(p.currentPosition)
                            p.prepare()
                            p.play()
                        }

                        bufferRecoveries <= 4 -> {
                            // hard recovery: fetch fresh stream URLs, same as the
                            // source-error path - expired links are often the cause.
                            // PrimeTube: the player must ONLY be touched on the main
                            // thread - this runnable runs on the main handler, but the
                            // coroutine below runs on Dispatchers.IO, so the final
                            // play() is posted back through the main handler.
                            toastFromMainThread(getString(R.string.prime_source_retry))
                            sourceErrorRetries = 0
                            sourceErrorRecovery = true
                            isPrimeRecovering = true
                            escalateSourcePipeline()
                            val resumePosition = p.currentPosition.takeIf { it > 0 } ?: 0L
                            scope.launch {
                                delay(600L)
                                if (sourceErrorRecovery) {
                                    isTransitioning = true
                                    startTimestampSeconds =
                                        (resumePosition / 1000L).takeIf { it > 0 }
                                    startPlayback()
                                    handler.post { exoPlayer?.play() }
                                }
                            }
                        }

                        else -> {
                            // nothing worked - give the network time, then a
                            // fresh cycle can try again
                            bufferRecoveries = 0
                            bufferStuckSince = -1L
                        }
                    }
                }
            }
            handler.postDelayed(this, BUFFER_WATCHDOG_TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    if (!isTransitioning) playNextVideo()
                }

                Player.STATE_IDLE -> {
                    // PrimeTube: an in-flight source-error recovery passes through
                    // IDLE on purpose - only a real stop tears the service down
                    if (!sourceErrorRecovery) onDestroy()
                }

                Player.STATE_BUFFERING -> {
                    // PrimeTube: stamp the start so the watchdog can act when
                    // the buffer never recovers on its own
                    if (bufferStuckSince <= 0) {
                        bufferStuckSince = System.currentTimeMillis()
                    }
                }
                Player.STATE_READY -> {
                    sourceErrorRetries = 0
                    sourceErrorRecovery = false
                    isPrimeRecovering = false
                    bufferStuckSince = -1L
                    bufferRecoveries = 0
                    // PrimeTube: playback works again - the automatic pipeline
                    // (SABR first) gets its chance back on the next video
                    primeSourceEscalation = 0
                    // PrimeTube: black-screen rescue - if the user picked a
                    // quality and NO video track got selected at all (e.g. the
                    // device cannot decode 4K), relax the exact-height demand
                    // so the closest playable quality BELOW the pick plays
                    // instead of a black screen
                    val requested = primeRequestedQualityHeight
                    if (requested != null &&
                        exoPlayer?.videoSize?.height == 0 &&
                        trackSelector?.parameters
                            ?.isTrackTypeDisabled(C.TRACK_TYPE_VIDEO) == false
                    ) {
                        trackSelector?.updateParameters {
                            setMinVideoSize(Int.MIN_VALUE, 0)
                            setMaxVideoSize(Int.MAX_VALUE, requested)
                        }
                        primeRequestedQualityHeight = null
                    }
                    // save video to watch history when the video starts playing or is being resumed
                    // waiting for the player to be ready since the video can't be claimed to be watched
                    // while it did not yet start actually, but did buffer only so far
                    if (PlayerHelper.watchHistoryEnabled) {
                        scope.launch(Dispatchers.IO) {
                            streams?.let { streams ->
                                val watchHistoryItem =
                                    streams.toStreamItem(videoId).toWatchHistoryItem(videoId)
                                DatabaseHelper.addToWatchHistory(watchHistoryItem)
                            }
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // PrimeTube: auto-recover from "source error" - expired stream URLs are
            // the usual culprit. Re-fetch the streams and resume where it stopped.
            if (sourceErrorRetries < SOURCE_ERROR_MAX_RETRIES && isVideoIdReady()) {
                sourceErrorRetries++
                sourceErrorRecovery = true
                isPrimeRecovering = true
                val resumePosition = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: 0L
                toastFromMainThread(getString(R.string.prime_source_retry))
                // PrimeTube: never retry the same broken pipeline - each error
                // escalates to a different source type (SABR -> DASH -> HLS)
                escalateSourcePipeline()
                scope.launch {
                    delay(600L * sourceErrorRetries)
                    if (sourceErrorRecovery) {
                        isTransitioning = true
                        startTimestampSeconds = (resumePosition / 1000L).takeIf { it > 0 }
                        startPlayback()
                        // PrimeTube: a recovered stream always continues playing.
                        // play() touches the player - it must run on the MAIN thread,
                        // this coroutine runs on Dispatchers.IO
                        handler.post { exoPlayer?.play() }
                    }
                }
                return
            }
            sourceErrorRecovery = false
            isPrimeRecovering = false
            // show a toast on errors
            toastFromMainThread(error.localizedMessage.orEmpty())
        }
    }

    override suspend fun onServiceCreated(args: Bundle) {
        val playerData = args.parcelable<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly)

        // get the intent arguments
        videoId = playerData.videoId!!
        playlistId = playerData.playlistId
        channelId = playerData.channelId
        startTimestampSeconds = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        exoPlayer?.addListener(playerListener)
        trackSelector?.updateParameters {
            setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
        }

        // PrimeTube: keep an eye on buffering for the whole session
        handler.post(bufferWatchdog)
    }

    override suspend fun startPlayback() {
        super.startPlayback()

        val timestampMs = startTimestampSeconds?.times(1000) ?: 0L
        startTimestampSeconds = null
        // PrimeTube: a recovered session must re-arm - the next source error
        // needs its full retry budget back
        sourceErrorRecovery = false

        // stop any previous task for loading video info
        fetchVideoInfoJob?.cancelAndJoin()

        // start loading the video info while keeping a reference to the job
        // so that it can be canceled once a different video is loaded
        fetchVideoInfoJob = scope.launch {
            // PrimeTube: the fetch itself is the #1 source of "source error" toast -
            // flaky instances and rate limits. Instead of giving up after ONE round,
            // retry the whole fetch (primary + alternative source) up to three times
            // with a growing pause. The player stays alive the whole time.
            var fetchError: Throwable? = null
            val fetched = (1..PRIME_FETCH_ATTEMPTS).firstNotNullOfOrNull { attempt ->
                if (attempt > 1) {
                    toastFromMainThread(getString(R.string.prime_source_retry))
                    delay(1200L * attempt)
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        MediaServiceRepository.instance.getStreams(videoId)
                    }.getOrElse { primaryError ->
                        Log.e(TAG(), primaryError.stackTraceToString())

                        // PrimeTube: age-restricted videos and blocked instances often work
                        // through the other source (local extraction <-> Piped) - retry once
                        runCatching {
                            MediaServiceRepository.getStreamsFromAlternativeSource(videoId)
                        }.getOrElse { alternativeError ->
                            Log.e(TAG(), alternativeError.stackTraceToString())
                            fetchError = if (alternativeError == primaryError) {
                                primaryError
                            } else {
                                alternativeError
                            }
                            null
                        }.also { retried ->
                            if (retried != null) {
                                toastFromMainDispatcher(R.string.prime_source_fallback)
                            }
                        }
                    }
                } ?: return@firstNotNullOfOrNull null

                DeArrowUtil.deArrowStreams(result, videoId)
            }

            if (fetched == null) {
                // PrimeTube: every attempt failed - never leave the player stuck in
                // "transitioning" (that killed the whole screen before). Show the
                // reason and stay alive so the user (or autoplay) can retry.
                withContext(Dispatchers.Main) {
                    isTransitioning = false
                    toastFromMainThread(
                        fetchError?.let { primeReadableStreamError(it) }
                            ?: getString(R.string.prime_stream_failed)
                    )
                }
                return@launch
            }
            streams = fetched

            // PrimeTube: if the instance only offers streams below 1440p, fetch YouTube's
            // official DASH manifest as a higher-quality (1440p/2160p) fallback source.
            officialDashManifest = streams?.let { fetchOfficialDashManifestIfNeeded(it) }

            streams?.toStreamItem(videoId)?.let {
                // save the current stream to the queue
                PlayingQueue.updateCurrent(it)

                if (!PlayingQueue.hasNext()) {
                    PlayingQueue.updateQueue(it, playlistId, channelId, streams!!.relatedStreams)
                }

                // update feed item with newer information, e.g. more up-to-date views
                SubscriptionHelper.submitFeedItemChange(it.toFeedItem())
            }

            launch {
                val segments = getSponsorBlockSegments()
                withContext(Dispatchers.Main) { setSponsorBlockSegments(segments) }
            }

            withContext(Dispatchers.Main) {
                setStreamSource()
                configurePlayer(timestampMs)
            }
        }

        fetchVideoInfoJob?.join()
        fetchVideoInfoJob = null
    }

    /**
     * PrimeTube: map raw stream-fetch exceptions to messages the user can act on.
     */
    private fun primeReadableStreamError(e: Throwable): String {
        val signature = (e.message.orEmpty() + " " + e.javaClass.simpleName).lowercase()
        return when {
            "age" in signature || "sign in" in signature || "confirm" in signature ->
                getString(R.string.prime_age_restricted)
            else -> getString(R.string.prime_stream_failed)
        }
    }

    /**
     * PrimeTube: move one step up the playback-pipeline ladder. A pipeline that
     * just threw is never retried as-is - the next recovery attempt uses a
     * DIFFERENT source type, which is what actually rescues the video when one
     * extraction path (e.g. SABR) is broken for a particular video or instance.
     */
    private fun escalateSourcePipeline() {
        if (primeSourceEscalation < 2) primeSourceEscalation++
    }

    /**
     * PrimeTube: the user picked a quality the loaded source cannot really
     * deliver (e.g. 1440p/2160p while playing from a capped pipeline). Rebuild
     * the source at DASH level - with the official DASH manifest fetched when
     * needed, which carries EVERY adaptive format up to the video's exact
     * maximum. The track selection parameters (min/max = the picked height)
     * are already set, so as soon as the fuller source is ready the chosen
     * quality actually plays.
     */
    override fun primeOnQualityEscalationNeeded(requestedHeight: Int) {
        if (!isVideoIdReady() || isPrimeRecovering || isTransitioning) return
        // live streams cap at what their HLS/DASH source carries - there is
        // no fuller source to escalate to
        if (streams?.isLive == true) return
        // one escalation attempt per (video, picked height) - the verification
        // pass calls this again, this keeps it from looping; a NEW pick (or
        // waiting a few seconds and re-picking) gets a fresh budget
        val key = videoId to requestedHeight
        val now = System.currentTimeMillis()
        if (key == primeQualityEscalatedFor && now - primeQualityEscalatedAt < 15_000L) return
        primeQualityEscalatedFor = key
        primeQualityEscalatedAt = now
        runCatching {
            // level 1 plays from the official DASH manifest (fetched when the
            // instance's own streams cap below the picked height) - that
            // manifest carries EVERY adaptive format up to the video's exact
            // maximum, which is what actually makes 1440p/2160p playable
            primeSourceEscalation = 1
            val resumePosition = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: 0L
            sourceErrorRetries = 0
            sourceErrorRecovery = true
            isPrimeRecovering = true
            scope.launch {
                delay(300L)
                isTransitioning = true
                startTimestampSeconds = (resumePosition / 1000L).takeIf { it > 0 }
                startPlayback()
                handler.post { exoPlayer?.play() }
            }
        }
    }

    /**
     * PrimeTube: the user picked a quality - remember it and schedule the
     * verification pass that confirms the picked height really plays.
     */
    override fun primeOnQualitySelected(requestedHeight: Int) {
        primeQualityVerifyTask?.let { handler.removeCallbacks(it) }
        primeQualityVerifyTask = null
        if (requestedHeight == Int.MAX_VALUE || requestedHeight <= 0) {
            primeRequestedQualityHeight = null
            return
        }
        primeRequestedQualityHeight = requestedHeight
        primeQualityVerifyAttempts = 0
        primeScheduleQualityVerification()
    }

    /**
     * PrimeTube: delayed check - is the picked quality actually playing?
     * SABR advertises every format in its track list but can quietly keep
     * streaming a lower one; without this check the quality picker looks
     * broken and the summary keeps saying "limited".
     */
    private fun primeScheduleQualityVerification() {
        val task = Runnable {
            primeQualityVerifyTask = null
            val requested = primeRequestedQualityHeight ?: return@Runnable
            val exo = exoPlayer ?: return@Runnable
            // a rebuild is already running - check again once it settles
            if (isTransitioning || isPrimeRecovering) {
                primeRetryQualityVerification()
                return@Runnable
            }
            // audio-only mode has no video track by design - nothing to verify
            val videoDisabled = trackSelector?.parameters
                ?.isTrackTypeDisabled(C.TRACK_TYPE_VIDEO) == true
            if (videoDisabled) {
                primeRequestedQualityHeight = null
                return@Runnable
            }
            val currentHeight = exo.videoSize.height
            if (currentHeight >= requested) {
                // the picked quality is actually playing - all good
                primeRequestedQualityHeight = null
                return@Runnable
            }
            // the loaded source advertised the quality but does not deliver
            // it (or no video track got selected at all) - rebuild from the
            // fullest source; the dedupe inside keeps this from looping
            primeOnQualityEscalationNeeded(requested)
            primeRetryQualityVerification()
        }
        primeQualityVerifyTask = task
        handler.postDelayed(task, 4500L)
    }

    private fun primeRetryQualityVerification() {
        if (primeQualityVerifyAttempts >= 3) return
        primeQualityVerifyAttempts++
        primeScheduleQualityVerification()
    }

    private fun configurePlayer(seekToPositionMs: Long) {
        // seek to the previous position if available
        if (seekToPositionMs != 0L) {
            exoPlayer?.seekTo(seekToPositionMs)
        } else if (watchPositionsEnabled) {
            DatabaseHelper.getWatchPositionBlocking(videoId)?.let {
                if (!DatabaseHelper.isVideoWatched(it, streams?.duration)) exoPlayer?.seekTo(it)
            }
        }

        exoPlayer?.apply {
            // automatically start playback when using the audio player
            playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            prepare()
        }
    }

    /**
     * Plays the next video from the queue
     */
    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null) {
            if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
                exoPlayer?.seekTo(0)
                return
            }

            if (!PlayerHelper.isAutoPlayEnabled(playlistId != null) || !shouldHandleAutoplay) return
        }

        val nextVideo = nextId ?: PlayingQueue.getNext() ?: return

        // play new video on background
        navigateVideo(nextVideo)
    }

    private suspend fun getSponsorBlockSegments(): List<Segment> {
        return runCatching {
            MediaServiceRepository.instance.getSegments(
                videoId,
                sponsorBlockConfig.keys.toList(),
                listOf("skip", "mute", "full", "poi", "chapter")
            ).segments
        }.getOrElse { emptyList() }
    }

    override fun navigateVideo(videoId: String) {
        this.streams = null
        this.officialDashManifest = null
        // PrimeTube: fresh video - drop the quality-pick verification state
        primeQualityVerifyTask?.let { handler.removeCallbacks(it) }
        primeQualityVerifyTask = null
        primeRequestedQualityHeight = null
        primeQualityEscalatedFor = null

        super.navigateVideo(videoId)
    }

    /**
     * PrimeTube: fetches YouTube's official DASH manifest when the instance-provided streams
     * don't include any video quality above 1080p. All googlevideo URLs inside the manifest
     * get rewritten through the instance proxy so that they can be fetched by this device.
     * Returns null on any failure, in which case the regular locally-built manifest is used.
     */
    private suspend fun fetchOfficialDashManifestIfNeeded(streams: Streams): String? {
        if (streams.isLive || streams.dash == null || !ProxyHelper.hasProxyUrl()) return null

        val maxVideoOnlyHeight = streams.videoStreams
            .filter { it.videoOnly == true }
            // PrimeTube: "sabr://" entries are PLACEHOLDERS the SABR pipeline
            // fills in on demand - they are not real fetchable streams.
            // Counting their advertised heights made the instance look 4K
            // capable and skipped the official DASH manifest, so 1440p/2160p
            // could never actually play.
            .filter { it.url?.startsWith("sabr://") != true }
            .maxOfOrNull { it.height ?: 0 } ?: 0
        if (maxVideoOnlyHeight >= 1440) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val manifestUrl = ProxyHelper.rewriteUrlUsingProxyPreference(streams.dash!!)
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(manifestUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val manifest = ProxyHelper.rewriteManifestUrls(
                        response.body?.string().orEmpty()
                    )
                    // only use the official manifest if it contains usable video representations
                    manifest.takeIf { it.contains("<Representation") }
                }
            }.onFailure {
                Log.w(TAG(), "failed to fetch official dash manifest: $it")
            }.getOrNull()
        }
    }

    /**
     * PrimeTube: encodes a DASH manifest string into a data URI usable by ExoPlayer.
     */
    private fun String.toDashDataUri(): Uri {
        val encoded = Base64.encodeToString(toByteArray(), Base64.DEFAULT)
        return "data:application/dash+xml;charset=utf-8;base64,$encoded".toUri()
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [exoPlayer]
     */
    private fun setStreamSource() {
        val streams = streams ?: return

        when {
            // SABR - only in the automatic pipeline (escalation 0). When SABR
            // playback failed once, recovery escalates and rebuilds the source
            // from a plain DASH manifest instead.
            // skip SABR for livestreams, as the player impl has no support for it
            primeSourceEscalation == 0 &&
                !streams.isLive &&
                streams.serverAbrStreamingUrl != null &&
                streams.videoPlaybackUstreamerConfig != null -> {
                val sabrMediaSourceFactory = SabrMediaSource.Factory(
                    SabrManifest(videoId, streams)
                )
                val mediaItem = createMediaItem(
                    streams.serverAbrStreamingUrl.toUri(),
                    "application/vnd.yt-ump",
                    streams
                )
                val mediaSource = sabrMediaSourceFactory.createMediaSource(mediaItem)
                val mediaSources = listOf<MediaSource>(mediaSource) + streams.subtitles.map {
                    val format = Format.Builder()
                        .setSampleMimeType(it.mimeType)
                        .setLanguage(it.code)
                        .setRoleFlags(getSubtitleRoleFlags(it))
                        .build()
                    val subtitleParserFactory = DefaultSubtitleParserFactory()
                    val extractorsFactory = ExtractorsFactory {
                        arrayOf(
                            SubtitleExtractor(
                                subtitleParserFactory.create(format), format
                            )
                        )
                    }
                    val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(this), extractorsFactory
                    ).setLoadOnlySelectedTracks(true)
                    try {
                        // `enableLazyLoadingWithSingleTrack` is private
                        val method =
                            ProgressiveMediaSource.Factory::class.java.getDeclaredMethod(
                                "enableLazyLoadingWithSingleTrack",
                                Int::class.java,
                                Format::class.java
                            )
                        method.isAccessible = true
                        method.invoke(
                            progressiveMediaSourceFactory, SubtitleExtractor.TRACK_ID,
                            format
                                .buildUpon()
                                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                .setCodecs(format.sampleMimeType)
                                .setCueReplacementBehavior( subtitleParserFactory.getCueReplacementBehavior(format))
                                .build()
                        )
                    } catch (e: Exception) {
                        Log.w(this::class.simpleName, "failed to set subtitle lazy-loading: ${e.stackTrace}")
                    }
                    progressiveMediaSourceFactory.createMediaSource(MediaItem.fromUri(it.url!!))
                }.toList()

                exoPlayer?.setMediaSource(MergingMediaSource(*mediaSources.toTypedArray()))
                return
            }
            // PrimeTube LIVE FIX: livestreams are played via HLS first, which is far more
            // reliable for live streams than DASH. The DASH manifest is only used as a
            // fallback when no HLS URL is available. Previously live streams always used
            // DASH, which frequently failed (403/blocked manifest URLs).
            streams.isLive && streams.hls != null -> {
                val hlsMediaSourceFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(this))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                val mediaItem = createMediaItem(
                    ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri(),
                    MimeTypes.APPLICATION_M3U8,
                    streams
                )
                val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(mediaSource)
                return
            }
            // live stream without HLS: use the DASH manifest generated by YT
            streams.isLive && streams.dash != null -> {
                val dashUri = ProxyHelper.rewriteUrlUsingProxyPreference(
                    streams.dash
                ).toUri()
                val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                exoPlayer?.setMediaItem(mediaItem)
            }
            // DASH (regular videos) - the escalated pipeline (level 1+) uses DASH.
            // At escalation level 2 the DASH branch is skipped when an HLS playlist
            // exists, so the direct-HLS pipeline gets its turn below.
            (primeSourceEscalation < 2 || streams.hls == null) &&
                streams.videoStreams.any { it.url?.startsWith("sabr://") != true } -> {
                // PrimeTube 4K BOOST: if the instance caps the streams below 1440p, use the
                // official YT DASH manifest (with proxied URLs) so that 1440p/2160p can play.
                val dashUri = officialDashManifest?.toDashDataUri()
                    ?: PlayerHelper.createDashSource(
                        streams.copy(
                            videoStreams = streams.videoStreams.filter {
                                it.url?.startsWith("sabr://") != true
                            }
                        ),
                        this
                    )

                val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                exoPlayer?.setMediaItem(mediaItem)
            }
            // HLS as last fallback
            streams.hls != null -> {
                val hlsMediaSourceFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(this))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                val mediaItem = createMediaItem(
                    ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri(),
                    MimeTypes.APPLICATION_M3U8,
                    streams
                )
                val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(mediaSource)
                return
            }
            // NO STREAM FOUND
            else -> {
                toastFromMainThread(R.string.unknown_error)
                return
            }
        }
    }

    private fun getSubtitleConfigs(): List<SubtitleConfiguration> = streams?.subtitles?.map {
        val roleFlags = getSubtitleRoleFlags(it)
        SubtitleConfiguration.Builder(it.url!!.toUri())
            .setRoleFlags(roleFlags)
            .setLanguage(it.code)
            .setMimeType(it.mimeType).build()
    }.orEmpty()

    private fun createMediaItem(uri: Uri, mimeType: String, streams: Streams) =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(getSubtitleConfigs())
            .setMetadata(streams, videoId)
            .build()
}

/** PrimeTube: how many times a playback error triggers a full stream re-fetch. */
private const val SOURCE_ERROR_MAX_RETRIES = 2

/** PrimeTube: how many rounds the stream-info fetch itself retries (primary + alternative source per round). */
private const val PRIME_FETCH_ATTEMPTS = 3

/** PrimeTube: buffering watchdog tuning - 15s stuck, checked every 2s. */
private const val BUFFER_STUCK_MS = 15_000L
private const val BUFFER_WATCHDOG_TICK_MS = 2_000L
