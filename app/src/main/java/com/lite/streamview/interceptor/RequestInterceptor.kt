package com.lite.streamview.interceptor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore
import com.lite.streamview.util.AppLogger
import java.io.ByteArrayInputStream
import java.net.URI

class RequestInterceptor(
    private val hlsUrlStore: HlsUrlStore,
    @Volatile var isLiteModeEnabled: Boolean = true
) {
    @Volatile var currentNavigationUrl: String? = null

    companion object {
        // 1x1 transparent PNG byte array (68 bytes) to return for blocked images
        val TRANSPARENT_PNG = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x42.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )

        val EMPTY_RESPONSE = ByteArray(0)

        // Ad and tracker pattern list
        val AD_KEYWORDS = listOf(
            "ads", "adserver", "doubleclick", "googlesyndication", "googleadservices",
            "analytics", "tracking", "tracker", "advert", "banner", "popunder",
            "scorecardresearch", "adnxs", "criteo", "taboola", "outbrain",
            "moatads", "zedo", "quantserve", "rubiconproject", "adsystem"
        )

        // Image file extensions
        val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp", ".ico", ".tiff"
        )
    }

    /**
     * Check if a request URL or header indicates an HLS stream.
     */
    fun isHlsResource(urlStr: String, headers: Map<String, String>? = null): Boolean {
        val lower = urlStr.lowercase()
        if (lower.contains(".m3u8")) {
            return true
        }
        headers?.let {
            val accept = it["Accept"]?.lowercase() ?: ""
            if (accept.contains("application/vnd.apple.mpegurl") || accept.contains("application/x-mpegurl")) {
                return true
            }
        }
        return false
    }

    /**
     * Check if URL host or path matches known ad/tracking patterns.
     */
    fun isAdOrTracker(urlStr: String): Boolean {
        // Essential media streams should never be blocked as ads
        if (isVideoOrStreamResource(urlStr)) {
            return false
        }

        val host = extractHost(urlStr)
        val path = extractPath(urlStr)

        for (keyword in AD_KEYWORDS) {
            if (host.contains(keyword) || path.contains(keyword)) {
                return true
            }
        }
        return false
    }

    /**
     * Check if resource is an image that should be blocked in Lite Mode.
     */
    fun isImageResource(urlStr: String, headers: Map<String, String>? = null): Boolean {
        // Never block video, audio, or crucial web code
        if (isVideoOrStreamResource(urlStr) || isEssentialResource(urlStr)) {
            return false
        }

        val path = extractPath(urlStr)
        if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) {
            return true
        }

        headers?.let {
            val accept = it["Accept"]?.lowercase() ?: ""
            if (accept.startsWith("image/") && !accept.contains("text/html")) {
                return true
            }
        }
        return false
    }

    fun isVideoOrStreamResource(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return lower.contains(".m3u8") || lower.contains(".ts") ||
                lower.contains(".mp4") || lower.contains(".webm") ||
                lower.contains(".m4s") || lower.contains(".mpd")
    }

    fun isEssentialResource(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return lower.endsWith(".js") || lower.endsWith(".css") ||
                lower.contains("/api/") || lower.endsWith(".json")
    }

    fun extractHost(urlStr: String): String {
        return try {
            val noScheme = if (urlStr.contains("://")) urlStr.substringAfter("://") else urlStr
            noScheme.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    fun extractPath(urlStr: String): String {
        return try {
            val noScheme = if (urlStr.contains("://")) urlStr.substringAfter("://") else urlStr
            val pathPart = if (noScheme.contains('/')) noScheme.substringAfter('/') else ""
            ("/" + pathPart.substringBefore('?')).lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Extracts Origin and Referer headers from WebResourceRequest headers,
     * falling back to the current navigation URL if headers are omitted by WebView.
     */
    fun extractOriginAndReferer(
        headers: Map<String, String>?,
        fallbackUrl: String? = currentNavigationUrl
    ): Pair<String, String> {
        var origin = ""
        var referer = ""

        if (headers != null) {
            for ((key, value) in headers) {
                if (key.equals("Origin", ignoreCase = true)) {
                    origin = value.trim()
                } else if (key.equals("Referer", ignoreCase = true)) {
                    referer = value.trim()
                }
            }
        }

        // Fallback to active navigation URL for Referer
        if (referer.isBlank() && !fallbackUrl.isNullOrBlank()) {
            referer = fallbackUrl.trim()
        }

        // Fallback to origin derived from Referer
        if (origin.isBlank() && referer.isNotBlank()) {
            try {
                val uri = URI(referer)
                if (uri.scheme != null && uri.host != null) {
                    origin = "${uri.scheme}://${uri.host}"
                    if (uri.port != -1 && uri.port != 80 && uri.port != 443) {
                        origin += ":${uri.port}"
                    }
                }
            } catch (_: Exception) {}
        }

        return Pair(origin, referer)
    }

    /**
     * Inspects a WebResourceRequest.
     * Returns null if allowed, or a custom WebResourceResponse if blocked/filtered.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val urlStr = request.url?.toString() ?: return null

        // 1. Detect HLS (First HLS only)
        if (isHlsResource(urlStr, request.requestHeaders)) {
            if (!hlsUrlStore.hasHls()) {
                val (origin, referer) = extractOriginAndReferer(request.requestHeaders)
                val stream = HlsStream(
                    url = urlStr,
                    contentType = "application/vnd.apple.mpegurl",
                    origin = origin.ifBlank { null },
                    referer = referer.ifBlank { null }
                )
                hlsUrlStore.setLatestHls(stream)
                AppLogger.log("HLS", "Stream found: $urlStr (Origin: $origin, Referer: $referer)")
            }
            // Allow stream to proceed to the HTML5 video player
            return null
        }

        // 2. Block Ads and Trackers
        if (isAdOrTracker(urlStr)) {
            AppLogger.log("AD_BLOCK", "Blocked: $urlStr")
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(EMPTY_RESPONSE)
            )
        }

        // 3. Block Images in Lite Mode
        if (isLiteModeEnabled && isImageResource(urlStr, request.requestHeaders)) {
            return WebResourceResponse(
                "image/png",
                "UTF-8",
                ByteArrayInputStream(TRANSPARENT_PNG)
            )
        }

        return null
    }
}
