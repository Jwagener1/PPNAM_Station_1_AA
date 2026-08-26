package com.mitas.ppnam.station1aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract v3.0.0 §3: allowedTabs values are tag_assignment/offload; a missing or empty list
 * means NO workflows enabled (fail closed), replacing the old fail-open behavior.
 */
class OperatorSessionTest {

    private fun session(tabs: List<String>) = OperatorSession(
        operatorSessionId = "s1",
        operatorId = "op1",
        operatorName = "Operator One",
        role = "Operator",
        allowedTabs = tabs,
    )

    @Test
    fun `wire values match the contract`() {
        assertEquals("tag_assignment", StationTab.TAG_ASSIGNMENT)
        assertEquals("offload", StationTab.OFFLOAD)
    }

    @Test
    fun `an empty allowedTabs list enables nothing`() {
        val s = session(emptyList())
        assertFalse(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertFalse(s.canShow(StationTab.OFFLOAD))
    }

    @Test
    fun `only the listed workflows are enabled`() {
        val s = session(listOf(StationTab.OFFLOAD))
        assertFalse(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertTrue(s.canShow(StationTab.OFFLOAD))
    }

    @Test
    fun `both workflows enabled when both are listed`() {
        val s = session(listOf(StationTab.TAG_ASSIGNMENT, StationTab.OFFLOAD))
        assertTrue(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertTrue(s.canShow(StationTab.OFFLOAD))
    }
}
