package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityVerifierTest {

    private val verifier = IdentityVerifier()

    @Test
    fun verifyIdentity_exactMatch_returnsTrue() {
        val documentText = "John Doe was arrested on Tuesday."
        assertTrue(verifier.verifyIdentity(documentText, "John Doe").isMatch)
    }

    @Test
    fun verifyIdentity_lastFirstMatch_returnsTrue() {
        val documentText = "Doe, John"
        assertTrue(verifier.verifyIdentity(documentText, "John Doe").isMatch)
    }

    @Test
    fun verifyIdentity_middleNameMatch_returnsTrue() {
        val documentText = "John Robert Doe"
        assertTrue(verifier.verifyIdentity(documentText, "John Doe").isMatch)
    }

    @Test
    fun verifyIdentity_caseInsensitive_returnsTrue() {
        val documentText = "JOHN DOE"
        assertTrue(verifier.verifyIdentity(documentText, "John Doe").isMatch)
    }

    @Test
    fun verifyIdentity_noMatch_returnsFalse() {
        val documentText = "Jane Doe was arrested."
        assertFalse(verifier.verifyIdentity(documentText, "John Doe").isMatch)
    }

    @Test
    fun verifyIdentity_singleTokenName_skipsWithReason() {
        val result = verifier.verifyIdentity("B was here", "B")
        assertFalse("Single-letter name must never match", result.isMatch)
        assertTrue(result.skipped)
        assertEquals("mononym", result.skipReason)
    }

    @Test
    fun verifyIdentity_mononymAcrossDocument_neverMatches() {
        val documentText = "Madonna performed yesterday."
        val result = verifier.verifyIdentity(documentText, "Madonna")
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
    }

    @Test
    fun verifyIdentity_shortToken_skips() {
        val result = verifier.verifyIdentity("Jo Smith was arrested", "Jo Smith")
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
        assertEquals("first_token_unacceptable", result.skipReason)
    }

    @Test
    fun verifyIdentity_stopwordAsFirstName_skips() {
        val result = verifier.verifyIdentity(
            "Search results: Smith, John was booked.",
            "Search Smith"
        )
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
    }

    @Test
    fun verifyIdentity_proximityRequired_distantTokensDoNotMatch() {
        val documentText = buildString {
            append("John ")
            repeat(50) { append("filler ") }
            append("Doe was the topic.")
        }
        val result = verifier.verifyIdentity(documentText, "John Doe")
        assertFalse("First and last 50 tokens apart must not match", result.isMatch)
    }

    @Test
    fun verifyIdentity_wordBoundary_singleLetterDoesNotMatchInsideOtherName() {
        // Confirms the underlying bug fix: "B Smith" must not match because "B"
        // alone is unverifiable and skipped before regex even runs. Also confirms
        // that even if a longer token were checked, \b boundaries prevent
        // partial-word matches like "B" -> "Bob".
        val result = verifier.verifyIdentity("Bob Smith and Jane Doe", "B Smith")
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
    }

    @Test
    fun verifyIdentity_lastFirstWithStopword_skips() {
        val result = verifier.verifyIdentity("Doe, Inmate", "Inmate Doe")
        assertFalse(result.isMatch)
        assertTrue(result.skipped)
    }

    @Test
    fun verifyIdentity_hyphenatedName_matches() {
        val documentText = "Mary-Jane Watson reported missing."
        val result = verifier.verifyIdentity(documentText, "Mary-Jane Watson")
        assertTrue(result.isMatch)
    }

    // ---- verifyBopInmateJson (Federal BOP inmate-locator API) ----

    private fun bopJson(vararg records: String): String =
        """{"Captcha":"","Messages":[],"FormToken":"x","InmateLocator":[${records.joinToString(",")}]}"""

    private fun inmate(
        first: String,
        last: String,
        actRel: String = "",
        facl: String = "USP ATLANTA",
        projRel: String = "01/01/2030",
        mid: String = "A",
        age: String = "45",
        inmateNum: String = "12345-678",
    ) = """{"nameLast":"$last","nameFirst":"$first","nameMiddle":"$mid","sex":"Male",""" +
        """"age":"$age","inmateNum":"$inmateNum","faclName":"$facl",""" +
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
        // whitespace between them, which the text-proximity matcher cannot see.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        assertFalse(
            "control: text proximity cannot match the compact JSON",
            verifier.verifyIdentity(json, "John Smith").isMatch
        )
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
    fun bop_malformedJson_isCleanMissNotCrash() {
        assertFalse(verifier.verifyBopInmateJson("<html>403 Forbidden</html>", "John Smith").isMatch)
        assertFalse(verifier.verifyBopInmateJson("", "John Smith").isMatch)
        assertFalse(verifier.verifyBopInmateJson("{truncated", "John Smith").isMatch)
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
    fun bop_facilityStateMismatch_notSurfaced() {
        // Corroborated by middle + age, but the contact resolves to CA while the
        // facility (USP ATLANTA) is in GA → suppressed by the agreement gate.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = "CA"))
        assertFalse(result.isMatch)
    }

    @Test
    fun bop_facilityStateUnknown_notSurfaced() {
        // Facility not in BopFacilityCatalog → cannot establish agreement →
        // not surfaced, even when fully corroborated.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH", facl = "FCI NOWHERELAND"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = "GA"))
        assertFalse(result.isMatch)
    }

    @Test
    fun bop_contactStateUnknown_notSurfaced() {
        // Contact geography unresolved → no agreement possible → not surfaced.
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith", corro(state = null))
        assertFalse(result.isMatch)
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
