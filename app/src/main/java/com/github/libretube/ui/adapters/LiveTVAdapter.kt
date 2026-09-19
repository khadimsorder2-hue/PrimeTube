package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.RowLiveChannelBinding
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.models.LiveChannel
import com.github.libretube.ui.viewholders.LiveTVViewHolder
import java.util.Locale

class LiveTVAdapter(
    private val onClick: (LiveChannel, Int) -> Unit
) : RecyclerView.Adapter<LiveTVViewHolder>() {

    private var channels: List<LiveChannel> = emptyList()
    private var currentUrl: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LiveChannel>) {
        channels = list
        notifyDataSetChanged()
    }

    /** PrimeTube: highlight the currently playing channel card. */
    @SuppressLint("NotifyDataSetChanged")
    fun setCurrentUrl(url: String?) {
        if (currentUrl == url) return
        currentUrl = url
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveTVViewHolder {
        val binding = RowLiveChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LiveTVViewHolder(binding)
    }

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: LiveTVViewHolder, position: Int) {
        val channel = channels.getOrNull(position) ?: return
        holder.binding.apply {
            // PrimeTube: 1-based numbering like a TV channel list (01, 02, ...)
            channelNumber.text = String.format(Locale.US, "%02d", position + 1)
            ImageHelper.loadImage(channel.logo, channelLogo)
            channelName.text = channel.name
            channelGroup.text = channel.group
            channelGroup.visibility =
                if (channel.group.isNullOrBlank()) View.GONE else View.VISIBLE

            val isCurrent = channel.url == currentUrl
            if (isCurrent) {
                channelCard.strokeWidth = (root.resources.displayMetrics.density * 2).toInt()
                channelCard.strokeColor = ContextCompat.getColor(root.context, R.color.red_md_theme_light_primary)
            } else {
                channelCard.strokeWidth = (root.resources.displayMetrics.density * 1).toInt()
                channelCard.strokeColor = Color.parseColor("#2A2A2A")
            }

            root.setOnClickListener { onClick(channel, position) }
        }
    }
}
