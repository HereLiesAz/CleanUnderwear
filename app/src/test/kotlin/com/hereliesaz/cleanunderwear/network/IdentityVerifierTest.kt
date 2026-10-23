package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityVerifierTest {

    private val verifier = IdentityVerifier()

    // ---- verifyBopInmateJson (Federal BOP inmate-locator API) ----

    private fun bopJson(vararg records: String): String =
        """{"Captcha":"","Messages":[],"FormToken":"x","InmateLocator":[${records.joinToString(",")}]}"""

    private fun inmate(
        first: String,
        last: String,
        actRel: String = "",
        facl: String = "USP ATLANTA",
        faclCode: String = "",
        projRel: String = "01/01/2030",
        mid: String = "A",
        age: String = "45",
        inmateNum: String = "12345-678",
    ) = """{"nameLast":"$last","nameFirst":"$first","nameMiddle":"$mid","sex":"Male",""" +
        """"age":"$age","inmateNum":"$inmateNum","faclName":"$facl","faclCode":"$faclCode",""" +
        """"projRelDate":"$projRel","actRelDate":"$actRel"}"""

    /**
     * Corroboration that agrees with the default [inmate] under the strictest
     * gates: middle initial A, age 45, and the state of the default facility
     * (USP ATLANTA → GA). A national BOP hit now requires both a positive
     * corroborator AND facility-state agreement to surface.
     */
    private fun corro(middle: String? = "Alan", dob: String? = "45", state: String? = "GA") =
        IdentityVerifier.Corroboration(middleName = middle, dob = dob, state = state)

    @Test
    fun bop_currentInmateMatchingName_isMatchWithCustodySnippet() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro())
        assertTrue(result.isMatch)
        assertTrue(result.snippet!!.contains("BOP custody"))
        assertTrue(result.snippet!!.contains("USP ATLANTA"))
    }

    @Test
    fun bop_namesAreLastThenFirstInJson_stillMatches() {
        // Regression guard: the API emits nameLast before nameFirst with no
        // whitespace between them; the structured parse must still read it.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        assertTrue(verifier.verifyBopInmateJson(json, "John Smith", corro()).isMatch)
    }

    @Test
    fun bop_releasedInmate_isNotMatched() {
        // actRelDate is populated -> the person was released and must not flip
        // the contact to INCARCERATED.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", actRel = "06/01/2015"))
        assertFalse(verifier.verifyBopInmateJson(json, "John Smith").isMatch)
    }

    @Test
    fun bop_differentPerson_isNotMatched() {
        val json = bopJson(inmate(first = "JANE", last = "DOE"))
        assertFalse(verifier.verifyBopInmateJson(json, "John Smith").isMatch)
    }

    @Test
    fun bop_emptyResultSet_isNotMatched() {
        assertFalse(verifier.verifyBopInmateJson(bopJson(), "John Smith").isMatch)
    }

    @Test
    fun bop_picksCurrentRecordAmongMixed() {
        // A released "John Smith" and a different in-custody person — no match,
        // because the only current inmate is someone else.
        val json = bopJson(
            inmate(first = "JOHN", last = "SMITH", actRel = "06/01/2015"),
            inmate(first = "BOB", last = "JONES"),
        )
        assertFalse(verifier.verifyBopInmateJson(json, "John Smith").isMatch)

        // Add a current John Smith and it matches (facility FCI BUTNER → NC, so
        // the contact's resolved state must be NC for the agreement gate).
        val json2 = bopJson(
            inmate(first = "JOHN", last = "SMITH", actRel = "06/01/2015"),
            inmate(first = "JOHN", last = "SMITH", facl = "FCI BUTNER"),
        )
        val r = verifier.verifyBopInmateJson(json2, "John Smith", corro(state = "NC"))
        assertTrue(r.isMatch)
        assertTrue(r.snippet!!.contains("FCI BUTNER"))
    }

    @Test
    fun bop_nicknameNotExpanded_doesNotMatch() {
        // Tightening: nickname expansion is intentionally removed, so a contact
        // "Bill Smith" no longer matches an inmate "WILLIAM SMITH" on name.
        val json = bopJson(inmate(first = "WILLIAM", last = "SMITH"))
        assertFalse(verifier.verifyBopInmateJson(json, "Bill Smith").isMatch)
    }

    @Test
    fun bop_malformedJson_isAFailedLookupNotACleanMiss() {
        // An error page or truncated body says nothing about the contact.
        for (body in listOf("<html>403 Forbidden</html>", "", "{truncated")) {
            val r = verifier.verifyBopInmateJson(body, "John Smith")
            assertFalse(r.isMatch)
            assertTrue(r.failed)
        }
    }

    @Test
    fun bop_mononym_skips() {
        val result = verifier.verifyBopInmateJson(bopJson(inmate("MADONNA", "X")), "Madonna")
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
    }

    // ---- corroboration (kills common-name false positives) ----

    @Test
    fun bop_compoundFirstName_doesNotMatchPlainFirst() {
        // "JOHN-PAUL SMITH" must NOT match contact "John Smith": exact first-name
        // match, not token-contains.
        val json = bopJson(inmate(first = "JOHN-PAUL", last = "SMITH"))
        assertFalse(verifier.verifyBopInmateJson(json, "John Smith").isMatch)
    }

    @Test
    fun bop_middleNameConflict_rejected() {
        // Contact's known middle name disagrees with the record → not our person.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", mid = "A"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(middleName = "Brian")
        )
        assertFalse(result.isMatch)
    }

    @Test
    fun bop_middleNameAgrees_corroborated() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", mid = "A"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(middleName = "Alan", state = "GA")
        )
        assertTrue(result.isMatch)
        assertTrue(result.basis!!.contains("middle name"))
    }

    @Test
    fun bop_ageConflict_rejected() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", age = "45"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(dob = "60")
        )
        assertFalse(result.isMatch)
    }

    @Test
    fun bop_ageAgrees_corroborated() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", age = "45"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(dob = "45", state = "GA")
        )
        assertTrue(result.isMatch)
        assertTrue(result.basis!!.contains("age"))
    }

    @Test
    fun bop_ageFromAgeLabel_corroborated() {
        // CBC's `.age` field yields the literal "Age 45"; corroboration must read
        // the number out of it rather than silently skipping age.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", age = "45"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(dob = "Age 45", state = "GA")
        )
        assertTrue(result.isMatch)
        assertTrue(result.basis!!.contains("age"))
    }

    @Test
    fun bop_ageFromBirthYear_corroborated() {
        // A 4-digit birth year resolves to roughly the record's age.
        val thisYear = java.time.Year.now().value
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", age = (thisYear - 1980).toString()))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(dob = "1980", state = "GA")
        )
        assertTrue(result.isMatch)
        assertTrue(result.basis!!.contains("age"))
    }

    // ---- strictest gates: corroborator required + facility-state agreement ----

    @Test
    fun bop_nameOnlyNoCorroborator_notSurfaced() {
        // Even with the contact's state known, a name-only hit (no agreeing
        // middle name or age) is never surfaced on the national BOP search.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(
            json, "John Smith",
            IdentityVerifier.Corroboration(state = "GA")
        )
        assertFalse(result.isMatch)
    }

    @Test
    fun bop_facilityStateMismatch_isSurfacedWithTheFacilityState() {
        // Federal inmates are often held out of state: a different facility state is evidence
        // for MatchVerifier to weigh, not a reason to hide a corroborated record.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = "CA"))
        assertTrue(result.isMatch)
        assertEquals("GA", result.record?.state)
    }

    @Test
    fun bop_resolvesViaFacilityCode_whenNameUnparseable() {
        // A stable faclCode resolves the facility state even when faclName is
        // garbage — the format-robust path.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", facl = "???", faclCode = "ATL"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = "GA"))
        assertTrue(result.isMatch)
    }

    @Test
    fun bop_facilityStateUnknown_stillSurfaced() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", facl = "FCI NOWHERELAND"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = "GA"))
        assertTrue(result.isMatch)
        assertEquals(null, result.record?.state)
    }

    @Test
    fun bop_contactStateUnknown_stillSurfaced() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = null))
        assertTrue(result.isMatch)
    }

    @Test
    fun bop_captchaFlag_isAFailedLookup() {
        val result = verifier.verifyBopInmateJson("""{"Captcha":true,"InmateLocator":[]}""", "John Smith", corro())
        assertTrue(result.failed)
    }

    @Test
    fun bop_fullyCorroboratedWithStateAgreement_matches() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro())
        assertTrue(result.isMatch)
        assertEquals("12345-678", result.matchKey)
    }

    @Test
    fun bop_dismissedRecord_isSuppressed() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", inmateNum = "99999-111"))
        // With full corroboration it matches…
        assertTrue(verifier.verifyBopInmateJson(json, "John Smith", corro()).isMatch)
        // …but a previously-rejected record id is skipped.
        assertFalse(
            verifier.verifyBopInmateJson(
                json, "John Smith", corro(),
                dismissedKeys = setOf("99999-111")
            ).isMatch
        )
    }
}
