package com.github.libretube.helpers

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * PrimeTube: a tiny shared tracker for ACTIVE downloads.
 *
 * Both download paths push their state into it:
 * - the internal offline downloader (DownloadService), and
 * - the "save to storage" downloader (StorageDownloadService).
 *
 * The Downloads page collects [downloads] and renders a live progress/queue
 * list on top of the finished downloads - regardless of which path is used.
 */
object PrimeDownloadTracker {

    enum class PrimeDownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

    data class PrimeActiveDownload(
        val key: String,
        val title: String,
        val state: PrimeDownloadState,
        val readBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val message: String? = null,
        val canCancel: Boolean = false,
        /** set once when the row reaches a terminal state (auto-cleanup) */
        val finishedAtMs: Long? = null
    )

    private val _downloads = MutableStateFlow<List<PrimeActiveDownload>>(emptyList())
    val downloads: StateFlow<List<PrimeActiveDownload>> = _downloads.asStateFlow()

    // PrimeTube: progress emissions are throttled per key - chunks can arrive
    // several times per second and the queue UI does not need every single one
    private val lastProgressEmitMs = ConcurrentHashMap<String, Long>()

    fun upsert(download: PrimeActiveDownload) {
        _downloads.update { list ->
            val index = list.indexOfFirst { it.key == download.key }
            if (index >= 0) {
                list.toMutableList().apply { this[index] = download }
            } else {
                list + download
            }
        }
    }

    fun updateProgress(key: String, readBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
        val now = System.currentTimeMillis()
        val last = lastProgressEmitMs[key] ?: 0L
        if (now - last < PROGRESS_EMIT_INTERVAL_MS) return
        lastProgressEmitMs[key] = now

        _downloads.update { list ->
            list.map {
                if (it.key == key && it.state == PrimeDownloadState.DOWNLOADING) {
                    it.copy(
                        readBytes = readBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec
                    )
                } else {
                    it
                }
            }
        }
    }

    fun remove(key: String) {
        lastProgressEmitMs.remove(key)
        _downloads.update { list -> list.filterNot { it.key == key } }
    }

    fun removeFinishedOlderThan(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        _downloads.update { list ->
            list.filterNot { (it.finishedAtMs ?: 0L) in 1 until cutoff }
        }
    }

    // ---------- cancel support (storage downloads) ----------

    private val cancelListeners = ConcurrentHashMap<String, () -> Unit>()

    fun setCancelListener(key: String, listener: (() -> Unit)?) {
        if (listener == null) cancelListeners.remove(key) else cancelListeners[key] = listener
    }

    /** Called by the UI. Removes the row and tells the owning service to stop. */
    fun requestCancel(key: String) {
        cancelListeners.remove(key)?.invoke()
        remove(key)
    }

    private const val PROGRESS_EMIT_INTERVAL_MS = 300L
}

/**
 * PrimeTube: smoothed download-speed estimator. Feed it the cumulative byte
 * count; it returns a stable bytes-per-second value (0 until enough data has
 * been observed).
 */
class PrimeSpeedCalculator {
    private var lastBytes = 0L
    private var lastTimeMs = 0L
    private var smoothedSpeed = 0L

    fun onBytes(cumulativeBytes: Long): Long {
        val now = System.currentTimeMillis()
        val deltaBytes = cumulativeBytes - lastBytes
        val deltaMs = (now - lastTimeMs).coerceAtLeast(0L)

        if (deltaMs >= SPEED_SAMPLE_INTERVAL_MS) {
            val instant = if (deltaMs > 0) deltaBytes * 1000 / deltaMs else 0L
            smoothedSpeed = if (instant > 0) {
                (smoothedSpeed * 4 + instant * 6) / 10
            } else {
                smoothedSpeed * 7 / 10
            }
            lastBytes = cumulativeBytes
            lastTimeMs = now
        }
        return smoothedSpeed
    }

    fun reset() {
        lastBytes = 0L
        lastTimeMs = 0L
        smoothedSpeed = 0L
    }

    companion object {
        private const val SPEED_SAMPLE_INTERVAL_MS = 500L
    }
}
