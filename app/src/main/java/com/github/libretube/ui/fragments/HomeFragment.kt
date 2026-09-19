package com.github.libretube.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PerformanceHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.snackbar.Snackbar

/**
 * PrimeTube: YouTube-app-like home built from the user's usage.
 *
 * A pinned filter chip row switches the feed in place:
 * - "All" mixes the personalized recommendations with the newest videos of subscribed
 *   channels and keeps a horizontal "Continue watching" shelf on top, like on YouTube.
 * - "Recommended" only shows usage-based recommendations (videos related to what the
 *   user watched recently).
 * - "Continue watching" shows unfinished videos from the watch history.
 *
 * No trending feed is used anywhere on the home screen.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()

    private val feedAdapter = VideoCardsAdapter()
    private val watchingAdapter = VideoCardsAdapter(columnWidthDp = 250f)

    private var currentMode = MODE_ALL
    private var continueWatchingItems: List<StreamItem> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.watchingRV.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.watchingRV.adapter = watchingAdapter
        binding.trendingRV.adapter = feedAdapter

        // PrimeTube: trim RecyclerView animations and caches depending on the RAM profile
        if (PerformanceHelper.isLowRamDevice()) {
            // change animations cause relayouts and flickering during feed updates
            binding.trendingRV.itemAnimator = null
            binding.watchingRV.itemAnimator = null
        } else {
            // keep more bound views around for smoother fast scrolling
            binding.trendingRV.setItemViewCacheSize(8)
            binding.watchingRV.setItemViewCacheSize(4)
        }

        with(homeViewModel) {
            feed.observe(viewLifecycleOwner) { render() }
            recommended.observe(viewLifecycleOwner) { render() }
            continueWatching.observe(viewLifecycleOwner, ::showContinueWatching)
            isLoading.observe(viewLifecycleOwner, ::updateLoading)
        }

        binding.watchingTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_watchHistoryFragment)
        }

        binding.refresh.setOnRefreshListener {
            binding.refresh.isRefreshing = true
            fetchHomeFeed(forceRefresh = true)
        }

        binding.refreshButton.setOnClickListener {
            fetchHomeFeed(forceRefresh = true)
        }

        binding.changeInstance.setOnClickListener {
            redirectToIntentSettings()
        }

        restoreSelectedChip()

        binding.homeChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_all
            selectChip(checkedId)
        }
    }

    override fun onResume() {
        super.onResume()

        if (homeViewModel.loadedSuccessfully.value == false) {
            fetchHomeFeed()
        } else {
            // watch positions may have changed after watching a video
            homeViewModel.refreshContinueWatching()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Highlight the chip matching the persisted selection without triggering a load.
     */
    private fun restoreSelectedChip() {
        val mode = PreferenceHelper.getString(PreferenceKeys.HOME_SELECTED_CHIP, MODE_ALL)
        currentMode = when (mode) {
            MODE_RECOMMENDED -> MODE_RECOMMENDED
            MODE_CONTINUE -> MODE_CONTINUE
            else -> MODE_ALL
        }
        val chipId = when (currentMode) {
            MODE_RECOMMENDED -> R.id.chip_recommended
            MODE_CONTINUE -> R.id.chip_continue
            else -> R.id.chip_all
        }
        binding.homeChips.check(chipId)
    }

    private fun selectChip(checkedId: Int) {
        currentMode = when (checkedId) {
            R.id.chip_recommended -> MODE_RECOMMENDED
            R.id.chip_continue -> MODE_CONTINUE
            else -> MODE_ALL
        }
        PreferenceHelper.putString(PreferenceKeys.HOME_SELECTED_CHIP, currentMode)
        render()
    }

    private fun fetchHomeFeed(forceRefresh: Boolean = false) {
        binding.nothingHere.isGone = true

        homeViewModel.loadHomeFeed(
            subscriptionsViewModel = subscriptionsViewModel,
            forceRefresh = forceRefresh,
            onUnusualLoadTime = ::showChangeInstanceSnackBar
        )
    }

    /**
     * Render the feed of the currently selected chip.
     */
    private fun render() {
        val feed = homeViewModel.feed.value.orEmpty()
        val recommended = homeViewModel.recommended.value.orEmpty()

        val list = when (currentMode) {
            MODE_ALL -> mixFeeds(recommended, feed)
            MODE_RECOMMENDED -> recommended
            else -> continueWatchingItems
        }

        binding.homeEmpty.isVisible = list.isEmpty() && homeViewModel.isLoading.value != true
        binding.trendingRV.isGone = list.isEmpty()
        feedAdapter.submitList(list)
    }

    /**
     * Mix the personalized recommendations and the subscription feed like the YouTube
     * home feed: a few recommendations per subscribed video, deduplicated, recommendations
     * first when the user has no subscriptions yet.
     */
    private fun mixFeeds(recommended: List<StreamItem>, subscribed: List<StreamItem>): List<StreamItem> {
        if (recommended.isEmpty()) return subscribed
        if (subscribed.isEmpty()) return recommended

        val mixed = mutableListOf<StreamItem>()
        val seen = mutableSetOf<String>()

        fun add(item: StreamItem) {
            val videoId = item.url?.toID() ?: return
            if (seen.add(videoId)) mixed += item
        }

        val recommendedIterator = recommended.iterator()
        val subscribedIterator = subscribed.iterator()

        while (mixed.size < MAX_MIXED_ITEMS && (recommendedIterator.hasNext() || subscribedIterator.hasNext())) {
            repeat(RECOMMENDATIONS_PER_SUBSCRIBED_VIDEO) {
                if (recommendedIterator.hasNext()) add(recommendedIterator.next())
            }
            if (subscribedIterator.hasNext()) add(subscribedIterator.next())
        }

        return mixed
    }

    private fun showContinueWatching(unwatchedVideos: List<StreamItem>?) {
        if (unwatchedVideos == null) return
        continueWatchingItems = unwatchedVideos
        updateContinueWatchingShelf()
        render()
    }

    /**
     * The horizontal "Continue watching" shelf is only visible on the "All" chip,
     * like on the YouTube home.
     */
    private fun updateContinueWatchingShelf() {
        val showShelf = currentMode == MODE_ALL && continueWatchingItems.isNotEmpty()
        binding.watchingTV.isVisible = showShelf
        binding.watchingRV.isVisible = showShelf
        watchingAdapter.submitList(continueWatchingItems)
    }

    private fun updateLoading(isLoading: Boolean) {
        if (isLoading) {
            showLoading()
        } else {
            hideLoading()
        }
    }

    private fun showLoading() {
        binding.progress.isVisible = !binding.refresh.isRefreshing
        binding.nothingHere.isVisible = false
        binding.homeContent.alpha = 0.3f
    }

    private fun hideLoading() {
        binding.progress.isVisible = false
        binding.refresh.isRefreshing = false

        val hasContent = homeViewModel.loadedSuccessfully.value == true
        if (hasContent) {
            showContent()
        } else {
            showNothingHere()
        }
        binding.homeContent.alpha = 1.0f
        render()
    }

    private fun showNothingHere() {
        binding.nothingHere.isVisible = true
        binding.homeContent.isVisible = false
    }

    private fun showContent() {
        binding.nothingHere.isVisible = false
        binding.homeContent.isVisible = true
    }

    private fun showChangeInstanceSnackBar() {
        val root = _binding?.root ?: return
        Snackbar
            .make(root, R.string.suggest_change_instance, Snackbar.LENGTH_LONG)
            .apply {
                setAction(R.string.change) {
                    redirectToIntentSettings()
                }
                show()
            }
    }

    private fun redirectToIntentSettings() {
        val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.REDIRECT_KEY, SettingsActivity.REDIRECT_TO_INTENT_SETTINGS)
        }
        startActivity(settingsIntent)
    }

    companion object {
        private const val MODE_ALL = "all"
        private const val MODE_RECOMMENDED = "recommended"
        private const val MODE_CONTINUE = "continue"
        private const val MAX_MIXED_ITEMS = 60
        private const val RECOMMENDATIONS_PER_SUBSCRIBED_VIDEO = 2
    }
}
