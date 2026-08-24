package com.mitas.ppnam.station1

/**
 * Per-station namespace topic structure per MQTT_CONTRACT.md (2026-08-17 restructure — payloads,
 * QoS, and workflow semantics unchanged; only the topic paths moved):
 *
 *   PPNAM/station_{n}                            station presence (retained, LWT)
 *   PPNAM/station_{n}/{deviceId}                 device presence (retained, LWT)
 *   PPNAM/station_{n}/{deviceId}/req/{suffix}    request  FROM device TO the station
 *   PPNAM/station_{n}/{deviceId}/res/{suffix}    response FROM station TO device
 *   PPNAM/station_{n}/res/{suffix}               station-initiated broadcast
 *
 * Presence lives on each participant's base topic node — there is no /status sub-topic.
 */
object MqttTopics {

    private fun stationBase(stationInt: Int) = "PPNAM/station_$stationInt"

    /** The station's base node — carries its retained online/offline presence payload. */
    fun stationPresence(stationInt: Int): String = stationBase(stationInt)

    /** This scanner's base node — carries its retained presence payload and Last Will. */
    fun devicePresence(stationInt: Int, deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "${stationBase(stationInt)}/$deviceId"
    }

    fun deviceRequest(stationInt: Int, deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "${stationBase(stationInt)}/$deviceId/req/$suffix"
    }

    fun deviceResponse(stationInt: Int, deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "${stationBase(stationInt)}/$deviceId/res/$suffix"
    }

    /** Station-initiated broadcasts — `res` is a reserved segment, never a device id. */
    fun stationBroadcast(stationInt: Int, suffix: String): String {
        validateSegment(suffix, "suffix")
        return "${stationBase(stationInt)}/res/$suffix"
    }

    /** One subscription capturing all of this station's traffic, presence included. */
    fun stationWildcard(stationInt: Int): String = "${stationBase(stationInt)}/#"

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}
