package com.github.libretube.repo

import com.github.libretube.db.obj.DownloadItem
import okio.BufferedSink



sealed class DownloadProgressResult {
    /**
     * Failed to download an additional chunk of data.
     */
    object Failed: DownloadProgressResult()

    /**
     * PrimeTube: the server answered HTTP 403 - the stream URL expired.
     * Retrying the same URL can never succeed; fresh stream info must be
     * fetched and the provider rebuilt (the file keeps its byte offset).
     */
    object UrlExpired: DownloadProgressResult()

    /**
     * Successfully downloaded an additional chunk of data of size [bytes].
     */
    class Progressed(val bytes: Long): DownloadProgressResult()

    /**
     * Full [DownloadItem] is downloaded, the download can be stopped.
     */
    object DownloadComplete: DownloadProgressResult()
}

interface DownloadProvider {
    /**
     * Start or continue downloading from `byteStartPosition`.
     */
    suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult
}