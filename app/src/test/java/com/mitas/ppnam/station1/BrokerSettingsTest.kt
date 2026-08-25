package com.mitas.ppnam.station1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Broker configuration mirrors Station 2's AppSettings: sensible defaults for the transport,
 * but NO default credentials — a shared username/password baked into the APK is exactly what
 * the Schema 4.1 handoff forbids, so an unprovisioned device must report no credential.
 */
class BrokerSettingsTest {

    @Test
    fun `defaults point at the production broker over TLS websockets with no credential`() {
        val settings = BrokerSettings()
        assertEquals("mqtt.sysone.co.za", settings.host)
        assertEquals(443, settings.port)
        assertTrue(settings.useWebSocket)
        assertTrue(settings.useTls)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
    }

    @Test
    fun `an unprovisioned device has no broker credential`() {
        assertFalse(BrokerSettings().hasBrokerCredential)
    }

    @Test
    fun `a username without a password is not a credential`() {
        assertFalse(BrokerSettings(username = "scanner_7").hasBrokerCredential)
    }

    @Test
    fun `a password without a username is not a credential`() {
        assertFalse(BrokerSettings(password = "s3cret").hasBrokerCredential)
    }

    @Test
    fun `both username and password make a credential`() {
        assertTrue(BrokerSettings(username = "scanner_7", password = "s3cret").hasBrokerCredential)
    }

    @Test
    fun `blank whitespace credentials do not count`() {
        assertFalse(BrokerSettings(username = "  ", password = "  ").hasBrokerCredential)
    }

    @Test
    fun `parsePort accepts a valid port`() {
        assertEquals(443, BrokerSettings.parsePort("443"))
    }

    @Test
    fun `parsePort trims surrounding whitespace`() {
        assertEquals(1883, BrokerSettings.parsePort(" 1883 "))
    }

    @Test
    fun `parsePort rejects zero`() {
        assertNull(BrokerSettings.parsePort("0"))
    }

    @Test
    fun `parsePort rejects ports above 65535`() {
        assertNull(BrokerSettings.parsePort("65536"))
    }

    @Test
    fun `parsePort rejects non-numeric input`() {
        assertNull(BrokerSettings.parsePort("abc"))
    }

    @Test
    fun `parsePort rejects empty input`() {
        assertNull(BrokerSettings.parsePort(""))
    }
}
