package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.runSafely
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val hideWatched
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.HIDE_WATCHED_FROM_FEED,
            false
        )
    private val showUpcoming
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SHOW_UPCOMING_IN_FEED,
            true
        )

    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val recommended: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    private var loadHomeJob: Job? = null
    private var recommendedCache: List<StreamItem>? = null

    /**
     * PrimeTube: YouTube-app-like home loading.
     *
     * The home feed is built from the user's usage, like on YouTube:
     * - [feed]: latest videos of subscribed channels
     * - [recommended]: videos related to what the user watched recently (usage-based
     *   personalization) - related videos of the most recent watch history entries are
     *   fetched in parallel and mixed together
     * - [continueWatching]: videos from the watch history that have not been finished
     */
    fun loadHomeFeed(
        subscriptionsViewModel: SubscriptionsViewModel,
        forceRefresh: Boolean,
        onUnusualLoadTime: () -> Unit
    ) {
        isLoading.value = true

        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val result = async {
                awaitAll(
                    async { loadFeed(subscriptionsViewModel) },
                    async {
                        // recommendations are expensive (several network calls), so they are
                        // cached in memory and only re-fetched when explicitly refreshed
                        if (forceRefresh || recommendedCache == null) {
                            loadRecommended()
                        } else {
                            recommended.updateIfChanged(recommendedCache.orEmpty())
                        }
                    },
                    async { loadVideosToContinueWatching() }
                )
                loadedSuccessfully.value = listOf(feed, recommended, continueWatching)
                    .any { !it.value.isNullOrEmpty() }
                isLoading.value = false
            }

            withContext(Dispatchers.IO) {
                delay(UNUSUAL_LOAD_TIME_MS)
                if (result.isActive) {
                    onUnusualLoadTime.invoke()
                }
            }
        }
    }

    /**
     * Cheap re-load of the continue-watching shelf, e.g. when returning to the home
     * fragment after watching a video.
     */
    fun refreshContinueWatching() {
        viewModelScope.launch { loadVideosToContinueWatching() }
    }

    private suspend fun loadFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        runSafely(
            onSuccess = { videos -> feed.updateIfChanged(videos) },
            ioBlock = { tryLoadFeed(subscriptionsViewModel) }
        )
    }

    private suspend fun loadRecommended() {
        val videos = withContext(Dispatchers.IO) {
            runCatching { fetchPersonalizedRecommendations() }
                .onFailure { it.printStackTrace() }
                .getOrDefault(emptyList())
        }
        recommendedCache = videos
        recommended.updateIfChanged(videos)
    }

    /**
     * Usage-based recommendations: take the most recent videos from the watch history and
     * mix together the related videos of each of them, round-robin per seed video so that
     * the result is a diverse, personalized feed.
     */
    private suspend fun fetchPersonalizedRecommendations(): List<StreamItem> {
        if (!PlayerHelper.watchHistoryEnabled) return emptyList()

        val history = DatabaseHelper.getWatchHistoryPage(1, HISTORY_SEED_PAGE_SIZE)
        val watchedIds = history.map { it.videoId }.toHashSet()

        val seedIds = history.map { it.videoId }.distinct().take(RECOMMENDATION_SEEDS)
        if (seedIds.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
        val relatedLists = coroutineScope {
            seedIds.map { videoId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runCatching {
                            MediaServiceRepository.instance.getStreams(videoId).relatedStreams
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        // round-robin over the related videos of every seed so the feed stays mixed
        val seen = mutableSetOf<String>()
        val result = mutableListOf<StreamItem>()
        val iterators = relatedLists.map { it.asSequence().iterator() }

        while (result.size < MAX_RECOMMENDATIONS) {
            var progressed = false
            for (iterator in iterators) {
                while (iterator.hasNext()) {
                    val item = iterator.next() ?: continue
                    val videoId = item.url?.toID() ?: continue
                    // don't recommend shorts/upcoming videos or videos the user just watched
                    if (videoId in seen || videoId in watchedIds) continue
                    if (item.isShort || item.isUpcoming) continue
                    seen += videoId
                    result += item
                    progressed = true
                    break
                }
                if (result.size >= MAX_RECOMMENDATIONS) break
            }
            if (!progressed) break
        }

        return result
    }

    private suspend fun loadVideosToContinueWatching() {
        if (!PlayerHelper.watchHistoryEnabled) return
        runSafely(
            onSuccess = { videos -> continueWatching.updateIfChanged(videos) },
            ioBlock = ::loadWatchingFromDB
        )
    }

    private suspend fun loadWatchingFromDB(): List<StreamItem> {
        val videos = DatabaseHelper.getWatchHistoryPage(1, 20)

        return DatabaseHelper
            .filterUnwatched(videos.map { it.toStreamItem() })
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        // use cached feed if available, otherwise load feed from API/database
        val feed = subscriptionsViewModel.videoFeed.value ?: run {
            SubscriptionHelper.getFeed(forceRefresh = false).also {
                subscriptionsViewModel.videoFeed.postValue(it)
            }
        }

        return DatabaseHelper.filterByStreamTypeAndWatchPosition(feed, hideWatched, showUpcoming)
    }

    companion object {
        private const val UNUSUAL_LOAD_TIME_MS = 10000L
        private const val HISTORY_SEED_PAGE_SIZE = 20
        private const val RECOMMENDATION_SEEDS = 8
        private const val MAX_CONCURRENT_FETCHES = 4
        private const val MAX_RECOMMENDATIONS = 40
    }
}
