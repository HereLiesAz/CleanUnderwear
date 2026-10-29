package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place a status gets its words: what the list and the profile say about a contact. The
 * words are string resources, so the spec holds the resource id; the ids are checked here.
 */
class StatusSpecTest {

    @Test
    fun aPossibleMatchNamesItsKindAndNeedsTheUser() {
        val death = statusSpec(TargetStatus.POSSIBLE_MATCH, "DEATH", MonitorabilityState.READY)
        assertEquals(R.string.status_possible_obituary, death.labelRes)
        assertTrue(death.needsUser)
        assertEquals(R.string.status_possible_arrest, statusSpec(TargetStatus.POSSIBLE_MATCH, "ARREST", null).labelRes)
        assertEquals(R.string.status_possible_custody, statusSpec(TargetStatus.POSSIBLE_MATCH, "CUSTODY", null).labelRes)
    }

    @Test
    fun confirmedAndRoutineStatusesSayWhatTheyAre() {
        assertEquals(R.string.status_confirmed_deceased, statusSpec(TargetStatus.DECEASED, null, null).labelRes)
        assertEquals(R.string.status_confirmed_in_custody, statusSpec(TargetStatus.INCARCERATED, null, null).labelRes)
        assertEquals(R.string.status_weak_match, statusSpec(TargetStatus.WEAK_MATCH, "DEATH", null).labelRes)
        assertFalse(statusSpec(TargetStatus.WEAK_MATCH, "DEATH", null).needsUser)
        assertEquals(R.string.status_archived, statusSpec(TargetStatus.IGNORED, null, null).labelRes)
        assertEquals(R.string.status_checked_daily, statusSpec(TargetStatus.MONITORING, null, MonitorabilityState.READY).labelRes)
        assertEquals(R.string.status_manual_only, statusSpec(TargetStatus.MONITORING, null, MonitorabilityState.NO_AUTOMATED_SOURCE).labelRes)
    }
}
