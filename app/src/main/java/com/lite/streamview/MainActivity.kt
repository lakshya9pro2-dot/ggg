package com.lite.streamview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lite.streamview.interceptor.RequestInterceptor
import com.lite.streamview.server.LiteHttpServer
import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore
import com.lite.streamview.tunnel.PinggyManager
import com.lite.streamview.tunnel.TunnelInfo
import com.lite.streamview.tunnel.TunnelState
import com.lite.streamview.util.AppLogger
import fi.iki.elonen.NanoHTTPD

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnOpen: Button
    private lateinit var tvHlsStatus: TextView
    private lateinit var btnCopyHls: Button
    private lateinit var tvServerStatus: TextView
    private lateinit var btnToggleHost: Button
    private lateinit var btnCopyServer: Button
    private lateinit var switchLiteMode: SwitchMaterial
    private lateinit var progressBar: ProgressBar
    private lateinit var panelBody: View
    private lateinit var btnTogglePanel: TextView
    private lateinit var btnToggleView: TextView
    private lateinit var logContainer: View
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogs: TextView
    private lateinit var btnClearLogs: TextView
    private lateinit var btnCopyLogs: TextView
    private lateinit var fullscreenContainer: FrameLayout

    // Pinggy Tunnel Views
    private lateinit var tvPinggyStatus: TextView
    private lateinit var tvPublicUrl: TextView
    private lateinit var btnCopyPinggyUrl: Button
    private lateinit var btnRenewPinggy: Button
    private lateinit var btnSendApi: Button
    private lateinit var btnToggleLogs: TextView
    private lateinit var tvPinggyLocalServer: TextView
    private lateinit var tvPinggyExpires: TextView
    private var isLogsActive: Boolean = true

    private val hlsUrlStore = HlsUrlStore()
    private lateinit var requestInterceptor: RequestInterceptor
    private var httpServer: LiteHttpServer? = null
    private lateinit var pinggyManager: PinggyManager

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var chromeClient: WebChromeClient

    // Toggle between LAN IP address and Localhost (127.0.0.1)
    private var useIpHost: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initLogger()
        initInterceptor()
        initWebView()
        initServer()
        initPinggy()
        setupListeners()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnOpen = findViewById(R.id.btnOpen)
        tvHlsStatus = findViewById(R.id.tvHlsStatus)
        btnCopyHls = findViewById(R.id.btnCopyHls)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        btnToggleHost = findViewById(R.id.btnToggleHost)
        btnCopyServer = findViewById(R.id.btnCopyServer)
        switchLiteMode = findViewById(R.id.switchLiteMode)
        progressBar = findViewById(R.id.progressBar)
        panelBody = findViewById(R.id.panelBody)
        btnTogglePanel = findViewById(R.id.btnTogglePanel)
        btnToggleView = findViewById(R.id.btnToggleView)
        logContainer = findViewById(R.id.logContainer)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogs = findViewById(R.id.tvLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        tvPinggyStatus = findViewById(R.id.tvPinggyStatus)
        tvPublicUrl = findViewById(R.id.tvPublicUrl)
        btnCopyPinggyUrl = findViewById(R.id.btnCopyPinggyUrl)
        btnRenewPinggy = findViewById(R.id.btnRenewPinggy)
        btnSendApi = findViewById(R.id.btnSendApi)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        tvPinggyLocalServer = findViewById(R.id.tvPinggyLocalServer)
        tvPinggyExpires = findViewById(R.id.tvPinggyExpires)
    }

    private fun initLogger() {
        tvLogs.text = AppLogger.getHistory()
        AppLogger.setListener { line ->
            if (!isLogsActive && line != "__CLEAR__") {
                return@setListener
            }
            runOnUiThread {
                if (line == "__CLEAR__") {
                    tvLogs.text = ""
                } else {
                    tvLogs.append(line + "\n")
                    scrollLogs.post {
                        scrollLogs.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
        AppLogger.log("SYSTEM", "LiteWebView started on Android (API ${android.os.Build.VERSION.SDK_INT})")
    }

    private fun initInterceptor() {
        requestInterceptor = RequestInterceptor(
            hlsUrlStore = hlsUrlStore,
            isLiteModeEnabled = switchLiteMode.isChecked
        )

        hlsUrlStore.setListener { stream ->
            runOnUiThread {
                updateHlsUI(stream)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = !requestInterceptor.isLiteModeEnabled

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val intercepted = requestInterceptor.intercept(request)
                    if (intercepted != null) {
                        return intercepted
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }

        chromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                logContainer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                customView = null
                fullscreenContainer.visibility = View.GONE
                // Restore previous active view
                if (btnToggleView.text.toString().contains("WebView")) {
                    logContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                } else {
                    webView.visibility = View.VISIBLE
                    logContainer.visibility = View.GONE
                }
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        webView.webChromeClient = chromeClient
    }

    private fun initServer() {
        try {
            if (httpServer != null && httpServer!!.isAlive) {
                return
            }
            val server = LiteHttpServer(
                port = LiteHttpServer.LOCAL_SERVER_PORT,
                hlsUrlStore = hlsUrlStore,
                onNavigateRequested = { url ->
                    runOnUiThread {
                        loadUrl(url, resetStore = false)
                    }
                },
                onLiteModeChanged = { enabled ->
                    runOnUiThread {
                        switchLiteMode.isChecked = enabled
                        applyLiteMode(enabled)
                    }
                }
            )
            server.isLiteMode = switchLiteMode.isChecked
            if (::pinggyManager.isInitialized) {
                server.pinggyUrl = pinggyManager.currentUrl
            }
            // Start NanoHTTPD with non-daemon thread for stable 24/7 background operation
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpServer = server
            updateServerDisplay()
            AppLogger.log("SERVER", "NanoHTTPD listening on :${LiteHttpServer.LOCAL_SERVER_PORT} (0.0.0.0)")
        } catch (e: Exception) {
            tvServerStatus.text = "● Error: ${e.message ?: "Failed to start"}"
            tvServerStatus.setTextColor(Color.parseColor("#C62828"))
            AppLogger.log("SERVER", "Start error: ${e.message}")
        }
    }

    private fun initPinggy() {
        pinggyManager = PinggyManager(this)
        pinggyManager.onTunnelStateChanged = { info ->
            runOnUiThread {
                updatePinggyUI(info)
                httpServer?.pinggyUrl = info.publicUrl
            }
        }
        pinggyManager.start()
    }

    private fun updatePinggyUI(info: TunnelInfo) {
        tvPinggyStatus.text = "Status: ${info.state.displayName}"
        val statusColor = when (info.state) {
            TunnelState.CONNECTED -> Color.parseColor("#2E7D32")
            TunnelState.STARTING, TunnelState.CONNECTING -> Color.parseColor("#F57C00")
            TunnelState.RENEWING -> Color.parseColor("#1976D2")
            TunnelState.DISCONNECTED, TunnelState.ERROR -> Color.parseColor("#C62828")
        }
        tvPinggyStatus.setTextColor(statusColor)

        if (!info.publicUrl.isNullOrBlank()) {
            tvPublicUrl.text = info.publicUrl
            tvPublicUrl.setTextColor(Color.parseColor("#1976D2"))
            btnCopyPinggyUrl.visibility = View.VISIBLE
        } else {
            val statusMsg = info.message ?: "Pending..."
            tvPublicUrl.text = "Public URL: $statusMsg"
            tvPublicUrl.setTextColor(Color.parseColor("#757575"))
            btnCopyPinggyUrl.visibility = View.GONE
        }

        tvPinggyLocalServer.text = "Local server: ${info.localServerUrl}"

        if (info.remainingMinutes != null) {
            tvPinggyExpires.text = "Expires: ~${info.remainingMinutes} minutes"
        } else {
            tvPinggyExpires.text = "Expires: --"
        }
    }

    private fun getActiveHostUrl(): String {
        val port = LiteHttpServer.LOCAL_SERVER_PORT
        val ip = getLocalIpAddress()
        return if (useIpHost && ip != null) {
            "http://$ip:$port"
        } else {
            "http://127.0.0.1:$port"
        }
    }

    private fun updateServerDisplay() {
        val activeUrl = getActiveHostUrl()
        val ip = getLocalIpAddress()
        val isIpActive = useIpHost && ip != null
        btnToggleHost.text = if (isIpActive) "Local" else "IP"
        tvServerStatus.text = "● $activeUrl"
        tvServerStatus.setTextColor(Color.parseColor("#2E7D32"))
    }

    private fun setupListeners() {
        btnOpen.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
                loadUrl(formatted, resetStore = true)
            }
        }

        btnTogglePanel.setOnClickListener {
            if (panelBody.visibility == View.VISIBLE) {
                panelBody.visibility = View.GONE
                btnTogglePanel.text = "[ Show Panel ]"
            } else {
                panelBody.visibility = View.VISIBLE
                btnTogglePanel.text = "[ Hide Panel ]"
            }
        }

        // Toggle between Live Server Logs and WebView
        btnToggleView.setOnClickListener {
            if (webView.visibility == View.VISIBLE) {
                webView.visibility = View.GONE
                logContainer.visibility = View.VISIBLE
                btnToggleView.text = "[ Show WebView ]"
                AppLogger.log("UI", "View mode: Live Logs Console")
            } else {
                logContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                btnToggleView.text = "[ Show Logs ]"
                AppLogger.log("UI", "View mode: WebView")
            }
        }

        // Toggle Server Address between Device LAN IP and Localhost (127.0.0.1)
        btnToggleHost.setOnClickListener {
            useIpHost = !useIpHost
            updateServerDisplay()
            val modeName = if (useIpHost && getLocalIpAddress() != null) "Device IP" else "Localhost"
            Toast.makeText(this, "Switched to $modeName", Toast.LENGTH_SHORT).show()
            AppLogger.log("UI", "Server host display toggled to: ${getActiveHostUrl()}")
        }

        btnCopyServer.setOnClickListener {
            val serverUrl = getActiveHostUrl()
            copyToClipboard("Server URL", serverUrl)
            Toast.makeText(this, "Copied $serverUrl", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            AppLogger.clear()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        btnCopyLogs.setOnClickListener {
            copyToClipboard("Console Logs", AppLogger.getHistory())
            Toast.makeText(this, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
        }

        switchLiteMode.setOnCheckedChangeListener { _, isChecked ->
            applyLiteMode(isChecked)
            httpServer?.isLiteMode = isChecked
        }

        btnCopyHls.setOnClickListener {
            val hls = hlsUrlStore.getLatestHls()
            if (hls != null) {
                copyToClipboard("HLS Stream URL", hls.url)
                Toast.makeText(this, "Copied HLS stream URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyPinggyUrl.setOnClickListener {
            val url = pinggyManager.currentUrl
            if (!url.isNullOrBlank()) {
                copyToClipboard("Pinggy Public URL", url)
                Toast.makeText(this, "Copied Pinggy URL", Toast.LENGTH_SHORT).show()
            }
        }

        tvPublicUrl.setOnClickListener {
            val url = pinggyManager.currentUrl
            if (!url.isNullOrBlank()) {
                copyToClipboard("Pinggy Public URL", url)
                Toast.makeText(this, "Copied $url", Toast.LENGTH_SHORT).show()
            }
        }

        btnRenewPinggy.setOnClickListener {
            pinggyManager.renew()
            Toast.makeText(this, "Renewing Pinggy tunnel...", Toast.LENGTH_SHORT).show()
        }

        btnSendApi.setOnClickListener {
            val url = pinggyManager.currentUrl
            if (url.isNullOrBlank()) {
                Toast.makeText(this, "No active Pinggy URL to send", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Sending URL to API...", Toast.LENGTH_SHORT).show()
            pinggyManager.sendUrlToWorker { success, errorMsg ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "POST /api/app — Success (HTTP 200)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "POST /api/app — Error: ${errorMsg ?: "invalid url"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnToggleLogs.setOnClickListener {
            isLogsActive = !isLogsActive
            if (isLogsActive) {
                btnToggleLogs.text = "[ Stop Logs ]"
                tvLogs.text = AppLogger.getHistory()
                scrollLogs.post {
                    scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
                Toast.makeText(this, "Logs resumed", Toast.LENGTH_SHORT).show()
            } else {
                btnToggleLogs.text = "[ Start Logs ]"
                Toast.makeText(this, "Logs paused", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyLiteMode(enabled: Boolean) {
        requestInterceptor.isLiteModeEnabled = enabled
        webView.settings.loadsImagesAutomatically = !enabled
    }

    private fun loadUrl(url: String, resetStore: Boolean = true) {
        etUrl.setText(url)
        resetHlsUI()
        if (resetStore) {
            hlsUrlStore.clear()
        }
        requestInterceptor.currentNavigationUrl = url
        AppLogger.log("WEBVIEW", "Loading: $url")
        webView.loadUrl(url)
    }

    private fun updateHlsUI(stream: HlsStream) {
        tvHlsStatus.text = "● ${stream.url}"
        tvHlsStatus.setTextColor(Color.parseColor("#2E7D32"))
        btnCopyHls.visibility = View.VISIBLE
    }

    private fun resetHlsUI() {
        tvHlsStatus.text = getString(R.string.hls_not_detected)
        tvHlsStatus.setTextColor(Color.parseColor("#757575"))
        btnCopyHls.visibility = View.GONE
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.asSequence()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            // Android 7 safe: calls our stored WebChromeClient reference
            chromeClient.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        updateServerDisplay()
        if (httpServer == null || !httpServer!!.isAlive) {
            initServer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::pinggyManager.isInitialized) {
            pinggyManager.stop()
        }
        try {
            httpServer?.stop()
        } catch (_: Exception) {}
        httpServer = null
        webView.destroy()
    }
}
