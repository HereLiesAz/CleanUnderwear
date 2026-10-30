package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger

/**
 * One list key per activity-log entry, so the log's rows are matched to their entries when a new
 * one arrives at the top instead of every row being redrawn. An entry's id is unique and fixed,
 * so a key never changes as newer entries arrive and two keys are never equal, which a lazy list
 * requires. [entries] are newest first.
 */
internal fun activityLogKeys(entries: List<DiagnosticLogger.LogEntry>): List<Long> = entries.map { it.id }
