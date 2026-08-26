package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scanner restarted faster than the broker notices the old connection died (~keepalive,
 * 15s) gets its fresh retained `online` clobbered by the old connection's Last Will —
 * leaving retained presence stuck at `offline` while the scanner is connected. The app
 * subscribes to the station wildcard, so it sees its own presence topic and can heal by
 * republishing `online` — but must never do so while gracefully shutting down, because
 * disconnect() publishes retained `offline` first and the broker echoes it back before
 * the DISCONNECT completes.
 */
class PresenceSelfHealTest {

    private val own = "PPNAM/station_1/scanner_c95f4d2b00c3"

    @Test
    fun `restores online when own presence reads offline while connected`() {
        assertTrue(PresenceSelfHeal.shouldRestoreOnline(own, "offline", own, isConnected = true, wantsConnection = true))
    }

    @Test
    fun `ignores another scanner's presence`() {
        assertFalse(PresenceSelfHeal.shouldRestoreOnline("PPNAM/station_1/scanner_002e874fd1ce", "offline", own, isConnected = true, wantsConnection = true))
    }

    @Test
    fun `ignores the station's presence node`() {
        assertFalse(PresenceSelfHeal.shouldRestoreOnline("PPNAM/station_1", "offline", own, isConnected = true, wantsConnection = true))
    }

    @Test
    fun `ignores an online payload`() {
        assertFalse(PresenceSelfHeal.shouldRestoreOnline(own, "online", own, isConnected = true, wantsConnection = true))
    }

    @Test
    fun `does not restore while disconnected`() {
        assertFalse(PresenceSelfHeal.shouldRestoreOnline(own, "offline", own, isConnected = false, wantsConnection = true))
    }

    @Test
    fun `does not restore during graceful shutdown when the offline echo races the disconnect`() {
        assertFalse(PresenceSelfHeal.shouldRestoreOnline(own, "offline", own, isConnected = true, wantsConnection = false))
    }

    @Test
    fun `payload comparison tolerates case and surrounding whitespace`() {
        assertTrue(PresenceSelfHeal.shouldRestoreOnline(own, " Offline ", own, isConnected = true, wantsConnection = true))
    }
}
