package com.github.libretube.services

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.SavedDownload
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.PrimeDownloadTracker
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeActiveDownload
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeDownloadState
import com.github.libretube.helpers.PrimeSpeedCalculator
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import java.nio.file.StandardOpenOption

/**
 * PrimeTube: "save to storage" downloader - lands media DIRECTLY in the phone
 * storage (Downloads/PrimeTube by default, or the SAF folder chosen in
 * Settings) as normal, playable files.
 *
 * YouTube switched to the SABR streaming protocol and stopped serving plain
 * progressive URLs for many formats (plain requests answer HTTP 403), which
 * made the old direct-URL download fail. This service therefore uses the
 * SAME battle-tested download machinery as the offline downloader:
 *
 * 1. a plain HTTP download when the stream still exposes a direct URL,
 * 2. the SABR protocol client (streaming segments from YouTube's servers)
 *    for every format without a usable URL,
 * 3. if no combined (video+audio) stream can be delivered at all, a
 *    video-only stream + an audio stream are saved as separate files so the
 *    user ALWAYS ends up with the media in the storage.
 *
 * PrimeTube: the quality picked in the download dialog is now PASSED THROUGH
 * and respected - only when the dialog was not used (quick "save" from the
 * video menu) the best available stream is saved.
 *
 * The live progress (file name, size, speed, percentage) is mirrored to
 * [PrimeDownloadTracker] so the Downloads page can show the queue in-app.
 */
@OptIn(UnstableApi::class)
class StorageDownloadService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerMutex = Mutex()
    private var worker: Job? = null
    private lateinit var notificationManager: NotificationManager
    private val speedCalculators = ConcurrentHashMap<String, PrimeSpeedCalculator>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY

        // PrimeTube: cancel request from the in-app download queue - drop the
        // task from the queue and flag any running copy so it stops at the
        // next chunk/file boundary
        if (intent.getBooleanExtra(EXTRA_CANCEL, false)) {
            QUEUE.removeAll { it.videoId == videoId }
            val running = cancelFlags.putIfAbsent(videoId, AtomicBoolean(true))
            running?.set(true)
            if (worker?.isActive != true) {
                finishCancelled(videoId)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val task = Task(
            videoId = videoId,
            audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false),
            videoQuality = intent.getStringExtra(EXTRA_VIDEO_QUALITY),
            videoFormat = intent.getStringExtra(EXTRA_VIDEO_FORMAT),
            audioQuality = intent.getStringExtra(EXTRA_AUDIO_QUALITY),
            audioFormat = intent.getStringExtra(EXTRA_AUDIO_FORMAT)
        )

        QUEUE.add(task)
        // PrimeTube: a fresh task always resets any leftover cancel flag of a
        // previous download of the same video
        cancelFlags[videoId] = AtomicBoolean(false)
        PrimeDownloadTracker.upsert(
            PrimeActiveDownload(
                key = trackerKey(videoId),
                title = task.videoId,
                state = PrimeDownloadState.QUEUED,
                canCancel = true
            )
        )
        if (worker?.isActive != true) {
            worker = scope.launch { processQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun processQueue() {
        workerMutex.withLock {
            while (true) {
                val task = QUEUE.poll() ?: break
                runCatching { saveVideo(task) }
                    .onFailure {
                        it.printStackTrace()
                        notifyFinished(task.videoId, false, it.message ?: "error")
                    }
            }
            stopSelf()
        }
    }

    private suspend fun saveVideo(task: Task) {
        val videoId = task.videoId
        // promote to foreground immediately (5s rule for startForegroundService)
        // - this MUST happen even for a cancelled task, otherwise the system
        // kills the app for not calling startForeground in time
        startForegroundCompat(
            videoId.notificationId(),
            buildNotification(null, getString(R.string.download_preparing), -1, 0)
        )
        // PrimeTube: cancelled while queued -> skip everything silently
        if (isCancelled(videoId)) {
            finishCancelled(videoId)
            return
        }

        val streams = runCatching {
            MediaServiceRepository.instance.getStreams(videoId)
        }.getOrElse {
            it.printStackTrace()
            notifyFinished(videoId, false, it.message ?: "stream error")
            return
        }

        if (isCancelled(videoId)) return finishCancelled(videoId)

        PrimeDownloadTracker.upsert(
            PrimeActiveDownload(
                key = trackerKey(videoId),
                title = streams.title.ifBlank { videoId },
                state = PrimeDownloadState.DOWNLOADING,
                canCancel = true
            )
        )

        if (task.audioOnly) {
            // PrimeTube: save the SELECTED audio stream, or the best one when
            // the download was started without the dialog
            val audio = resolveAudio(streams, task.audioQuality, task.audioFormat)
                ?: streams.audioStreams
                    .filter { isUsableStream(it) }
                    .maxByOrNull { streamRank(it, audio = true) }
            if (audio == null) {
                notifyFinished(videoId, false, getString(R.string.no_audio))
                return
            }
            saveStream(videoId, streams, audio, isAudio = true, rawTitle = streams.title, suffix = "")
            return
        }

        // PrimeTube: the quality the user selected in the download dialog.
        // A combined (video+audio) stream at that quality is preferred - one
        // playable file like a normal download.
        val selected = resolveVideo(streams, task.videoQuality, task.videoFormat)

        if (selected != null) {
            if (isCancelled(videoId)) return finishCancelled(videoId)

            if (selected.videoOnly != true &&
                saveStream(videoId, streams, selected, isAudio = false, rawTitle = streams.title, suffix = "")
            ) {
                return
            }

            // combined stream not available/deliverable at the selected
            // quality -> save the SAME QUALITY video-only stream + audio
            val wanted = selected.height
                ?: selected.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0
            val video = if (selected.videoOnly == true) {
                selected
            } else {
                streams.videoStreams
                    .filter { it.videoOnly == true && isUsableStream(it) }
                    .filter {
                        (it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0) == wanted
                    }
                    .minByOrNull { streamRank(it, audio = false) }
                    // absolutely no stream at the selected height deliverable?
                    // stay as close to the user's choice as possible
                    ?: streams.videoStreams
                        .filter { it.videoOnly == true && isUsableStream(it) }
                        .filter { (it.height ?: 0) <= wanted }
                        .maxByOrNull { it.height ?: 0 }
            }

            var savedAny = false
            if (video != null) {
                savedAny = saveStream(
                    videoId, streams, video, isAudio = false,
                    rawTitle = streams.title,
                    suffix = " (${video.quality.orEmpty()})"
                )
            }
            if (isCancelled(videoId)) return finishCancelled(videoId)
            val audio = resolveAudio(streams, task.audioQuality, task.audioFormat)
                ?: streams.audioStreams
                    .filter { isUsableStream(it) }
                    .maxByOrNull { streamRank(it, audio = true) }
            if (audio != null) {
                savedAny = saveStream(
                    videoId, streams, audio, isAudio = true,
                    rawTitle = streams.title, suffix = " (audio)"
                ) || savedAny
            }
            if (!savedAny && video == null) {
                notifyFinished(videoId, false, getString(R.string.no_muxed_stream))
            }
            return
        }

        // PrimeTube: no explicit selection (quick save from the video menu) -
        // keep the historic behavior: the best combined stream, otherwise the
        // best video-only stream + the best audio stream
        val muxed = streams.videoStreams
            .filter { it.videoOnly != true && isUsableStream(it) }
            .maxByOrNull { streamRank(it, audio = false) }

        if (muxed != null && saveStream(videoId, streams, muxed, isAudio = false, rawTitle = streams.title, suffix = "")) {
            return
        }

        val video = streams.videoStreams
            .filter { it.videoOnly == true && isUsableStream(it) }
            .maxByOrNull { streamRank(it, audio = false) }
        val audio = streams.audioStreams
            .filter { isUsableStream(it) }
            .maxByOrNull { streamRank(it, audio = true) }

        var savedAny = false
        if (video != null) {
            savedAny = saveStream(videoId, streams, video, isAudio = false, rawTitle = streams.title, suffix = "")
        }
        if (isCancelled(videoId)) return finishCancelled(videoId)
        if (audio != null) {
            savedAny = saveStream(videoId, streams, audio, isAudio = true, rawTitle = streams.title, suffix = " (audio)") || savedAny
        }
        if (!savedAny && video == null && audio == null) {
            notifyFinished(videoId, false, getString(R.string.no_muxed_stream))
        }
    }

    /**
     * PrimeTube: find the stream that matches the quality the user picked in
     * the download dialog. Falls back progressively (quality+format, quality,
     * closest lower height) so the download never silently degrades to the
     * maximum quality again.
     */
    private fun resolveVideo(streams: Streams, quality: String?, format: String?): PipedStream? {
        if (quality.isNullOrBlank()) return null
        val usable = streams.videoStreams.filter { isUsableStream(it) }

        usable.firstOrNull {
            it.quality == quality && (format.isNullOrBlank() || it.format == format) && it.videoOnly != true
        }?.let { return it }
        usable.firstOrNull {
            it.quality == quality && (format.isNullOrBlank() || it.format == format)
        }?.let { return it }
        usable.firstOrNull { it.quality == quality && it.videoOnly != true }?.let { return it }
        usable.firstOrNull { it.quality == quality }?.let { return it }

        val wanted = quality.filter(Char::isDigit).toIntOrNull() ?: return null
        return usable
            .map { it to (it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0) }
            .filter { it.second > 0 && it.second <= wanted }
            .minByOrNull { wanted - it.second }
            ?.first
    }

    private fun resolveAudio(streams: Streams, quality: String?, format: String?): PipedStream? {
        if (quality.isNullOrBlank()) return null
        val usable = streams.audioStreams.filter { isUsableStream(it) }
        usable.firstOrNull {
            it.quality == quality && (format.isNullOrBlank() || it.format == format)
        }?.let { return it }
        return usable.firstOrNull { it.quality == quality }
    }

    /**
     * Download one stream into a temp file (raw URL first, SABR protocol as
     * fallback) and publish it into the phone storage. Returns false when the
     * stream could not be delivered at all.
     */
    private suspend fun saveStream(
        videoId: String,
        streams: Streams,
        stream: PipedStream,
        isAudio: Boolean,
        rawTitle: String,
        suffix: String
    ): Boolean {
        val rawName = rawTitle.ifBlank { videoId }
        val mimeType = stream.mimeType.orEmpty().ifBlank { if (isAudio) "audio/mp4" else "video/mp4" }
        val extension = when {
            isAudio && mimeType.contains("webm") || isAudio && mimeType.contains("opus") -> "opus"
            isAudio && mimeType.contains("mpeg") -> "mp3"
            isAudio -> "m4a"
            mimeType.contains("webm") -> "webm"
            else -> "mp4"
        }
        val fileName = sanitizeFileName("$rawName$suffix.$extension")

        speedCalculators[videoId] = PrimeSpeedCalculator()
        PrimeDownloadTracker.upsert(
            PrimeActiveDownload(
                key = trackerKey(videoId),
                title = fileName,
                state = PrimeDownloadState.DOWNLOADING,
                canCancel = true
            )
        )
        updateProgress(videoId, 0L, stream.contentLength, fileName)

        val temp = downloadToTempFile(videoId, streams, stream, fileName) ?: run {
            speedCalculators.remove(videoId)
            return false
        }
        if (isCancelled(videoId)) {
            temp.delete()
            speedCalculators.remove(videoId)
            finishCancelled(videoId)
            return true // task aborted on purpose - no error reporting
        }

        val published = runCatching {
            publishTempFile(temp, fileName, mimeType, videoId)
        }.getOrElse {
            it.printStackTrace()
            temp.delete()
            speedCalculators.remove(videoId)
            notifyFinished(videoId, false, it.message ?: "error")
            return false
        }
        temp.delete()
        speedCalculators.remove(videoId)

        val (savedUri, sizeBytes) = published
        DatabaseHolder.Database.savedDownloadDao().insert(
            SavedDownload(
                videoId = videoId,
                title = rawName + suffix,
                uploader = streams.uploader,
                uri = savedUri.toString(),
                fileName = queryDisplayName(savedUri) ?: fileName,
                sizeBytes = sizeBytes,
                duration = streams.duration,
                savedAt = System.currentTimeMillis()
            )
        )

        notifyFinished(videoId, true, fileName)
        return true
    }

    /**
     * A stream is downloadable when it either has a direct HTTP URL or can be
     * served through the SABR protocol client (itag + lastModified present).
     */
    private fun isUsableStream(stream: PipedStream): Boolean {
        val hasUrl = !stream.url.isNullOrBlank() && stream.url!!.startsWith("http")
        val hasSabrFormat = stream.itag != null && stream.lastModified != null
        return hasUrl || hasSabrFormat
    }

    private fun streamRank(stream: PipedStream, audio: Boolean): Int {
        return if (audio) {
            stream.bitrate ?: stream.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        } else {
            stream.height ?: stream.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        }
    }

    /**
     * Download the stream into a temp file in the app cache using the same
     * providers as the offline downloader: direct URL first, SABR protocol
     * second (YouTube serves many formats ONLY through SABR these days).
     */
    private suspend fun downloadToTempFile(
        videoId: String,
        streams: Streams,
        stream: PipedStream,
        fileName: String
    ): File? {
        val isAudio = stream.mimeType.orEmpty().startsWith("audio/")
        val temp = File.createTempFile("prime_dl_", ".bin", cacheDir)

        // attempt 1: plain HTTP download when a direct URL exists
        val url = stream.url
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            val item = tempDownloadItem(videoId, isAudio, stream, temp)
            val provider = RawByteStreamDownloadProvider(url.toHttpUrl())
            if (runDownloadProvider(provider, item, videoId, fileName)) return temp
        }

        if (isCancelled(videoId)) return null

        // attempt 2: SABR protocol client - YouTube's new streaming protocol
        if (stream.itag != null && stream.lastModified != null) {
            runCatching {
                temp.delete()
                temp.createNewFile()
            }
            val item = tempDownloadItem(videoId, isAudio, stream, temp)
            val provider = runCatching { SabrDownloadProvider(item, streams, stream) }.getOrElse {
                it.printStackTrace()
                temp.delete()
                return null
            }
            if (runDownloadProvider(provider, item, videoId, fileName)) return temp
        }

        temp.delete()
        return null
    }

    private fun tempDownloadItem(
        videoId: String,
        isAudio: Boolean,
        stream: PipedStream,
        temp: File
    ): DownloadItem {
        return DownloadItem(
            type = if (isAudio) FileType.AUDIO else FileType.VIDEO,
            videoId = videoId,
            fileName = temp.name,
            path = temp.toPath(),
            format = stream.format,
            quality = stream.quality,
            downloadSize = stream.contentLength
        )
    }

    /**
     * Run a chunked [DownloadProvider] until the file is complete, retrying
     * transient failures a few times before giving up.
     */
    private suspend fun runDownloadProvider(
        provider: DownloadProvider,
        item: DownloadItem,
        videoId: String,
        fileName: String
    ): Boolean {
        var retries = 0
        var totalRead = 0L
        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        try {
            while (retries < MAX_CHUNK_RETRIES) {
                if (isCancelled(videoId)) return false
                try {
                    when (val result = provider.downloadNextChunk(item, sink)) {
                        DownloadProgressResult.DownloadComplete -> {
                            sink.flush()
                            return true
                        }

                        DownloadProgressResult.Failed -> {
                            retries++
                            sink.flush()
                            delay(RETRY_DELAY_MS)
                        }

                        is DownloadProgressResult.Progressed -> {
                            retries = 0
                            totalRead += result.bytes
                            sink.flush()
                            updateProgress(videoId, totalRead, item.downloadSize, fileName)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retries++
                    runCatching { sink.flush() }
                    delay(RETRY_DELAY_MS)
                }
            }
        } finally {
            runCatching { sink.close() }
        }
        return false
    }

    /**
     * Copy the finished temp file into the configured SAF folder, or into
     * Downloads/PrimeTube in the shared storage when no folder was chosen
     * (MediaStore on Android 10+, legacy public dir below).
     */
    private fun publishTempFile(
        temp: File,
        fileName: String,
        mimeType: String,
        videoId: String
    ): Pair<Uri, Long> {
        val folderPref = PreferenceHelper.getString(PreferenceKeys.MP4_DOWNLOAD_FOLDER, "")

        val targetUri = when {
            folderPref.isNotBlank() -> createSafFile(folderPref.toUri(), fileName, mimeType)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> createMediaStoreFile(fileName, mimeType)
            else -> createLegacyPublicFile(fileName)
        }

        try {
            contentResolver.openOutputStream(targetUri)?.use { out ->
                temp.inputStream().use { input ->
                    val total = temp.length()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    var lastUpdate = 0L
                    while (true) {
                        if (isCancelled(videoId)) error("cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            updateProgress(videoId, written, total, fileName)
                        }
                    }
                    out.flush()
                }
            } ?: error("cannot open output stream")

            finalizeFile(targetUri)
            return Pair(targetUri, temp.length())
        } catch (t: Throwable) {
            cleanup(targetUri)
            throw t
        }
    }

    /** PrimeTube: publish pending MediaStore files + index legacy files. */
    private fun finalizeFile(uri: Uri) {
        runCatching {
            if (uri.toString().startsWith("content://media/") &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            } else if (uri.scheme == "file") {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(uri.path!!),
                    null,
                    null
                )
            }
        }
    }

    private fun createSafFile(treeUri: Uri, fileName: String, mimeType: String): Uri {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.createDocument(contentResolver, dirUri, mimeType, fileName)
                ?: error("cannot create document")
        } catch (e: Exception) {
            error("cannot create file in the chosen folder: ${e.message}")
        }
    }

    /**
     * PrimeTube: default target - Downloads/PrimeTube, the folder every file
     * manager and the gallery show. MediaStore needs NO storage permission on
     * Android 10+ for this.
     */
    private fun createMediaStoreFile(fileName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PrimeTube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return contentResolver.insert(collection, values)
            ?: error("cannot create media file")
    }

    /**
     * PrimeTube: Android 8/9 legacy path - the permission is requested when
     * the download is triggered; the file lands in Downloads/PrimeTube too.
     */
    private fun createLegacyPublicFile(fileName: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PrimeTube"
        )
        if (!dir.exists() && !dir.mkdirs()) error("cannot create Downloads/PrimeTube")
        val file = File(dir, fileName)
        file.createNewFile()
        return Uri.fromFile(file)
    }

    private fun cleanup(uri: Uri) {
        runCatching {
            if (uri.toString().startsWith("content://media/")) {
                contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "content") {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } else {
                File(uri.path!!).delete()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                }
        }.getOrNull()
    }

    /**
     * PrimeTube: progress notification with EVERYTHING the user asked for -
     * the file name as the title, then read/total size, live speed and the
     * percentage, plus a determinate progress bar.
     */
    private fun updateProgress(videoId: String, written: Long, total: Long, fileName: String) {
        val speed = speedCalculators[videoId]?.onBytes(written) ?: 0L
        val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else -1

        val text = buildString {
            append(written.formatAsFileSize())
            if (total > 0) {
                append(" / ").append(total.formatAsFileSize())
            }
            if (speed > 0) {
                append(" • ").append(speed.formatAsFileSize()).append("/s")
            }
            if (percent >= 0) {
                append(" • ").append(percent).append('%')
            }
        }

        runCatching {
            notificationManager.notify(
                videoId.notificationId(),
                buildNotification(fileName, text, percent, total)
            )
        }

        PrimeDownloadTracker.updateProgress(trackerKey(videoId), written, total, speed)
    }

    private fun notifyFinished(videoId: String, success: Boolean, detail: String) {
        speedCalculators.remove(videoId)
        val title = if (success) {
            getString(R.string.download_saved)
        } else {
            getString(R.string.download_failed_with_reason, detail)
        }
        runCatching {
            notificationManager.notify(
                videoId.notificationId(),
                NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_NAME)
                    .setSmallIcon(R.drawable.ic_launcher_lockscreen)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setAutoCancel(true)
                    .build()
            )
        }

        // PrimeTube: mirror the terminal state to the in-app queue, then
        // clean the row up after a short moment
        val key = trackerKey(videoId)
        PrimeDownloadTracker.setCancelListener(key, null)
        cancelFlags.remove(videoId)
        val finishedAt = System.currentTimeMillis()
        if (success) {
            PrimeDownloadTracker.upsert(
                PrimeActiveDownload(
                    key = key,
                    title = detail,
                    state = PrimeDownloadState.COMPLETED,
                    finishedAtMs = finishedAt
                )
            )
        } else {
            PrimeDownloadTracker.upsert(
                PrimeActiveDownload(
                    key = key,
                    title = detail,
                    state = PrimeDownloadState.FAILED,
                    message = detail,
                    finishedAtMs = finishedAt
                )
            )
        }
        scope.launch {
            delay(if (success) TERMINAL_ROW_LIFETIME_MS else FAILED_ROW_LIFETIME_MS)
            PrimeDownloadTracker.remove(key)
        }
        runCatching {
            if (success) stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun isCancelled(videoId: String): Boolean =
        cancelFlags[videoId]?.get() == true

    private fun finishCancelled(videoId: String) {
        speedCalculators.remove(videoId)
        cancelFlags.remove(videoId)
        val key = trackerKey(videoId)
        PrimeDownloadTracker.setCancelListener(key, null)
        PrimeDownloadTracker.remove(key)
        runCatching {
            notificationManager.cancel(videoId.notificationId())
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    private fun buildNotification(title: String?, text: String, progress: Int, total: Long): Notification {
        val builder = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(title ?: getString(R.string.save_to_storage))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        when {
            total > 0 && progress >= 0 -> builder.setProgress(100, progress.coerceAtMost(100), false)
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        ServiceCompat.startForeground(
            this,
            id,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.takeLast(FILE_NAME_MAX_LENGTH.coerceAtMost(cleaned.length))
    }

    private fun String.notificationId(): Int = (this.hashCode() and 0x7fffffff) % 100000

    private fun trackerKey(videoId: String): String = "storage:$videoId"

    override fun onBind(intent: Intent?) = null

    data class Task(
        val videoId: String,
        val audioOnly: Boolean,
        val videoQuality: String? = null,
        val videoFormat: String? = null,
        val audioQuality: String? = null,
        val audioFormat: String? = null
    )

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_AUDIO_ONLY = "audio_only"
        private const val EXTRA_VIDEO_QUALITY = "video_quality"
        private const val EXTRA_VIDEO_FORMAT = "video_format"
        private const val EXTRA_AUDIO_QUALITY = "audio_quality"
        private const val EXTRA_AUDIO_FORMAT = "audio_format"
        private const val FILE_NAME_MAX_LENGTH = 100
        private const val MAX_CHUNK_RETRIES = 8
        private const val RETRY_DELAY_MS = 300L
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val TERMINAL_ROW_LIFETIME_MS = 3_500L
        private const val FAILED_ROW_LIFETIME_MS = 6_000L
        private val QUEUE = ConcurrentLinkedQueue<Task>()

        /**
         * Enqueue a video (or audio) to be saved as a normal media file into
         * the phone storage - Downloads/PrimeTube by default, or the SAF
         * folder the user picked in Settings.
         */
        fun enqueue(context: android.content.Context, videoId: String, audioOnly: Boolean = false) {
            val intent = Intent(context, StorageDownloadService::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            context.startForegroundService(intent)
        }

        /**
         * PrimeTube: enqueue with the EXACT quality picked in the download
         * dialog - the selected stream is downloaded as-is instead of always
         * grabbing the best available quality.
         */
        fun enqueue(
            context: android.content.Context,
            videoId: String,
            audioOnly: Boolean,
            videoQuality: String?,
            videoFormat: String?,
            audioQuality: String?,
            audioFormat: String?
        ) {
            val intent = Intent(context, StorageDownloadService::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                .putExtra(EXTRA_VIDEO_QUALITY, videoQuality.orEmpty())
                .putExtra(EXTRA_VIDEO_FORMAT, videoFormat.orEmpty())
                .putExtra(EXTRA_AUDIO_QUALITY, audioQuality.orEmpty())
                .putExtra(EXTRA_AUDIO_FORMAT, audioFormat.orEmpty())
            context.startForegroundService(intent)
        }

        /**
         * PrimeTube: cancel a pending/running storage download (called from
         * the in-app queue on the Downloads page).
         */
        fun cancel(context: android.content.Context, videoId: String) {
            QUEUE.removeAll { it.videoId == videoId }
            val intent = Intent(context, StorageDownloadService::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_CANCEL, true)
            runCatching { context.startService(intent) }
        }

        private const val EXTRA_CANCEL = "cancel"
    }
}
