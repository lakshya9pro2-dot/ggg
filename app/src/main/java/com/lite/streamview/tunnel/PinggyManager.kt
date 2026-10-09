package com.lite.streamview.tunnel

import android.content.Context
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.lite.streamview.registration.KineflexRegistration
import com.lite.streamview.server.LiteHttpServer
import com.lite.streamview.util.AppLogger
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Tunnel lifecycle states reported to the UI.
 */
enum class TunnelState(val displayName: String) {
    DISCONNECTED("Disconnected"),
    STARTING("Starting..."),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    RENEWING("Renewing..."),
    ERROR("Error")
}

/**
 * Detailed snapshot of the active Pinggy tunnel state.
 */
data class TunnelInfo(
    val state: TunnelState,
    val publicUrl: String? = null,
    val localServerUrl: String = "http://127.0.0.1:${LiteHttpServer.LOCAL_SERVER_PORT}",
    val expiresAtTimestampSeconds: Long? = null,
    val remainingMinutes: Int? = null,
    val message: String? = null
)

/**
 * Manages the Pinggy SSH reverse tunnel using an embedded pure Java SSH client (JSch).
 * Features:
 * - Robust URL validation and immediate registration with Cloudflare Worker
 * - Continuous non-blocking chunk parsing without waiting for newlines
 * - Terminal screen-redraw spam filtering (stops repetitive logs once connected)
 * - Infinite socket read timeout to prevent fast 20-second connection drops
 * - Automatic background renewal when tunnel is approaching expiration (<= 10 min)
 * - Manual on-demand renewal via the UI button
 */
class PinggyManager(
    private val context: Context,
    private val registration: KineflexRegistration = KineflexRegistration()
) {

    companion object {
        // =========================================================================
        // Configuration Parameters (Configured in one central place)
        // =========================================================================

        const val PINGGY_HOST = "free.pinggy.io"
        const val PINGGY_PORT = 443
        const val PINGGY_USER = "qr"

        /**
         * Target port for Pinggy reverse tunnel forward (-R0:localhost:<PORT>).
         * Matches the embedded Android LiteHttpServer port (7777).
         */
        const val PINGGY_TARGET_PORT = LiteHttpServer.LOCAL_SERVER_PORT
        const val LOCAL_FORWARD_PORT = PINGGY_TARGET_PORT

        const val RENEW_BEFORE_EXPIRY_MINUTES = 10
        const val REGISTER_URL = "https://pinggy-registry.kineflex-netflex.workers.dev/api/app"

        // Default duration for Pinggy free tier if not parsed from output (60 minutes)
        const val DEFAULT_EXPIRY_MINUTES = 60

        // Retry backoff parameters
        const val INITIAL_RETRY_DELAY_SECONDS = 5L
        const val MAX_RETRY_DELAY_SECONDS = 60L

        /**
         * Robust URL regex matching free.pinggy.net, run.pinggy-free.link,
         * and other Pinggy tunnel domains.
         */
        val pinggyUrlRegex = Regex(
            """https://[a-zA-Z0-9.-]+\.(?:free\.pinggy\.net|run\.pinggy-free\.link|pinggy\.link|free\.pinggy\.io|pinggy-free\.link|free\.pinggy\.online|pinggy\.online|pinggy\.io|pinggy-free\.me)(?::\d+)?""",
            RegexOption.IGNORE_CASE
        )
        val PINGGY_URL_REGEX = pinggyUrlRegex

        // Expiry pattern e.g. "Your tunnel will expire in 60 minutes."
        val EXPIRY_PATTERN = Pattern.compile(
            """expire(?:s)?\s+in\s+(\d+)\s+minute""",
            Pattern.CASE_INSENSITIVE
        )

        // Domains that belong to Pinggy admin/dashboard, not public tunnels
        val EXCLUDED_HOSTS = setOf(
            "dashboard.pinggy.io",
            "pinggy.io",
            "api.pinggy.io",
            "admin.pinggy.io",
            "docs.pinggy.io",
            "openssh.com"
        )

        /**
         * Strips VT100/ANSI escape sequences, terminal box characters, and control codes
         * from interactive terminal output chunks before URL detection.
         */
        fun cleanTerminalOutput(input: String): String {
            return input
                .replace(Regex("""\u001B\[[0-?]*[ -/]*[@-~]"""), "")
                .replace(Regex("""\u001B\][^\u0007]*(?:\u0007|\u001B\\)"""), "")
                .replace(Regex("""\u001B[()][0-2A-Z]"""), "")
                .replace(Regex("""[\u0000-\u001F\u007F]"""), " ")
        }

        /**
         * Validates and extracts a clean Pinggy HTTPS URL from raw terminal text.
         */
        fun extractPublicUrl(input: String): String? {
            val clean = cleanTerminalOutput(input)
            val match = pinggyUrlRegex.find(clean) ?: return null
            val candidate = match.value.trim()
            val host = candidate.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            if (!EXCLUDED_HOSTS.contains(host)) {
                return candidate
            }
            return null
        }
    }

    // UI listener callback
    var onTunnelStateChanged: ((TunnelInfo) -> Unit)? = null

    @Volatile
    var currentInfo: TunnelInfo = TunnelInfo(
        state = TunnelState.DISCONNECTED,
        localServerUrl = "http://127.0.0.1:${LiteHttpServer.LOCAL_SERVER_PORT}"
    )
        private set

    val currentUrl: String?
        get() = currentInfo.publicUrl

    private val isRunning = AtomicBoolean(false)
    private val isRenewing = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var activeSession: Session? = null
    @Volatile
    private var activeChannel: Channel? = null

    @Volatile
    private var renewingSession: Session? = null
    @Volatile
    private var renewingChannel: Channel? = null

    private var retryFuture: ScheduledFuture<*>? = null
    private var expiryWatchdogFuture: ScheduledFuture<*>? = null
    private var retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

    @Volatile
    private var lastRegisteredUrl: String? = null
    private var lastAutoRenewTimestampMs = 0L

    /**
     * Starts the Pinggy tunnel manager and begins the connection process in the background.
     */
    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) {
            return
        }

        // Avoid starting duplicate sessions if active tunnel is already connected and healthy
        if (activeSession?.isConnected == true && currentInfo.state == TunnelState.CONNECTED) {
            AppLogger.log("Pinggy", "Active tunnel is already connected and healthy.")
            return
        }

        executor = Executors.newScheduledThreadPool(4)
        retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

        AppLogger.log("Pinggy", "Starting embedded SSH tunnel...")
        updateState(TunnelState.STARTING, message = "Initializing...")

        executor?.execute {
            startTunnelSession(isRenewal = false)
        }

        startExpiryWatchdog()
    }

    /**
     * Cleanly disconnects all active and renewing SSH sessions, cancels timers, and updates UI state.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        isRenewing.set(false)

        AppLogger.log("Pinggy", "Stopping tunnel...")
        cancelScheduledTasks()

        disconnectSession(activeSession, activeChannel)
        activeSession = null
        activeChannel = null

        disconnectSession(renewingSession, renewingChannel)
        renewingSession = null
        renewingChannel = null

        executor?.shutdownNow()
        executor = null

        AppLogger.log("Pinggy", "Tunnel stopped")
        updateState(TunnelState.DISCONNECTED, message = "Stopped")
    }

    /**
     * Tunnel renewal trigger (called via UI button or automatic expiry watchdog).
     * Guards against overlapping renewals and ensures obsolete sessions are closed.
     */
    fun renew() {
        if (!isRunning.get()) return

        if (!isRenewing.compareAndSet(false, true)) {
            AppLogger.log("Pinggy", "Renewal already in progress, skipping duplicate trigger")
            return
        }

        AppLogger.log("Pinggy", "Starting tunnel renewal...")
        updateState(TunnelState.RENEWING, message = "Renewing tunnel...")

        executor?.execute {
            try {
                val previousSession = activeSession
                val previousChannel = activeChannel
                startTunnelSession(
                    isRenewal = true,
                    oldSessionToClose = previousSession,
                    oldChannelToClose = previousChannel
                )
            } finally {
                isRenewing.set(false)
            }
        }
    }

    /**
     * Establishes the SSH connection using JSch, requests remote port forwarding,
     * and monitors output streams with persistent socket configuration.
     */
    private fun startTunnelSession(
        isRenewal: Boolean,
        oldSessionToClose: Session? = null,
        oldChannelToClose: Channel? = null
    ) {
        if (!isRunning.get()) return

        // Stop creating replacement sessions while the current tunnel remains healthy
        if (!isRenewal && activeSession?.isConnected == true && currentInfo.state == TunnelState.CONNECTED) {
            AppLogger.log("Pinggy", "Active tunnel is already healthy, skipping redundant connection")
            return
        }

        AppLogger.log("Pinggy", "Connecting to $PINGGY_HOST:$PINGGY_PORT...")
        updateState(
            if (isRenewal) TunnelState.RENEWING else TunnelState.CONNECTING,
            message = "Connecting to $PINGGY_HOST..."
        )

        var newSession: Session? = null
        var newChannel: ChannelShell? = null
        var urlDetected = false

        try {
            val jsch = JSch()
            ensureSshIdentity(jsch)

            val session = jsch.getSession(PINGGY_USER, PINGGY_HOST, PINGGY_PORT)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.setPassword("")
            session.userInfo = object : UserInfo {
                override fun getPassphrase(): String = ""
                override fun getPassword(): String = ""
                override fun promptPassword(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = true
                override fun promptYesNo(message: String?): Boolean = true
                override fun showMessage(message: String?) {}
            }

            // Keepalive settings: send heartbeat every 30s to keep connection open 24/7
            session.setServerAliveInterval(30000)
            session.setServerAliveCountMax(3)

            // CRITICAL: Handshake timeout is 20s, but socket read timeout is set to 0 (infinite)
            // Setting a non-zero read timeout causes JSch to drop idle reverse tunnels every 20s!
            session.connect(20000)
            session.setTimeout(0) // 0 = infinite read timeout, keeping tunnel permanently open
            newSession = session

            // Set remote port forwarding: remote port 0 (dynamic), local 127.0.0.1:LOCAL_FORWARD_PORT
            session.setPortForwardingR(0, "127.0.0.1", LOCAL_FORWARD_PORT)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType("vt100")
            channel.connect(15000)
            newChannel = channel

            if (isRenewal) {
                renewingSession = session
                renewingChannel = channel
            } else {
                activeSession = session
                activeChannel = channel
            }

            AppLogger.log("Pinggy", "SSH session established, awaiting public URL...")

            urlDetected = monitorChannelOutput(
                session = session,
                channel = channel,
                isRenewal = isRenewal,
                oldSessionToClose = oldSessionToClose,
                oldChannelToClose = oldChannelToClose
            )

        } catch (e: Exception) {
            AppLogger.log("Pinggy", "SSH connection failed: ${e.javaClass.simpleName}: ${e.message}")
            updateState(TunnelState.ERROR, message = e.message ?: "Connection failed")
        } finally {
            if (isRenewal) {
                renewingSession = null
                renewingChannel = null
            }
        }

        if (!isRenewal && isRunning.get()) {
            disconnectSession(newSession, newChannel)
            if (activeSession == newSession) {
                activeSession = null
                activeChannel = null
            }
            AppLogger.log("Pinggy", "Tunnel session ended, scheduling reconnect...")
            scheduleReconnect()
        } else if (isRenewal && !urlDetected) {
            disconnectSession(newSession, newChannel)
            AppLogger.log("Pinggy", "Renewal session disconnected. Retaining previous session.")
            if (oldSessionToClose != null && oldSessionToClose.isConnected) {
                updateState(TunnelState.CONNECTED, message = "Connected (renewal retried later)")
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Reads output chunks, extracts the public URL, registers with Cloudflare Worker,
     * and suppresses repetitive terminal screen-redraw logs once connected.
     */
    private fun monitorChannelOutput(
        session: Session,
        channel: ChannelShell,
        isRenewal: Boolean,
        oldSessionToClose: Session?,
        oldChannelToClose: Channel?
    ): Boolean {
        val bufferLock = Any()
        val rollingBuffer = StringBuilder()
        var isUrlFound = false
        var parsedExpiryMinutes = DEFAULT_EXPIRY_MINUTES

        // Callback invoked on every received chunk from stdout
        val processChunk: (String, String) -> Unit = { rawChunk, _ ->
            val cleanChunk = cleanTerminalOutput(rawChunk)
            if (cleanChunk.isNotBlank()) {
                synchronized(bufferLock) {
                    val isConnected = isUrlFound || (currentInfo.state == TunnelState.CONNECTED && currentInfo.publicUrl != null)

                    // Before connection is established, log non-blank setup messages (skip border noise)
                    if (!isConnected) {
                        val isNoise = cleanChunk.all { it.isWhitespace() || it == '│' || it == '─' || it == '┌' || it == '└' || it == '┼' || it == '┤' || it == '├' }
                        if (!isNoise && cleanChunk.length > 3) {
                            AppLogger.log("Pinggy", "SSH: $cleanChunk")
                        }
                    }

                    rollingBuffer.append(cleanChunk)
                    if (rollingBuffer.length > 8192) {
                        rollingBuffer.delete(0, rollingBuffer.length - 4096)
                    }

                    val currentBuffer = rollingBuffer.toString()

                    // Check for expiry information
                    val expiryMatcher = EXPIRY_PATTERN.matcher(currentBuffer)
                    if (expiryMatcher.find()) {
                        val minutes = expiryMatcher.group(1)?.toIntOrNull()
                        if (minutes != null && minutes > 0) {
                            parsedExpiryMinutes = minutes
                        }
                    }

                    // Check for public URL continuously as chunks arrive
                    if (!isUrlFound) {
                        val matchedUrl = extractPublicUrl(currentBuffer)
                        if (matchedUrl != null) {
                            isUrlFound = true
                            retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

                            if (isRenewal) {
                                activeSession = session
                                activeChannel = channel
                                // Close obsolete previous session after brief 3-second grace period
                                oldSessionToClose?.let { old ->
                                    executor?.schedule({
                                        AppLogger.log("Pinggy", "Closing obsolete previous session following renewal")
                                        disconnectSession(old, oldChannelToClose)
                                    }, 3, TimeUnit.SECONDS)
                                }
                            } else {
                                activeSession = session
                                activeChannel = channel
                            }

                            checkAndRegisterUrl(matchedUrl, parsedExpiryMinutes)
                        }
                    }
                }
            }
        }

        // Dedicated daemon thread for stdout reading (does not occupy executor thread pool)
        val stdoutThread = Thread({
            readStreamChunks(channel.inputStream, "stdout", session, channel, processChunk)
        }, "Pinggy-StdoutReader").apply { isDaemon = true }
        stdoutThread.start()

        // Keep monitoring active session continuously until it disconnects (no auto-timeout killing the stream)
        try {
            while (isRunning.get() && session.isConnected && channel.isConnected) {
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (activeSession == session) {
            activeSession = null
            activeChannel = null
        }

        if (isRunning.get()) {
            AppLogger.log("Pinggy", "SSH session disconnected")
            updateState(TunnelState.DISCONNECTED, message = "Tunnel disconnected")
        }

        return isUrlFound
    }

    /**
     * Reads byte chunks continuously from an InputStream without waiting for newlines.
     */
    private fun readStreamChunks(
        stream: InputStream,
        streamName: String,
        session: Session,
        channel: Channel,
        onChunk: (String, String) -> Unit
    ) {
        val buffer = ByteArray(2048)
        try {
            var bytesRead: Int
            while (isRunning.get() && session.isConnected && channel.isConnected) {
                bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    val rawChunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    onChunk(rawChunk, streamName)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get() && session.isConnected) {
                AppLogger.log("Pinggy", "Stream read notice ($streamName): ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Checks if candidateUrl is valid and new, updates tunnel state,
     * and automatically registers it with the Cloudflare Worker.
     */
    fun checkAndRegisterUrl(candidateUrl: String, expiryMinutes: Int = DEFAULT_EXPIRY_MINUTES) {
        val nowSec = System.currentTimeMillis() / 1000
        val expSec = nowSec + (expiryMinutes * 60)

        val isNewUrl = (lastRegisteredUrl != candidateUrl)
        lastRegisteredUrl = candidateUrl

        updateState(
            state = TunnelState.CONNECTED,
            publicUrl = candidateUrl,
            expiresAtSeconds = expSec,
            remainingMinutes = expiryMinutes,
            message = "Connected ($expiryMinutes min)"
        )

        if (isNewUrl) {
            AppLogger.log("Pinggy", "Public URL detected: $candidateUrl")
            sendUrlToWorker()
        }
    }

    /**
     * Sends the current public URL to Cloudflare Worker in a dedicated background thread.
     */
    fun sendUrlToWorker(callback: ((Boolean, String?) -> Unit)? = null) {
        val publicUrl = currentUrl
        if (publicUrl.isNullOrBlank()) {
            val msg = "No active Pinggy URL to send"
            AppLogger.log("API", msg)
            callback?.invoke(false, msg)
            return
        }
        val expiresAt = currentInfo.expiresAtTimestampSeconds
            ?: ((System.currentTimeMillis() / 1000) + (DEFAULT_EXPIRY_MINUTES * 60))

        Thread({
            try {
                AppLogger.log("API", "Sending URL to Cloudflare Worker: $publicUrl (expires: $expiresAt)")
                val result = registration.register(
                    registerUrl = REGISTER_URL,
                    publicUrl = publicUrl,
                    expiresAtSeconds = expiresAt
                )

                if (result.success) {
                    AppLogger.log("API", "POST /api/app — Success (HTTP 200)")
                    callback?.invoke(true, null)
                } else {
                    val errorMsg = result.errorMessage ?: "HTTP ${result.statusCode}"
                    AppLogger.log("API", "POST /api/app — Error: $errorMsg")
                    callback?.invoke(false, errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown network error"
                AppLogger.log("API", "Worker registration exception: $errorMsg")
                callback?.invoke(false, errorMsg)
            }
        }, "Pinggy-WorkerRegistration").start()
    }

    /**
     * Periodic watchdog to check if the tunnel is approaching expiration (<= 10 min).
     * If nearing expiration, automatically renews the tunnel seamlessly.
     */
    private fun startExpiryWatchdog() {
        expiryWatchdogFuture?.cancel(false)
        expiryWatchdogFuture = executor?.scheduleWithFixedDelay({
            if (!isRunning.get() || currentInfo.state != TunnelState.CONNECTED) return@scheduleWithFixedDelay

            val expiresAt = currentInfo.expiresAtTimestampSeconds ?: return@scheduleWithFixedDelay
            val nowSeconds = System.currentTimeMillis() / 1000
            val remainingSeconds = expiresAt - nowSeconds
            val remainingMinutes = (remainingSeconds / 60).toInt()

            // Update remaining minutes in UI display
            if (remainingMinutes >= 0 && remainingMinutes != currentInfo.remainingMinutes) {
                updateState(
                    state = TunnelState.CONNECTED,
                    remainingMinutes = remainingMinutes,
                    message = "Connected ($remainingMinutes min)"
                )
            }

            // Proactively renew when nearing expiration (<= RENEW_BEFORE_EXPIRY_MINUTES)
            if (remainingMinutes in 1..RENEW_BEFORE_EXPIRY_MINUTES) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastAutoRenewTimestampMs > 3 * 60 * 1000L) { // 3-minute cooldown
                    lastAutoRenewTimestampMs = nowMs
                    AppLogger.log(
                        "Pinggy",
                        "Tunnel is near expiration (~$remainingMinutes min left). Automatically renewing..."
                    )
                    renew()
                }
            }
        }, 15, 30, TimeUnit.SECONDS)
    }

    /**
     * Schedules a reconnect attempt with exponential backoff.
     * Prevents reconnecting if an active tunnel is already healthy.
     */
    private fun scheduleReconnect() {
        if (!isRunning.get()) return

        // Stop creating replacement sessions while the current tunnel remains healthy
        if (activeSession?.isConnected == true && currentInfo.state == TunnelState.CONNECTED) {
            AppLogger.log("Pinggy", "Active tunnel is already healthy, skipping unnecessary reconnect")
            return
        }

        retryFuture?.cancel(false)
        val delay = retryDelaySeconds
        AppLogger.log("Pinggy", "Retrying tunnel connection in ${delay}s...")

        retryFuture = executor?.schedule({
            if (isRunning.get()) {
                startTunnelSession(isRenewal = false)
            }
        }, delay, TimeUnit.SECONDS)

        retryDelaySeconds = (retryDelaySeconds * 2).coerceAtMost(MAX_RETRY_DELAY_SECONDS)
    }

    /**
     * Ensures an RSA key exists in app private storage using pure Java JSch KeyPair.
     * Guarantees persistent identity without requiring external ssh-keygen binaries.
     */
    private fun ensureSshIdentity(jsch: JSch) {
        try {
            val keyFile = File(context.filesDir, "id_pinggy_rsa")
            val pubFile = File(context.filesDir, "id_pinggy_rsa.pub")
            if (!keyFile.exists() || keyFile.length() == 0L) {
                val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)
                keyPair.writePrivateKey(keyFile.absolutePath)
                keyPair.writePublicKey(pubFile.absolutePath, "pinggy")
                keyPair.dispose()
            }
            if (keyFile.exists() && keyFile.length() > 0L) {
                jsch.addIdentity(keyFile.absolutePath)
            }
        } catch (e: Exception) {
            AppLogger.log("Pinggy", "Identity note: ${e.message}")
        }
    }

    private fun disconnectSession(session: Session?, channel: Channel?) {
        try {
            channel?.disconnect()
        } catch (e: Exception) {
            AppLogger.log("Pinggy", "Channel disconnect note: ${e.message}")
        }
        try {
            session?.disconnect()
        } catch (e: Exception) {
            AppLogger.log("Pinggy", "Session disconnect note: ${e.message}")
        }
    }

    private fun cancelScheduledTasks() {
        retryFuture?.cancel(true)
        retryFuture = null
        expiryWatchdogFuture?.cancel(true)
        expiryWatchdogFuture = null
    }

    private fun updateState(
        state: TunnelState,
        publicUrl: String? = currentInfo.publicUrl,
        expiresAtSeconds: Long? = currentInfo.expiresAtTimestampSeconds,
        remainingMinutes: Int? = currentInfo.remainingMinutes,
        message: String? = null
    ) {
        val newInfo = TunnelInfo(
            state = state,
            publicUrl = publicUrl,
            localServerUrl = "http://127.0.0.1:${LiteHttpServer.LOCAL_SERVER_PORT}",
            expiresAtTimestampSeconds = expiresAtSeconds,
            remainingMinutes = remainingMinutes,
            message = message
        )
        currentInfo = newInfo
        onTunnelStateChanged?.invoke(newInfo)
    }
}
