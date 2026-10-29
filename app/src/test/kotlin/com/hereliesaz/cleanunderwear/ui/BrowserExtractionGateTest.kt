package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtractionGateTest {
    private val key = "29-3"

    @Test
    fun resultFromPreviousContactIsRejectedAfterReset() {
        val gate = BrowserExtractionGate()
        val first = gate.beginExtraction(key)!!
        gate.reset()
        val second = gate.beginExtraction(key)!!

        assertNotEquals(first, second)
        assertFalse(gate.accept(first, key))
        assertTrue(gate.accept(second, key))
        assertFalse(gate.accept(second, key))
    }

    @Test
    fun tokensAreUnguessable() {
        val gate = BrowserExtractionGate()
        val tokens = (1..50).map { gate.invalidate(); gate.beginExtraction(key)!! }
        assertTrue(tokens.all { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
        assertEquals(tokens.size, tokens.toSet().size)
        assertFalse(tokens.contains("1"))
    }

    @Test
    fun navigationInvalidatesInFlightResult() {
        val gate = BrowserExtractionGate()
        val beforeNavigation = gate.beginExtraction(key)!!
        gate.invalidate()
        val afterNavigation = gate.beginExtraction(key)!!

        assertFalse(gate.accept(beforeNavigation, key))
        assertTrue(gate.accept(afterNavigation, key))
    }

    @Test
    fun completedBatchCannotExtractOrDeliverAgain() {
        val gate = BrowserExtractionGate()
        val token = gate.beginExtraction(key)!!
        gate.finish()

        assertNull(gate.beginExtraction(key))
        assertFalse(gate.accept(token, key))
        gate.reset()
        assertTrue(gate.beginExtraction(key) != null)
    }

    @Test
    fun resultIsBoundToTheMissionItWasMintedFor() {
        val gate = BrowserExtractionGate()
        val token = gate.beginExtraction("29-3")!!

        // The right token with another mission's key (a late dump from the previous contact) loses...
        assertFalse(gate.accept(token, "29-2"))
        assertFalse(gate.accept(token, null))
        // ...and does not use the token up: the real result still lands, once.
        assertTrue(gate.accept(token, "29-3"))
        assertFalse(gate.accept(token, "29-3"))
    }

    @Test
    fun missingOrWrongTokenIsRejected() {
        val gate = BrowserExtractionGate()
        val token = gate.beginExtraction(key)!!

        assertFalse(gate.accept(null, key))
        assertFalse(gate.accept("0".repeat(32), key))
        assertTrue(gate.accept(token, key))
    }

    @Test
    fun bridgeNamesAreRandomIdentifiersNotTheFixedOne() {
        val names = (1..20).map { BrowserExtractionGate.newBridgeName() }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        assertFalse(names.any { it == "HTMLOUT" })
    }

    @Test
    fun scriptRoutesEveryDumpThroughTheTaggedClosure() {
        val gate = BrowserExtractionGate("cuBridgeName")
        val script = """
            window.HTMLOUT.dump('first');
            window.HTMLOUT.dump('second');
        """.trimIndent()

        val tagged = gate.tagScript(script, "17", "29-3")

        // Every dump goes through the closure; the mission script no longer names the bridge.
        assertTrue(tagged.contains("cuDump('first');"))
        assertTrue(tagged.contains("cuDump('second');"))
        assertFalse(tagged.contains("HTMLOUT"))
        // The closure holds the bridge, the token and the mission key.
        assertTrue(tagged.contains("window['cuBridgeName']"))
        assertTrue(tagged.contains("dumpTagged('17', '29-3', html)"))
        // Wrapped, so none of it leaks into the page's globals.
        assertTrue(tagged.startsWith("(function(){"))
        assertTrue(tagged.endsWith("})();"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptWithoutBridgeFailsRatherThanRunningUntagged() {
        BrowserExtractionGate().tagScript("document.body.innerHTML", "5", "1-0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missionKeyThatCouldBreakOutOfTheScriptIsRefused() {
        BrowserExtractionGate().tagScript("window.HTMLOUT.dump('x')", "5", "1-0'); alert(1); ('")
    }

    @Test(expected = IllegalArgumentException::class)
    fun tokenThatCouldBreakOutOfTheScriptIsRefused() {
        BrowserExtractionGate().tagScript("window.HTMLOUT.dump('x')", "5'); alert(1); ('", "1-0")
    }
}
