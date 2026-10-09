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
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * Features concurrent stdout/stderr stream reading, full diagnostic output logging,
 * dedicated URL receipt timeout, race-free renewal protection, and reliable session cleanup.
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
         * Set to match the embedded Android LiteHttpServer port (7777).
         */
        const val PINGGY_TARGET_PORT = LiteHttpServer.LOCAL_SERVER_PORT
        const val LOCAL_FORWARD_PORT = PINGGY_TARGET_PORT

        const val RENEW_BEFORE_EXPIRY_MINUTES = 10
        const val REGISTER_URL = "https://pinggy-registry.kineflex-netflex.workers.dev/api/app"

        // Default duration for Pinggy free tier if not parsed from output (60 minutes)
        const val DEFAULT_EXPIRY_MINUTES = 60

        // Periodic server renewal check interval in seconds (heartbeat)
        const val SERVER_POLL_INTERVAL_SECONDS = 60L

        // Timeout for receiving public URL from Pinggy after SSH channel opens
        const val URL_DETECTION_TIMEOUT_SECONDS = 30L

        // Retry backoff parameters
        const val INITIAL_RETRY_DELAY_SECONDS = 5L
        const val MAX_RETRY_DELAY_SECONDS = 60L

        // Regex to strip ANSI escape sequences from terminal banner
        val ANSI_PATTERN = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")

        // Robust regex patterns to extract public HTTPS URLs from Pinggy SSH output
        private val PINGGY_URL_PATTERN = Pattern.compile(
            """https://([a-zA-Z0-9-.]+\.(?:pinggy\.link|free\.pinggy\.net|run\.pinggy-free\.link|pinggy-free\.link|free\.pinggy\.io|pinggy\.online|pinggy\.io|pinggy-free\.me))(?::\d+)?(?:/[^\s]*)?""",
            Pattern.CASE_INSENSITIVE
        )

        // Expiry pattern e.g. "Your tunnel will expire in 60 minutes."
        private val EXPIRY_PATTERN = Pattern.compile(
            """expire(?:s)?\s+in\s+(\d+)\s+minute""",
            Pattern.CASE_INSENSITIVE
        )

        // Domains that belong to Pinggy admin/dashboard, not public tunnels
        private val EXCLUDED_HOSTS = setOf(
            "dashboard.pinggy.io",
            "pinggy.io",
            "api.pinggy.io",
            "admin.pinggy.io",
            "docs.pinggy.io",
            "openssh.com"
        )
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

    private var proactiveRenewalFuture: ScheduledFuture<*>? = null
    private var serverPollFuture: ScheduledFuture<*>? = null
    private var retryFuture: ScheduledFuture<*>? = null

    private var retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

    /**
     * Starts the Pinggy tunnel manager and begins the connection process in the background.
     */
    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) {
            return
        }

        executor = Executors.newScheduledThreadPool(6)
        retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

        AppLogger.log("Pinggy", "Starting embedded SSH tunnel manager...")
        updateState(TunnelState.STARTING, message = "Initializing...")

        executor?.execute {
            startTunnelSession(isRenewal = false)
            schedulePeriodicServerCheck()
        }
    }

    /**
     * Cleanly disconnects all active and renewing SSH sessions, cancels background timers, and updates UI state.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        isRenewing.set(false)

        AppLogger.log("Pinggy", "Stopping embedded SSH tunnel...")
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
     * Triggers tunnel renewal immediately. Guards against overlapping renewals and ensures obsolete sessions are closed.
     */
    fun renew() {
        if (!isRunning.get()) return

        if (!isRenewing.compareAndSet(false, true)) {
            AppLogger.log("Pinggy", "Renewal already in progress, ignoring duplicate trigger")
            return
        }

        AppLogger.log("Pinggy", "Renewal starting...")
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
     * and monitors output streams with a dedicated URL receipt timeout.
     */
    private fun startTunnelSession(
        isRenewal: Boolean,
        oldSessionToClose: Session? = null,
        oldChannelToClose: Channel? = null
    ) {
        if (!isRunning.get()) return

        AppLogger.log("Pinggy", "Connecting to $PINGGY_HOST:$PINGGY_PORT via embedded SSH...")
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
            session.setServerAliveInterval(30000)
            session.setServerAliveCountMax(3)
            session.setTimeout(20000)

            session.connect(20000)
            newSession = session
            AppLogger.log("Pinggy", "SSH session established, requesting remote port forwarding...")

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

            AppLogger.log("Pinggy", "SSH channel opened, reading output for public URL (timeout: ${URL_DETECTION_TIMEOUT_SECONDS}s)...")

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

        if (!urlDetected) {
            // Clean up the failed new session
            disconnectSession(newSession, newChannel)

            if (isRenewal) {
                AppLogger.log("Pinggy", "Renewal attempt failed to detect URL. Disconnected candidate session.")
                // If previous session is still alive, retain it and reschedule renewal
                if (oldSessionToClose != null && oldSessionToClose.isConnected) {
                    AppLogger.log("Pinggy", "Previous session remains active. Rescheduling renewal.")
                    updateState(TunnelState.CONNECTED, message = "Connected (renewal retry scheduled)")
                    executor?.schedule({ renew() }, 60, TimeUnit.SECONDS)
                    return
                }
            }

            if (activeSession == newSession) {
                activeSession = null
                activeChannel = null
            }

            if (isRunning.get()) {
                AppLogger.log("Pinggy", "Triggering tunnel reconnect...")
                scheduleReconnect()
            }
        }
    }

    /**
     * Reads stdout and stderr concurrently without stalling the manager,
     * logs every received line, captures stream exceptions, and enforces a URL detection timeout.
     */
    private fun monitorChannelOutput(
        session: Session,
        channel: ChannelShell,
        isRenewal: Boolean,
        oldSessionToClose: Session?,
        oldChannelToClose: Channel?
    ): Boolean {
        val urlDetectedLatch = CountDownLatch(1)
        val detectedUrlRef = AtomicReference<String?>(null)
        var parsedExpiryMinutes = DEFAULT_EXPIRY_MINUTES

        val processLine: (String, String) -> Unit = { rawLine, streamName ->
            val cleanLine = rawLine.replace(ANSI_PATTERN, "").trim()
            if (cleanLine.isNotBlank()) {
                // Log every received line after stripping ANSI escape sequences
                AppLogger.log("Pinggy", "SSH [$streamName]: $cleanLine")

                // Check for expiry information
                val expiryMatcher = EXPIRY_PATTERN.matcher(cleanLine)
                if (expiryMatcher.find()) {
                    val minutes = expiryMatcher.group(1)?.toIntOrNull()
                    if (minutes != null && minutes > 0) {
                        parsedExpiryMinutes = minutes
                    }
                }

                // Check for public HTTPS URL
                if (detectedUrlRef.get() == null) {
                    val parsed = extractPublicUrl(cleanLine)
                    if (parsed != null && detectedUrlRef.compareAndSet(null, parsed)) {
                        AppLogger.log("Pinggy", "SSH connected")
                        AppLogger.log("Pinggy", "Public URL detected: $parsed")
                        urlDetectedLatch.countDown()
                    }
                }
            }
        }

        // Dedicated concurrent worker to read stdout
        executor?.submit {
            try {
                val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
                while (isRunning.get() && session.isConnected && channel.isConnected) {
                    val line = reader.readLine() ?: break
                    processLine(line, "stdout")
                }
            } catch (e: Exception) {
                if (isRunning.get() && session.isConnected) {
                    AppLogger.log("Pinggy", "Stream read error (stdout): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // Dedicated concurrent worker to read stderr / extended stream
        executor?.submit {
            try {
                val errStream = channel.extInputStream
                val reader = BufferedReader(InputStreamReader(errStream, Charsets.UTF_8))
                while (isRunning.get() && session.isConnected && channel.isConnected) {
                    val line = reader.readLine() ?: break
                    processLine(line, "stderr")
                }
            } catch (e: Exception) {
                if (isRunning.get() && session.isConnected) {
                    AppLogger.log("Pinggy", "Stream read error (stderr): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // Await public URL detection with URL_DETECTION_TIMEOUT_SECONDS timeout
        val detected = try {
            urlDetectedLatch.await(URL_DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val detectedUrl = detectedUrlRef.get()

        if (detected && detectedUrl != null) {
            retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

            if (isRenewal) {
                activeSession = session
                activeChannel = channel
                // Close obsolete previous session after 3-second grace period
                oldSessionToClose?.let { old ->
                    executor?.schedule({
                        AppLogger.log("Pinggy", "Closing obsolete previous session following successful renewal")
                        disconnectSession(old, oldChannelToClose)
                    }, 3, TimeUnit.SECONDS)
                }
            }

            val nowSeconds = System.currentTimeMillis() / 1000
            val expiresAt = nowSeconds + (parsedExpiryMinutes * 60)

            updateState(
                TunnelState.CONNECTED,
                publicUrl = detectedUrl,
                expiresAtSeconds = expiresAt,
                remainingMinutes = parsedExpiryMinutes,
                message = "Connected ($parsedExpiryMinutes min)"
            )

            // Register URL with Kineflex asynchronously
            registerUrlWithKineflex(detectedUrl, expiresAt)

            // Schedule proactive renewal before expiry
            scheduleProactiveRenewal(parsedExpiryMinutes)

            // Keep monitoring active session until it disconnects
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
                updateState(TunnelState.ERROR, message = "Tunnel disconnected")
            }

            return true
        } else {
            AppLogger.log(
                "Pinggy",
                "URL detection timed out after ${URL_DETECTION_TIMEOUT_SECONDS}s without finding public URL"
            )
            updateState(
                TunnelState.ERROR,
                message = "URL detection timeout (${URL_DETECTION_TIMEOUT_SECONDS}s)"
            )
            return false
        }
    }

    /**
     * Extracts a public Pinggy HTTPS URL from an output line, excluding admin/dashboard domains.
     */
    fun extractPublicUrl(line: String): String? {
        val clean = line.replace(ANSI_PATTERN, "")
        val matcher = PINGGY_URL_PATTERN.matcher(clean)
        while (matcher.find()) {
            val candidate = matcher.group() ?: continue
            val host = candidate.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            if (!EXCLUDED_HOSTS.contains(host)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Registers the public URL with Kineflex and inspects for a renewal directive.
     */
    private fun registerUrlWithKineflex(publicUrl: String, expiresAt: Long) {
        executor?.execute {
            if (!isRunning.get()) return@execute

            AppLogger.log("Pinggy", "Registering URL with Kineflex...")
            val result = registration.register(
                registerUrl = REGISTER_URL,
                publicUrl = publicUrl,
                expiresAtSeconds = expiresAt
            )

            if (result.success) {
                if (result.shouldRenew) {
                    AppLogger.log("Pinggy", "Server requested immediate renewal in registration response")
                    renew()
                }
            } else {
                AppLogger.log("Pinggy", "Registration attempt failed: ${result.errorMessage}")
            }
        }
    }

    /**
     * Schedules proactive renewal RENEW_BEFORE_EXPIRY_MINUTES minutes before expiration.
     */
    private fun scheduleProactiveRenewal(totalMinutes: Int) {
        proactiveRenewalFuture?.cancel(false)

        val renewDelayMinutes = (totalMinutes - RENEW_BEFORE_EXPIRY_MINUTES).coerceAtLeast(1)
        AppLogger.log(
            "Pinggy",
            "Renewal scheduled in $renewDelayMinutes minutes (~$RENEW_BEFORE_EXPIRY_MINUTES min before expiry)"
        )

        proactiveRenewalFuture = executor?.schedule({
            if (isRunning.get()) {
                AppLogger.log("Pinggy", "Proactive renewal triggered")
                renew()
            }
        }, renewDelayMinutes.toLong(), TimeUnit.MINUTES)
    }

    /**
     * Schedules periodic check with Kineflex to see if the server directives request a renewal.
     */
    private fun schedulePeriodicServerCheck() {
        serverPollFuture?.cancel(false)

        serverPollFuture = executor?.scheduleWithFixedDelay({
            if (isRunning.get() && currentInfo.state == TunnelState.CONNECTED) {
                val result = registration.checkRenewal(REGISTER_URL)
                if (result.shouldRenew) {
                    AppLogger.log("Pinggy", "Server requested renewal via periodic check")
                    renew()
                }
            }
        }, SERVER_POLL_INTERVAL_SECONDS, SERVER_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Schedules a reconnect attempt with exponential backoff.
     */
    private fun scheduleReconnect() {
        if (!isRunning.get()) return

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
        proactiveRenewalFuture?.cancel(true)
        proactiveRenewalFuture = null
        serverPollFuture?.cancel(true)
        serverPollFuture = null
        retryFuture?.cancel(true)
        retryFuture = null
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
