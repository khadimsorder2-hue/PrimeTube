package com.github.libretube.helpers

import com.github.libretube.api.PipedMediaServiceRepository
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ProxyHelper {
    fun fetchProxyUrl() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                RetrofitInstance.externalApi.getInstanceConfig(PipedMediaServiceRepository.apiUrl)
                    .imageProxyUrl?.let {
                        PreferenceHelper.putString(PreferenceKeys.IMAGE_PROXY_URL, it)
                    }
            }
        }
    }

    /**
     * Decide whether the proxy should be used or not for a given stream URL based on user preferences
     */
    fun rewriteUrlUsingProxyPreference(url: String): String {
        return proxyRewriteUrl(url) ?: url
    }

    /**
     * PrimeTube: whether a proxy URL of the currently selected instance is known.
     */
    fun hasProxyUrl(): Boolean =
        PreferenceHelper.getString(PreferenceKeys.IMAGE_PROXY_URL, "").isNotBlank()

    /**
     * PrimeTube: rewrite every googlevideo URL contained inside a fetched manifest (DASH/HLS)
     * to use the proxy of the currently selected instance, so that playback also works when
     * the URLs are bound to the IP address of the instance server.
     * XML-escaped ampersands are handled so that escaped URLs keep working.
     */
    fun rewriteManifestUrls(manifest: String): String {
        val regex = Regex("https?://[A-Za-z0-9.\\-]*googlevideo\\.com[^\\s\"'<>]*")
        return regex.replace(manifest) { match ->
            val rawUrl = match.value
            val decodedUrl = rawUrl.replace("&amp;", "&")
            val rewritten = proxyRewriteUrl(decodedUrl) ?: return@replace rawUrl
            rewritten.replace("&", "&amp;")
        }
    }

    /**
     * Rewrite the URL to use the stored image proxy url of the selected instance.
     * Can handle both Piped links and normal YouTube links.
     */
    private fun proxyRewriteUrl(url: String?): String? {
        if (url == null) return null

        val proxyUrl = PreferenceHelper.getString(PreferenceKeys.IMAGE_PROXY_URL, "")
            .toHttpUrlOrNull()

        // parsedUrl should now be a plain YouTube URL without using any proxy
        val parsedUrl = unwrapUrl(url).toHttpUrlOrNull()
        if (proxyUrl == null || parsedUrl == null) return null

        return parsedUrl.newBuilder()
            .host(proxyUrl.host)
            .port(proxyUrl.port)
            .setQueryParameter("host", parsedUrl.host)
            .build()
            .toString()
    }

    /**
     * Convert a proxied Piped url to a YouTube url that's not proxied
     *
     * Should not be called directly in most cases, use [rewriteUrlUsingProxyPreference] instead
     */
    fun unwrapUrl(url: String): String {
        val parsedUrl = url.toHttpUrlOrNull() ?: return url

        val host = parsedUrl.queryParameter("host")
        // If the host is not set, the URL is probably already unwrapped
        if (host.isNullOrEmpty()) {
            return url
        }

        return parsedUrl.newBuilder()
            .host(host)
            .removeAllQueryParameters("host")
            .removeAllQueryParameters("qhash")
            .build()
            .toString()
    }
}
