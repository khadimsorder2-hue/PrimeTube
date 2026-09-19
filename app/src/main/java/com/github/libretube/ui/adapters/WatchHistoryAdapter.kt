package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.VideoRowBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setFormattedDuration
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.viewholders.WatchHistoryViewHolder
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchHistoryAdapter :
    ListAdapter<WatchHistoryItem, WatchHistoryViewHolder>(DiffUtilItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WatchHistoryViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = VideoRowBinding.inflate(layoutInflater, parent, false)
        return WatchHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WatchHistoryViewHolder, position: Int) {
        // PrimeTube: guard against NO_POSITION - bindingAdapterPosition can be -1 while
        // DiffUtil animations are pending, which crashed getItem(-1) with IndexOutOfBounds
        val video = currentList.getOrNull(holder.bindingAdapterPosition) ?: return
        holder.binding.apply {
            videoTitle.text = video.title
            channelName.text = video.uploader
            videoInfo.text =
                video.uploadDate?.takeIf { !video.isLive }?.let { TextUtils.localizeDate(it) }
            ImageHelper.loadImage(video.thumbnailUrl, thumbnail)

            if (video.duration != null) {
                // we pass in 0 for the uploadDate, as a future video cannot be watched already
                thumbnailDuration.setFormattedDuration(video.duration, null, 0)
            } else {
                thumbnailDurationCard.isGone = true
            }

            if (video.uploaderAvatar != null) {
                ImageHelper.loadImage(video.uploaderAvatar, channelImage, true)
            } else {
                channelImageContainer.isGone = true
            }

            channelImage.setOnClickListener {
                // PrimeTube: uploaderUrl may be empty in old history entries
                if (!video.uploaderUrl.isNullOrBlank()) {
                    NavigationHelper.navigateChannel(root.context, video.uploaderUrl)
                }
            }

            root.setOnClickListener {
                // PrimeTube: never navigate with an empty video id (crash guard)
                if (!video.videoId.isNullOrBlank()) {
                    NavigationHelper.navigateVideo(root.context, PlayerData(video.videoId))
                }
            }

            // PrimeTube: the context is not always the raw activity - use a safe unwrap
            // instead of a hard cast that could throw ClassCastException
            val activity = ContextHelper.tryUnwrapActivity<BaseActivity>(root.context)
            if (activity != null) {
                val fragmentManager = activity.supportFragmentManager
                root.setOnLongClickListener {
                    fragmentManager.setFragmentResultListener(
                        VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                        activity
                    ) { _, _ ->
                        notifyItemChanged(position)
                    }
                    val sheet = VideoOptionsBottomSheet()
                    sheet.arguments = bundleOf(IntentData.streamItem to video.toStreamItem())
                    sheet.show(fragmentManager, WatchHistoryAdapter::class.java.name)
                    true
                }
            } else {
                root.setOnLongClickListener(null)
            }

            // PrimeTube: live items have no meaningful duration - avoid dividing by 0
            val duration = video.duration
            if (duration != null && duration > 0) watchProgress.setWatchProgressLength(
                video.videoId,
                duration
            )

            CoroutineScope(Dispatchers.IO).launch {
                // PrimeTube: a closed/migrating database must never crash the list
                val isDownloaded = runCatching {
                    DatabaseHolder.Database.downloadDao().exists(video.videoId)
                }.getOrDefault(false)

                withContext(Dispatchers.Main) {
                    downloadBadge.isVisible = isDownloaded
                }
            }
        }
    }
}
