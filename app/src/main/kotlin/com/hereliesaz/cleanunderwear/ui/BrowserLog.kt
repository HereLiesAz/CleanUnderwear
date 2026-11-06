package com.hereliesaz.cleanunderwear.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger

/** Whether the log under a browser screen is open; kept between runs. */
internal object BrowserLogPreference {
    private const val FILE = "browser_log"
    private const val KEY = "shown"

    fun shown(context: Context): Boolean = try {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    } catch (e: Exception) {
        false
    }

    fun set(context: Context, shown: Boolean) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, shown).apply()
        } catch (e: Exception) {
            // A preference that can't be saved is only forgotten next time.
        }
    }
}

/** How many of the newest log lines the panel lists. */
private const val LOG_LINES_SHOWN = 80

/** The log lines as text, oldest first, under [header]: what "Copy log" puts on the clipboard. */
internal fun browserLogText(header: String, entries: List<DiagnosticLogger.LogEntry>): String =
    header + "\n\n" + entries.take(LOG_LINES_SHOWN).asReversed().joinToString("\n") { "${it.timestamp}  ${it.message}" }

/**
 * Under every screen that runs scripts in a page: one line saying what it is doing or waiting on
 * right now, and an optional log that says what it is trying to do, step by step, and why it
 * can or can't. The header names the build, so which version this is never has to be guessed.
 */
@Composable
internal fun BrowserLogBar(
    status: String,
    header: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AppCaption(status, modifier = Modifier.weight(1f))
            AppTextAction(
                text = stringResource(if (open) R.string.browser_log_hide else R.string.browser_log_show),
                onClick = onToggle,
                fillWidth = false
            )
        }
        if (open) LogPanel(header)
    }
}

@Composable
private fun LogPanel(header: String) {
    val entries by DiagnosticLogger.logs.collectAsState()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AppCaption(header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            entries.take(LOG_LINES_SHOWN).forEach { entry ->
                val bad = entry.level == DiagnosticLogger.LogEntry.LogLevel.WARN ||
                    entry.level == DiagnosticLogger.LogEntry.LogLevel.ERROR
                Text(
                    "${entry.timestamp}  ${entry.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (bad) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppTextAction(
            text = stringResource(R.string.browser_log_copy),
            onClick = {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("log", browserLogText(header, entries)))
                    Toast.makeText(context, R.string.browser_log_copied, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // No clipboard to copy to: the log is still on screen.
                }
            }
        )
    }
}
