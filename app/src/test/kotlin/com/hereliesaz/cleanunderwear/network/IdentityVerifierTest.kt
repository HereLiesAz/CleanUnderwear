package com.hereliesaz.cleanunderwear.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityVerifierTest {

    private val researchAgent: OnDeviceResearchAgent = mockk<OnDeviceResearchAgent>(relaxed = true).also {
        every { it.getNicknames(any<String>()) } returns emptyList()
    }
    private val verifier = IdentityVerifier(researchAgent)

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
    ) = """{"nameLast":"$last","nameFirst":"$first","nameMiddle":"A","sex":"Male",""" +
        """"age":"45","inmateNum":"12345-678","faclName":"$facl",""" +
        """"projRelDate":"$projRel","actRelDate":"$actRel"}"""

    @Test
    fun bop_currentInmateMatchingName_isMatchWithCustodySnippet() {
        val json = bopJson(inmate(first = "JOHN", last = "SMITH"))
        val result = verifier.verifyBopInmateJson(json, "John Smith")
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
        assertTrue(verifier.verifyBopInmateJson(json, "John Smith").isMatch)
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

        // Add a current John Smith and it matches.
        val json2 = bopJson(
            inmate(first = "JOHN", last = "SMITH", actRel = "06/01/2015"),
            inmate(first = "JOHN", last = "SMITH", facl = "FCI BUTNER"),
        )
        val r = verifier.verifyBopInmateJson(json2, "John Smith")
        assertTrue(r.isMatch)
        assertTrue(r.snippet!!.contains("FCI BUTNER"))
    }

    @Test
    fun bop_nicknameExpansion_matches() {
        val agent = mockk<OnDeviceResearchAgent>(relaxed = true).also {
            every { it.getNicknames(any<String>()) } returns listOf("William")
        }
        val v = IdentityVerifier(agent)
        val json = bopJson(inmate(first = "WILLIAM", last = "SMITH"))
        assertTrue(v.verifyBopInmateJson(json, "Bill Smith").isMatch)
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
}
