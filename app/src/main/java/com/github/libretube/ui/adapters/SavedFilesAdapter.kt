package com.github.libretube.ui.adapters

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.ItemSavedFileBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SavedDownload
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.toastFromMainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PrimeTube: adapter for the "Saved files" tab - videos saved as normal MP4 files
 * in the user's storage by the "Save to storage (MP4)" downloader.
 */
class SavedFilesAdapter : ListAdapter<SavedDownload, SavedFilesAdapter.SavedFileViewHolder>(
    DiffCallback
) {
    object DiffCallback : DiffUtil.ItemCallback<SavedDownload>() {
        override fun areItemsTheSame(oldItem: SavedDownload, newItem: SavedDownload) =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: SavedDownload, newItem: SavedDownload) =
            oldItem == newItem
    }

    inner class SavedFileViewHolder(
        private val binding: ItemSavedFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                openFile(getItem(bindingAdapterPosition))
            }
            binding.savedDelete.setOnClickListener {
                deleteFile(getItem(bindingAdapterPosition))
            }
        }

        fun bind(item: SavedDownload) {
            binding.savedTitle.text = item.title
            binding.savedSubtitle.text = listOf(
                item.uploader,
                item.sizeBytes.formatAsFileSize(),
                formatDuration(item.duration),
                item.fileName
            ).filter { it.isNotBlank() }.joinToString("  •  ")
        }

        private fun openFile(item: SavedDownload) {
            val context = binding.root.context
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(android.net.Uri.parse(item.uri), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.toastFromMainThread(R.string.could_not_open_file)
            }
        }

        private fun deleteFile(item: SavedDownload) {
            val context = binding.root.context
            val position = bindingAdapterPosition
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val uri = android.net.Uri.parse(item.uri)
                    if (item.uri.startsWith("content://media/")) {
                        context.contentResolver.delete(uri, null, null)
                    } else {
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }
                }
                DatabaseHolder.Database.savedDownloadDao().delete(item.videoId)

                withContext(Dispatchers.Main) {
                    if (position != RecyclerView.NO_POSITION) {
                        submitList(currentList.toMutableList().apply { removeAt(position) })
                    }
                    if (currentList.isEmpty()) {
                        // let the fragment show the empty state on next resume
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedFileViewHolder {
        val binding = ItemSavedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SavedFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SavedFileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%02d:%02d", min, sec)
    }
}
