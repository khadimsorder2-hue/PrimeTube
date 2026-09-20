package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
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
import com.github.libretube.services.StorageDownloadService
import com.github.libretube.ui.dialogs.DownloadDialog
import com.github.libretube.ui.dialogs.DownloadPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

/**
 * PrimeTube: implemented by screens that can trigger a "save to storage"
 * download. Hosts the WRITE_EXTERNAL_STORAGE runtime request on Android 8/9.
 */
interface PrimeStorageDownloadHost {
    fun requestPrimeStorageDownload(videoId: String)
}

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

    // PrimeTube: special values of the download handoff preference
    const val PROVIDER_ASK = "ask"
    const val PROVIDER_INTERNAL = "internal"
    const val PROVIDER_STORAGE = "storage"

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

    /**
     * PrimeTube: route the download tap according to the "Download handoff" preference:
     * - Seal (default): hand the video URL to Seal directly (one tap)
     * - Ask every time: let the user pick between Seal and the in-app downloader
     * - Internal: open the built-in download dialog (downloads are listed on the
     *   Downloads page in the bottom navigation)
     */
    fun startDownloadDialog(
        fragment: Fragment,
        fragmentManager: FragmentManager,
        videoId: String
    ) {
        val context = fragment.requireContext()
        // PrimeTube: storage is the DEFAULT handoff - the media lands directly
        // in the phone storage (Downloads/PrimeTube) without an external app
        val provider = PreferenceHelper.getString(
            PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
            PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER
        )

        when {
            provider == PROVIDER_INTERNAL ->
                showInAppDownloadDialog(fragmentManager, videoId)

            provider == PROVIDER_STORAGE ->
                (fragment as? PrimeStorageDownloadHost)?.requestPrimeStorageDownload(videoId)
                    ?: StorageDownloadService.enqueue(context, videoId)

            provider == PROVIDER_ASK ->
                showDownloadChoiceDialog(
                    context,
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
                ) {
                    showInAppDownloadDialog(fragmentManager, videoId)
                }

            else ->
                // Seal or any custom downloader package
                openInExternalDownloader(
                    context,
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
                )
        }
    }

    /**
     * PrimeTube: compat overload for callers without a fragment (user asked
     * for the storage flow through the "ask" dialog as well).
     */
    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val provider = PreferenceHelper.getString(
            PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
            PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER
        )
        when {
            provider == PROVIDER_INTERNAL ->
                showInAppDownloadDialog(fragmentManager, videoId)

            provider == PROVIDER_STORAGE ->
                StorageDownloadService.enqueue(context, videoId)

            provider == PROVIDER_ASK ->
                showDownloadChoiceDialog(
                    context,
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
                ) {
                    showInAppDownloadDialog(fragmentManager, videoId)
                }

            else ->
                openInExternalDownloader(
                    context,
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId"
                )
        }
    }

    private fun showInAppDownloadDialog(fragmentManager: FragmentManager, videoId: String) {
        DownloadDialog().apply {
            arguments = bundleOf(IntentData.videoId to videoId)
        }.show(fragmentManager, DownloadDialog::class.java.name)
    }

    private fun showInAppDownloadPlaylistDialog(
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        DownloadPlaylistDialog().apply {
            arguments = bundleOf(
                IntentData.playlistId to playlistId,
                IntentData.playlistName to playlistName,
                IntentData.playlistType to playlistType
            )
        }.show(fragmentManager, null)
    }

    private fun showDownloadChoiceDialog(
        context: Context,
        url: String,
        onInAppDownload: () -> Unit
    ) {
        val options = arrayOf(
            context.getString(R.string.download_with_seal),
            context.getString(R.string.save_to_storage),
            context.getString(R.string.download_in_app)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_handoff)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openInExternalDownloader(context, url)

                    1 -> StorageDownloadService.enqueue(
                        context,
                        Regex("v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: url
                    )

                    else -> onInAppDownload()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * PrimeTube: hand a media URL to the configured external downloader (e.g. Seal).
     *
     * Tries in order until one succeeds - a failure at any step (including
     * ActivityNotFoundException at start time) falls through to the next one:
     * 1. ACTION_SEND text/plain with the URL to the provider package (Seal's
     *    documented integration)
     * 2. ACTION_VIEW with the URL to the provider package
     * 3. A generic Android share chooser as last-resort fallback
     */
    fun openInExternalDownloader(context: Context, url: String) {
        val provider = PreferenceHelper.getString(
            PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
            ""
        ).takeIf { it.isNotBlank() && it != PROVIDER_ASK && it != PROVIDER_INTERNAL }
            ?: PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER
        val packageManager = context.packageManager

        val sendIntent = Intent(Intent.ACTION_SEND)
            .setPackage(provider)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sendIntent.resolveActivity(packageManager) != null) {
            if (runCatching { context.startActivity(sendIntent) }.isSuccess) return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setPackage(provider)
            .setDataAndType(url.toUri(), VIDEO_MIMETYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (viewIntent.resolveActivity(packageManager) != null) {
            if (runCatching { context.startActivity(viewIntent) }.isSuccess) return
        }

        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url),
            null
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooserIntent) }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        // PrimeTube: Seal is the default handoff target (matches the settings default)
        val provider = PreferenceHelper.getString(
            PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
            PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER
        )
        val playlistUrl = "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId"

        when {
            provider == PROVIDER_INTERNAL ->
                showInAppDownloadPlaylistDialog(
                    fragmentManager,
                    playlistId,
                    playlistName,
                    playlistType
                )

            provider == PROVIDER_ASK ->
                showDownloadChoiceDialog(context, playlistUrl) {
                    showInAppDownloadPlaylistDialog(
                        fragmentManager,
                        playlistId,
                        playlistName,
                        playlistType
                    )
                }

            playlistType == PlaylistType.PUBLIC ->
                openInExternalDownloader(context, playlistUrl)

            else -> {
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
