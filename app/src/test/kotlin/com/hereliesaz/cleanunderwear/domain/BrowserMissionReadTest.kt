package com.hereliesaz.cleanunderwear.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMissionReadTest {

    @Test
    fun theLookupsWantOnlyThePagesSourceSoTheyAreReadDirectly() {
        listOf(
            BrowserMission.CbcByName("Jane Roe"),
            BrowserMission.CbcByEmail("jane@example.com"),
            BrowserMission.CbcByPhone("5551234567"),
            BrowserMission.CbcByAddress("1 Main St"),
        ).forEach { assertTrue(it.label, it.readsPageDirectly) }
    }

    @Test
    fun theImportsRunTheirOwnScriptsSoTheyKeepTheBridge() {
        listOf(
            BrowserMission.HarvestWhatsApp,
            BrowserMission.HarvestInstagram,
            BrowserMission.HarvestGoogleContacts,
            BrowserMission.HarvestFacebookFriends,
        ).forEach { assertFalse(it.label, it.readsPageDirectly) }
    }
}
