package com.lite.streamview.server

import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore
import com.lite.streamview.util.AppLogger
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * Embedded NanoHTTPD server bound to all local interfaces (0.0.0.0).
 * Exposes lightweight REST endpoints to control WebView and extract HLS streams.
 */
class LiteHttpServer(
    val port: Int = LOCAL_SERVER_PORT,
    private val hlsUrlStore: HlsUrlStore,
    private val onNavigateRequested: (String) -> Unit,
    private val onLiteModeChanged: ((Boolean) -> Unit)? = null
) : NanoHTTPD(port) {

    companion object {
        /**
         * Default port for the local Android HTTP server.
         * Configured in one central place.
         */
        const val LOCAL_SERVER_PORT = 7777
    }

    @Volatile
    var pinggyUrl: String? = null

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var isLiteMode: Boolean = true

    override fun serve(session: IHTTPSession): Response {
        // Handle CORS Preflight request from web browsers
        if (session.method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
            resp.addHeader("Access-Control-Allow-Headers", "*")
            resp.addHeader("Access-Control-Max-Age", "86400")
            return resp
        }

        val uri = session.uri ?: "/"
        @Suppress("DEPRECATION")
        val params = session.parms

        try {
            // Endpoint 1: /extract?url=...
            if (uri == "/extract" || uri.startsWith("/extract")) {
                val targetUrl = extractTargetUrl(session)
                val timeoutSec = params["timeout"]?.toLongOrNull() ?: 5L
                AppLogger.log("HTTP", "GET /extract url=${targetUrl ?: "active"} timeout=$timeoutSec")

                if (!targetUrl.isNullOrBlank()) {
                    val validated = sanitizeUrl(targetUrl)
                    if (validated != null) {
                        // If same URL is requested and we already have the first HLS stream, return immediately
                        if (validated == currentUrl && hlsUrlStore.hasHls()) {
                            val existing = hlsUrlStore.getLatestHls()
                            if (existing != null) {
                                val json = JSONObject().apply {
                                    put("success", true)
                                    put("type", "hls")
                                    put("url", existing.url)
                                    put("contentType", existing.contentType)
                                    val hObj = createHeadersJson(existing)
                                    put("headers", hObj ?: JSONObject.NULL)
                                }
                                AppLogger.log("HTTP", "Cached HLS returned for: $validated")
                                return createJsonResponse(Response.Status.OK, json.toString())
                            }
                        }

                        currentUrl = validated
                        val waitLatch = hlsUrlStore.prepareForNewStream()
                        onNavigateRequested(validated)
                        val stream = hlsUrlStore.waitForHls(timeoutSec, waitLatch)

                        val json = JSONObject()
                        if (stream != null) {
                            json.put("success", true)
                            json.put("type", "hls")
                            json.put("url", stream.url)
                            json.put("contentType", stream.contentType)
                            val hObj = createHeadersJson(stream)
                            json.put("headers", hObj ?: JSONObject.NULL)
                            AppLogger.log("HTTP", "HLS Extracted: ${stream.url}")
                        } else {
                            json.put("success", false)
                            json.put("url", JSONObject.NULL)
                            json.put("headers", JSONObject.NULL)
                            json.put("error", "HLS stream not detected")
                            AppLogger.log("HTTP", "HLS extraction timed out for: $validated")
                        }
                        return createJsonResponse(Response.Status.OK, json.toString())
                    } else {
                        val json = JSONObject().apply {
                            put("success", false)
                            put("error", "Invalid destination URL. Only http:// and https:// URLs are allowed.")
                        }
                        AppLogger.log("HTTP", "Invalid URL rejected: $targetUrl")
                        return createJsonResponse(Response.Status.BAD_REQUEST, json.toString())
                    }
                } else {
                    // No new URL supplied, check existing or wait on active stream
                    val stream = hlsUrlStore.waitForHls(timeoutSec)
                    val json = JSONObject()
                    if (stream != null) {
                        json.put("success", true)
                        json.put("type", "hls")
                        json.put("url", stream.url)
                        json.put("contentType", stream.contentType)
                        val hObj = createHeadersJson(stream)
                        json.put("headers", hObj ?: JSONObject.NULL)
                    } else {
                        json.put("success", false)
                        json.put("url", JSONObject.NULL)
                        json.put("headers", JSONObject.NULL)
                        json.put("error", "HLS stream not detected")
                    }
                    return createJsonResponse(Response.Status.OK, json.toString())
                }
            }

            // Endpoint 2: /status
            if (uri == "/status") {
                val latestHls = hlsUrlStore.getLatestHls()
                val json = JSONObject().apply {
                    put("success", true)
                    put("status", "running")
                    put("host", "0.0.0.0")
                    put("port", port)
                    put("liteMode", isLiteMode)
                    put("currentUrl", currentUrl ?: JSONObject.NULL)
                    put("hlsDetected", latestHls != null)
                    put("hlsUrl", latestHls?.url ?: JSONObject.NULL)
                    put("pinggyUrl", pinggyUrl ?: JSONObject.NULL)
                    val hObj = createHeadersJson(latestHls)
                    put("headers", hObj ?: JSONObject.NULL)
                }
                return createJsonResponse(Response.Status.OK, json.toString())
            }

            // Endpoint 3: /mode?lite=on|off or /lite?enabled=true|false
            if (uri == "/mode" || uri == "/lite") {
                val liteParam = params["lite"] ?: params["enabled"]
                if (liteParam != null) {
                    val enabled = liteParam.equals("on", true) || liteParam.equals("true", true) || liteParam == "1"
                    isLiteMode = enabled
                    onLiteModeChanged?.invoke(enabled)
                    AppLogger.log("MODE", "Lite Mode set to $enabled")
                    val json = JSONObject().apply {
                        put("success", true)
                        put("liteMode", enabled)
                    }
                    return createJsonResponse(Response.Status.OK, json.toString())
                }
            }

            // Endpoint 4: /?url=... or /url=...
            val requestedUrl = extractTargetUrl(session)
            if (!requestedUrl.isNullOrBlank()) {
                val valid = sanitizeUrl(requestedUrl)
                return if (valid != null) {
                    currentUrl = valid
                    hlsUrlStore.prepareForNewStream()
                    onNavigateRequested(valid)
                    AppLogger.log("HTTP", "Navigating to: $valid")

                    val json = JSONObject().apply {
                        put("success", true)
                        put("url", valid)
                    }
                    createJsonResponse(Response.Status.OK, json.toString())
                } else {
                    val json = JSONObject().apply {
                        put("success", false)
                        put("error", "Invalid destination URL. Only http:// and https:// URLs are allowed.")
                    }
                    createJsonResponse(Response.Status.BAD_REQUEST, json.toString())
                }
            }

            // Root info
            val defaultJson = JSONObject().apply {
                put("app", "LiteWebView")
                put("status", "running")
                put("endpoints", listOf(
                    "GET /?url=https://example.com",
                    "GET /extract?url=https://example.com",
                    "GET /status",
                    "GET /mode?lite=on|off"
                ))
            }
            return createJsonResponse(Response.Status.OK, defaultJson.toString())

        } catch (e: Exception) {
            val errorJson = JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Internal server error")
            }
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson.toString())
        }
    }

    fun extractTargetUrl(session: IHTTPSession): String? {
        @Suppress("DEPRECATION")
        val params = session.parms
        val uri = session.uri ?: ""

        // 1. Path format: /url=https://...
        if (uri.startsWith("/url=")) {
            val raw = uri.substring(5)
            val query = session.queryParameterString
            val full = if (!query.isNullOrBlank()) "$raw?$query" else raw
            return decodeSafely(full).trim()
        }

        // 2. Query string parsing: check for url= prefix in query parameter string
        val query = session.queryParameterString
        if (!query.isNullOrBlank()) {
            val prefix = "url="
            val index = query.indexOf(prefix)
            if (index != -1) {
                var raw = query.substring(index + prefix.length)
                // Remove trailing parameters like &timeout= if present
                val ampIndex = raw.indexOf("&timeout=")
                if (ampIndex != -1) {
                    raw = raw.substring(0, ampIndex)
                }
                val decoded = decodeSafely(raw).trim()
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        }

        // 3. Fallback to parsed parameter map
        params["url"]?.let {
            if (it.isNotBlank()) return it.trim()
        }

        return null
    }

    fun createHeadersJson(stream: HlsStream?): JSONObject? {
        if (stream == null) return null
        val obj = JSONObject()
        if (!stream.origin.isNullOrBlank()) {
            obj.put("Origin", stream.origin)
        }
        if (!stream.referer.isNullOrBlank()) {
            obj.put("Referer", stream.referer)
        }
        return if (obj.length() > 0) obj else null
    }

    fun sanitizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return try {
            val uri = URI(trimmed)
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) trimmed else null
        } catch (_: Exception) {
            try {
                val url = URL(trimmed)
                if (url.protocol.equals("http", true) || url.protocol.equals("https", true)) trimmed else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun decodeSafely(input: String): String {
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    private fun createJsonResponse(status: Response.Status, body: String): Response {
        val resp = newFixedLengthResponse(status, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        resp.addHeader("Access-Control-Allow-Headers", "*")
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return resp
    }
}
