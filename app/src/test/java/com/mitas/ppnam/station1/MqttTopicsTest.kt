package com.mitas.ppnam.station1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Per-station namespace per MQTT_CONTRACT.md (2026-08-17 restructure): all Station 1 traffic
 * nests under PPNAM/station_1/..., and presence lives on the base topic nodes — there is no
 * /status sub-topic.
 *
 * This app serves Station 1 only, so the station segment is fixed rather than a parameter.
 */
class MqttTopicsTest {

    @Test
    fun `station presence is the station base topic`() {
        assertEquals("PPNAM/station_1", MqttTopics.stationPresence())
    }

    @Test
    fun `device presence lives on the device base node`() {
        assertEquals("PPNAM/station_1/scanner_2", MqttTopics.devicePresence("scanner_2"))
    }

    @Test
    fun `device request nests under the station namespace`() {
        assertEquals(
            "PPNAM/station_1/scanner_1/req/assignment_v2",
            MqttTopics.deviceRequest("scanner_1", "assignment_v2")
        )
    }

    @Test
    fun `device response nests under the station namespace`() {
        assertEquals(
            "PPNAM/station_1/scanner_1/res/assignment_result",
            MqttTopics.deviceResponse("scanner_1", "assignment_result")
        )
    }

    @Test
    fun `station broadcast goes out under the station res tree`() {
        assertEquals(
            "PPNAM/station_1/res/sap_products_response",
            MqttTopics.stationBroadcast("sap_products_response")
        )
    }

    @Test
    fun `station wildcard captures all station traffic`() {
        assertEquals("PPNAM/station_1/#", MqttTopics.stationWildcard())
    }

    @Test
    fun `a deviceId containing a topic separator is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceRequest("scanner/1", "assignment")
        }
    }

    @Test
    fun `a suffix containing a wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceResponse("scanner_1", "res+ult")
        }
    }

    @Test
    fun `a blank suffix is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.stationBroadcast(" ")
        }
    }
}
