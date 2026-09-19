package com.github.libretube.ui.views

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * PrimeTube: crash-proof LinearLayoutManager.
 *
 * Fixes "Inconsistency detected. Invalid view holder adapter position"
 * (java.lang.IndexOutOfBoundsException) that happens when the backing data of a
 * RecyclerView is mutated from outside while the RecyclerView is laying out or
 * prefetching view holders (GapWorker). This is exactly what happened with the
 * playing queue: the queue is updated by the player service in the background
 * (next video, source errors, ...), while the queue sheet RecyclerView kept
 * prefetching positions of the old list.
 *
 * - prefetching is disabled so GapWorker never binds positions ahead
 * - onLayoutChildren/onLayoutCompleted swallow IndexOutOfBoundsException as a
 *   last-resort safety net instead of crashing the whole app
 */
class SafeLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    init {
        isItemPrefetchEnabled = false
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State) {
        try {
            super.onLayoutCompleted(state)
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        }
    }

    override fun scrollVerticallyBy(
        dx: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int = try {
        super.scrollVerticallyBy(dx, recycler, state)
    } catch (e: IndexOutOfBoundsException) {
        e.printStackTrace()
        0
    }
}
