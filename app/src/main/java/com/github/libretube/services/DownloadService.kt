package com.github.libretube.services

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.SparseBooleanArray
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.util.contains
import androidx.core.util.keyIterator
import androidx.core.util.set
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.helpers.PrimeDownloadTracker
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeActiveDownload
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeDownloadState
import com.github.libretube.helpers.PrimeSpeedCalculator
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import java.net.HttpURLConnection
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineContext = dispatcher + SupervisorJob()

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: Builder

    // PrimeTube: per-item speed estimator for the notifications + live queue
    private val speedCalculators = java.util.concurrent.ConcurrentHashMap<Int, PrimeSpeedCalculator>()

    /**
     * Maps all currently running downloads to `true`, and all paused or stopped downloads to `false`.
     */
    private val downloadQueue = SparseBooleanArray()

    // PrimeTube: registerNetworkChangedCallback is guarded against double-registration
    private var networkCallbackRegistered = false
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    /**
     * Cache that contains all the already-loaded video info.
     */
    private val cachedStreamsInfo: MutableMap<String, Streams> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        IS_DOWNLOAD_RUNNING = true
        notifyForeground()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
    }

    /**
     * Listen for network changes and pause the download if the network connection becomes metered
     */
    fun registerNetworkChangedCallback() {
        // PrimeTube: onStartCommand runs for every download action - register the
        // callback only once or the callbacks pile up over a long session
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        val connectivityManager = getSystemService<ConnectivityManager>()
        connectivityManager?.registerDefaultNetworkCallback(object :
            ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                // pause all downloads when switching to an unmetered connection
                if (NetworkHelper.isNetworkMetered(this@DownloadService)) {
                    for (download in downloadQueue.keyIterator()) {
                        pause(download)
                    }
                } else {
                    // PrimeTube: the network came back (WiFi blip, airplane mode
                    // off) - stalled downloads come back to life on their own
                    resumeAll()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val downloadId = intent?.getIntExtra("id", -1)
        when (intent?.action) {
            ACTION_DOWNLOAD_RESUME -> resume(downloadId!!)
            ACTION_DOWNLOAD_PAUSE -> pause(downloadId!!)
            ACTION_DOWNLOAD_STOP -> stop(downloadId!!)
            ACTION_RESUME_ALL -> resumeAll()
        }

        registerNetworkChangedCallback()

        val downloadData = intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
            ?: return START_NOT_STICKY
        val videoId = downloadData.videoId

        lifecycleScope.launch(coroutineContext) {
            val streams = loadStreamsInfo(videoId) ?: return@launch

            storeVideoMetadata(videoId, streams)

            val downloadItems = streams.toDownloadItems(downloadData)
            for (downloadItem in downloadItems) {
                start(downloadItem)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun loadStreamsInfo(videoId: String, forceRefresh: Boolean = false): Streams? {
        // PrimeTube: a forced refresh bypasses the cache - stream URLs expire
        // after a few hours and stale entries would keep serving dead 403 URLs
        if (!forceRefresh && cachedStreamsInfo.contains(videoId)) {
            return cachedStreamsInfo[videoId]
        }

        val streams = try {
            withContext(Dispatchers.IO) {
                MediaServiceRepository.instance.getStreams(videoId)
            }
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            toastFromMainDispatcher(e.localizedMessage.orEmpty())
            return null
        }

        cachedStreamsInfo[videoId] = streams
        return streams
    }

    private suspend fun storeVideoMetadata(videoId: String, streams: Streams) {
        val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, videoId)

        val download = Download(
            videoId,
            streams.title,
            streams.description,
            streams.uploader,
            streams.duration,
            streams.uploadTimestamp?.toLocalDate(),
            thumbnailTargetPath,
            streams.uploaderUrl,
            streams.views,
            streams.likes,
            streams.dislikes,
        )
        Database.downloadDao().insertDownload(download)

        for (chapter in streams.chapters) {
            val downloadChapter = DownloadChapter(
                videoId = videoId,
                name = chapter.title,
                start = chapter.start,
                thumbnailUrl = chapter.image
            )
            Database.downloadDao().insertDownloadChapter(downloadChapter)
        }

        // asynchronously load the remaining metadata
        // this allows the main thread to already start the actual download items (i.e. video/audio)
        // while the thumbnail and SponsorBlock segments are loaded in the background
        coroutineScope {
            launch(Dispatchers.IO) {
                downloadExtraVideoMetadata(videoId, streams.thumbnailUrl, thumbnailTargetPath)
            }
        }
    }

    /**
     * Download the thumbnail and SponsorBlock segments for the given [videoId].
     */
    private suspend fun downloadExtraVideoMetadata(
        videoId: String,
        thumbnailUrl: String,
        thumbnailTargetPath: Path
    ) {
        coroutineScope {
            launch {
                val segmentData = try {
                    val categories = PlayerHelper.getSponsorBlockCategories()
                    MediaServiceRepository.instance.getSegments(videoId, categories.map { it.key })
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download SponsorBlock segments for $videoId")
                    Log.e(TAG(), e.stackTraceToString())
                    return@launch
                }

                Database.downloadDao().insertSponsorBlockSegments(
                    segmentData.segments.map { it.toDownloadSegment(videoId) }
                )
            }

            launch {
                try {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        ProxyHelper.rewriteUrlUsingProxyPreference(thumbnailUrl),
                        thumbnailTargetPath
                    )
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download image $thumbnailUrl")
                    Log.e(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun selectFormatAndDownloadFile(item: DownloadItem) {
        // PrimeTube: show the item in the in-app download queue immediately
        PrimeDownloadTracker.upsert(
            PrimeActiveDownload(
                key = trackerKey(item.id),
                title = item.fileName,
                state = PrimeDownloadState.DOWNLOADING
            )
        )
        speedCalculators[item.id] = PrimeSpeedCalculator()

        val build = buildDownloadProvider(item, forceRefresh = false)
        val provider = build.provider
        if (provider == null) {
            // PrimeTube: the queue row must not hang forever when the video
            // info cannot even be fetched
            markTrackerFinished(item, false, build.error ?: "stream error")
            speedCalculators.remove(item.id)
            return
        }
        downloadFile(item, provider)
    }

    /** PrimeTube: provider build outcome - a ready provider or the reason it failed. */
    private class ProviderBuild(val provider: DownloadProvider?, val error: String? = null)

    /**
     * PrimeTube: fetch the stream info (optionally bypassing the stale cache)
     * and select the download provider for the given item. Kept separate from
     * the download loop so it can be re-run with FRESH URLs when the server
     * starts answering 403 (expired stream URLs).
     */
    private suspend fun buildDownloadProvider(item: DownloadItem, forceRefresh: Boolean): ProviderBuild {
        val streams = loadStreamsInfo(item.videoId, forceRefresh)
            ?: return ProviderBuild(null, "stream error")
        if (item.type == FileType.SUBTITLE) {
            // subtitles are always plain files and don't use SABR
            val subtitle = streams.subtitles.firstOrNull { it.code == item.language }
                ?: return ProviderBuild(null, "subtitle missing")
            return ProviderBuild(RawByteStreamDownloadProvider(subtitle.url!!.toHttpUrl()))
        }
        val selectedStream = selectMatchingStream(streams, item)
        if (selectedStream == null) {
            // PrimeTube: exact quality match failed - fall back to the
            // closest lower/equal quality instead of silently dying
            val fallback = when (item.type) {
                FileType.AUDIO -> streams.audioStreams
                    .maxByOrNull { it.bitrate ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
                FileType.VIDEO -> streams.videoStreams
                    .filter { !it.url.isNullOrEmpty() || (it.itag != null && it.lastModified != null) }
                    .filter {
                        (it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0) <=
                            (item.quality?.filter(Char::isDigit)?.toIntOrNull() ?: Int.MAX_VALUE)
                    }
                    .maxByOrNull { it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
                else -> null
            }
                ?: return ProviderBuild(null, "quality not available")
            return if (fallback.url?.startsWith("http") == true) {
                ProviderBuild(RawByteStreamDownloadProvider(fallback.url!!.toHttpUrl()))
            } else {
                // PrimeTube: a broken SABR init must end as a reported error,
                // not as a stuck "downloading" row
                val sabr = runCatching { SabrDownloadProvider(item, streams, fallback) }
                    .getOrElse { return ProviderBuild(null, "sabr init failed") }
                ProviderBuild(sabr)
            }
        }
        return if (selectedStream.url?.startsWith("http") == true) {
            ProviderBuild(RawByteStreamDownloadProvider(selectedStream.url!!.toHttpUrl()))
        } else {
            val sabr = runCatching { SabrDownloadProvider(item, streams, selectedStream) }
                .getOrElse { return ProviderBuild(null, "sabr init failed") }
            ProviderBuild(sabr)
        }
    }

    /**
     * Starts and progresses until the download is canceled or finished.
     *
     * You should probably not call this directly, call [selectFormatAndDownloadFile].
     */
    private suspend fun downloadFile(
        item: DownloadItem,
        downloadProvider: DownloadProvider,
    ) {
        downloadQueue[item.id] = true
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        var totalRead = item.path.fileSize()
        var numberOfTries = 0
        // PrimeTube: separate recovery budgets - expired URLs need a fresh
        // stream-info fetch, transient failures need backoff, unexpected
        // exceptions need their own small retry pool
        var urlRefreshes = 0
        var exceptionTries = 0
        var provider = downloadProvider
        while (downloadQueue[item.id] && !item.isFinished) {
            try {
                when (val result = provider.downloadNextChunk(item, sink)) {
                    DownloadProgressResult.DownloadComplete -> {
                        setPauseNotification(notificationBuilder, item, true)
                        _downloadFlow.emit(item.id to DownloadStatus.Completed)
                        downloadQueue[item.id] = false
                        markTrackerFinished(item, true, null)
                        speedCalculators.remove(item.id)
                        break
                    }
                    DownloadProgressResult.UrlExpired -> {
                        // PrimeTube: the stream URL expired (HTTP 403) - refetch
                        // fresh stream info and rebuild the provider; the file
                        // resumes at its byte offset (raw) / stored position (SABR)
                        if (urlRefreshes < MAX_URL_REFRESHES && downloadQueue[item.id]) {
                            urlRefreshes++
                            delay(minOf(1_000L * urlRefreshes, 5_000L))
                            val fresh = buildDownloadProvider(item, forceRefresh = true).provider
                            if (fresh != null) {
                                provider = fresh
                            } else {
                                setPauseNotification(notificationBuilder, item, false)
                                pause(item.id)
                                break
                            }
                        } else {
                            setPauseNotification(notificationBuilder, item, false)
                            pause(item.id)
                            break
                        }
                    }
                    DownloadProgressResult.Failed -> {
                        if (numberOfTries < MAX_SEGMENT_RETRIES) {
                            // PrimeTube: exponential backoff - hammering the
                            // server every 200ms never healed a network blip
                            delay(minOf(500L shl numberOfTries, 8_000L))
                            numberOfTries++
                        } else {
                            setPauseNotification(notificationBuilder, item, false)
                            pause(item.id)
                            break
                        }
                    }
                    is DownloadProgressResult.Progressed -> {
                        numberOfTries = 0
                        totalRead += result.bytes
                        _downloadFlow.emit(
                            item.id to DownloadStatus.Progress(
                                result.bytes,
                                totalRead,
                                item.downloadSize
                            )
                        )
                        updateNotification(notificationBuilder, item, totalRead.toInt())
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                // PrimeTube: one socket reset must not kill the whole download -
                // unexpected exceptions get a small retry budget of their own
                if (exceptionTries < MAX_EXCEPTION_RETRIES && downloadQueue[item.id]) {
                    exceptionTries++
                    runCatching { sink.flush() }
                    delay(1_000L * exceptionTries)
                    continue
                }
                toastFromMainThread("${getString(R.string.download)}: ${e.message}")
                Log.e(this@DownloadService::class.java.name, e.stackTraceToString())
                _downloadFlow.emit(item.id to DownloadStatus.Error(e.message.toString(), e))
                markTrackerFinished(item, false, e.message.toString())
                speedCalculators.remove(item.id)
                break
            }
        }

        withContext(Dispatchers.IO) {
            sink.flush()
            sink.close()
        }

        // start the next download if there are any remaining ones enqueued
        startNextEnqueueDownload()

        // explicitly send a pause event if the user paused the download, although it's not yet finished
        if (!item.isFinished) {
            // PrimeTube: the in-app queue row switches to the paused state
            PrimeDownloadTracker.upsert(
                PrimeActiveDownload(
                    key = trackerKey(item.id),
                    title = item.fileName,
                    state = PrimeDownloadState.PAUSED,
                    readBytes = item.path.fileSize(),
                    totalBytes = item.downloadSize
                )
            )
            speedCalculators.remove(item.id)
            pause(item.id)
        }

        // if no new download was enqueued (i.e. there's no paused/stopped download left),
        // look if any downloads are still running, and if not, stop the service
        stopServiceIfDone()
    }

    private suspend fun startNextEnqueueDownload() {
        for (id in downloadQueue.keyIterator()) {
            if (downloadQueue[id]) continue

            val dbItem = Database.downloadDao().findDownloadItemById(id)
            if (dbItem != null && (dbItem.downloadSize <= 0L || dbItem.path.fileSize() < dbItem.downloadSize)) {
                resume(id)
                return
            }
        }
    }

    private fun updateNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        totalRead: Int
    ) {
        // PrimeTube: live speed + percentage in the notification
        val speed = speedCalculators[item.id]?.onBytes(totalRead.toLong()) ?: 0L
        val percent = if (item.downloadSize > 0) {
            (totalRead * 100 / item.downloadSize).toInt().coerceIn(0, 100)
        } else {
            -1
        }

        notificationBuilder
            .setContentText(
                buildString {
                    append(totalRead.toLong().formatAsFileSize())
                    append(" / ")
                    append(item.downloadSize.formatAsFileSize())
                    if (speed > 0) {
                        append(" • ")
                        append(speed.formatAsFileSize())
                        append("/s")
                    }
                    if (percent >= 0) {
                        append(" • ")
                        append(percent)
                        append('%')
                    }
                }
            )
            .setProgress(
                item.downloadSize.toInt(),
                totalRead,
                false
            )
        notificationManager.notify(
            item.getNotificationId(),
            notificationBuilder.build()
        )

        // PrimeTube: mirror the progress into the in-app download queue
        PrimeDownloadTracker.updateProgress(
            trackerKey(item.id),
            totalRead.toLong(),
            item.downloadSize,
            speed
        )
    }

    /** PrimeTube: tracker key for an offline download item. */
    private fun trackerKey(itemId: Int): String = "internal:$itemId"

    /**
     * PrimeTube: move a queue row into a terminal state and clean it up after
     * a moment. For failures the reason is shown briefly.
     */
    private fun markTrackerFinished(item: DownloadItem, success: Boolean, message: String?) {
        val key = trackerKey(item.id)
        PrimeDownloadTracker.upsert(
            PrimeActiveDownload(
                key = key,
                title = item.fileName,
                state = if (success) PrimeDownloadState.COMPLETED else PrimeDownloadState.FAILED,
                message = message,
                finishedAtMs = System.currentTimeMillis()
            )
        )
        lifecycleScope.launch {
            delay(if (success) 3_500L else 6_000L)
            PrimeDownloadTracker.remove(key)
        }
    }

    /**
     * Returns true if the current amount of downloads is still less than the maximum amount of
     * concurrent downloads.
     */
    private fun mayStartNewDownload(): Boolean {
        val downloadCount = downloadQueue.valueIterator().asSequence().count { it }
        return downloadCount < DownloadHelper.MAX_CONCURRENT_DOWNLOADS
    }

    /**
     * Initiate download [Job] using [DownloadItem] by creating file according to [FileType]
     * for the requested file.
     */
    private fun start(item: DownloadItem) {
        item.path = when (item.type) {
            FileType.AUDIO -> getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
            FileType.VIDEO -> getDownloadPath(DownloadHelper.VIDEO_DIR, item.fileName)
            FileType.SUBTITLE -> getDownloadPath(DownloadHelper.SUBTITLE_DIR, item.fileName)
        }.apply { deleteIfExists() }.createFile()

        lifecycleScope.launch(coroutineContext) {
            item.id = Database.downloadDao().insertDownloadItem(item).toInt()

            if (mayStartNewDownload()) {
                selectFormatAndDownloadFile(item)
            } else {
                pause(item.id)
            }
        }
    }

    /**
     * Resume download which may have been paused.
     */
    fun resume(id: Int) {
        // If file is already downloading then avoid new download job.
        if (downloadQueue[id]) return

        if (!mayStartNewDownload()) {
            toastFromMainThread(getString(R.string.concurrent_downloads_limit_reached))
            lifecycleScope.launch(coroutineContext) {
                _downloadFlow.emit(id to DownloadStatus.Paused)
            }
            return
        }

        lifecycleScope.launch(coroutineContext) {
            val file = Database.downloadDao().findDownloadItemById(id) ?: return@launch
            selectFormatAndDownloadFile(file)
        }
    }

    /**
     * Pause downloading job for given [id]. If no downloads are active, stop the service.
     */
    fun pause(id: Int) {
        downloadQueue[id] = false

        lifecycleScope.launch(coroutineContext) {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        stopServiceIfDone()
    }

    /**
     * Resume all downloads: Queue them all, then fill empty slots.
     */
    private fun resumeAll() {
        lifecycleScope.launch(coroutineContext) {
            val incompleteItems = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
                    .flatMap { it.downloadItems }
                    .filter { !it.isFinished }
            }

            incompleteItems.forEach {
                if (!downloadQueue.contains(it.id)) {
                    downloadQueue.put(it.id, false)
                }
            }

            val current = downloadQueue.valueIterator().asSequence().count { it }
            val slotsToFill = DownloadHelper.MAX_CONCURRENT_DOWNLOADS - current

            if (slotsToFill > 0) {
                val candidates = incompleteItems.filter { !downloadQueue[it.id] }
                    .take(slotsToFill)

                candidates.forEach { item ->
                    launch {
                        selectFormatAndDownloadFile(item)
                    }
                }
            }
        }
    }

    /**
     * Stop downloading job for given [id]. If no downloads are active, stop the service.
     */
    private fun stop(id: Int) = lifecycleScope.launch(coroutineContext) {
        downloadQueue[id] = false
        _downloadFlow.emit(id to DownloadStatus.Stopped)

        // PrimeTube: a stopped download disappears from the in-app queue too
        speedCalculators.remove(id)
        PrimeDownloadTracker.remove(trackerKey(id))

        val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
        notificationManager.cancel(item.getNotificationId())
        Database.downloadDao().deleteDownloadItemById(id)
        stopServiceIfDone()
    }

    /**
     * Stop service if no downloads are active
     */
    private fun stopServiceIfDone() {
        if (downloadQueue.valueIterator().asSequence().none { it }) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
            stopSelf()
        }
    }

    private fun selectMatchingStream(streams: Streams, item: DownloadItem): PipedStream? {
        val stream = when (item.type) {
            FileType.AUDIO -> streams.audioStreams
            FileType.VIDEO -> streams.videoStreams
            FileType.SUBTITLE -> null
        }
        return stream?.find {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }
    }

    /**
     * Check whether the file downloading or not.
     */
    fun isDownloading(id: Int): Boolean {
        return downloadQueue[id]
    }

    private fun notifyForeground() {
        notificationManager = getSystemService()!!

        summaryNotificationBuilder = Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.downloading))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setGroupSummary(true)

        ServiceCompat.startForeground(
            this, NotificationId.DOWNLOAD_IN_PROGRESS.id, summaryNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun getNotificationBuilder(item: DownloadItem): Builder {
        val intent = Intent(this@DownloadService, MainActivity::class.java)
            .putExtra(IntentData.OPEN_DOWNLOADS, true)
        val activityIntent = PendingIntentCompat
            .getActivity(this@DownloadService, 0, intent, FLAG_CANCEL_CURRENT, false)

        return Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: Builder,
        item: DownloadItem
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        isCompleted: Boolean = false
    ) {
        notificationBuilder
            .setProgress(0, 0, false)
            .setOngoing(false)
            .clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_RESUME)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.resume),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_PAUSE)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.pause),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_STOP
            putExtra("id", id)
        }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntentCompat.getBroadcast(this, requestCode, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    /**
     * Get a [Path] from the corresponding download directory and the file name
     */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    override fun onDestroy() {
        downloadQueue.clear()
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        intent.getIntArrayExtra("ids")?.forEach { resume(it) }
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"
        const val ACTION_RESUME_ALL =
            "com.github.libretube.services.DownloadService.ACTION_RESUME_ALL"

        private const val MAX_SEGMENT_RETRIES = 6

        // PrimeTube: recovery budgets - expired stream URLs (403) are refetched
        // up to five times, unexpected exceptions retry three times before the
        // download is reported as failed
        private const val MAX_URL_REFRESHES = 5
        private const val MAX_EXCEPTION_RETRIES = 3
        var IS_DOWNLOAD_RUNNING = false
    }
}