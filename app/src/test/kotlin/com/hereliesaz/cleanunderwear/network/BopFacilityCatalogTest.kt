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
        assertEquals("NC", BopFacilityCatalog.stateFor("  fci   butner  "))
    }

    @Test
    fun stateFor_multiWordLocation_isMatched() {
        // Longest-key-first must resolve "FORT WORTH" rather than miss it.
        assertEquals("TX", BopFacilityCatalog.stateFor("FMC FORT WORTH"))
        assertEquals("MS", BopFacilityCatalog.stateFor("FCI YAZOO CITY LOW"))
    }

    @Test
    fun stateFor_wordOrderIndependent() {
        // Whatever order the API emits the name in, the city keyword resolves.
        assertEquals("GA", BopFacilityCatalog.stateFor("Atlanta USP"))
    }

    @Test
    fun stateFor_punctuationIndependent() {
        assertEquals("NC", BopFacilityCatalog.stateFor("FCI-BUTNER"))
        assertEquals("GA", BopFacilityCatalog.stateFor("U.S. Penitentiary, Atlanta, GA"))
    }

    @Test
    fun stateFor_facilityCode_isMostStable() {
        // Code wins and is format-invariant, even with a garbage display name.
        assertEquals("GA", BopFacilityCatalog.stateFor(faclCode = "atl", faclName = "???"))
        // Unknown code falls through to name parsing.
        assertEquals("NC", BopFacilityCatalog.stateFor(faclCode = "XYZ", faclName = "FCI BUTNER"))
    }

    @Test
    fun stateFor_trailingStateToken_resolvesUnknownCity() {
        // City not in the table, but an explicit, valid trailing state code is.
        assertEquals("NC", BopFacilityCatalog.stateFor("FCI SOMEPLACE, NC"))
    }

    @Test
    fun stateFor_nonTrailingTwoLetterRun_doesNotFalseTrigger() {
        // "OR" appears mid-name but is not the trailing token → no false match.
        assertNull(BopFacilityCatalog.stateFor("CAMP OR FARM"))
    }

    @Test
    fun stateFor_unknownOrBlank_returnsNull() {
        assertNull(BopFacilityCatalog.stateFor("FCI NOWHERELAND"))
        assertNull(BopFacilityCatalog.stateFor(""))
        assertNull(BopFacilityCatalog.stateFor(null))
        assertNull(BopFacilityCatalog.stateFor(faclCode = "", faclName = null))
    }
}
