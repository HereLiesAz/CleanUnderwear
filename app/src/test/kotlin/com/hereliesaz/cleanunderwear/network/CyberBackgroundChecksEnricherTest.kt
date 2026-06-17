package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyberBackgroundChecksEnricherTest {

    private val enricher = CyberBackgroundChecksEnricher()

    private fun target(
        displayName: String = "Jane Roe",
        phone: String? = null,
        email: String? = null,
        residence: String? = null,
        areaCode: String? = null,
    ) = Target(
        displayName = displayName,
        phoneNumber = phone,
        email = email,
        residenceInfo = residence,
        areaCode = areaCode,
    )

    // ---- pickAllMissions ----

    @Test
    fun pickAllMissions_allFieldsPresent_returnsFourMissionsInPriorityOrder() {
        val t = target(
            displayName = "Jane Roe",
            phone = "+1 (555) 123-4567",
            email = "jane@example.com",
            residence = "1 Main St, Anytown, NY 10001",
        )

        val missions = enricher.pickAllMissions(t)

        assertEquals(4, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByPhone)
        assertTrue(missions[1] is BrowserMission.CbcByEmail)
        assertTrue(missions[2] is BrowserMission.CbcByAddress)
        assertTrue(missions[3] is BrowserMission.CbcByName)
    }

    @Test
    fun pickAllMissions_singleWordName_dropsNameMission() {
        val missions = enricher.pickAllMissions(target(displayName = "Jane"))
        assertTrue(missions.isEmpty())
    }

    @Test
    fun pickAllMissions_phoneTooShort_dropsPhoneMission() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", phone = "555-12")
        )
        assertEquals(1, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByName)
    }

    @Test
    fun pickAllMissions_placeholderName_dropsNameMission() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Unnamed Entity", email = "x@y.com")
        )
        assertEquals(1, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByEmail)
    }

    // ---- mergeAll ----

    @Test
    fun mergeAll_consensusWinsOverHigherPrioritySingleton() {
        val t = target(displayName = "Unnamed Entity")
        val phoneFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Alpha", address = null, phone = null
        )
        val emailFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Bravo", address = null, phone = null
        )
        val addrFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Bravo", address = null, phone = null
        )

        val merged = enricher.mergeAll(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to phoneFinding,
                BrowserMission.CbcByEmail("a@b.com") to emailFinding,
                BrowserMission.CbcByAddress("1 Main") to addrFinding,
            )
        )

        // "Bravo" appears twice (email + address), "Alpha" only once (phone),
        // so consensus picks "Bravo" even though phone is the highest source.
        assertEquals("Bravo", merged.displayName)
    }

    @Test
    fun mergeAll_allDistinct_picksHighestPrioritySource() {
        val t = target(displayName = "Unnamed Entity")
        val results = listOf(
            BrowserMission.CbcByPhone("5551234567") to
                CyberBackgroundChecksEnricher.Findings("From Phone", null, null),
            BrowserMission.CbcByEmail("a@b.com") to
                CyberBackgroundChecksEnricher.Findings("From Email", null, null),
            BrowserMission.CbcByAddress("1 Main") to
                CyberBackgroundChecksEnricher.Findings("From Addr", null, null),
            BrowserMission.CbcByName("Jane Roe") to
                CyberBackgroundChecksEnricher.Findings("From Name", null, null),
        )

        val merged = enricher.mergeAll(t, results)
        assertEquals("From Phone", merged.displayName)
    }

    @Test
    fun mergeAll_doesNotOverwriteExistingTargetFields() {
        val t = target(
            displayName = "Real Name",
            phone = "5559999999",
            residence = "Existing Address",
        )
        val results = listOf(
            BrowserMission.CbcByPhone("5559999999") to
                CyberBackgroundChecksEnricher.Findings(
                    name = "Different Name",
                    address = "Different Address",
                    phone = "5550000000",
                ),
        )

        val merged = enricher.mergeAll(t, results)

        assertEquals("Real Name", merged.displayName)        // not a placeholder
        assertEquals("5559999999", merged.phoneNumber)        // existing wins
        assertEquals("Existing Address", merged.residenceInfo)
    }

    @Test
    fun mergeAll_emptyResults_leavesTargetUnchangedExceptDerivedFields() {
        val t = target(displayName = "Jane Roe", phone = "5551234567")
        val merged = enricher.mergeAll(t, emptyList())
        assertEquals("Jane Roe", merged.displayName)
        assertEquals("5551234567", merged.phoneNumber)
    }

    @Test
    fun mergeAll_fillsNullAreaCodeFromConsensusPhone() {
        val t = target(displayName = "Unnamed Entity")
        val results = listOf(
            BrowserMission.CbcByPhone("5551234567") to
                CyberBackgroundChecksEnricher.Findings(
                    name = null, address = null, phone = "(212) 555-7777"
                ),
        )
        val merged = enricher.mergeAll(t, results)
        assertEquals("212", merged.areaCode)
    }

    // ---- looksLikeBlock ----

    @Test
    fun looksLikeBlock_emptyHtml_isBlocked() {
        assertTrue(enricher.looksLikeBlock(""))
        assertTrue(enricher.looksLikeBlock("   \n  "))
    }

    @Test
    fun looksLikeBlock_captchaMarker_isBlocked() {
        assertTrue(enricher.looksLikeBlock("<html><body>Please solve the CAPTCHA</body></html>"))
        assertTrue(enricher.looksLikeBlock("<html>verify you are human</html>"))
        assertTrue(enricher.looksLikeBlock("<script src='/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1'></script>"))
    }

    @Test
    fun looksLikeBlock_normalResultPage_isNotBlocked() {
        val html = """
            <html><body>
              <div class="person-card">
                <h2 class="full-name">Jane Roe</h2>
                <span class="phone">555-123-4567</span>
              </div>
            </body></html>
        """.trimIndent()
        assertFalse(enricher.looksLikeBlock(html))
    }

    // ---- Sanity: pickAllMissions feeds reasonable URLs ----

    @Test
    fun pickAllMissions_phoneMissionUrlStripsCountryCode() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", phone = "+1 555-123-4567")
        )
        val phoneMission = missions.first { it is BrowserMission.CbcByPhone } as BrowserMission.CbcByPhone
        // The mission stores the raw digit string; the URL builder applies the
        // strip. Check the URL the mission resolves to.
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            phoneMission.initialUrl
        )
    }

    @Test
    fun pickAllMissions_emailMissionUrlPreservesAtSign() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", email = "jane@example.com")
        )
        val emailMission = missions.first { it is BrowserMission.CbcByEmail } as BrowserMission.CbcByEmail
        assertEquals(
            "https://www.cyberbackgroundchecks.com/email/jane@example.com",
            emailMission.initialUrl
        )
    }

    @Test
    fun mergeAll_returnsNullForFieldNoSourceProvided() {
        val t = target(displayName = "Unnamed Entity")
        val merged = enricher.mergeAll(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = null, address = null, phone = null
                    )
            )
        )
        assertEquals("Unnamed Entity", merged.displayName)
        assertNull(merged.phoneNumber)
        assertNull(merged.residenceInfo)
    }

    // ---- enrich (verify-before-merge) ----

    @Test
    fun enrich_phoneLookupResolvesPlaceholderIdentity_isVerifiedAndAdoptsName() {
        val t = target(displayName = "Unnamed Entity")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Jane Roe", address = "1 Main St", phone = null
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("Jane Roe", outcome.target.displayName)
        assertEquals("1 Main St", outcome.target.residenceInfo)
        assertNotNull(outcome.target.enrichmentProvenance)
    }

    @Test
    fun enrich_nameSearchConsistentWithRealName_isVerifiedAndFillsGaps() {
        val t = target(displayName = "John Smith")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("John Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "John Smith", address = "5 Oak St", phone = null
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("John Smith", outcome.target.displayName)
        assertEquals("5 Oak St", outcome.target.residenceInfo)
    }

    @Test
    fun enrich_phoneCardNameConflictsWithRealName_isRejectedAndNotMerged() {
        // Recycled phone number: the card belongs to a different person, so
        // nothing must be written into the contact.
        val t = target(displayName = "John Smith")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Bob Jones", address = "9 Elm Ave", phone = "5551234567"
                    )
            )
        )
        assertFalse(outcome.verified)
        assertEquals("John Smith", outcome.target.displayName)
        assertNull(outcome.target.residenceInfo)
        assertTrue(outcome.provenance.contains("conflict"))
    }

    @Test
    fun enrich_placeholderWithOnlyAddressCard_isRejected() {
        val t = target(displayName = "Unnamed Entity")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByAddress("1 Main St") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Stranger Person", address = "1 Main St", phone = "5550001111"
                    )
            )
        )
        assertFalse(outcome.verified)
        assertEquals("Unnamed Entity", outcome.target.displayName)
        assertNull(outcome.target.phoneNumber)
    }

    @Test
    fun enrich_firstInitialMatchCountsAsConsistent() {
        // "Jon Smith" vs "Jonathan Smith" — same last name, matching first
        // initial (a common nickname/long-form variation).
        val t = target(displayName = "Jon Smith")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("Jon Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Jonathan Smith", address = "5 Oak St", phone = null
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("5 Oak St", outcome.target.residenceInfo)
    }

    @Test
    fun enrich_emptyResults_isRejectedWithProvenance() {
        val outcome = enricher.enrich(target(displayName = "Jane Roe"), emptyList())
        assertFalse(outcome.verified)
        assertEquals("no results parsed", outcome.provenance)
    }
}
