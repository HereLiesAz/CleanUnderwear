package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Not them" and taking it back: a verdict that can be undone, and a dismissed list that can be emptied. */
class MatchVerdictsTest {

    private val matched = Target(
        id = 7,
        displayName = "Pat Doe",
        status = TargetStatus.POSSIBLE_MATCH,
        matchKind = "ARREST",
        matchRecordKey = "rec-1",
        matchConfidence = 0.8,
        matchReasons = "Same name",
        lastVerificationSnippet = "Pat Doe [#4411]",
        lockupUrl = "https://example.org/rec-1",
        dismissedMatchKeys = "rec-0"
    )

    @Test
    fun notThemReturnsToChecksAndRemembersTheRecord() {
        val after = dismissed(matched, evidenceUrl = null)
        assertEquals(TargetStatus.MONITORING, after.status)
        assertEquals("rec-0,rec-1", after.dismissedMatchKeys)
        assertNull(after.matchKind)
        assertNull(after.matchRecordKey)
        assertNull(after.lockupUrl)
        assertNull(after.lastVerificationSnippet)
    }

    @Test
    fun theKeyFallsBackToTheInmateNumberThenTheEvidenceUrl() {
        val noKey = matched.copy(matchRecordKey = null)
        assertEquals("rec-0,4411", dismissed(noKey, "https://x").dismissedMatchKeys)
        val noSnippet = noKey.copy(lastVerificationSnippet = null)
        assertEquals("rec-0,https://x", dismissed(noSnippet, "https://x").dismissedMatchKeys)
        assertEquals("rec-0", dismissed(noSnippet, null).dismissedMatchKeys)
    }

    @Test
    fun dismissingTheSameRecordTwiceKeepsOneKey() {
        val twice = dismissed(matched.copy(dismissedMatchKeys = "rec-1"), null)
        assertEquals("rec-1", twice.dismissedMatchKeys)
    }

    @Test
    fun undoPutsTheMatchBackAndUnsuppressesTheRecord() {
        val after = dismissed(matched, null)
        // A check filled in an address in between; Undo must not lose it.
        val meanwhile = after.copy(residenceInfo = "Springfield")
        val restored = restoreDismissal(meanwhile, matched)
        assertEquals(TargetStatus.POSSIBLE_MATCH, restored.status)
        assertEquals("rec-0", restored.dismissedMatchKeys)
        assertEquals("rec-1", restored.matchRecordKey)
        assertEquals("ARREST", restored.matchKind)
        assertEquals("https://example.org/rec-1", restored.lockupUrl)
        assertEquals(0.8, restored.matchConfidence!!, 0.0)
        assertEquals("Springfield", restored.residenceInfo)
    }

    @Test
    fun undoOfTheFirstDismissalLeavesNoDismissedList() {
        val first = matched.copy(dismissedMatchKeys = null)
        val restored = restoreDismissal(dismissed(first, null), first)
        assertNull(restored.dismissedMatchKeys)
    }

    @Test
    fun forgettingDismissalsEmptiesTheListAndNothingElse() {
        val monitoring = dismissed(matched, null)
        val forgotten = forgetDismissals(monitoring)
        assertNull(forgotten.dismissedMatchKeys)
        assertEquals(emptyList<String>(), dismissedKeys(forgotten))
        assertEquals(TargetStatus.MONITORING, forgotten.status)
        assertEquals(listOf("rec-0", "rec-1"), dismissedKeys(monitoring))
    }
}
