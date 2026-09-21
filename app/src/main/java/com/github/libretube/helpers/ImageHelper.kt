package com.github.libretube.helpers

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.widget.ImageView
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.load
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.memory.MemoryCache
import coil3.toBitmap
import com.github.libretube.BuildConfig
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.PrimeImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.nio.file.Path

object ImageHelper {
    private lateinit var imageLoader: ImageLoader

    private const val HTTP_SCHEME = "http"

    // PrimeTube: bounded caches for low-RAM devices.
    // The coil disk cache (DiskLruCache) has been REPLACED by PrimeTube's own
    // PrimeImageStore + a standard OkHttp HTTP cache - DiskLruCache was the source
    // of the NullPointerException crashes reported from inside coil3.disk.
    private const val HTTP_CACHE_BYTES = 64L * 1024 * 1024
    private const val LOW_RAM_MEMORY_CACHE_PERCENT = 0.08
    private const val DEFAULT_MEMORY_CACHE_PERCENT = 0.20

    /**
     * Initialize the image loader
     */
    fun initializeImageLoader(context: Context) {
        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)
        }

        // PrimeTube: low-RAM detection, either automatic or user-enforced
        val activityManager = context.getSystemService<ActivityManager>()
        val isLowRamDevice = activityManager?.isLowRamDevice == true ||
            PreferenceHelper.getBoolean(PreferenceKeys.LOW_RAM_MODE, false)
        val smoothUi = !isLowRamDevice && !DataSaverMode.isEnabled(context)

        val client = httpClient
            .cache(Cache(File(context.cacheDir, "prime_http"), HTTP_CACHE_BYTES))
            .addInterceptor(PrimeImageStore.TeeInterceptor(context))
            .build()

        imageLoader = ImageLoader.Builder(context)
            .crossfade(smoothUi)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(client)
                )
            }
            .apply {
                // PrimeTube: memory cache only - no coil disk cache anymore.
                // Persistence comes from the OkHttp HTTP cache + PrimeImageStore.
                memoryCachePolicy(CachePolicy.ENABLED)

                memoryCache(
                    MemoryCache.Builder()
                        .maxSizePercent(
                            context,
                            if (isLowRamDevice) LOW_RAM_MEMORY_CACHE_PERCENT else DEFAULT_MEMORY_CACHE_PERCENT
                        )
                        .build()
                )
            }
            .build()
    }

    /**
     * PrimeTube: low-RAM safety valve, called from [LibreTubeApp.onTrimMemory].
     * Hands the in-memory bitmap cache back to the system as soon as it is
     * running low, so the app survives instead of being killed mid-video or
     * mid-download. Disk caches are kept - only RAM is released.
     */
    fun trimMemoryCaches() {
        runCatching { imageLoader.memoryCache?.clear() }
    }

    /**
     * Checks if the corresponding image for the given key (e.g. a url) is cached.
     * PrimeTube: uses our own PrimeImageStore instead of coil's DiskLruCache -
     * plain file existence check, cannot throw.
     */
    private fun isCached(context: Context, key: String): Boolean {
        return PrimeImageStore.isCached(context, key)
    }

    /**
     * load an image from a url into an imageView
     */
    fun loadImage(url: String?, target: ImageView, whiteBackground: Boolean = false) {
        if (url.isNullOrEmpty()) return

        // clear image to avoid loading issues at fast scrolling
        target.setImageBitmap(null)

        val urlToLoad = ProxyHelper.rewriteUrlUsingProxyPreference(url)

        // only load online images if the data saver mode is disabled
        val canLoad = runCatching {
            !DataSaverMode.isEnabled(target.context) ||
                !urlToLoad.startsWith(HTTP_SCHEME) ||
                isCached(target.context, urlToLoad)
        }.getOrDefault(true)
        if (!canLoad) return

        runCatching {
            target.load(urlToLoad) {
                listener(
                    onSuccess = { _, _ ->
                        // set the background to white for transparent images
                        if (whiteBackground) target.setBackgroundColor(Color.WHITE)
                    }
                )
            }
        }
    }

    suspend fun downloadImage(context: Context, url: String, path: Path) {
        val bitmap = getImage(context, url) ?: return
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toAndroidUri())?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 25, it)
            }
        }
    }

    suspend fun getImage(context: Context, url: String?): Bitmap? {
        return getImage(context, url?.toUri())
    }

    suspend fun getImage(context: Context, url: Uri?): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        return imageLoader.execute(request).image?.toBitmap()
    }

    fun insertText(bitmap: Bitmap, text: String, posX: Float, posY: Float, fontSize: Float) {
        val canvas = Canvas(bitmap)

        canvas.drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
        canvas.drawText(text, bitmap.width * posX, bitmap.height * posY, Paint().apply {
            style = Paint.Style.FILL
            textSize = fontSize
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        })
    }
}
