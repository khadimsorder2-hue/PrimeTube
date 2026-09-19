package com.github.libretube.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
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

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    if (!isTransitioning) playNextVideo()
                }

                Player.STATE_IDLE -> {
                    onDestroy()
                }

                Player.STATE_BUFFERING -> {}
                Player.STATE_READY -> {
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
    }

    override suspend fun startPlayback() {
        super.startPlayback()

        val timestampMs = startTimestampSeconds?.times(1000) ?: 0L
        startTimestampSeconds = null

        // stop any previous task for loading video info
        fetchVideoInfoJob?.cancelAndJoin()

        // start loading the video info while keeping a reference to the job
        // so that it can be canceled once a different video is loaded
        fetchVideoInfoJob = scope.launch {
            streams = withContext(Dispatchers.IO) {
                val fetched = runCatching {
                    MediaServiceRepository.instance.getStreams(videoId)
                }.getOrElse { primaryError ->
                    Log.e(TAG(), primaryError.stackTraceToString())

                    // PrimeTube: age-restricted videos and blocked instances often work
                    // through the other source (local extraction <-> Piped) - retry once
                    val retried = MediaServiceRepository
                        .getStreamsFromAlternativeSource(videoId)
                    if (retried != null) {
                        toastFromMainDispatcher(R.string.prime_source_fallback)
                        retried
                    } else {
                        toastFromMainDispatcher(primeReadableStreamError(primaryError))
                        return@withContext null
                    }
                }

                DeArrowUtil.deArrowStreams(fetched, videoId)
            } ?: return@launch

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
    private fun primeReadableStreamError(e: Exception): String {
        val signature = (e.message.orEmpty() + " " + e.javaClass.simpleName).lowercase()
        return when {
            "age" in signature || "sign in" in signature || "confirm" in signature ->
                getString(R.string.prime_age_restricted)
            else -> getString(R.string.prime_stream_failed)
        }
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
            // SABR
            // skip SABR for livestreams, as the player impl has no support for it
            !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
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
            // DASH (regular videos)
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
