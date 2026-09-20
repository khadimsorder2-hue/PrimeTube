package com.github.libretube.util

import android.content.Context
import android.content.Intent

import androidx.core.content.FileProvider
import com.github.libretube.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * PrimeTube: in-app updater for the fork.
 *
 * Checks the latest GitHub release of khadimsorder2-hue/PrimeTube, offers the
 * release APK asset and installs it OVER the running app (same stable
 * signature), so the user never has to sideload anything by hand again.
 */
object PrimeUpdater {

    private const val RELEASE_API =
        "https://api.github.com/repos/khadimsorder2-hue/PrimeTube/releases/latest"

    /** PrimeTube: release tags look like "v32.1-build18". */
    private val BUILD_NUMBER = Regex("build(\\d+)")

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** PrimeTube: latest release information, parsed from the GitHub API. */
    data class ReleaseInfo(
        val tag: String,
        val changelog: String,
        val apkUrl: String?
    )

    /**
     * Fetches the latest release (blocking - call from IO). The APK asset is
     * the file ending in "-release.apk", or the first .apk as a fallback.
     */
    fun fetchLatest(): ReleaseInfo {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PrimeTube-Updater")
            .build()
        checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API returned ${response.code}")
            }
            val json = JSONObject(response.body!!.string())
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (url.isEmpty()) continue
                    if (name.endsWith("-release.apk")) {
                        apkUrl = url
                        break
                    }
                    if (apkUrl == null && name.endsWith(".apk")) apkUrl = url
                }
            }
            return ReleaseInfo(
                tag = json.optString("tag_name", ""),
                changelog = json.optString("body", "").orEmpty(),
                apkUrl = apkUrl
            )
        }
    }

    /**
     * True when [latestTag] is a newer build than [currentVersion]
     * ("32.1-build17" vs "v32.1-build18"). Anything without a build number
     * (local/dev builds) is treated as older than the latest release.
     */
    fun isNewer(currentVersion: String, latestTag: String): Boolean {
        if (latestTag.isBlank()) return false
        val latest = latestTag.removePrefix("v")
        if (latest == currentVersion) return false
        val latestBuild = BUILD_NUMBER.find(latest)?.groupValues?.get(1)?.toIntOrNull()
            ?: return latest != currentVersion
        val currentBuild = BUILD_NUMBER.find(currentVersion)?.groupValues?.get(1)?.toIntOrNull()
            ?: return true
        return latestBuild > currentBuild
    }

    /** PrimeTube: the version this app was built as (injected by CI). */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Downloads the APK into the app's private external dir (blocking - call
     * from IO). Returns the ready-to-install file.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("prime_update") ?: context.cacheDir
        val file = File(dir, "PrimeTube-update.apk")
        file.parentFile?.mkdirs()
        file.delete()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PrimeTube-Updater")
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("APK download failed: HTTP ${response.code}")
            }
            val total = response.body?.contentLength() ?: -1L
            response.body!!.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent.coerceIn(0, 100))
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        file
    }

    /**
     * PrimeTube: hands the APK to the system installer (install-over). The
     * system itself guides the user through the "allow installs" step when
     * needed; the stable signature makes the update an in-place upgrade.
     */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            // PrimeTube: the source is the app's own verified updater, not an
            // unknown web download - lets the installer skip one extra warning
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        context.startActivity(intent)
    }
}
