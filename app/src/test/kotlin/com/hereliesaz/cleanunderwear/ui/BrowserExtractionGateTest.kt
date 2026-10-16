package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtractionGateTest {
    @Test
    fun resultFromPreviousContactIsRejectedAfterReset() {
        val gate = BrowserExtractionGate()
        val first = gate.beginExtraction()!!
        gate.reset()
        val second = gate.beginExtraction()!!

        assertNotEquals(first, second)
        assertFalse(gate.accept(first))
        assertTrue(gate.accept(second))
        assertFalse(gate.accept(second))
    }

    @Test
    fun navigationInvalidatesInFlightResult() {
        val gate = BrowserExtractionGate()
        val beforeNavigation = gate.beginExtraction()!!
        gate.invalidate()
        val afterNavigation = gate.beginExtraction()!!

        assertFalse(gate.accept(beforeNavigation))
        assertTrue(gate.accept(afterNavigation))
    }

    @Test
    fun completedBatchCannotExtractOrDeliverAgain() {
        val gate = BrowserExtractionGate()
        val token = gate.beginExtraction()!!
        gate.finish()

        assertNull(gate.beginExtraction())
        assertFalse(gate.accept(token))
        gate.reset()
        assertTrue(gate.beginExtraction() != null)
    }

    @Test
    fun scriptTagsEveryCallbackAndScopesDrillToMission() {
        val gate = BrowserExtractionGate()
        val script = """
            var drillKey = 'cleanunderwear-cbc-drill';
            window.HTMLOUT.dump('first');
            window.HTMLOUT.dump('second');
        """.trimIndent()

        val tagged = gate.tagScript(script, "17", "29-3")

        assertEquals(
            """
            var drillKey = 'cleanunderwear-cbc-drill-29-3';
            window.HTMLOUT.dumpTagged('17','first');
            window.HTMLOUT.dumpTagged('17','second');
            """.trimIndent(),
            tagged
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptWithoutBridgeFailsRatherThanRunningUntagged() {
        BrowserExtractionGate().tagScript("document.body.innerHTML", "5", "1-0")
    }
}
