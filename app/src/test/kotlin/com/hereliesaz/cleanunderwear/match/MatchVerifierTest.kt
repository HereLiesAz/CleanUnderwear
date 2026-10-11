package com.hereliesaz.cleanunderwear.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchVerifierTest {

    private val verifier = MatchVerifier(currentYear = 2026)

    private fun rec(
        age: Int? = null,
        middle: String? = null,
        city: String? = null,
        state: String? = null,
        relatives: List<String> = emptyList(),
        source: String = "a",
        kind: EventKind = EventKind.DEATH
    ) = CandidateRecord(source, source, kind, "John", "Smith", middle, age, null, city, state, relatives)

    private val contact = ContactProfile("John", "Smith", age = 45, city = "Metairie", state = "LA")

    @Test
    fun `name only is insufficient and dropped`() {
        assertEquals(MatchTier.INSUFFICIENT, verifier.verify(ContactProfile("John", "Smith"), rec()).tier)
        assertTrue(verifier.verifyAll(ContactProfile("John", "Smith"), listOf(rec(), rec(source = "b"))).isEmpty())
    }

    @Test
    fun `name and state alone is insufficient`() {
        val c = ContactProfile("John", "Smith", state = "LA")
        assertEquals(MatchTier.INSUFFICIENT, verifier.verify(c, rec(state = "LA")).tier)
    }

    @Test
    fun `middle initial agreement is enough to show as weak`() {
        val c = ContactProfile("John", "Smith", middleName = "R")
        assertEquals(MatchTier.WEAK, verifier.verify(c, rec(middle = "Robert")).tier)
    }

    @Test
    fun `age plus city is strong`() {
        assertEquals(MatchTier.STRONG, verifier.verify(contact, rec(age = 45, city = "Metairie", state = "LA")).tier)
    }

    @Test
    fun `age alone stays weak`() {
        assertEquals(MatchTier.WEAK, verifier.verify(ContactProfile("John", "Smith", age = 45), rec(age = 46)).tier)
    }

    @Test
    fun `age conflict rejects`() {
        assertEquals(MatchTier.REJECTED, verifier.verify(contact, rec(age = 70, city = "Metairie")).tier)
    }

    @Test
    fun `middle initial conflict rejects`() {
        val c = ContactProfile("John", "Smith", middleName = "Robert")
        assertEquals(MatchTier.REJECTED, verifier.verify(c, rec(middle = "K")).tier)
    }

    @Test
    fun `known survivors lift an aged match to strong`() {
        val c = ContactProfile("John", "Smith", age = 80, otherContactNames = setOf("Mary Smith"))
        val v = verifier.verify(c, rec(age = 80, relatives = listOf("Mary Smith", "Peter Jones")))
        assertEquals(MatchTier.STRONG, v.tier)
        assertTrue(v.reasons.any { it.contains("Mary Smith") })
    }

    @Test
    fun `different state counts against`() {
        val same = verifier.verify(contact, rec(age = 45, state = "LA")).confidence
        val other = verifier.verify(contact, rec(age = 45, state = "TX")).confidence
        assertTrue(other < same)
    }

    @Test
    fun `cross source agreement raises confidence and drops rejects`() {
        val c = ContactProfile("John", "Smith", age = 45, state = "LA")
        val results = verifier.verifyAll(
            c,
            listOf(
                rec(age = 45, state = "LA", source = "news"),
                rec(age = 45, state = "LA", source = "obits"),
                rec(age = 90, source = "other")
            )
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it.corroboratingSources.isNotEmpty() })
        assertEquals(MatchTier.STRONG, results.first().tier)
    }
}
