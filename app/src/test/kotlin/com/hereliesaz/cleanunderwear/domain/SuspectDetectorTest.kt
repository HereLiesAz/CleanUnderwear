package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.HistoryStats
import com.hereliesaz.cleanunderwear.match.FieldContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspectDetectorTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L // fixed clock so month arithmetic is stable

    private fun stats(count: Int, firstDaysAgo: Long, lastDaysAgo: Long) =
        HistoryStats(count, now - firstDaysAgo * day, now - lastDaysAgo * day)

    private fun merge(
        survivorPhone: String? = "504-555-0101",
        absorbedPhone: String? = "504-555-0202",
        survivorName: String = "Robert Smith",
        absorbedName: String = "Bob Smith",
        probability: Double = 0.97,
        contributions: List<FieldContribution> = listOf(FieldContribution("email", "AGREE", 9.0)),
        survivorHistory: HistoryStats? = null,
        absorbedHistory: HistoryStats? = null,
        historyAvailable: Boolean = false,
        sharers: Int = 0
    ) = MergeCaseInput(
        ledgerId = 7, survivorId = 1,
        survivorName = survivorName, survivorPhone = survivorPhone, survivorEmail = null,
        absorbedName = absorbedName, absorbedPhone = absorbedPhone, absorbedEmail = null,
        probability = probability, contributions = contributions, mergedAtMillis = now - 10 * day,
        survivorHistory = survivorHistory, absorbedHistory = absorbedHistory,
        historyAvailable = historyAvailable, survivorPhoneSharers = sharers
    )

    @Test
    fun overlappingHistories_arePrimeEvidenceOfTwoPeople() {
        val case = SuspectDetector.assessMerge(
            merge(
                historyAvailable = true,
                survivorHistory = stats(40, 700, 5),
                absorbedHistory = stats(25, 400, 3)
            ),
            now
        )
        assertNotNull(case)
        assertTrue(case!!.suspicion.any { it.text.contains("same") && it.weight >= 3.0 })
        assertEquals(Lean.LIKELY_PROBLEM, case.lean)
        // The case for the merge is always shown too.
        assertTrue(case.defense.any { it.text.contains("two numbers") })
    }

    @Test
    fun sequentialHistories_looksLikeANumberChange_andLeansFine() {
        val case = SuspectDetector.assessMerge(
            merge(
                historyAvailable = true,
                // Old number dies ~13 months ago, new one starts ~12 months ago.
                survivorHistory = stats(30, 2000, 400),
                absorbedHistory = stats(30, 360, 2),
                probability = 0.97
            ),
            now
        )
        assertNotNull(case)
        val defense = case!!.defense.single { it.weight == 2.5 }
        assertTrue(defense.text.contains("changing numbers"))
        assertTrue(case.suspicion.none { it.text.contains("same") && it.weight >= 3.0 })
    }

    @Test
    fun historyIsSkippedWhenUnavailable_notGuessed() {
        val case = SuspectDetector.assessMerge(
            merge(historyAvailable = false, survivorHistory = null, absorbedHistory = null),
            now
        )
        assertNotNull(case) // two numbers (2.0) + thin margin (1.0)
        assertTrue(case!!.suspicion.none { it.text.contains("in touch") })
        assertTrue(case.defense.none { it.text.contains("histories") })
    }

    @Test
    fun mergeWithStrongCorroboration_isNotSurfaced() {
        val case = SuspectDetector.assessMerge(
            merge(
                survivorPhone = "504-555-0101", absorbedPhone = "(504) 555-0101",
                probability = 0.999,
                contributions = listOf(
                    FieldContribution("phone", "AGREE", 9.0),
                    FieldContribution("name", "AGREE", 4.5)
                )
            ),
            now
        )
        assertNull(case)
    }

    @Test
    fun surnameConflictOutvotedByOtherFields_isSuspect() {
        val case = SuspectDetector.assessMerge(
            merge(
                survivorName = "Anna Smith", absorbedName = "Anna Jones",
                survivorPhone = "504-555-0101", absorbedPhone = "504-555-0101",
                probability = 0.96,
                contributions = listOf(
                    FieldContribution("phone", "AGREE", 9.0),
                    FieldContribution("email", "AGREE", 9.0),
                    FieldContribution("name", "CONFLICT", -6.0)
                )
            ),
            now
        )
        assertNotNull(case)
        assertTrue(case!!.suspicion.any { it.text.contains("surnames disagreed") })
        // ...and the same-phone / same-email reasons are listed as the defense.
        assertTrue(case.defense.any { it.text.contains("same email") })
        assertTrue(case.defense.any { it.text.contains("same phone") })
    }

    @Test
    fun singleFieldMerge_onASharedLine_isSuspect() {
        val case = SuspectDetector.assessMerge(
            merge(
                survivorPhone = "504-555-0101", absorbedPhone = "504-555-0101",
                probability = 0.96,
                contributions = listOf(FieldContribution("phone", "AGREE", 9.0)),
                sharers = 2
            ),
            now
        )
        assertNotNull(case)
        assertTrue(case!!.suspicion.any { it.text.contains("Only the phone agreed") })
        assertTrue(case.suspicion.any { it.text.contains("2 other contacts share") })
    }

    @Test
    fun staleNumber_silentForYears_isSuspect_butRecentContactClearsIt() {
        fun stale(history: HistoryStats?, coverageDays: Long = 3000) = SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = null,
                lookupFailed = false, history = history, historyAvailable = true,
                historyCoverageMillis = coverageDays * day, sharers = emptyList(), replacement = null
            ),
            now
        )

        val old = stale(stats(12, 2500, 1500))
        assertNotNull(old)
        assertEquals(Lean.LIKELY_PROBLEM, old!!.lean)

        assertNull(stale(stats(12, 2500, 20)))
    }

    @Test
    fun staleNumber_shortHistoryWindow_tempersSilence() {
        val case = SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = "d@x.com",
                lookupFailed = false, history = null, historyAvailable = true,
                historyCoverageMillis = 200 * day, sharers = emptyList(), replacement = null
            ),
            now
        )
        assertNotNull(case)
        assertTrue(case!!.defense.any { it.text.contains("proves little") })
        assertTrue(case.defense.any { it.text.contains("email") })
        assertEquals(Lean.TOSS_UP, case.lean)
    }

    @Test
    fun staleNumber_withoutHistoryAccess_needsOtherEvidence() {
        val silent = SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = null,
                lookupFailed = false, history = null, historyAvailable = false,
                historyCoverageMillis = null, sharers = emptyList(), replacement = null
            ),
            now
        )
        assertNull(silent)
    }

    @Test
    fun sharedNumber_differentSurname_isStale_sameSurname_isHousehold() {
        fun withSharer(name: String) = SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = null,
                lookupFailed = true, history = null, historyAvailable = false,
                historyCoverageMillis = null, sharers = listOf(NumberSharer(name)), replacement = null
            ),
            now
        )

        val recycled = withSharer("Pat Okafor")
        assertNotNull(recycled)
        assertTrue(recycled!!.suspicion.any { it.text.contains("different surname") })

        // Same surname: no recycled-number suspicion; lookup failure alone (1.0) is below the bar.
        assertNull(withSharer("Luis Reyes"))
    }

    @Test
    fun liveReplacementNumber_isOfferedAndWeighedHeavily() {
        val replacement = ReplacementCandidate("504-555-0909", "Dana R", stats(20, 300, 2))
        val case = SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = null,
                lookupFailed = false, history = stats(10, 2000, 700), historyAvailable = true,
                historyCoverageMillis = 3000 * day, sharers = emptyList(), replacement = replacement
            ),
            now
        )
        assertNotNull(case)
        assertEquals("504-555-0909", case!!.replacementPhone)
        assertTrue(case.suspicion.any { it.text.contains("live line") })
    }

    @Test
    fun overlapMillis_isZeroForSequentialSpans() {
        assertEquals(0L, SuspectDetector.overlapMillis(stats(5, 100, 50), stats(5, 40, 1)))
        assertEquals(10 * day, SuspectDetector.overlapMillis(stats(5, 100, 20), stats(5, 30, 1)))
    }

    @Test
    fun keys_areStablePerNumber() {
        assertEquals(SuspectDetector.staleKey(3, "(504) 555-0303"), SuspectDetector.staleKey(3, "504-555-0303"))
        assertTrue(SuspectDetector.staleKey(3, "504-555-0303") != SuspectDetector.staleKey(3, "504-555-0404"))
    }
}
