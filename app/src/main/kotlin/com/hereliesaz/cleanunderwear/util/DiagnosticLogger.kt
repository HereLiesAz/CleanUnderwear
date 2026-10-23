package com.hereliesaz.cleanunderwear.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DiagnosticLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // DateTimeFormatter is immutable + thread-safe, unlike SimpleDateFormat.
    // log() is called from arbitrary threads (workers, scrapers, UI).
    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault())

    data class LogEntry(
        val timestamp: String,
        val message: String,
        val level: LogLevel = LogEntry.LogLevel.DEBUG
    ) {
        enum class LogLevel { INFO, DEBUG, WARN, ERROR }
    }

    fun log(raw: String, level: LogEntry.LogLevel = LogEntry.LogLevel.DEBUG) {
        val message = redact(raw)
        val entry = LogEntry(dateFormat.format(Instant.now()), message, level)
        // Atomic: log() is called from many threads at once.
        _logs.update { current -> (listOf(entry) + current).take(500) } // newest first
        
        // Logcat is readable over adb and in bug reports: debug builds only.
        if (!com.hereliesaz.cleanunderwear.BuildConfig.DEBUG) return
        when (level) {
            LogEntry.LogLevel.INFO -> android.util.Log.i("CleanUnderwear", message)
            LogEntry.LogLevel.DEBUG -> android.util.Log.d("CleanUnderwear", message)
            LogEntry.LogLevel.WARN -> android.util.Log.w("CleanUnderwear", message)
            LogEntry.LogLevel.ERROR -> android.util.Log.e("CleanUnderwear", message)
        }
    }

    /**
     * Strips what would identify a contact's details in a shared log: URL query strings (where
     * searches put names) and all but the last four digits of any phone-length number.
     */
    internal fun redact(message: String): String = message
        .replace(Regex("""(https?://[^\s?#]+)[?#]\S*""")) { it.groupValues[1] + "?…" }
        .replace(Regex("""(?<!\d)(\d[\d\s().-]{5,}\d)(?!\d)""")) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length >= 7) "…" + digits.takeLast(4) else m.value
        }

    fun clear() {
        _logs.value = emptyList()
    }
}
