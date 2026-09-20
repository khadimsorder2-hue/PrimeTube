package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.QueueRowBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.viewholders.PlayingQueueViewHolder
import com.github.libretube.util.PlayingQueue

/**
 * PrimeTube: the queue adapter no longer reads the live, mutable queue of
 * [PlayingQueue] directly while binding. Binding against a list that the player
 * service mutates in the background (append next video, source error, ...)
 * leads to "Inconsistency detected. Invalid view holder adapter position"
 * IndexOutOfBoundsException crashes. Instead we keep a private snapshot that
 * can only change together with a notifyDataSetChanged() call.
 */
class PlayingQueueAdapter(
    private val onQueueItemSelected: (String) -> Unit
) : RecyclerView.Adapter<PlayingQueueViewHolder>() {

    var items: List<StreamItem> = PlayingQueue.getStreams()
        private set

    private var lastCurrentIndex: Int = PlayingQueue.currentIndex()

    /** PrimeTube: live filter of the queue side panel (blank = show all). */
    private var filterQuery: String = ""

    /**
     * PrimeTube: filter the visible queue rows by title/uploader without
     * touching the real playback queue.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setFilter(query: String) {
        filterQuery = query.trim()
        applyFilter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        items = if (filterQuery.isEmpty()) {
            PlayingQueue.getStreams()
        } else {
            PlayingQueue.getStreams().filter {
                it.title?.contains(filterQuery, ignoreCase = true) == true ||
                    it.uploaderName?.contains(filterQuery, ignoreCase = true) == true
            }
        }
        notifyDataSetChanged()
    }

    /**
     * Take a fresh snapshot of the queue and notify the RecyclerView only when
     * something actually changed (avoids flicker and keeps the adapter
     * internally consistent at any time).
     */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        val newItems = PlayingQueue.getStreams()
        val newIndex = PlayingQueue.currentIndex()
        if (filterQuery.isNotEmpty()) {
            lastCurrentIndex = newIndex
            applyFilter()
            return
        }
        if (newItems == items && newIndex == lastCurrentIndex) return
        items = newItems
        lastCurrentIndex = newIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayingQueueViewHolder {
        val binding = QueueRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayingQueueViewHolder(binding)
    }

    override fun getItemCount() = items.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: PlayingQueueViewHolder, position: Int) {
        // PrimeTube: bounds guard - never bind against a stale position
        val streamItem = items.getOrNull(position) ?: return
        holder.binding.apply {
            ImageHelper.loadImage(streamItem.thumbnail, thumbnail)
            title.text = streamItem.title
            videoInfo.text = streamItem.uploaderName + "  •  " +
                DateUtils.formatElapsedTime(streamItem.duration ?: 0)

            val currentIndex = if (filterQuery.isEmpty()) lastCurrentIndex else -1
            root.setBackgroundColor(
                if (currentIndex == position) {
                    ThemeHelper.getThemeColor(root.context, android.R.attr.colorControlHighlight)
                } else {
                    Color.TRANSPARENT
                }
            )

            root.setOnClickListener {
                val newVideoId = streamItem.url?.toID() ?: return@setOnClickListener

                val oldPosition = PlayingQueue.currentIndex()
                // get the new position from the queue to work properly after reordering the queue
                val newPosition = PlayingQueue.getStreams().indexOfFirst {
                    it.url?.toID() == newVideoId
                }.takeIf { it >= 0 } ?: return@setOnClickListener
                PlayingQueue.updateCurrent(streamItem)

                // select the new item in the queue and update the selected item in the UI
                onQueueItemSelected(newVideoId)
                refresh()
            }
        }
    }
}
