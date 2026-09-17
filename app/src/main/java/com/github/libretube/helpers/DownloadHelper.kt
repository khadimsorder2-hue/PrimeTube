package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.dialogs.DownloadDialog
import com.github.libretube.ui.dialogs.DownloadPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

object DownloadHelper {
    const val VIDEO_DIR = "video"
    const val AUDIO_DIR = "audio"
    const val SUBTITLE_DIR = "subtitle"
    const val THUMBNAIL_DIR = "thumbnail"
    const val PLAYLIST_THUMBNAIL_DIR = "playlist_thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 8L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val VIDEO_MIMETYPE = "video/*"

    fun getDownloadDir(context: Context, path: String): Path {
        val storageDir =
            try {
                context.getExternalFilesDir(null)!!
            } catch (e: Exception) {
                context.filesDir
            }
        return (storageDir.toPath() / path).createDirectories()
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        ContextCompat.startForegroundService(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            DownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, DownloadDialog::class.java.name)
        } else {
            openInExternalDownloader(
                context,
                "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
            )
        }
    }

    /**
     * PrimeTube: hand a media URL to the configured external downloader (e.g. Seal).
     *
     * Tries in order until one succeeds:
     * 1. ACTION_VIEW with the URL to the provider package
     * 2. ACTION_SEND text/plain with the URL to the provider package
     * 3. A generic Android share chooser as last-resort fallback
     */
    fun openInExternalDownloader(context: Context, url: String) {
        val provider = PreferenceHelper.getString(
            PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
            ""
        ).ifBlank { PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER }
        val packageManager = context.packageManager

        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setPackage(provider)
            .setDataAndType(url.toUri(), VIDEO_MIMETYPE)
        if (viewIntent.resolveActivity(packageManager) != null) {
            runCatching { context.startActivity(viewIntent) }
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND)
            .setPackage(provider)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        if (sendIntent.resolveActivity(packageManager) != null) {
            runCatching { context.startActivity(sendIntent) }
            return
        }

        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url),
            null
        )
        runCatching { context.startActivity(chooserIntent) }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            val downloadPlaylistDialog = DownloadPlaylistDialog().apply {
                arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName,
                    IntentData.playlistType to playlistType
                )
            }
            downloadPlaylistDialog.show(fragmentManager, null)
        } else if (playlistType == PlaylistType.PUBLIC) {
            openInExternalDownloader(
                context,
                "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId"
            )
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val playlistVideoIds = try {
                    PlaylistsHelper.getPlaylist(playlistId)
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.unknown_error)
                    return@launch
                }.relatedStreams.mapNotNull { it.url?.toID() }.joinToString(",")

                withContext(Dispatchers.Main) {
                    openInExternalDownloader(
                        context,
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch_videos?video_ids=${playlistVideoIds}"
                    )
                }
            }
        }
    }

    fun extractDownloadInfoText(context: Context, download: DownloadWithItems): List<String> {
        val downloadInfo = mutableListOf<String>()
        download.downloadItems.firstOrNull { it.type == FileType.VIDEO }?.let { videoItem ->
            downloadInfo.add(context.getString(R.string.video) + ": ${videoItem.format} ${videoItem.quality}")
        }
        download.downloadItems.firstOrNull { it.type == FileType.AUDIO }?.let { audioItem ->
            var infoString = ": ${audioItem.quality} ${audioItem.format})"
            if (audioItem.language != null) infoString += " ${audioItem.language}"
            downloadInfo.add(context.getString(R.string.audio) + infoString)
        }
        download.downloadItems.firstOrNull { it.type == FileType.SUBTITLE }?.let {
            downloadInfo.add(context.getString(R.string.captions) + ": ${it.language}")
        }
        return downloadInfo
    }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }
}
