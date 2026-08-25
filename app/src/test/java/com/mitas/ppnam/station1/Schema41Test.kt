package com.mitas.ppnam.station1

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Schema 4.1 authentication envelope per Station1_MQTT_Contract v3.0.0 §4.1-§4.2: exact
 * six-fractional-digit timestamps, response correlation on inResponseToMessageId, and
 * branching on `accepted`/`errorCode` — never on free-text reason.
 */
class Schema41Test {

    @Test
    fun `timestampUtc truncates to exactly six fractional digits`() {
        assertEquals(
            "2026-08-25T06:00:00.000123Z",
            Schema41.timestampUtc(Instant.parse("2026-08-25T06:00:00.000123456Z"))
        )
    }

    @Test
    fun `timestampUtc pads a whole second to six zeros`() {
        assertEquals(
            "2026-08-25T06:00:00.000000Z",
            Schema41.timestampUtc(Instant.parse("2026-08-25T06:00:00Z"))
        )
    }

    @Test
    fun `envelope carries the four schema 41 fields`() {
        val env = Schema41.envelope("auth-start-001", "scanner_5c64df8d86a8")
        assertEquals("auth-start-001", env.getString("messageId"))
        assertEquals("4.1", env.getString("schemaVersion"))
        assertEquals("scanner_5c64df8d86a8", env.getString("deviceId"))
        val ts = env.getString("timestampUtc")
        assertTrue(ts, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z""").matches(ts))
    }

    @Test
    fun `newMessageId is prefixed and unique per call`() {
        val a = Schema41.newMessageId("auth-start")
        val b = Schema41.newMessageId("auth-start")
        assertTrue(a.startsWith("auth-start-"))
        assertNotEquals(a, b)
        assertTrue("must fit the contract's 128-char messageId cap", a.length <= 128)
    }

    @Test
    fun `isResponseTo matches only the request message id`() {
        val response = JSONObject().put("inResponseToMessageId", "auth-start-001")
        assertTrue(Schema41.isResponseTo(response, "auth-start-001"))
        assertFalse(Schema41.isResponseTo(response, "auth-start-002"))
        assertFalse(Schema41.isResponseTo(JSONObject(), "auth-start-001"))
    }

    @Test
    fun `isAccepted defaults to false when absent`() {
        assertTrue(Schema41.isAccepted(JSONObject().put("accepted", true)))
        assertFalse(Schema41.isAccepted(JSONObject().put("accepted", false)))
        assertFalse(Schema41.isAccepted(JSONObject()))
    }

    @Test
    fun `rejectionMessage prefers reason then errorCode then a generic fallback`() {
        assertEquals(
            "Badge not recognised",
            Schema41.rejectionMessage(
                JSONObject().put("reason", "Badge not recognised").put("errorCode", "badge_rejected")
            )
        )
        assertEquals(
            "badge_rejected",
            Schema41.rejectionMessage(JSONObject().put("errorCode", "badge_rejected"))
        )
        assertEquals("Authentication failed", Schema41.rejectionMessage(JSONObject()))
    }
}
