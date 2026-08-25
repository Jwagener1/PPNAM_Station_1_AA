package com.mitas.ppnam.station1

import org.json.JSONObject
import java.time.Instant

/**
 * Workflow request payloads (contract v3.0.0 §5-§7): the lightweight envelope — `ts`,
 * `deviceId`, `operatorSessionId` — plus the business fields. Workflow messages carry no
 * messageId/schemaVersion; those belong to the schema 4.1 authentication envelope.
 */
object WorkflowMessages {

    fun tagScan(deviceId: String, operatorSessionId: String, tagId: String): JSONObject =
        base(deviceId, operatorSessionId).put("tagId", tagId)

    fun offloadScan(
        deviceId: String,
        operatorSessionId: String,
        tagId: String,
        barcode: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("tagId", tagId)
        .put("barcode", barcode)

    /** §6.2: bagWeight is a JSON number, bagCount a positive JSON integer. */
    fun offloadConfirm(
        deviceId: String,
        operatorSessionId: String,
        tagId: String,
        barcode: String,
        bagWeight: Double,
        bagCount: Int,
        batchReference: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("tagId", tagId)
        .put("barcode", barcode)
        .put("bagWeight", bagWeight)
        .put("bagCount", bagCount)
        .put("batchReference", batchReference)

    /** Prefill display: whole kilograms without the ".0" tail an operator would have to erase. */
    fun formatWeight(weight: Double): String =
        if (weight == Math.floor(weight) && !weight.isInfinite()) weight.toLong().toString()
        else weight.toString()

    private fun base(deviceId: String, operatorSessionId: String): JSONObject = JSONObject().apply {
        put("ts", Instant.now().toString())
        put("deviceId", deviceId)
        put("operatorSessionId", operatorSessionId)
    }
}

/**
 * The three packaging values a matched offload_scan_result must carry (§6.1). Parsing returns
 * null when any is missing or out of range — a "matched" result without usable prefill is
 * treated as unusable rather than showing the operator empty fields.
 */
data class OffloadPrefill(
    val bagWeight: Double,
    val bagCount: Int,
    val batchReference: String,
) {
    companion object {
        fun fromScanResult(json: JSONObject): OffloadPrefill? {
            val weight = json.optDouble("bagWeight", Double.NaN)
            val count = json.optInt("bagCount", 0)
            val batch = json.optString("batchReference", "")
            if (weight.isNaN() || weight <= 0.0 || count <= 0 || batch.isBlank()) return null
            return OffloadPrefill(weight, count, batch)
        }
    }
}

/** Operator-edited values, validated before offload_confirm goes on the wire. */
object OffloadInput {
    fun parseWeight(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

    fun parseCount(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it > 0 }

    fun parseBatch(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() }
}
