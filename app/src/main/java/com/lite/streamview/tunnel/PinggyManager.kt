package com.lite.streamview.tunnel

import android.content.Context
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
 * Manages the Pinggy SSH reverse tunnel process, output parsing, URL detection,
 * automatic proactive renewal, server-directed renewal, and registration with Kineflex.
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
         * Currently configured to 8080 as requested in the SSH command:
         *   ssh -p 443 -R0:localhost:8080 qr@free.pinggy.io
         *
         * NOTE: To expose the embedded Android LiteHttpServer (which runs on port 7777),
         * change this value to 7777 (or LiteHttpServer.LOCAL_SERVER_PORT).
         * The target port must match the local service that should be exposed.
         */
        const val PINGGY_TARGET_PORT = 8080
        const val LOCAL_FORWARD_PORT = PINGGY_TARGET_PORT

        const val RENEW_BEFORE_EXPIRY_MINUTES = 10
        const val REGISTER_URL = "https://kineflex.site/api/app"

        // Default duration for Pinggy free tier if not parsed from output (60 minutes)
        const val DEFAULT_EXPIRY_MINUTES = 60

        // Periodic server renewal check interval in seconds (heartbeat)
        const val SERVER_POLL_INTERVAL_SECONDS = 60L

        // Retry backoff parameters
        const val INITIAL_RETRY_DELAY_SECONDS = 5L
        const val MAX_RETRY_DELAY_SECONDS = 60L

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

    private var activeProcess: Process? = null
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

        AppLogger.log("Pinggy", "Starting tunnel manager...")
        updateState(TunnelState.STARTING, message = "Initializing...")

        executor?.execute {
            startTunnelProcess(isRenewal = false)
            schedulePeriodicServerCheck()
        }
    }

    /**
     * Cleanly stops the SSH process, cancels background timers, and updates UI state.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        AppLogger.log("Pinggy", "Stopping tunnel manager...")
        cancelScheduledTasks()

        killProcess(activeProcess)
        activeProcess = null

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
            // Keep existing process running until new process establishes URL if possible
            val previousProcess = activeProcess
            startTunnelProcess(isRenewal = true, oldProcessToKill = previousProcess)
        }
    }

    /**
     * Spawns the SSH process and handles output parsing and monitoring.
     */
    private fun startTunnelProcess(isRenewal: Boolean, oldProcessToKill: Process? = null) {
        if (!isRunning.get()) return

        AppLogger.log("Pinggy", "Starting tunnel...")
        updateState(
            if (isRenewal) TunnelState.RENEWING else TunnelState.CONNECTING,
            message = "Connecting to $PINGGY_HOST..."
        )

        val sshPath = findSshExecutable()
        if (sshPath == null) {
            val err = "SSH binary not found on device"
            AppLogger.log("Pinggy", err)
            updateState(TunnelState.ERROR, message = err)
            scheduleReconnect(isBinaryMissing = true)
            return
        }

        val keyFile = ensureSshKey()
        val command = buildSshCommand(sshPath, keyFile)

        try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true) // Merge stdout and stderr for unified parsing

            val process = processBuilder.start()
            activeProcess = process

            // Send immediate newline to standard input to bypass empty password prompts
            try {
                process.outputStream.write("\n".toByteArray())
                process.outputStream.flush()
            } catch (_: Exception) {}

            AppLogger.log("Pinggy", "SSH process launched (PID: ${getProcessPid(process)})")

            // Monitor output stream in a dedicated thread
            val urlDetected = monitorProcessOutput(process, isRenewal, oldProcessToKill)

            // Wait for process exit in background
            val exitCode = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }

            if (activeProcess == process) {
                activeProcess = null
            }

            if (isRunning.get()) {
                AppLogger.log("Pinggy", "SSH process exited with code $exitCode")
                AppLogger.log("Pinggy", "Reconnecting...")
                updateState(TunnelState.ERROR, message = "Tunnel disconnected (exit $exitCode)")
                scheduleReconnect(isBinaryMissing = false)
            }

        } catch (e: Exception) {
            AppLogger.log("Pinggy", "Failed to start SSH process: ${e.message}")
            updateState(TunnelState.ERROR, message = e.message ?: "Process failure")
            scheduleReconnect(isBinaryMissing = false)
        }
    }

    /**
     * Reads process output lines, parses Pinggy public URL and expiry, and triggers registration.
     */
    private fun monitorProcessOutput(
        process: Process,
        isRenewal: Boolean,
        oldProcessToKill: Process?
    ): Boolean {
        var detectedUrl: String? = null
        var parsedExpiryMinutes = DEFAULT_EXPIRY_MINUTES

        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

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

                        // Clean up old tunnel process if this was a renewal
                        oldProcessToKill?.let { old ->
                            executor?.schedule({
                                killProcess(old)
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
        val matcher = PINGGY_URL_PATTERN.matcher(line)
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
    private fun scheduleReconnect(isBinaryMissing: Boolean) {
        if (!isRunning.get()) return

        retryFuture?.cancel(false)
        val delay = if (isBinaryMissing) MAX_RETRY_DELAY_SECONDS else retryDelaySeconds
        AppLogger.log("Pinggy", "Retrying tunnel connection in ${delay}s...")

        retryFuture = executor?.schedule({
            if (isRunning.get()) {
                startTunnelProcess(isRenewal = false)
            }
        }, delay, TimeUnit.SECONDS)

        if (!isBinaryMissing) {
            retryDelaySeconds = (retryDelaySeconds * 2).coerceAtMost(MAX_RETRY_DELAY_SECONDS)
        }
    }

    /**
     * Builds the SSH argument list with non-interactive flags.
     */
    private fun buildSshCommand(sshBinary: String, keyFile: File?): List<String> {
        val cmd = mutableListOf<String>()
        cmd.add(sshBinary)
        cmd.add("-p")
        cmd.add(PINGGY_PORT.toString())
        cmd.add("-R0:localhost:$PINGGY_TARGET_PORT")
        cmd.add("-o")
        cmd.add("StrictHostKeyChecking=no")
        cmd.add("-o")
        cmd.add("UserKnownHostsFile=/dev/null")
        cmd.add("-o")
        cmd.add("ServerAliveInterval=30")
        cmd.add("-o")
        cmd.add("ServerAliveCountMax=3")

        if (keyFile != null && keyFile.exists() && keyFile.length() > 0) {
            cmd.add("-i")
            cmd.add(keyFile.absolutePath)
        }

        cmd.add("$PINGGY_USER@$PINGGY_HOST")
        return cmd
    }

    /**
     * Ensures an ed25519 SSH key exists in the private app files directory.
     * Prevents interactive password prompts when connecting to Pinggy.
     */
    private fun ensureSshKey(): File? {
        return try {
            val keyFile = File(context.filesDir, "id_pinggy_ed25519")
            if (keyFile.exists() && keyFile.length() > 0) {
                return keyFile
            }

            val keygenBinary = findKeygenExecutable()
            if (keygenBinary != null) {
                val pb = ProcessBuilder(
                    keygenBinary,
                    "-t", "ed25519",
                    "-N", "",
                    "-f", keyFile.absolutePath
                )
                val proc = pb.start()
                proc.waitFor(5, TimeUnit.SECONDS)
                if (keyFile.exists()) {
                    return keyFile
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Searches standard Android/Linux binary directories for the `ssh` executable.
     */
    private fun findSshExecutable(): String? {
        val candidates = listOf(
            "ssh",
            "/system/bin/ssh",
            "/vendor/bin/ssh",
            "/apex/com.android.runtime/bin/ssh",
            "/data/data/com.termux/files/usr/bin/ssh",
            "/usr/bin/ssh",
            "/bin/ssh"
        )
        for (candidate in candidates) {
            if (isExecutable(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun findKeygenExecutable(): String? {
        val candidates = listOf(
            "ssh-keygen",
            "/system/bin/ssh-keygen",
            "/vendor/bin/ssh-keygen",
            "/apex/com.android.runtime/bin/ssh-keygen",
            "/data/data/com.termux/files/usr/bin/ssh-keygen",
            "/usr/bin/ssh-keygen"
        )
        for (candidate in candidates) {
            if (isExecutable(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isExecutable(path: String): Boolean {
        if (!path.contains("/")) {
            // Check PATH by testing exec version
            return try {
                val p = ProcessBuilder(path, "-V").start()
                p.waitFor(2, TimeUnit.SECONDS)
                true
            } catch (_: Exception) {
                false
            }
        }
        val file = File(path)
        return file.exists() && file.canExecute()
    }

    private fun killProcess(proc: Process?) {
        if (proc == null) return
        try {
            proc.destroy()
            proc.waitFor(500, TimeUnit.MILLISECONDS)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {}
        }
    }

    private fun getProcessPid(process: Process): String {
        return try {
            val pidMethod = process.javaClass.getMethod("pid")
            pidMethod.invoke(process)?.toString() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
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
