package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLogTest {
    private fun entry(n: Int) = LogEntry(timestamp = "10:00:%02d.000".format(n), message = "step $n")

    @Test
    fun theCopiedLogNamesTheBuildFirstAndReadsOldestToNewest() {
        // The activity log holds newest first.
        val text = browserLogText("CleanUnderwear 1.17.3.1 (build 47)", listOf(entry(3), entry(2), entry(1)))
        val lines = text.lines()
        assertEquals("CleanUnderwear 1.17.3.1 (build 47)", lines.first())
        assertEquals(listOf("10:00:01.000  step 1", "10:00:02.000  step 2", "10:00:03.000  step 3"), lines.takeLast(3))
    }

    @Test
    fun onlyTheNewestEightyLinesAreKept() {
        val entries = (200 downTo 1).map(::entry)
        val body = browserLogText("h", entries).lines().drop(2)
        assertEquals(80, body.size)
        assertTrue(body.last().endsWith("step 200"))
        assertTrue(body.first().endsWith("step 121"))
    }

    @Test
    fun anEmptyLogIsJustTheHeader() {
        assertEquals("h", browserLogText("h", emptyList()).trim())
    }
}
