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

    /**
     * §6.3: bagWeight is a JSON number, bagCount a positive JSON integer. The document
     * reference is repeated verbatim from the pallet's scan result (§6.1).
     */
    fun offloadConfirm(
        deviceId: String,
        operatorSessionId: String,
        tagId: String,
        barcode: String,
        documentType: String,
        documentNumber: String,
        bagWeight: Double,
        bagCount: Int,
        batchReference: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("tagId", tagId)
        .put("barcode", barcode)
        .put("documentType", documentType)
        .put("documentNumber", documentNumber)
        .put("bagWeight", bagWeight)
        .put("bagCount", bagCount)
        .put("batchReference", batchReference)

    /** §6.4: close the looked-up document with a Short/Complete/Over classification. */
    fun offloadComplete(
        deviceId: String,
        operatorSessionId: String,
        documentType: String,
        documentNumber: String,
        status: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("documentType", documentType)
        .put("documentNumber", documentNumber)
        .put("status", status)

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

/** The §6.4 receipt classifications, exactly as they go on the wire. */
object OffloadStatus {
    const val SHORT = "short"
    const val COMPLETE = "complete"
    const val OVER = "over"
}

/**
 * The document a scanned pallet belongs to, from the four flat fields every matched
 * offload_scan_result carries (§6.1). The reference is repeated verbatim on the pallet's
 * confirm and on a completion — it is never scanner-side state or operator entry.
 */
data class OffloadDocument(
    val documentType: String,
    val documentNumber: String,
    val palletsScanned: Int,
    val palletsExpected: Int,
) {
    companion object {
        private val DOCUMENT_TYPES = setOf("purchase_order", "stock_transfer")

        fun fromScanResult(json: JSONObject): OffloadDocument? {
            val type = json.optString("documentType", "")
            val number = json.optString("documentNumber", "").trim()
            val scanned = json.optInt("palletsScanned", -1)
            val expected = json.optInt("palletsExpected", -1)
            if (type !in DOCUMENT_TYPES || number.isEmpty() || scanned < 0 || expected < 0) return null
            return OffloadDocument(type, number, scanned, expected)
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
