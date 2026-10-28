package com.hereliesaz.cleanunderwear.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two promises about what leaves the phone in text: crash reports and the shared activity log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyTest {

    @Test
    fun aCrashReportCarriesTypesAndFrames_neverMessages() {
        val crash = IllegalStateException(
            "Scrape failed for Jane Doe 504-555-0100",
            RuntimeException("https://example.test/?q=Jane+Doe")
        )
        val report = CrashReport.scrub(crash, "1.0")
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException"))
        assertFalse(report.contains("Jane"))
        assertFalse(report.contains("504"))
        assertFalse(report.contains("example.test"))
    }

    @Test
    fun theLogKeepsNoQueryOrWholeNumber() {
        val line = DiagnosticLogger.redact("GET https://roster.test/search?first=Jane&last=Doe for 504-555-0100")
        assertFalse(line.contains("Jane"))
        assertFalse(line.contains("504-555"))
        assertTrue(line.contains("https://roster.test/search"))
        assertTrue(line.contains("0100"))
    }
}
