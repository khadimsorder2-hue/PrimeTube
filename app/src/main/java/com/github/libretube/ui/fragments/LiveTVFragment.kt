package com.github.libretube.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentLiveTvBinding
import com.github.libretube.helpers.LiveTvHelper
import com.github.libretube.ui.adapters.LiveTVAdapter
import com.github.libretube.ui.activities.LiveTVPlayerActivity
import kotlinx.coroutines.launch

/**
 * PrimeTube: Live TV tab - plays the IPTV playlist channels in a numbered grid
 * (3 cards per row). Tapping a card opens the dedicated live player.
 */
class LiveTVFragment : Fragment(R.layout.fragment_live_tv) {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    private val adapter = LiveTVAdapter { channel ->
        startActivity(
            Intent(requireContext(), LiveTVPlayerActivity::class.java)
                .putExtra(LiveTVPlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(LiveTVPlayerActivity.EXTRA_URL, channel.url)
                .putExtra(LiveTVPlayerActivity.EXTRA_LOGO, channel.logo)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // PrimeTube: fixed 3 cards per row, like a TV channel list
        binding.liveRecView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.liveRecView.adapter = adapter
        binding.liveRecView.addItemDecoration(spacingDecoration())

        binding.liveSwipe.setOnRefreshListener { loadChannels(forceRefresh = true) }
        binding.liveRetry.setOnClickListener { loadChannels(forceRefresh = true) }

        loadChannels()
    }

    private fun spacingDecoration(): RecyclerView.ItemDecoration {
        val spacing = (resources.displayMetrics.density * 4).toInt()
        return object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(spacing, spacing, spacing, spacing)
            }
        }
    }

    private fun loadChannels(forceRefresh: Boolean = false) {
        val context = context ?: return
        if (loaded && !forceRefresh) return
        binding.liveProgress.isVisible = !loaded
        binding.liveError.isVisible = false
        binding.liveSwipe.isRefreshing = loaded

        viewLifecycleOwner.lifecycleScope.launch {
            val channels = runCatching {
                LiveTvHelper.fetchChannels(context.applicationContext, forceRefresh)
            }.getOrDefault(emptyList())

            // the fragment might be gone while the request is running
            val b = _binding ?: return@launch
            loaded = channels.isNotEmpty()
            adapter.submitList(channels)
            b.liveSwipe.isRefreshing = false
            b.liveProgress.isVisible = false
            b.liveError.isVisible = channels.isEmpty()
            b.liveRecView.isVisible = channels.isNotEmpty()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
