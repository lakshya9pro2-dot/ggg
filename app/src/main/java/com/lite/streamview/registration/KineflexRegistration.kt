package com.lite.streamview.registration

import com.lite.streamview.util.AppLogger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Result data class for Kineflex API registration and status check calls.
 */
data class RegistrationResult(
    val statusCode: Int,
    val success: Boolean,
    val shouldRenew: Boolean = false,
    val rawResponse: String? = null,
    val errorMessage: String? = null
)

/**
 * Lightweight HTTP registration client for communicating with the Kineflex backend.
 * Uses standard Android HttpsURLConnection without any heavy third-party networking libraries.
 */
class KineflexRegistration(
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 10000
) {

    companion object {
        const val DEFAULT_REGISTER_URL = "https://pinggy-registry.kineflex-netflex.workers.dev/api/app"
    }

    /**
     * Registers the public Pinggy URL and expiration timestamp with Kineflex.
     *
     * @param registerUrl Target registration endpoint (default: https://kineflex.site/api/app)
     * @param publicUrl Public HTTPS URL obtained from Pinggy (e.g. https://xxxxx.pinggy.link)
     * @param expiresAtSeconds Epoch timestamp in seconds when the Pinggy URL expires
     * @return RegistrationResult containing HTTP status, success boolean, and whether renewal is requested
     */
    fun register(
        registerUrl: String = DEFAULT_REGISTER_URL,
        publicUrl: String,
        expiresAtSeconds: Long
    ): RegistrationResult {
        AppLogger.log("Pinggy", "Registering URL: $publicUrl (expires: $expiresAtSeconds)")

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(registerUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LiteWebView-Pinggy/1.0")
            }

            // Construct payload: {"url": "...", "expires_at": ...}
            val payload = JSONObject().apply {
                put("url", publicUrl)
                put("expires_at", expiresAtSeconds)
            }.toString()

            connection.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
            }

            val statusCode = connection.responseCode
            val isSuccess = statusCode in 200..299

            val responseBody = readResponseBody(connection, isSuccess)
            val shouldRenew = parseRenewalDirective(responseBody)

            if (isSuccess) {
                AppLogger.log("Pinggy", "Registration successful (HTTP $statusCode)")
            } else {
                handleHttpErrorStatus(statusCode, responseBody)
            }

            RegistrationResult(
                statusCode = statusCode,
                success = isSuccess,
                shouldRenew = shouldRenew,
                rawResponse = responseBody,
                errorMessage = if (!isSuccess) "HTTP $statusCode: $responseBody" else null
            )
        } catch (e: java.net.SocketTimeoutException) {
            val msg = "Connection timed out connecting to $registerUrl"
            AppLogger.log("Pinggy", "Registration failed: $msg")
            RegistrationResult(statusCode = 0, success = false, errorMessage = msg)
        } catch (e: java.net.UnknownHostException) {
            val msg = "Unable to resolve host: ${e.message}"
            AppLogger.log("Pinggy", "Registration failed: $msg")
            RegistrationResult(statusCode = 0, success = false, errorMessage = msg)
        } catch (e: Exception) {
            val msg = "Network error: ${e.message ?: "Unknown error"}"
            AppLogger.log("Pinggy", "Registration failed: $msg")
            RegistrationResult(statusCode = 0, success = false, errorMessage = msg)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checks if Kineflex server directs the client to renew the tunnel.
     * Useful for periodic heartbeat polling.
     */
    fun checkRenewal(registerUrl: String = DEFAULT_REGISTER_URL): RegistrationResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(registerUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LiteWebView-Pinggy/1.0")
            }

            val statusCode = connection.responseCode
            val isSuccess = statusCode in 200..299
            val responseBody = readResponseBody(connection, isSuccess)
            val shouldRenew = parseRenewalDirective(responseBody)

            RegistrationResult(
                statusCode = statusCode,
                success = isSuccess,
                shouldRenew = shouldRenew,
                rawResponse = responseBody
            )
        } catch (e: Exception) {
            RegistrationResult(
                statusCode = 0,
                success = false,
                errorMessage = e.message
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses the response body for `{"renew": true}` directives.
     */
    fun parseRenewalDirective(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            val json = JSONObject(body)
            json.optBoolean("renew", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, success: Boolean): String {
        return try {
            val stream = if (success) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun handleHttpErrorStatus(code: Int, body: String) {
        when (code) {
            400 -> AppLogger.log("Pinggy", "Registration HTTP 400 Bad Request: $body")
            401, 403 -> AppLogger.log("Pinggy", "Registration HTTP $code Unauthorized/Forbidden")
            500 -> AppLogger.log("Pinggy", "Registration HTTP 500 Server Error: $body")
            else -> AppLogger.log("Pinggy", "Registration HTTP $code: $body")
        }
    }
}
