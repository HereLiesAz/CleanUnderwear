package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one place a status gets its words: what the list and the profile say about a contact. */
class StatusSpecTest {

    @Test
    fun aPossibleMatchNamesItsKindAndNeedsTheUser() {
        val death = statusSpec(TargetStatus.POSSIBLE_MATCH, "DEATH", MonitorabilityState.READY)
        assertEquals("Possible obituary", death.label)
        assertTrue(death.needsUser)
        assertEquals("Possible arrest record", statusSpec(TargetStatus.POSSIBLE_MATCH, "ARREST", null).label)
        assertEquals("Possible custody record", statusSpec(TargetStatus.POSSIBLE_MATCH, "CUSTODY", null).label)
    }

    @Test
    fun confirmedAndRoutineStatusesSayWhatTheyAre() {
        assertEquals("Confirmed deceased", statusSpec(TargetStatus.DECEASED, null, null).label)
        assertEquals("Confirmed in custody", statusSpec(TargetStatus.INCARCERATED, null, null).label)
        assertEquals("Weak match", statusSpec(TargetStatus.WEAK_MATCH, "DEATH", null).label)
        assertFalse(statusSpec(TargetStatus.WEAK_MATCH, "DEATH", null).needsUser)
        assertEquals("Archived", statusSpec(TargetStatus.IGNORED, null, null).label)
        assertEquals("Checked daily", statusSpec(TargetStatus.MONITORING, null, MonitorabilityState.READY).label)
        assertEquals("Manual checks only", statusSpec(TargetStatus.MONITORING, null, MonitorabilityState.NO_AUTOMATED_SOURCE).label)
    }
}
