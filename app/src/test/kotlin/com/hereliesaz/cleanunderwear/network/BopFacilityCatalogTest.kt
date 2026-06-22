package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BopFacilityCatalogTest {

    @Test
    fun stateFor_knownFacility_returnsState() {
        assertEquals("GA", BopFacilityCatalog.stateFor("USP ATLANTA"))
        assertEquals("NC", BopFacilityCatalog.stateFor("FCI BUTNER"))
        assertEquals("IN", BopFacilityCatalog.stateFor("USP TERRE HAUTE"))
    }

    @Test
    fun stateFor_caseAndWhitespaceInsensitive() {
        assertEquals("GA", BopFacilityCatalog.stateFor("  usp atlanta  "))
    }

    @Test
    fun stateFor_multiWordLocation_isMatched() {
        // Longest-key-first must resolve "FORT WORTH" rather than miss it.
        assertEquals("TX", BopFacilityCatalog.stateFor("FMC FORT WORTH"))
        assertEquals("MS", BopFacilityCatalog.stateFor("FCI YAZOO CITY LOW"))
    }

    @Test
    fun stateFor_unknownOrBlank_returnsNull() {
        assertNull(BopFacilityCatalog.stateFor("FCI NOWHERELAND"))
        assertNull(BopFacilityCatalog.stateFor(""))
        assertNull(BopFacilityCatalog.stateFor(null))
    }
}
