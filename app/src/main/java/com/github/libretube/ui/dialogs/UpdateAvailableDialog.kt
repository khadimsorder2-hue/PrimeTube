package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateTag
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.util.PrimeUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.launch

/**
 * PrimeTube: "new version" dialog with a REAL in-app update flow - pressing
 * download fetches the release APK (with progress) and hands it to the system
 * installer, which updates the app in place (same signature).
 */
class UpdateAvailableDialog : DialogFragment() {

    private var tag: String? = null
    private var changelog: String? = null
    private var apkUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            tag = getString(appUpdateTag)
            changelog = getString(appUpdateChangelog)
            apkUrl = getString(appUpdateURL)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.prime_update_available_title) + " · " + tag.orEmpty())
            .setMessage(changelog?.takeIf { it.isNotBlank() } ?: tag.orEmpty())
            .setPositiveButton(R.string.prime_update_download_install) { _, _ ->
                startDownload()
            }
            .setNegativeButton(R.string.tooltip_dismiss, null)
            .show()
    }

    private fun startDownload() {
        val url = apkUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.prime_update_no_apk, Toast.LENGTH_LONG)
                .show()
            return
        }
        val activity = requireActivity()

        // PrimeTube: small indeterminate->determinate progress dialog
        val view = layoutInflater.inflate(R.layout.prime_update_progress, null, false)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val label = view.findViewById<TextView>(R.id.updateProgressLabel)
        label.setText(R.string.prime_update_checking)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.prime_update)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        activity.lifecycleScope.launch {
            try {
                val file: File = PrimeUpdater.downloadApk(activity, url) { percent ->
                    // PrimeTube: the download runs on IO - marshal to the UI
                    view.post {
                        if (!isAdded || !dialog.isShowing) return@post
                        progress.isIndeterminate = false
                        progress.progress = percent
                        label.text = getString(R.string.prime_update_downloading, percent)
                    }
                }
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(activity, R.string.prime_update_installing, Toast.LENGTH_SHORT)
                    .show()
                PrimeUpdater.installApk(activity, file)
            } catch (e: Exception) {
                e.printStackTrace()
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(activity, R.string.prime_update_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
