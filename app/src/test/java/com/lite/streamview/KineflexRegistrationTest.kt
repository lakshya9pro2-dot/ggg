package com.lite.streamview

import com.lite.streamview.registration.KineflexRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KineflexRegistrationTest {

    private lateinit var registration: KineflexRegistration

    @Before
    fun setUp() {
        registration = KineflexRegistration()
    }

    @Test
    fun testParseRenewalDirectiveTrue() {
        val json = """{"renew": true, "message": "Tunnel restart requested"}"""
        assertTrue(registration.parseRenewalDirective(json))
    }

    @Test
    fun testParseRenewalDirectiveFalse() {
        val json = """{"renew": false, "message": "Active"}"""
        assertFalse(registration.parseRenewalDirective(json))
    }

    @Test
    fun testParseRenewalDirectiveEmptyOrMalformed() {
        assertFalse(registration.parseRenewalDirective(""))
        assertFalse(registration.parseRenewalDirective(null))
        assertFalse(registration.parseRenewalDirective("not-a-json"))
        assertFalse(registration.parseRenewalDirective("{}"))
        assertFalse(registration.parseRenewalDirective("""{"status": "ok"}"""))
    }

    @Test
    fun testDefaultRegisterUrl() {
        assertEquals("https://pinggy-registry.kineflex-netflex.workers.dev/api/app", KineflexRegistration.DEFAULT_REGISTER_URL)
    }
}
