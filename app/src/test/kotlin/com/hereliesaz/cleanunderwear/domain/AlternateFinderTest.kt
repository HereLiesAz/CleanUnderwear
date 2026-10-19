package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateFinderTest {

    private val finder = AlternateFinder(CyberBackgroundChecksEnricher())
    private val stale = "5045550303"
    private val dana = Target(id = 3, displayName = "Dana Reyes", phoneNumber = "504-555-0303")

    private fun phoneCard(name: String?) = BrowserMission.CbcByPhone(stale) to Findings(name, null, null)
    private fun nameCard(name: String, phone: String?, address: String? = null) =
        BrowserMission.CbcByName("Dana Reyes") to Findings(name, address, phone)

    @Test
    fun recycledNumber_isFlagged_andVerifiedReplacementOffered() {
        val info = finder.assess(
            dana, stale,
            listOf(phoneCard("Pat Okafor"), nameCard("Dana Reyes", "5045559999", "New Orleans, LA"))
        )
        assertTrue(info.holderDiffers)
        assertEquals("Pat Okafor", info.holderName)
        assertEquals("5045559999", info.phone)
        assertEquals("New Orleans, LA", info.address)
        assertTrue(info.hasReplacement)
    }

    @Test
    fun holderWhoMatchesTheContact_isNotCalledADifferentPerson() {
        val info = finder.assess(dana, stale, listOf(phoneCard("Dana Reyes")))
        assertFalse(info.holderDiffers)
        assertFalse(info.hasReplacement)
    }

    @Test
    fun replacementEqualToTheStaleNumber_isIgnored() {
        val info = finder.assess(dana, stale, listOf(nameCard("Dana Reyes", "(504) 555-0303")))
        assertNull(info.phone)
    }

    @Test
    fun cardForSomeoneElse_isNeverOffered() {
        val info = finder.assess(dana, stale, listOf(nameCard("Dana Smith", "5045559999", "Houston, TX")))
        assertNull(info.phone)
        assertNull(info.address)
        assertTrue(info.basis.contains("NOT merged"))
    }

    @Test
    fun addressIsOnlyOfferedWhenTheContactHasNone() {
        val withAddress = dana.copy(residenceInfo = "Baton Rouge, LA")
        val info = finder.assess(withAddress, stale, listOf(nameCard("Dana Reyes", "5045559999", "New Orleans, LA")))
        assertEquals("5045559999", info.phone)
        assertNull(info.address)
    }

    @Test
    fun afterASplit_thereIsNoOldNumberToCheck() {
        val bare = dana.copy(phoneNumber = null)
        val info = finder.assess(bare, null, listOf(nameCard("Dana Reyes", "5045559999")))
        assertNull(info.holderName)
        assertEquals("5045559999", info.phone)
    }
}
