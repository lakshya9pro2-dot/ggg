package com.lite.streamview

import com.lite.streamview.server.LiteHttpServer
import com.lite.streamview.store.HlsUrlStore
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.InputStream

class LiteHttpServerTest {

    private lateinit var store: HlsUrlStore
    private lateinit var server: LiteHttpServer

    @Before
    fun setUp() {
        store = HlsUrlStore()
        server = LiteHttpServer(
            port = 8089, // use non-standard test port
            hlsUrlStore = store,
            onNavigateRequested = {},
            onLiteModeChanged = {}
        )
    }

    @Test
    fun testSanitizeUrlValidHttp() {
        val url = "http://example.com/test"
        assertEquals(url, server.sanitizeUrl(url))
    }

    @Test
    fun testSanitizeUrlValidHttps() {
        val url = "https://example.com/video?stream=1"
        assertEquals(url, server.sanitizeUrl(url))
    }

    @Test
    fun testSanitizeUrlRejectsInvalidSchemes() {
        assertNull(server.sanitizeUrl("javascript:alert(1)"))
        assertNull(server.sanitizeUrl("file:///etc/passwd"))
        assertNull(server.sanitizeUrl("data:text/html,test"))
        assertNull(server.sanitizeUrl("ftp://example.com"))
        assertNull(server.sanitizeUrl("not-a-url"))
    }

    @Test
    fun testLiteModeProperty() {
        server.isLiteMode = true
        assertEquals(true, server.isLiteMode)

        server.isLiteMode = false
        assertEquals(false, server.isLiteMode)
    }

    @Test
    fun testExtractTargetUrlFromQueryParams() {
        val session = createMockSession(
            uri = "/",
            parms = mapOf("url" to "https://example.com/stream.m3u8"),
            queryString = "url=https://example.com/stream.m3u8"
        )
        val extracted = server.extractTargetUrl(session)
        assertEquals("https://example.com/stream.m3u8", extracted)
    }

    @Test
    fun testExtractTargetUrlFromRawQueryWithTimeout() {
        val session = createMockSession(
            uri = "/extract",
            parms = emptyMap(),
            queryString = "url=https://example.com/live/master.m3u8&timeout=10"
        )
        val extracted = server.extractTargetUrl(session)
        assertEquals("https://example.com/live/master.m3u8", extracted)
    }

    @Test
    fun testExtractTargetUrlFromPath() {
        val session = createMockSession(
            uri = "/url=https://example.com/video",
            parms = emptyMap(),
            queryString = null
        )
        val extracted = server.extractTargetUrl(session)
        assertEquals("https://example.com/video", extracted)
    }

    private fun createMockSession(
        uri: String,
        parms: Map<String, String>,
        queryString: String?
    ): NanoHTTPD.IHTTPSession {
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = emptyMap()
            override fun getInputStream(): InputStream? = null
            override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.GET
            override fun getParms(): Map<String, String> = parms
            override fun getQueryParameterString(): String? = queryString
            override fun getUri(): String = uri
            override fun parseBody(files: Map<String, String>?) {}
            override fun getRemoteIpAddress(): String = "127.0.0.1"
            override fun getRemoteHostName(): String = "localhost"
        }
    }
}
