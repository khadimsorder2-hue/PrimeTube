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
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import java.io.File
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
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

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
 * 3. if no combined (video+audio) stream can be delivered at all, the best
 *    video-only stream + the best audio stream are saved as separate files
 *    so the user ALWAYS ends up with the media in the storage.
 */
@OptIn(UnstableApi::class)
class StorageDownloadService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerMutex = Mutex()
    private var worker: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)

        QUEUE.add(Task(videoId, audioOnly))
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
                runCatching { saveVideo(task.videoId, task.audioOnly) }
                    .onFailure {
                        it.printStackTrace()
                        notifyFinished(task.videoId, false, it.message ?: "error")
                    }
            }
            stopSelf()
        }
    }

    private suspend fun saveVideo(videoId: String, audioOnly: Boolean = false) {
        // promote to foreground immediately (5s rule for startForegroundService)
        startForegroundCompat(
            videoId.notificationId(),
            buildNotification(getString(R.string.download_preparing), 0, 0)
        )

        val streams = runCatching {
            MediaServiceRepository.instance.getStreams(videoId)
        }.getOrElse {
            it.printStackTrace()
            notifyFinished(videoId, false, it.message ?: "stream error")
            return
        }

        if (audioOnly) {
            // PrimeTube: save the best audio stream (m4a/opus/mp3)
            val audio = streams.audioStreams
                .filter { isUsableStream(it) }
                .maxByOrNull { streamRank(it, audio = true) }
            if (audio == null) {
                notifyFinished(videoId, false, getString(R.string.no_audio))
                return
            }
            saveStream(videoId, streams, audio, isAudio = true, rawTitle = streams.title, suffix = "")
            return
        }

        // PrimeTube: prefer the best combined (video+audio) stream - one
        // playable file like a normal download
        val muxed = streams.videoStreams
            .filter { it.videoOnly != true && isUsableStream(it) }
            .maxByOrNull { streamRank(it, audio = false) }

        if (muxed != null && saveStream(videoId, streams, muxed, isAudio = false, rawTitle = streams.title, suffix = "")) {
            return
        }

        // PrimeTube SABR-era fallback: no combined stream deliverable - save
        // the best video-only stream and the best audio stream as two files
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
        if (audio != null) {
            savedAny = saveStream(videoId, streams, audio, isAudio = true, rawTitle = streams.title, suffix = " (audio)") || savedAny
        }
        if (!savedAny && video == null && audio == null) {
            notifyFinished(videoId, false, getString(R.string.no_muxed_stream))
        }
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

        val temp = downloadToTempFile(videoId, streams, stream) ?: return false

        val published = runCatching {
            publishTempFile(temp, fileName, mimeType, videoId)
        }.getOrElse {
            it.printStackTrace()
            temp.delete()
            notifyFinished(videoId, false, it.message ?: "error")
            return false
        }
        temp.delete()

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
        stream: PipedStream
    ): File? {
        val isAudio = stream.mimeType.orEmpty().startsWith("audio/")
        val temp = File.createTempFile("prime_dl_", ".bin", cacheDir)

        // attempt 1: plain HTTP download when a direct URL exists
        val url = stream.url
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            val item = tempDownloadItem(videoId, isAudio, stream, temp)
            val provider = RawByteStreamDownloadProvider(url.toHttpUrl())
            if (runDownloadProvider(provider, item, videoId)) return temp
        }

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
            if (runDownloadProvider(provider, item, videoId)) return temp
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
        videoId: String
    ): Boolean {
        var retries = 0
        var totalRead = 0L
        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        try {
            while (retries < MAX_CHUNK_RETRIES) {
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
                            updateProgress(videoId, totalRead, item.downloadSize)
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
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            updateProgress(videoId, written, total)
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

    private fun updateProgress(videoId: String, written: Long, total: Long) {
        val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else 0
        val label = if (total > 0) {
            getString(R.string.download_in_progress, "$percent%")
        } else {
            getString(R.string.download_in_progress, "${written / (1024 * 1024)} MB")
        }
        notificationManager.notify(
            videoId.notificationId(),
            buildNotification(label, percent, total)
        )
    }

    private fun notifyFinished(videoId: String, success: Boolean, detail: String) {
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
    }

    private fun buildNotification(text: String, progress: Int, total: Long): Notification {
        val builder = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.save_to_storage))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0 && progress in 1..99) {
            builder.setProgress(100, progress, false)
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

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_AUDIO_ONLY = "audio_only"
        private const val FILE_NAME_MAX_LENGTH = 100
        private const val MAX_CHUNK_RETRIES = 8
        private const val RETRY_DELAY_MS = 300L
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private val QUEUE = ConcurrentLinkedQueue<Task>()

        private data class Task(val videoId: String, val audioOnly: Boolean)

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
    }
}
