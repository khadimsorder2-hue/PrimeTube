package com.github.libretube.util

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * PrimeTube: tiny self-made on-disk image store.
 *
 * The app previously relied on coil's DiskLruCache, which produced hard-to-reproduce
 * NullPointerExceptions inside its internal bookkeeping when the cache was accessed
 * from multiple threads (crash reports came through LiveData observers while feed
 * lists were bound at fragment start).
 *
 * This store replaces the coil disk cache completely:
 * - plain files under cacheDir/prime_images, one file per image URL (MD5-named)
 * - every operation is wrapped in try/catch - it can never crash the app
 * - used by the data saver mode to check whether an image is already available offline
 * - [TeeInterceptor] fills the store as a by-product of regular image loading
 *
 * Additionally a standard OkHttp HTTP cache is installed by ImageHelper, so
 * thumbnails are still served without a network round-trip across app restarts.
 */
object PrimeImageStore {
    private const val MAX_CACHE_BYTES = 100L * 1024 * 1024
    private const val MAX_TEE_BYTES = 5L * 1024 * 1024

    /**
     * Single background thread for all file operations - no locking needed anywhere.
     */
    private val diskExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PrimeImageStore")
    }

    fun cacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, "prime_images")

    /**
     * Deterministic, collision-resistant file name for an image URL.
     */
    private fun fileFor(context: Context, url: String): File {
        val name = runCatching {
            val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            digest.joinToString(separator = "") { "%02x".format(it) }
        }.getOrDefault(Integer.toHexString(url.hashCode()))
        return File(cacheDir(context), "$name.img")
    }

    /**
     * Whether the image for [url] has already been saved to disk. Never throws.
     */
    fun isCached(context: Context, url: String): Boolean {
        return runCatching { fileFor(context, url).isFile }.getOrDefault(false)
    }

    /**
     * Persist [bytes] for [url] on the background thread. Never throws.
     */
    fun store(context: Context, url: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        diskExecutor.execute {
            runCatching {
                val target = fileFor(context, url)
                if (target.isFile) return@runCatching
                val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }
    }

    /**
     * Trim the store on a background thread so it never grows unboundedly.
     */
    fun pruneAsync(context: Context) {
        diskExecutor.execute {
            runCatching { prune(context.applicationContext, MAX_CACHE_BYTES) }
        }
    }

    private fun prune(context: Context, maxBytes: Long) {
        val files = cacheDir(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        // delete the oldest files first until below the cap
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    /**
     * OkHttp interceptor that saves image responses into the store while they pass by.
     *
     * Uses [Interceptor.Chain.peekBody] so the response body itself is never consumed -
     * coil keeps receiving the untouched response.
     */
    class TeeInterceptor(context: Context) : Interceptor {
        private val appContext = context.applicationContext

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())

            runCatching {
                val request = chain.request()
                if (request.method != "GET") return@runCatching

                val url = request.url.toString()
                if (!url.startsWith("http")) return@runCatching

                val contentType = response.header("Content-Type") ?: return@runCatching
                if (!contentType.startsWith("image/")) return@runCatching

                val length = response.body?.contentLength() ?: return@runCatching
                if (length <= 0L || length > MAX_TEE_BYTES) return@runCatching

                val peeked = chain.peekBody(length)
                val bytes = peeked.bytes()
                if (bytes.isNotEmpty()) store(appContext, url, bytes)
            }

            return response
        }
    }
}
