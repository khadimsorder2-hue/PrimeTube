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
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.helpers.PerformanceHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.models.TrendsViewModel
import com.google.android.material.snackbar.Snackbar

/**
 * PrimeTube: YouTube-like home.
 *
 * A pinned filter chip row (All / Music / Gaming / Live / Podcasts / Trailers) switches the
 * video feed in place - exactly like the YouTube app and youtube.com - instead of navigating
 * to a separate trends page:
 * - "All" shows the subscription feed (new videos of subscribed channels, like the YouTube home
 *   feed) and falls back to trending if no channels are subscribed yet.
 * - Category chips show the matching trending category feed.
 * A "Continue watching" shelf is kept above the feed, like on YouTube.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()
    private val trendsViewModel: TrendsViewModel by activityViewModels()

    private val feedAdapter = VideoCardsAdapter()
    private val watchingAdapter = VideoCardsAdapter(columnWidthDp = 250f)

    private var currentCategory = MODE_ALL
    private var feedItems: List<StreamItem>? = null
    private var trendingItems: List<StreamItem>? = null

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
            trending.observe(viewLifecycleOwner, ::showTrending)
            feed.observe(viewLifecycleOwner, ::showFeed)
            continueWatching.observe(viewLifecycleOwner, ::showContinueWatching)
            isLoading.observe(viewLifecycleOwner, ::updateLoading)
        }

        binding.watchingTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_watchHistoryFragment)
        }

        binding.refresh.setOnRefreshListener {
            binding.refresh.isRefreshing = true
            fetchHomeFeed()
        }

        binding.refreshButton.setOnClickListener {
            fetchHomeFeed()
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

        // Avoid re-fetching when re-entering the screen if it was loaded successfully, except when
        // the value of trending region has changed
        val isTrendingRegionChanged = homeViewModel.trending.value?.let {
            it.second.region != PreferenceHelper.getTrendingRegion(requireContext())
        } == true

        if (homeViewModel.loadedSuccessfully.value == false || isTrendingRegionChanged) {
            fetchHomeFeed()
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
        val chipName = PreferenceHelper.getString(PreferenceKeys.HOME_SELECTED_CHIP, MODE_ALL)
        val chipId = when (chipName) {
            TrendingCategory.MUSIC.name -> R.id.chip_music
            TrendingCategory.GAMING.name -> R.id.chip_gaming
            TrendingCategory.LIVE.name -> R.id.chip_live
            TrendingCategory.PODCASTS.name -> R.id.chip_podcasts
            TrendingCategory.TRAILERS.name -> R.id.chip_trailers
            else -> R.id.chip_all
        }
        currentCategory = if (chipId == R.id.chip_all) MODE_ALL else chipName
        binding.homeChips.check(chipId)
    }

    private fun selectChip(checkedId: Int) {
        when (checkedId) {
            R.id.chip_all -> {
                currentCategory = MODE_ALL
                PreferenceHelper.putString(PreferenceKeys.HOME_SELECTED_CHIP, MODE_ALL)
                // render the cached subscription feed immediately, refresh in background
                feedItems = homeViewModel.feed.value
                trendingItems = null
                renderPrimaryList()
                fetchHomeFeed()
            }

            else -> {
                val category = when (checkedId) {
                    R.id.chip_music -> TrendingCategory.MUSIC
                    R.id.chip_gaming -> TrendingCategory.GAMING
                    R.id.chip_live -> TrendingCategory.LIVE
                    R.id.chip_podcasts -> TrendingCategory.PODCASTS
                    else -> TrendingCategory.TRAILERS
                }
                currentCategory = category.name
                PreferenceHelper.putString(PreferenceKeys.HOME_SELECTED_CHIP, category.name)
                PreferenceHelper.putString(PreferenceKeys.TRENDING_CATEGORY, category.name)
                trendingItems = trendsViewModel.trendingVideos.value?.get(category)?.streams
                renderPrimaryList()
                fetchHomeFeed()
            }
        }
    }

    private fun fetchHomeFeed() {
        binding.nothingHere.isGone = true
        val visibleItems = when (currentCategory) {
            MODE_ALL -> setOf("featured", "trending", "watching")
            else -> setOf("trending", "watching")
        }

        homeViewModel.loadHomeFeed(
            context = requireContext(),
            subscriptionsViewModel = subscriptionsViewModel,
            visibleItems = visibleItems,
            onUnusualLoadTime = ::showChangeInstanceSnackBar
        )
    }

    private fun showFeed(streamItems: List<StreamItem>?) {
        if (streamItems == null) return
        feedItems = streamItems
        renderPrimaryList()
    }

    private fun showTrending(trends: Pair<TrendingCategory, TrendsViewModel.TrendingStreams>?) {
        if (trends == null) return
        val (category, trendingStreams) = trends

        // cache the loaded trends in the [TrendsViewModel] so that the trends don't need to be
        // reloaded there
        val region = PreferenceHelper.getTrendingRegion(requireContext())
        trendsViewModel.setStreamsForCategory(
            category,
            TrendsViewModel.TrendingStreams(region, trendingStreams.streams)
        )

        trendingItems = trendingStreams.streams
        renderPrimaryList()
    }

    /**
     * Decide which list to display: for "All" the subscription feed with a trending fallback,
     * for category chips the matching trending feed.
     */
    private fun renderPrimaryList() {
        val list = when (currentCategory) {
            MODE_ALL -> feedItems?.takeIf { it.isNotEmpty() } ?: trendingItems
            else -> trendingItems
        }.orEmpty()

        binding.trendingRV.isGone = list.isEmpty()
        feedAdapter.submitList(list)
    }

    private fun showContinueWatching(unwatchedVideos: List<StreamItem>?) {
        if (unwatchedVideos == null) return

        binding.watchingTV.isVisible = true
        binding.watchingRV.isVisible = true
        watchingAdapter.submitList(unwatchedVideos)
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
    }
}
