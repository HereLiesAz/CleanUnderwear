package com.hereliesaz.cleanunderwear.util

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Crash reports the user chooses to send, by their own hand.
 *
 * The app carries no GitHub token. When it crashes, [record] keeps a scrubbed report on the
 * device: exception types and stack frames only, never an exception's message (which can echo
 * a contact's name, number or a scraped page). On the next launch the user is asked whether to
 * send it; if they agree, GitHub's new-issue page opens in their browser with the report filled
 * in, where they can read it and post it, or not.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val NEW_ISSUE = "https://github.com/HereLiesAz/CleanUnderwear/issues/new"

    /** Installs the handler once per process; the previous handler still runs after it. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(app, throwable)
            } catch (_: Throwable) {
                // Never let reporting mask the crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(context: Context, throwable: Throwable) {
        File(context.filesDir, FILE).writeText(scrub(throwable, appVersion(context)))
    }

    /** The saved report, if the app crashed since the user was last asked. */
    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun discard(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /**
     * Longest link [issueUrl] makes, in characters once encoded. GitHub refuses a request line
     * past roughly 8 KB, and a stack trace encodes to up to three times its length (spaces and
     * newlines become %20 and %0A), so the report is cut to fit, not to a length before encoding.
     */
    internal const val MAX_URL_LENGTH = 7000

    /**
     * GitHub's new-issue page with [report] filled in, for the user to read and post. A report
     * too long for the link is cut at the end, and says so.
     */
    fun issueUrl(report: String): Uri {
        val title = "Crash: " + report.lineSequence().firstOrNull { it.startsWith("Exception: ") }
            ?.removePrefix("Exception: ").orEmpty().take(200)
        var keep = minOf(report.length, 6000)
        while (true) {
            val cut = keep < report.length
            val body = "```\n" + report.take(keep) + "\n```" + if (cut) "\n\n(Report shortened to fit this link.)" else ""
            val uri = Uri.parse(NEW_ISSUE).buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("labels", "crash")
                .appendQueryParameter("body", body)
                .build()
            if (keep == 0 || uri.toString().length <= MAX_URL_LENGTH) return uri
            keep = keep * 3 / 4
        }
    }

    /** Types and frames only: a cause chain of class names and stack lines, no messages. */
    internal fun scrub(throwable: Throwable, version: String): String = buildString {
        appendLine("App: $version")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 5) {
            appendLine((if (depth == 0) "Exception: " else "Caused by: ") + t.javaClass.name)
            t.stackTrace.take(25).forEach { appendLine("    at $it") }
            t = t.cause?.takeIf { it !== t }
            depth++
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
