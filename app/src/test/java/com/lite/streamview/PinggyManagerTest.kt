package com.lite.streamview

import com.lite.streamview.tunnel.PinggyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PinggyManagerTest {

    @Test
    fun testConfigurationConstants() {
        assertEquals("free.pinggy.io", PinggyManager.PINGGY_HOST)
        assertEquals(443, PinggyManager.PINGGY_PORT)
        assertEquals("qr", PinggyManager.PINGGY_USER)
        assertEquals(7777, PinggyManager.PINGGY_TARGET_PORT)
        assertEquals(7777, PinggyManager.LOCAL_FORWARD_PORT)
        assertEquals(10, PinggyManager.RENEW_BEFORE_EXPIRY_MINUTES)
        assertEquals("https://pinggy-registry.kineflex-netflex.workers.dev/api/app", PinggyManager.REGISTER_URL)
    }

    @Test
    fun testExtractPublicUrlVariants() {
        // Dummy manager instance with reflection/static method testing
        // Standard .pinggy.link
        val line1 = "Your URL: https://abc123xyz.pinggy.link"
        val pattern = java.util.regex.Pattern.compile(
            """https://([a-zA-Z0-9-.]+\.(?:pinggy\.link|free\.pinggy\.net|run\.pinggy-free\.link|pinggy-free\.link|free\.pinggy\.io|pinggy\.online|pinggy\.io|pinggy-free\.me))(?::\d+)?(?:/[^\s]*)?""",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )
        val m1 = pattern.matcher(line1)
        var found1: String? = null
        if (m1.find()) found1 = m1.group()
        assertEquals("https://abc123xyz.pinggy.link", found1)

        // Real output from Pinggy test
        val line2 = "https://godzl-103-79-170-210.run.pinggy-free.link"
        val m2 = pattern.matcher(line2)
        var found2: String? = null
        if (m2.find()) found2 = m2.group()
        assertEquals("https://godzl-103-79-170-210.run.pinggy-free.link", found2)

        // free.pinggy.net
        val line3 = "https://dzxqv-103-79-170-210.free.pinggy.net"
        val m3 = pattern.matcher(line3)
        var found3: String? = null
        if (m3.find()) found3 = m3.group()
        assertEquals("https://dzxqv-103-79-170-210.free.pinggy.net", found3)

        // Pinggy online with port
        val line4 = "URL: https://rndnj-103-170-183-11.a.free.pinggy.online:37315"
        val m4 = pattern.matcher(line4)
        var found4: String? = null
        if (m4.find()) found4 = m4.group()
        assertEquals("https://rndnj-103-170-183-11.a.free.pinggy.online:37315", found4)
    }

    @Test
    fun testExcludeAdminAndDashboardUrls() {
        val excluded = setOf(
            "dashboard.pinggy.io",
            "pinggy.io",
            "api.pinggy.io",
            "admin.pinggy.io",
            "docs.pinggy.io",
            "openssh.com"
        )

        val dashboard = "https://dashboard.pinggy.io"
        val host1 = dashboard.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        assertEquals(true, excluded.contains(host1))

        val openSsh = "https://openssh.com/pq.html"
        val host2 = openSsh.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        assertEquals(true, excluded.contains(host2))

        val tunnel = "https://abc.pinggy.link"
        val host3 = tunnel.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        assertEquals(false, excluded.contains(host3))
    }

    @Test
    fun testExpiryRegexDetection() {
        val expiryPattern = java.util.regex.Pattern.compile(
            """expire(?:s)?\s+in\s+(\d+)\s+minute""",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )

        val line = "Your tunnel will expire in 60 minutes. Upgrade to Pinggy Pro to get unrestricted tunnels."
        val m = expiryPattern.matcher(line)
        assertEquals(true, m.find())
        assertEquals("60", m.group(1))

        val line2 = "Tunnel expires in 55 minutes"
        val m2 = expiryPattern.matcher(line2)
        assertEquals(true, m2.find())
        assertEquals("55", m2.group(1))
    }

    @Test
    fun testCleanTerminalOutput() {
        val raw = "\u001B[2J\u001B[H\u001B[32mTunnel Connected!\u001B[0m\r\n\u001B(0lqqk\u001B(B"
        val cleaned = PinggyManager.cleanTerminalOutput(raw)
        assertEquals(false, cleaned.contains("\u001B"))
        assertEquals(true, cleaned.contains("Tunnel Connected!"))
    }

    @Test
    fun testExtractRealInteractivePinggyUrls() {
        // Real URLs observed in Pinggy interactive terminal UI
        val chunk1 = "\u001B[2J\u001B[H┌────────────────────────────────────────────────────────┐\r\n" +
                "│  https://jwxgo-103-95-164-167.free.pinggy.net          │\r\n" +
                "└────────────────────────────────────────────────────────┘"
        val url1 = PinggyManager.extractPublicUrl(chunk1)
        assertEquals("https://jwxgo-103-95-164-167.free.pinggy.net", url1)

        val chunk2 = "\u001B[1;34mYour URL: \u001B[4mhttps://kuhzc-103-95-164-167.run.pinggy-free.link\u001B[0m"
        val url2 = PinggyManager.extractPublicUrl(chunk2)
        assertEquals("https://kuhzc-103-95-164-167.run.pinggy-free.link", url2)
    }
}
