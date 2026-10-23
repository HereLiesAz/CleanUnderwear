package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalePhoneTest {

    private val enricher = CyberBackgroundChecksEnricher()
    private val stale = Target(
        id = 1, displayName = "Dana Reyes", phoneNumber = "504-555-0303", areaCode = "504",
        email = "d@x.com", stalePhone = "(504) 555-0303"
    )

    @Test
    fun mark_isAboutTheNumber_andLapsesWhenItChanges() {
        assertTrue(stale.phoneIsStale)
        assertNull(stale.usablePhone)
        val replaced = stale.copy(phoneNumber = "225-555-0909")
        assertFalse(replaced.phoneIsStale)
        assertEquals("225-555-0909", replaced.usablePhone)
        assertFalse(Target(displayName = "A B", phoneNumber = "504-555-0303").phoneIsStale)
    }

    @Test
    fun staleNumber_isNotSearched_unlessAskedFor() {
        assertTrue(enricher.pickAllMissions(stale).none { it is BrowserMission.CbcByPhone })
        assertTrue(enricher.pickAllMissions(stale, includeStalePhone = true).any { it is BrowserMission.CbcByPhone })
        assertTrue(enricher.pickMission(stale) is BrowserMission.CbcByEmail)
    }

    @Test
    fun enrichment_replacesAStaleNumber_andFollowsItsAreaCode() {
        // The name card has to show something else of hers, here the town on her address.
        val card = BrowserMission.CbcByName("Dana Reyes") to Findings("Dana Reyes", "12 Elm St, Baton Rouge, LA", "2255550909")
        val outcome = enricher.enrich(stale.copy(residenceInfo = "Baton Rouge, LA"), listOf(card))
        assertTrue(outcome.verified)
        assertEquals("2255550909", outcome.target.phoneNumber)
        assertEquals("225", outcome.target.areaCode)
        assertFalse(outcome.target.phoneIsStale)
    }

    @Test
    fun enrichment_neverReAdoptsTheStaleNumber_orFillsAHealthyOne() {
        val sameCard = BrowserMission.CbcByName("Dana Reyes") to Findings("Dana Reyes", null, "5045550303")
        val kept = enricher.enrich(stale, listOf(sameCard)).target
        assertEquals("504-555-0303", kept.phoneNumber)
        assertTrue(kept.phoneIsStale)

        val healthy = stale.copy(stalePhone = null)
        val other = BrowserMission.CbcByName("Dana Reyes") to Findings("Dana Reyes", null, "2255550909")
        assertEquals("504-555-0303", enricher.enrich(healthy, listOf(other)).target.phoneNumber)
    }
}
