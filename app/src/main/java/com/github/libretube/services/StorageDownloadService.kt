package com.github.libretube.services

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SavedDownload
import com.github.libretube.helpers.PreferenceHelper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * PrimeTube: "Save to storage (MP4)" downloader.
 *
 * Unlike the offline downloader (which stores stream chunks inside the app's private
 * cache), this service downloads the best combined video+audio stream as a normal
 * MP4 file into a folder of the user's choice:
 * - a SAF folder picked in Settings -> General -> MP4 download folder (phone storage
 *   or SD card), or
 * - by default Movies/PrimeTube in the shared storage (MediaStore, Android 10+).
 *
 * Every finished file is recorded in the local database so it shows up in the
 * "Saved files" tab on the Downloads page.
 */
class StorageDownloadService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerMutex = Mutex()
    private var worker: Job? = null
    private lateinit var notificationManager: NotificationManager

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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

        // PrimeTube: audio requests save the best audio stream (m4a/opus),
        // video requests the best combined (video+audio) stream as MP4
        val (stream, isAudio) = if (audioOnly) {
            val audio = streams.audioStreams
                .filter { !it.url.isNullOrEmpty() }
                .maxByOrNull {
                    (it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0)
                }
            if (audio == null) {
                notifyFinished(videoId, false, getString(R.string.no_audio))
                return
            }
            Pair(audio, true)
        } else {
            val muxed = streams.videoStreams
                .filter { it.videoOnly != true && !it.url.isNullOrEmpty() }
                .maxByOrNull {
                    it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0
                }
            if (muxed == null) {
                notifyFinished(videoId, false, getString(R.string.no_muxed_stream))
                return
            }
            Pair(muxed, false)
        }

        val rawName = streams.title.ifBlank { videoId }
        val mimeType = stream.mimeType.orEmpty().ifBlank { if (isAudio) "audio/mp4" else "video/mp4" }
        val extension = when {
            isAudio && mimeType.contains("webm") || isAudio && mimeType.contains("opus") -> "opus"
            isAudio && mimeType.contains("mpeg") -> "mp3"
            isAudio -> "m4a"
            mimeType.contains("webm") -> "webm"
            else -> "mp4"
        }
        val fileName = sanitizeFileName("$rawName.$extension")

        val (savedUri, sizeBytes) = writeStream(stream.url!!, fileName, mimeType) { written, total ->
            updateProgress(videoId, written, total)
        }

        DatabaseHolder.Database.savedDownloadDao().insert(
            SavedDownload(
                videoId = videoId,
                title = rawName,
                uploader = streams.uploader,
                uri = savedUri.toString(),
                fileName = queryDisplayName(savedUri) ?: fileName,
                sizeBytes = sizeBytes,
                duration = streams.duration,
                savedAt = System.currentTimeMillis()
            )
        )

        notifyFinished(videoId, true, rawName)
    }

    /**
     * Write the downloaded bytes into the configured SAF folder, or into
     * Downloads/PrimeTube in the shared storage when no folder was chosen
     * (MediaStore on Android 10+, legacy public dir below).
     */
    private fun writeStream(
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: (Long, Long) -> Unit
    ): Pair<Uri, Long> {
        val folderPref = PreferenceHelper.getString(PreferenceKeys.MP4_DOWNLOAD_FOLDER, "")

        val targetUri = if (folderPref.isNotBlank()) {
            createSafFile(folderPref.toUri(), fileName, mimeType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreFile(fileName, mimeType)
        } else {
            createLegacyPublicFile(fileName)
        }

        val request = okhttp3.Request.Builder().url(url).build()
        val response = runCatching { httpClient.newCall(request).execute() }
            .getOrElse { cleanup(targetUri); throw it }

        response.use { resp ->
            if (!resp.isSuccessful) {
                cleanup(targetUri)
                error("HTTP ${resp.code}")
            }
            val body = resp.body ?: run { cleanup(targetUri); error("empty response body") }
            val total = body.contentLength()
            var written = 0L
            var lastUpdate = 0L

            contentResolver.openOutputStream(targetUri)?.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                }
            } ?: run { cleanup(targetUri); error("cannot open output stream") }

            finalizeFile(targetUri)
            return Pair(targetUri, written)
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
        val percent = if (total > 0) (written * 100 / total).toInt() else 0
        notificationManager.notify(
            videoId.notificationId(),
            buildNotification(getString(R.string.download_in_progress, "$percent%"), percent, total)
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
        private val QUEUE = ConcurrentLinkedQueue<Task>()

        private data class Task(val videoId: String, val audioOnly: Boolean)

        /**
         * Enqueue a video (or audio) to be saved as a normal media file into
         * the phone storage - Downloads/PrimeTube by default, or the SAF
         * folder the user picked in Settings.
         */
        fun enqueue(context: Context, videoId: String, audioOnly: Boolean = false) {
            val intent = Intent(context, StorageDownloadService::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            context.startForegroundService(intent)
        }
    }
}
