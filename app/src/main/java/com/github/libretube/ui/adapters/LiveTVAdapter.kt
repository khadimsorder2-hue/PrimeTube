package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.RowLiveChannelBinding
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.models.LiveChannel
import com.github.libretube.ui.viewholders.LiveTVViewHolder
import java.util.Locale

class LiveTVAdapter(
    private val onClick: (LiveChannel) -> Unit
) : RecyclerView.Adapter<LiveTVViewHolder>() {

    private var channels: List<LiveChannel> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LiveChannel>) {
        channels = list
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
            channelGroup.isGone = channel.group.isNullOrBlank()
            root.setOnClickListener { onClick(channel) }
        }
    }
}
