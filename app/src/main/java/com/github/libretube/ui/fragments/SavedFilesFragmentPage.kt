package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.FragmentDownloadContentBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.ui.adapters.SavedFilesAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PrimeTube: "Saved files" tab of the Downloads page - shows the history of videos
 * saved as normal MP4 files into the user's storage (phone storage or SD card) by
 * the "Save to storage (MP4)" downloader.
 */
class SavedFilesFragmentPage : Fragment(R.layout.fragment_download_content) {
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!

    private val adapter = SavedFilesAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.downloadsRecView.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadsRecView.adapter = adapter

        loadSavedFiles()
    }

    override fun onResume() {
        super.onResume()
        // new files may have been saved since the last visit
        loadSavedFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadSavedFiles() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { Database.savedDownloadDao().getAll() }

            _binding ?: return@launch
            adapter.submitList(items)
            binding.downloadsEmpty.isGone = items.isNotEmpty()
            binding.downloadsRecView.isVisible = items.isNotEmpty()
        }
    }
}
