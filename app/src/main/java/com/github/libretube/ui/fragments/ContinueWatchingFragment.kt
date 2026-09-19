package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.FragmentContinueWatchingBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.helpers.PerformanceHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.adapters.WatchHistoryAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PrimeTube: dedicated "Continue watching" tab in the bottom navigation.
 *
 * Shows the videos from the watch history that have not been finished yet, together
 * with the watch progress of each video - like the "Continue watching" row on YouTube.
 * Replaces the subscriptions tab in the bottom navigation.
 */
class ContinueWatchingFragment : DynamicLayoutManagerFragment(R.layout.fragment_continue_watching) {
    private var _binding: FragmentContinueWatchingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContinueWatchingViewModel by viewModels()
    private val continueWatchingAdapter = WatchHistoryAdapter()

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.continueRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentContinueWatchingBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // PrimeTube: trim RecyclerView animations on low-RAM devices
        if (PerformanceHelper.isLowRamDevice()) {
            binding.continueRecView.itemAnimator = null
        }

        binding.continueRecView.adapter = continueWatchingAdapter

        viewModel.videos.observe(viewLifecycleOwner) { videos ->
            // PrimeTube: a failure while binding the list (e.g. a bad image cache state)
            // must never crash the whole app - this observer runs on every fragment
            // start, including right after tapping a video to play
            runCatching {
                binding.progress.isGone = true
                binding.continueEmpty.isGone = videos.isNotEmpty()
                binding.continueRecView.isVisible = videos.isNotEmpty()
                continueWatchingAdapter.submitList(videos)
            }
        }

        binding.continueRecView.addOnBottomReachedListener {
            viewModel.loadNextPage()
        }
    }

    override fun onResume() {
        super.onResume()

        // watch positions may have changed after watching a video
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Loads unfinished videos from the watch history, page by page.
 */
class ContinueWatchingViewModel : ViewModel() {
    val videos: MutableLiveData<List<WatchHistoryItem>> = MutableLiveData(null)

    private var currentPage = 1
    private var isLoading = false

    fun refresh() {
        if (videos.value == null) {
            loadNextPage()
        } else {
            // re-query the first pages so that finished videos disappear
            currentPage = 1
            loadNextPage(reset = true)
        }
    }

    fun loadNextPage(reset: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (isLoading) return@launch
        isLoading = true

        val newVideos = loadPage(currentPage)

        isLoading = false
        currentPage++

        videos.postValue(
            if (reset) newVideos else videos.value.orEmpty() + newVideos
        )
    }

    private suspend fun loadPage(page: Int): List<WatchHistoryItem> {
        if (!PlayerHelper.watchHistoryEnabled) return emptyList()

        val history = DatabaseHelper.getWatchHistoryPage(page, PAGE_SIZE)

        // only keep videos that have not been finished yet
        return history.filter { DatabaseHelper.filterByWatchStatus(it) }
    }

    companion object {
        private const val PAGE_SIZE = 10
    }
}
