package com.github.libretube.helpers

import android.app.ActivityManager
import androidx.core.content.getSystemService
import com.github.libretube.LibreTubeApp
import com.github.libretube.constants.PreferenceKeys

/**
 * PrimeTube: central detection of low-RAM devices so that memory-heavy features
 * (player buffers, image caches, UI animations) can be toned down on budget phones.
 *
 * The result is cached because it cannot change while the process is alive.
 */
object PerformanceHelper {
    private var lowRamDeviceCached: Boolean? = null

    /**
     * Whether the app should run in the memory-saving profile. This is the case when
     * the device reports itself as a low-RAM device (ActivityManager.isLowRamDevice)
     * or when the user manually enabled the Low RAM mode in the appearance settings.
     */
    fun isLowRamDevice(): Boolean {
        lowRamDeviceCached?.let { return it }

        val context = LibreTubeApp.instance
        val activityManager = context.getSystemService<ActivityManager>()
        val lowRam = activityManager?.isLowRamDevice == true ||
            PreferenceHelper.getBoolean(PreferenceKeys.LOW_RAM_MODE, false)

        lowRamDeviceCached = lowRam
        return lowRam
    }
}
