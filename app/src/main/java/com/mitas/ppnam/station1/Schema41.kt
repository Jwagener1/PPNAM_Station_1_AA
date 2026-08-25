package com.mitas.ppnam.station1

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.UUID

/**
 * The shared Station 2 schema 4.1 authentication envelope, as adopted by Station 1
 * (Station1_MQTT_Contract v3.0.0 §4.1-§4.2):
 *
 *  - every auth request carries messageId, schemaVersion "4.1", deviceId and a timestampUtc
 *    with exactly six fractional digits;
 *  - responses correlate on inResponseToMessageId and are branched on `accepted` and
 *    `errorCode` — free-text `reason` is display-only.
 */
object Schema41 {

    const val SCHEMA_VERSION = "4.1"

    // The contract fixes the auth timestamp to yyyy-MM-dd'T'HH:mm:ss.ffffff'Z' — exactly six
    // fractional digits, so a plain Instant.toString() (variable precision) is not acceptable.
    private val TIMESTAMP_FORMAT = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, true)
        .appendLiteral('Z')
        .toFormatter()
        .withZone(ZoneOffset.UTC)

    fun timestampUtc(instant: Instant = Instant.now()): String = TIMESTAMP_FORMAT.format(instant)

    /** One id per logical operation — a retry must reuse it, so callers mint it once up front. */
    fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    fun envelope(messageId: String, deviceId: String): JSONObject = JSONObject().apply {
        put("messageId", messageId)
        put("schemaVersion", SCHEMA_VERSION)
        put("deviceId", deviceId)
        put("timestampUtc", timestampUtc())
    }

    fun isResponseTo(response: JSONObject, requestMessageId: String): Boolean =
        response.optString("inResponseToMessageId", "") == requestMessageId

    fun isAccepted(response: JSONObject): Boolean = response.optBoolean("accepted", false)

    /** Operator-facing text for a rejection: the station's sanitized reason, else the code. */
    fun rejectionMessage(response: JSONObject): String =
        response.optString("reason", "").ifBlank {
            response.optString("errorCode", "").ifBlank { "Authentication failed" }
        }
}
