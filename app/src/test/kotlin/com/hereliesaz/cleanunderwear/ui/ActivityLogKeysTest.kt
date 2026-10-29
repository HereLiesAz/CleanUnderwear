package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity log's rows are keyed by entry: distinct, and unmoved when a newer entry arrives. */
class ActivityLogKeysTest {

    private fun entry(time: String, text: String) = LogEntry(time, text)

    @Test
    fun everyKeyIsDistinctEvenForRepeatedLines() {
        val log = listOf(entry("10:00:00.001", "a"), entry("10:00:00.001", "a"), entry("10:00:00.001", "a"), entry("10:00:00.000", "b"))
        val keys = activityLogKeys(log)
        assertEquals(log.size, keys.toSet().size)
    }

    @Test
    fun anEntryKeepsItsKeyWhenNewerOnesArriveAtTheTop() {
        val older = listOf(entry("10:00:00.001", "a"), entry("10:00:00.001", "a"), entry("10:00:00.000", "b"))
        val newer = listOf(entry("10:00:01.000", "c")) + older
        assertEquals(activityLogKeys(older), activityLogKeys(newer).drop(1))
    }

    @Test
    fun anEmptyLogHasNoKeys() {
        assertTrue(activityLogKeys(emptyList()).isEmpty())
    }
}
