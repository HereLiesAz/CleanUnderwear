package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.match.FieldContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetSnapshotTest {

    @Test
    fun snapshot_roundTrips() {
        val t = Target(
            id = 4, displayName = "Bob Smith", phoneNumber = "504-555-0101",
            status = TargetStatus.INCARCERATED, monitorabilityState = MonitorabilityState.NO_AUTOMATED_SOURCE,
            lastScrapedTimestamp = 99L
        )
        assertEquals(t, TargetSnapshot.fromJson(TargetSnapshot.toJson(t)))
    }

    @Test
    fun revert_restoresOnlyFieldsTheMergeFilled() {
        val before = Target(id = 1, displayName = "Robert Smith", phoneNumber = "504-555-0101")
        val after = before.copy(email = "bob@x.com", residenceInfo = "New Orleans, LA")
        val changes = TargetSnapshot.changes(before, after)

        // Since the merge the user edited the address; the email is still the merge's.
        val current = after.copy(residenceInfo = "Baton Rouge, LA")
        val reverted = TargetSnapshot.revert(current, changes)

        assertNull(reverted.email)
        assertEquals("Baton Rouge, LA", reverted.residenceInfo)
        assertEquals("504-555-0101", reverted.phoneNumber)
    }

    @Test
    fun changes_isEmptyWhenNothingChanged() {
        val t = Target(id = 1, displayName = "A B")
        assertEquals("{}", TargetSnapshot.changes(t, t))
    }

    @Test
    fun contributionCodec_roundTrips() {
        val list = listOf(FieldContribution("phone", "AGREE", 8.5), FieldContribution("name", "CONFLICT", -6.0))
        assertEquals(list, ContributionCodec.decode(ContributionCodec.encode(list)))
    }

    @Test
    fun vetoKey_isOrderIndependent() {
        assertEquals(MergeSignature.vetoKey("a", "b"), MergeSignature.vetoKey("b", "a"))
    }

    @Test
    fun historyIndex_matchesNumbersRegardlessOfFormatting() {
        val index = HistoryIndex.Builder().apply {
            add("+1 (504) 555-0101", 1_000L)
            add("5045550101", 3_000L)
            add("12345", 2_000L) // short code: ignored
        }.build()

        val stats = index.statsFor("504-555-0101")!!
        assertEquals(2, stats.count)
        assertEquals(1_000L, stats.firstMillis)
        assertEquals(3_000L, stats.lastMillis)
        assertEquals(1_000L, index.oldestMillis)
        assertNull(index.statsFor("504-555-9999"))
    }
}
