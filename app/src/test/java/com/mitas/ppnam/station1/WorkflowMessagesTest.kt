package com.mitas.ppnam.station1

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract v3.0.0 §5-§7: workflow requests use the lightweight envelope (ts, deviceId,
 * operatorSessionId) — no messageId/schemaVersion — and offload_confirm carries bagWeight as a
 * JSON number, bagCount as a JSON integer, batchReference as a string.
 */
class WorkflowMessagesTest {

    @Test
    fun `tagScan carries the lightweight envelope and tagId`() {
        val p = WorkflowMessages.tagScan("scanner_abc", "sess-1", "E280TAG")
        assertEquals("scanner_abc", p.getString("deviceId"))
        assertEquals("sess-1", p.getString("operatorSessionId"))
        assertEquals("E280TAG", p.getString("tagId"))
        assertTrue(p.getString("ts").endsWith("Z"))
        assertTrue("workflow messages carry no schema 4.1 fields", !p.has("messageId") && !p.has("schemaVersion"))
    }

    @Test
    fun `offloadScan adds the barcode`() {
        val p = WorkflowMessages.offloadScan("scanner_abc", "sess-1", "E280TAG", "BC-000123")
        assertEquals("E280TAG", p.getString("tagId"))
        assertEquals("BC-000123", p.getString("barcode"))
    }

    @Test
    fun `offloadConfirm sends typed values`() {
        val p = WorkflowMessages.offloadConfirm(
            "scanner_abc", "sess-1", "E280TAG", "BC-000123",
            bagWeight = 24.5, bagCount = 40, batchReference = "BATCH-2026-0815",
        )
        assertEquals(24.5, p.getDouble("bagWeight"), 0.0)
        assertTrue("bagCount must be a JSON integer", p.get("bagCount") is Int)
        assertEquals(40, p.getInt("bagCount"))
        assertEquals("BATCH-2026-0815", p.getString("batchReference"))
    }

    @Test
    fun `prefill parses a matched scan result`() {
        val json = JSONObject()
            .put("matched", true)
            .put("bagWeight", 25.0)
            .put("bagCount", 40)
            .put("batchReference", "BATCH-2026-0815")
        val prefill = OffloadPrefill.fromScanResult(json)
        assertNotNull(prefill)
        assertEquals(25.0, prefill!!.bagWeight, 0.0)
        assertEquals(40, prefill.bagCount)
        assertEquals("BATCH-2026-0815", prefill.batchReference)
    }

    @Test
    fun `prefill rejects missing or non-positive values`() {
        assertNull(OffloadPrefill.fromScanResult(JSONObject().put("matched", true)))
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 0.0).put("bagCount", 40).put("batchReference", "B")
            )
        )
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 25.0).put("bagCount", 0).put("batchReference", "B")
            )
        )
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 25.0).put("bagCount", 40).put("batchReference", " ")
            )
        )
    }

    @Test
    fun `operator input parsing enforces positive typed values`() {
        assertEquals(24.5, OffloadInput.parseWeight(" 24.5 ")!!, 0.0)
        assertNull(OffloadInput.parseWeight("0"))
        assertNull(OffloadInput.parseWeight("-1"))
        assertNull(OffloadInput.parseWeight("abc"))
        assertNull(OffloadInput.parseWeight(""))

        assertEquals(40, OffloadInput.parseCount(" 40 "))
        assertNull(OffloadInput.parseCount("0"))
        assertNull(OffloadInput.parseCount("2.5"))

        assertEquals("BATCH-1", OffloadInput.parseBatch(" BATCH-1 "))
        assertNull(OffloadInput.parseBatch("   "))
    }

    @Test
    fun `formatWeight shows whole kilograms without a decimal tail`() {
        assertEquals("25", WorkflowMessages.formatWeight(25.0))
        assertEquals("24.5", WorkflowMessages.formatWeight(24.5))
    }
}
