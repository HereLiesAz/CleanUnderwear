package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger

/**
 * One list key per activity-log entry, so the log's rows are matched to their entries when a new
 * one arrives at the top instead of every row being redrawn. A log entry has no id of its own, so
 * the key is its time, level and text; entries that repeat all three (the same line twice in one
 * millisecond) get a number counted from the oldest, which does not change as newer entries
 * arrive. Keys are always distinct, which a lazy list requires. [entries] are newest first.
 */
internal fun activityLogKeys(entries: List<DiagnosticLogger.LogEntry>): List<String> {
    val seen = HashMap<String, Int>()
    val oldestFirst = entries.asReversed().map { entry ->
        val base = "${entry.timestamp}|${entry.level}|${entry.message}"
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
    return oldestFirst.asReversed()
}
