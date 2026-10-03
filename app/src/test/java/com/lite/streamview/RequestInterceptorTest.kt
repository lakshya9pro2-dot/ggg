package com.lite.streamview

import com.lite.streamview.interceptor.RequestInterceptor
import com.lite.streamview.store.HlsUrlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RequestInterceptorTest {

    private lateinit var store: HlsUrlStore
    private lateinit var interceptor: RequestInterceptor

    @Before
    fun setUp() {
        store = HlsUrlStore()
        interceptor = RequestInterceptor(store, isLiteModeEnabled = true)
    }

    @Test
    fun testHlsResourceDetection() {
        // Standard .m3u8 extension
        assertTrue(interceptor.isHlsResource("https://stream.example.com/live/index.m3u8"))
        // .m3u8 with token query parameters
        assertTrue(interceptor.isHlsResource("https://stream.example.com/hls/master.m3u8?token=xyz123&expires=9999"))
        // MIME type header detection
        val headers = mapOf("Accept" to "application/vnd.apple.mpegurl")
        assertTrue(interceptor.isHlsResource("https://stream.example.com/playlist", headers))

        // Non-HLS
        assertFalse(interceptor.isHlsResource("https://example.com/index.html"))
        assertFalse(interceptor.isHlsResource("https://example.com/style.css"))
    }

    @Test
    fun testAdAndTrackerBlocking() {
        // Known ad domains / paths
        assertTrue(interceptor.isAdOrTracker("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(interceptor.isAdOrTracker("https://securepubads.g.doubleclick.net/gampad/ads"))
        assertTrue(interceptor.isAdOrTracker("https://www.google-analytics.com/analytics.js"))
        assertTrue(interceptor.isAdOrTracker("https://cdn.example.com/banner/popunder.js"))

        // Legitimate video / content URLs should not be blocked
        assertFalse(interceptor.isAdOrTracker("https://example.com/video/master.m3u8"))
        assertFalse(interceptor.isAdOrTracker("https://example.com/content/movie.mp4"))
        assertFalse(interceptor.isAdOrTracker("https://example.com/home"))
    }

    @Test
    fun testImageBlockingInLiteMode() {
        // Image files
        assertTrue(interceptor.isImageResource("https://example.com/hero.jpg"))
        assertTrue(interceptor.isImageResource("https://example.com/logo.png"))
        assertTrue(interceptor.isImageResource("https://example.com/thumb.webp?size=small"))
        assertTrue(interceptor.isImageResource("https://example.com/icon.svg"))

        // Accept header for image
        val imgHeader = mapOf("Accept" to "image/webp,image/png,image/*;q=0.8")
        assertTrue(interceptor.isImageResource("https://example.com/dynamic-avatar", imgHeader))

        // Never block scripts, styles, or video streams
        assertFalse(interceptor.isImageResource("https://example.com/bundle.js"))
        assertFalse(interceptor.isImageResource("https://example.com/style.css"))
        assertFalse(interceptor.isImageResource("https://example.com/video.mp4"))
        assertFalse(interceptor.isImageResource("https://example.com/live.m3u8"))
    }

    @Test
    fun testLiteModeToggle() {
        interceptor.isLiteModeEnabled = false
        assertFalse(interceptor.isLiteModeEnabled)

        interceptor.isLiteModeEnabled = true
        assertTrue(interceptor.isLiteModeEnabled)
    }

    @Test
    fun testExtractHostAndPath() {
        val url = "https://ads.example.com:8080/tracker/pixel.gif?id=123"
        assertEquals("ads.example.com", interceptor.extractHost(url))
        assertEquals("/tracker/pixel.gif", interceptor.extractPath(url))
    }
}
