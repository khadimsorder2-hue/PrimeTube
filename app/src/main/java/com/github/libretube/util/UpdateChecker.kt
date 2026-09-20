package com.github.libretube.util

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.github.libretube.R
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.constants.IntentData.appUpdateTag
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.ui.dialogs.UpdateAvailableDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PrimeTube: update check against the FORK repository
 * (khadimsorder2-hue/PrimeTube), with in-app download & install.
 */
class UpdateChecker(private val context: Context) {

    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        try {
            val release = withContext(Dispatchers.IO) { PrimeUpdater.fetchLatest() }
            if (PrimeUpdater.isNewer(PrimeUpdater.currentVersion(), release.tag)) {
                withContext(Dispatchers.Main) { showUpdateAvailableDialog(release) }
            } else if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.app_uptodate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.prime_update_failed)
            }
        }
    }

    private fun showUpdateAvailableDialog(release: PrimeUpdater.ReleaseInfo) {
        val dialog = UpdateAvailableDialog()
        dialog.arguments = Bundle().apply {
            putString(appUpdateTag, release.tag)
            putString(appUpdateChangelog, release.changelog)
            putString(appUpdateURL, release.apkUrl)
        }
        (context as? FragmentActivity)?.supportFragmentManager?.let {
            dialog.show(it, UpdateAvailableDialog::class.java.simpleName)
        }
    }
}
