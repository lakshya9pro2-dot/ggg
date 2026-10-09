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
import java.io.InputStreamReader
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
 * Does not require any external binaries (ssh, ssh-keygen, or Termux).
 * Supports automatic proactive renewal, server-directed renewal, and registration with Kineflex.
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
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var activeSession: Session? = null
    @Volatile
    private var activeChannel: Channel? = null

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

        executor = Executors.newScheduledThreadPool(3)
        retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

        AppLogger.log("Pinggy", "Starting embedded SSH tunnel manager...")
        updateState(TunnelState.STARTING, message = "Initializing...")

        executor?.execute {
            startTunnelSession(isRenewal = false)
            schedulePeriodicServerCheck()
        }
    }

    /**
     * Cleanly disconnects the SSH session, cancels background timers, and updates UI state.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        AppLogger.log("Pinggy", "Stopping embedded SSH tunnel...")
        cancelScheduledTasks()

        disconnectSession(activeSession, activeChannel)
        activeSession = null
        activeChannel = null

        executor?.shutdownNow()
        executor = null

        AppLogger.log("Pinggy", "Tunnel stopped")
        updateState(TunnelState.DISCONNECTED, message = "Stopped")
    }

    /**
     * Triggers tunnel renewal immediately (called proactively before expiry or upon server directive).
     */
    fun renew() {
        if (!isRunning.get()) return

        AppLogger.log("Pinggy", "Renewal starting...")
        updateState(TunnelState.RENEWING, message = "Renewing tunnel...")

        executor?.execute {
            // Keep existing session open until the new session establishes the new URL
            val previousSession = activeSession
            val previousChannel = activeChannel
            startTunnelSession(
                isRenewal = true,
                oldSessionToClose = previousSession,
                oldChannelToClose = previousChannel
            )
        }
    }

    /**
     * Establishes the SSH connection using JSch, requests remote port forwarding,
     * and monitors the stream for the public URL.
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
            AppLogger.log("Pinggy", "SSH session established, requesting remote port forwarding...")

            // Set remote port forwarding: remote port 0 (dynamic), local 127.0.0.1:LOCAL_FORWARD_PORT
            session.setPortForwardingR(0, "127.0.0.1", LOCAL_FORWARD_PORT)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType("vt100")
            channel.connect(15000)

            activeSession = session
            activeChannel = channel

            AppLogger.log("Pinggy", "SSH channel opened, monitoring output for public URL...")

            monitorChannelOutput(
                session = session,
                channel = channel,
                isRenewal = isRenewal,
                oldSessionToClose = oldSessionToClose,
                oldChannelToClose = oldChannelToClose
            )

            // When channel/session disconnects
            if (activeSession == session) {
                activeSession = null
                activeChannel = null
            }

            if (isRunning.get()) {
                AppLogger.log("Pinggy", "SSH session disconnected. Reconnecting...")
                updateState(TunnelState.ERROR, message = "Tunnel disconnected")
                scheduleReconnect()
            }

        } catch (e: Exception) {
            AppLogger.log("Pinggy", "SSH connection failed: ${e.message}")
            updateState(TunnelState.ERROR, message = e.message ?: "Connection failed")
            scheduleReconnect()
        }
    }

    /**
     * Reads channel stream lines, parses Pinggy public URL and expiry, and triggers registration.
     */
    private fun monitorChannelOutput(
        session: Session,
        channel: ChannelShell,
        isRenewal: Boolean,
        oldSessionToClose: Session?,
        oldChannelToClose: Channel?
    ): Boolean {
        var detectedUrl: String? = null
        var parsedExpiryMinutes = DEFAULT_EXPIRY_MINUTES

        try {
            val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))

            while (isRunning.get() && session.isConnected && channel.isConnected) {
                val rawLine = reader.readLine() ?: break
                val currentLine = rawLine.replace(ANSI_PATTERN, "").trim()
                if (currentLine.isBlank()) continue

                // Check for expiry information
                val expiryMatcher = EXPIRY_PATTERN.matcher(currentLine)
                if (expiryMatcher.find()) {
                    val minutes = expiryMatcher.group(1)?.toIntOrNull()
                    if (minutes != null && minutes > 0) {
                        parsedExpiryMinutes = minutes
                    }
                }

                // Check for public HTTPS URL
                if (detectedUrl == null) {
                    val parsed = extractPublicUrl(currentLine)
                    if (parsed != null) {
                        detectedUrl = parsed
                        AppLogger.log("Pinggy", "SSH connected")
                        AppLogger.log("Pinggy", "Public URL detected: $detectedUrl")

                        // Reset retry backoff on successful connection
                        retryDelaySeconds = INITIAL_RETRY_DELAY_SECONDS

                        // Clean up old tunnel after brief grace period
                        oldSessionToClose?.let { old ->
                            executor?.schedule({
                                disconnectSession(old, oldChannelToClose)
                            }, 3, TimeUnit.SECONDS)
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
                    }
                }
            }
        } catch (_: Exception) {}

        return detectedUrl != null
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
        } catch (_: Exception) {}
        try {
            session?.disconnect()
        } catch (_: Exception) {}
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
