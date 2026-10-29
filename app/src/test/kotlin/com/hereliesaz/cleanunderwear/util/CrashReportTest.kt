package com.hereliesaz.cleanunderwear.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The link a crash report opens: it must stay short enough for GitHub to accept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportTest {

    private fun report(frames: Int) = buildString {
        appendLine("App: 1.0")
        appendLine("Android: 14 (API 34)")
        appendLine("Exception: java.lang.IllegalStateException")
        repeat(frames) { appendLine("    at com.hereliesaz.cleanunderwear.domain.Thing.method$it(Thing.kt:$it)") }
    }

    @Test
    fun aShortReportIsSentWhole() {
        val short = report(3)
        val uri = CrashReport.issueUrl(short)
        assertTrue(uri.toString().length <= CrashReport.MAX_URL_LENGTH)
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains(short.trim()))
        assertFalse(body.contains("shortened"))
        assertEquals("Crash: java.lang.IllegalStateException", uri.getQueryParameter("title"))
        assertEquals("crash", uri.getQueryParameter("labels"))
    }

    @Test
    fun aLongReportIsCutSoTheEncodedLinkStaysUnderTheLimit() {
        // Over 6000 characters, and spaces and newlines each encode to three.
        val long = report(200)
        assertTrue(long.length > 6000)
        val uri = CrashReport.issueUrl(long)
        assertTrue("link was ${uri.toString().length} characters", uri.toString().length <= CrashReport.MAX_URL_LENGTH)
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains("shortened"))
        assertTrue(body.startsWith("```\nApp: 1.0"))
    }

    @Test
    fun anAbsurdlyLongTitleCannotBlowTheLimit() {
        val uri = CrashReport.issueUrl("Exception: " + "A".repeat(50_000) + "\n" + report(200))
        assertTrue(uri.toString().length <= CrashReport.MAX_URL_LENGTH)
    }
}
