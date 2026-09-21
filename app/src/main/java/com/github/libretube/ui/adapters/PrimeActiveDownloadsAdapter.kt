package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.PrimeActiveDownloadRowBinding
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeActiveDownload
import com.github.libretube.helpers.PrimeDownloadTracker.PrimeDownloadState

/**
 * PrimeTube: renders the ACTIVE download queue (progress bars, sizes, speed)
 * at the top of the Downloads page. Tapping the cancel button on a storage
 * download stops that download.
 */
class PrimeActiveDownloadsAdapter(
    private val onCancel: (key: String) -> Unit
) : ListAdapter<PrimeActiveDownload, PrimeActiveDownloadsAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = PrimeActiveDownloadRowBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: PrimeActiveDownloadRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(item: PrimeActiveDownload) {
            val context = binding.root.context
            binding.primeActiveTitle.text = item.title

            val info = when (item.state) {
                PrimeDownloadState.QUEUED ->
                    context.getString(R.string.prime_dl_queued)

                PrimeDownloadState.DOWNLOADING -> {
                    val sb = StringBuilder(context.getString(R.string.prime_dl_downloading))
                    if (item.totalBytes > 0) {
                        sb.append(" • ")
                            .append(item.readBytes.formatAsFileSize())
                            .append(" / ")
                            .append(item.totalBytes.formatAsFileSize())
                    } else if (item.readBytes > 0) {
                        sb.append(" • ").append(item.readBytes.formatAsFileSize())
                    }
                    if (item.speedBytesPerSec > 0) {
                        sb.append(" • ")
                            .append(item.speedBytesPerSec.formatAsFileSize())
                            .append("/s")
                    }
                    if (item.totalBytes > 0) {
                        sb.append(" • ")
                            .append((item.readBytes * 100 / item.totalBytes).toInt().coerceIn(0, 100))
                            .append('%')
                    }
                    sb.toString()
                }

                PrimeDownloadState.PAUSED -> context.getString(R.string.prime_dl_paused)

                PrimeDownloadState.COMPLETED -> context.getString(R.string.prime_dl_completed)

                PrimeDownloadState.FAILED ->
                    item.message?.let {
                        context.getString(R.string.prime_dl_failed_with_reason, it)
                    } ?: context.getString(R.string.prime_dl_failed)
            }
            binding.primeActiveInfo.text = info

            when (item.state) {
                PrimeDownloadState.DOWNLOADING -> {
                    binding.primeActiveProgress.isVisible = true
                    binding.primeActiveProgress.isIndeterminate = item.totalBytes <= 0
                    if (item.totalBytes > 0) {
                        binding.primeActiveProgress.max = 100
                        binding.primeActiveProgress.progress =
                            ((item.readBytes * 100 / item.totalBytes).toInt()).coerceIn(0, 100)
                    }
                }

                PrimeDownloadState.COMPLETED -> {
                    binding.primeActiveProgress.isVisible = true
                    binding.primeActiveProgress.isIndeterminate = false
                    binding.primeActiveProgress.max = 100
                    binding.primeActiveProgress.progress = 100
                }

                else -> {
                    binding.primeActiveProgress.isVisible = false
                }
            }

            // PrimeTube: only storage downloads can be canceled in-app for
            // now - the offline ones expose pause/resume through their rows
            binding.primeActiveCancel.isGone = !item.canCancel
            binding.primeActiveCancel.setOnClickListener {
                if (item.canCancel) onCancel(item.key)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PrimeActiveDownload>() {
            override fun areItemsTheSame(oldItem: PrimeActiveDownload, newItem: PrimeActiveDownload) =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: PrimeActiveDownload, newItem: PrimeActiveDownload) =
                oldItem == newItem
        }
    }
}
