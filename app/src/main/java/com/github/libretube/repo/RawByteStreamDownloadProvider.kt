package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.IOException
import okio.source
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download from RAW HTTP stream.
 */
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        val startByteOffset = item.path.fileSize()
        // PrimeTube: HTTP 403 must be reported separately - the URL expired and
        // only a fresh stream-info fetch can heal it (plain retries never will)
        val connection = startConnection(url, startByteOffset, item.downloadSize)
        val responseBody = connection.body
            ?: return if (connection.urlExpired) {
                DownloadProgressResult.UrlExpired
            } else {
                DownloadProgressResult.Failed
            }

        val sourceByte = responseBody.byteStream().source()

        var totalRead = 0L
        var lastRead = 0L
        // Check if downloading is still active and read next bytes.
        while (sourceByte
                .read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE)
                .also { lastRead = it } != -1L
        ) {
            sink.emit()
            totalRead += lastRead
        }

        withContext(Dispatchers.IO) {
            sourceByte.close()
            responseBody.close()
        }

        return if (startByteOffset + totalRead < item.downloadSize) {
            DownloadProgressResult.Progressed(totalRead)
        } else {
            DownloadProgressResult.DownloadComplete
        }
    }

    /** PrimeTube: connection attempt outcome - body or the reason it failed. */
    private class ConnectionResult(val body: ResponseBody?, val urlExpired: Boolean = false)

    private suspend fun startConnection(
        url: HttpUrl,
        alreadyRead: Long,
        readLimit: Long?
    ): ConnectionResult {
        val limit = readLimit?.takeIf { it > 0 }?.let {
            min(readLimit, alreadyRead + BYTES_PER_REQUEST)
        }?.toString().orEmpty()

        val request = Request.Builder()
            .url(url)
            .method("GET", null)
            .header("Range", "bytes=$alreadyRead-$limit")
            .build()

        return withContext(Dispatchers.IO) {
            // Retry connecting to server for n times.
            try {
                val call = httpClient.newCall(request)
                val response = call.execute()

                if (response.code == 403) {
                    val errorBody = response.body.string()
                    response.close()
                    Log.e(TAG(), "Got HTTP 403 while downloading: $errorBody")
                    // PrimeTube: signal the expired-URL state to the service
                    return@withContext ConnectionResult(body = null, urlExpired = true)
                } else if (response.code !in 200..299) {
                    response.close()
                    return@withContext ConnectionResult(body = null) // TODO: print response.message
                }

                return@withContext ConnectionResult(body = response.body)
            } catch (e: IOException) {
                Log.e(this.javaClass.name, e.printStackTrace().toString())
                // TODO: forward error message

                return@withContext ConnectionResult(body = null)
            }
        }
    }

    companion object {
        // maximum working tested chunk size is 3MB, the 512MB value here is from NewPipe
        private const val BYTES_PER_REQUEST = 512 * 1024L
    }
}