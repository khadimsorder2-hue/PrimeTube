package com.github.libretube.ui.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentLiveTvBinding
import com.github.libretube.helpers.LiveTvHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.adapters.LiveTVAdapter
import com.github.libretube.ui.activities.LiveTVPlayerActivity
import com.github.libretube.ui.models.LiveChannel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * PrimeTube: Live TV tab - plays the IPTV playlist channels in a numbered grid
 * (3 cards per row). Tapping a card opens the dedicated live player.
 *
 * PrimeTube: playlist SOURCES - users can add M3U / M3U8 / JSON links or local
 * playlist files (SAF) and instantly switch ("toggle") between them.
 */
class LiveTVFragment : Fragment(R.layout.fragment_live_tv) {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    /** PrimeTube: picked local playlist file (persisted read permission). */
    private var pickedFileUri: Uri? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pickedFileUri = uri
            _binding?.let { b ->
                // echo the picked file into the still-open add dialog via the tag
                b.root.findViewWithTag<EditText>("prime_source_link_tag")
                    ?.setText(uri.lastPathSegment ?: uri.toString())
            }
        }

    private val adapter = LiveTVAdapter(
        onClick = { channel, _ ->
            // PrimeTube: resolve the index from the FULL list - the grid may be
            // sorted (favorites first), so the adapter position is not the index
            val ctx = context ?: return@LiveTVAdapter
            startActivity(
                Intent(ctx, LiveTVPlayerActivity::class.java)
                    .putExtra(
                        LiveTVPlayerActivity.EXTRA_INDEX,
                        fullChannels.indexOfFirst { it.url == channel.url }
                    )
                    .putExtra(LiveTVPlayerActivity.EXTRA_NAME, channel.name)
                    .putExtra(LiveTVPlayerActivity.EXTRA_URL, channel.url)
                    .putExtra(LiveTVPlayerActivity.EXTRA_LOGO, channel.logo)
            )
        },
        onFavoriteToggle = { channel -> toggleFavorite(channel) }
    )

    private var fullChannels: List<LiveChannel> = emptyList()

    /** PrimeTube: the active category filter (null = All). */
    private var selectedCategory: String? = null

    /** PrimeTube: star toggle - re-sorts so favorites move to the top. */
    private fun toggleFavorite(channel: LiveChannel) {
        val context = context ?: return
        LiveTvHelper.toggleFavorite(context, channel.name)
        adapter.setFavorites(LiveTvHelper.getFavoriteNames(context))
        adapter.submitList(sortedFilteredChannels())
    }

    /** PrimeTube: favorites/recent ordering + the active category filter. */
    private fun sortedFilteredChannels(): List<LiveChannel> {
        val context = context ?: return fullChannels
        val sorted = LiveTvHelper.sortChannels(context, fullChannels)
        val category = selectedCategory ?: return sorted
        return sorted.filter { it.group == category }
    }

    /** PrimeTube: category chips from the channel groups of the playlist. */
    private fun buildCategoryChips() {
        val context = context ?: return
        val categories = fullChannels.mapNotNull { it.group?.trim()?.takeIf { g -> g.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .map { it.key }
            .sorted()
        val b = _binding ?: return
        b.liveCategories.removeAllViews()
        if (categories.isEmpty()) {
            b.liveCategoriesScroll.isVisible = false
            return
        }
        b.liveCategoriesScroll.isVisible = true

        fun addChip(label: String, category: String?, checked: Boolean) {
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = checked
                setTextAppearanceResource(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setOnClickListener { _ ->
                    selectedCategory = category
                    adapter.submitList(sortedFilteredChannels())
                }
            }
            b.liveCategories.addView(chip)
        }

        addChip(getString(R.string.prime_category_all), null, selectedCategory == null)
        categories.forEach { group -> addChip(group, group, selectedCategory == group) }
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
        binding.liveSourcesBtn.setOnClickListener { showSourcesDialog() }

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
            fullChannels = channels
            if (selectedCategory != null &&
                fullChannels.none { it.group == selectedCategory }
            ) {
                // the new source may not have the previously chosen category
                selectedCategory = null
            }
            adapter.setFavorites(LiveTvHelper.getFavoriteNames(context.applicationContext))
            buildCategoryChips()
            adapter.submitList(sortedFilteredChannels())
            b.liveSwipe.isRefreshing = false
            b.liveProgress.isVisible = false
            b.liveError.isVisible = channels.isEmpty()
            b.liveRecView.isVisible = channels.isNotEmpty()
            if (channels.isEmpty() && forceRefresh) {
                Toast.makeText(
                    requireContext(),
                    R.string.prime_source_load_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------- sources management ----------------

    /** PrimeTube: dialog listing every playlist source; tap = switch, trash = delete. */
    private fun showSourcesDialog() {
        val context = context ?: return
        val view = layoutInflater.inflate(R.layout.dialog_live_sources, null)
        val container = view.findViewById<LinearLayout>(R.id.sourcesContainer)
        val sources = LiveTvHelper.getSources(context)
        val activeId = LiveTvHelper.getActiveSource(context).id

        sources.forEach { source ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(4), dp(8))
                background = borderlessRipple(context)
            }

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = source.name
                setTextColor(
                    if (source.id == activeId) {
                        ContextCompat.getColor(context, R.color.red_md_theme_light_primary)
                    } else {
                        ThemeHelper.getThemeColor(context, android.R.attr.textColorPrimary)
                    }
                )
                textSize = 14.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(context).apply {
                text = typeLabel(source.type) + if (source.id == activeId) "  ·  " +
                    getString(R.string.prime_source_active) else ""
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            })
            row.addView(texts)

            if (source.id != LiveTvHelper.DEFAULT_SOURCE_ID) {
                row.addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_trash)
                    background = borderlessRipple(context)
                    setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    setOnClickListener {
                        LiveTvHelper.removeSource(context, source.id)
                        loaded = false
                        loadChannels(forceRefresh = true)
                        Toast.makeText(context, R.string.prime_source_deleted, Toast.LENGTH_SHORT).show()
                    }
                })
            }

            row.setOnClickListener {
                LiveTvHelper.setActiveSource(context, source.id)
                loaded = false
                loadChannels(forceRefresh = true)
            }
            container.addView(row)
        }

        view.findViewById<View>(R.id.btnAddSource).setOnClickListener {
            showAddSourceDialog()
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.prime_sources_title)
            .setView(view)
            .setNegativeButton(R.string.prime_close, null)
            .show()
    }

    /** PrimeTube: add a source - type toggle (M3U / M3U8 / JSON / FILE). */
    private fun showAddSourceDialog() {
        val context = context ?: return
        pickedFileUri = null
        val view = layoutInflater.inflate(R.layout.dialog_live_source_add, null)
        val typeGroup = view.findViewById<RadioGroup>(R.id.sourceTypeGroup)
        val nameEt = view.findViewById<EditText>(R.id.sourceName)
        val linkEt = view.findViewById<EditText>(R.id.sourceLink)
        val browseBtn = view.findViewById<View>(R.id.btnBrowse)
        val linkLabel = view.findViewById<TextView>(R.id.sourceLinkLabel)

        linkEt.tag = "prime_source_link_tag"

        fun refreshForType() {
            val isFile = typeGroup.checkedRadioButtonId == R.id.rbFile
            browseBtn.isVisible = isFile
            linkLabel.setText(
                if (isFile) R.string.prime_source_file_label else R.string.prime_source_link
            )
            linkEt.hint = getString(
                when (typeGroup.checkedRadioButtonId) {
                    R.id.rbM3u8 -> R.string.prime_src_m3u8_hint
                    R.id.rbJson -> R.string.prime_src_json_hint
                    else -> R.string.prime_source_link_hint
                }
            )
        }
        typeGroup.setOnCheckedChangeListener { _, _ -> refreshForType() }

        browseBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { filePicker.launch(intent) }
                .onFailure { toast(R.string.prime_source_load_failed) }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.prime_add_source)
            .setView(view)
            .setPositiveButton(R.string.prime_add) { _, _ ->
                val type = when (typeGroup.checkedRadioButtonId) {
                    R.id.rbM3u8 -> LiveTvHelper.TYPE_M3U8
                    R.id.rbJson -> LiveTvHelper.TYPE_JSON
                    R.id.rbFile -> LiveTvHelper.TYPE_FILE
                    else -> LiveTvHelper.TYPE_M3U
                }
                val uri = if (type == LiveTvHelper.TYPE_FILE) {
                    pickedFileUri?.toString().orEmpty()
                } else {
                    linkEt.text.toString().trim()
                }
                val ok = uri.isNotEmpty() &&
                    LiveTvHelper.addSource(context, nameEt.text.toString(), type, uri)
                if (ok) {
                    loaded = false
                    loadChannels(forceRefresh = true)
                    toast(R.string.prime_source_added)
                } else {
                    toast(R.string.prime_source_invalid)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun typeLabel(type: String) = getString(
        when (type) {
            LiveTvHelper.TYPE_M3U8 -> R.string.prime_src_m3u8
            LiveTvHelper.TYPE_JSON -> R.string.prime_src_json
            LiveTvHelper.TYPE_FILE -> R.string.prime_src_file
            else -> R.string.prime_src_m3u
        }
    )

    private fun toast(resId: Int) {
        context?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()

    /** PrimeTube: theme-correct borderless ripple for programmatically built rows. */
    private fun borderlessRipple(context: android.content.Context): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, value, true
        )
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
