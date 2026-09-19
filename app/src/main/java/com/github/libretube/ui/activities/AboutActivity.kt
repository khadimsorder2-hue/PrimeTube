package com.github.libretube.ui.activities

import android.os.Bundle
import android.view.View
import com.github.libretube.R
import com.github.libretube.databinding.ActivityAboutBinding
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * PrimeTube: the About screen shows ONLY the developer information by request of
 * the app owner - all upstream entries (help, donate, website, Piped, translate,
 * license, third-party credits, device info) have been removed.
 */
class AboutActivity : BaseActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // the developer card opens the PrimeTube repository
        binding.developer.setOnClickListener {
            IntentHelper.openLinkFromHref(this, supportFragmentManager, GITHUB_URL)
        }
        binding.developer.setOnLongClickListener {
            onLongClick(GITHUB_URL)
            true
        }

        // crash report row: only visible when a crash has been logged on this device
        if (PreferenceHelper.getErrorLog().isBlank()) {
            binding.crashLog.visibility = View.GONE
        }
        binding.crashLog.setOnClickListener {
            showCrashLog()
        }
    }

    private fun onLongClick(href: String) {
        ClipboardHelper.save(this, text = href, notify = true)
    }

    /**
     * Let the user copy the last crash log (to share it with the developer) or
     * open the bug tracker on GitHub.
     */
    private fun showCrashLog() {
        val log = PreferenceHelper.getErrorLog()
        if (log.isBlank()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prime_crash_log_title)
            .setMessage(log)
            .setPositiveButton(R.string.okay, null)
            .setNeutralButton(R.string.prime_copy_crash_log) { _, _ ->
                ClipboardHelper.save(this, text = log, notify = true)
            }
            .setNegativeButton(R.string.prime_report_bug) { _, _ ->
                IntentHelper.openLinkFromHref(
                    this,
                    supportFragmentManager,
                    ISSUES_URL,
                    forceDefaultOpen = true
                )
            }
            .show()
    }

    companion object {
        // PrimeTube: point all GitHub links to the PrimeTube repository
        const val GITHUB_URL = "https://github.com/khadimsorder2-hue/PrimeTube"
        @Deprecated("PrimeTube: donate links removed from the UI")
        const val DONATE_URL = "https://github.com/libre-tube/LibreTube#donate"
        private const val ISSUES_URL = "https://github.com/khadimsorder2-hue/PrimeTube/issues"
    }
}
