package com.hereliesaz.cleanunderwear.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for the cold-start scorer. These tests pin the safety properties the
 * rest of the app relies on: a shared (possibly recycled) phone never auto-merges people whose
 * names contradict, a common name alone is "not enough data", and only genuinely corroborating
 * evidence reaches a confident MATCH.
 */
class ProbabilisticScorerTest {

    private val scorer = ProbabilisticScorer()

    @Test
    fun samePhoneAndName_isConfidentMatch() {
        val a = MatchRecord.fromDisplayName("John Smith", phone = "555-123-4567")
        val b = MatchRecord.fromDisplayName("John Smith", phone = "(555) 123-4567")

        val verdict = scorer.score(a, b)

        assertEquals(MatchDecision.MATCH, verdict.decision)
        assertTrue(verdict.isConfidentMatch)
    }

    @Test
    fun sameEmail_isConfidentMatch() {
        val a = MatchRecord.fromDisplayName("John Smith", email = "jsmith@example.com")
        val b = MatchRecord.fromDisplayName("John Smith", email = "JSmith@Example.com")

        assertEquals(MatchDecision.MATCH, scorer.score(a, b).decision)
    }

    @Test
    fun sharedPhoneButConflictingSurnames_isReviewNotMatch() {
        // The recycled-number case: a shared phone is strong evidence, but two clearly different
        // surnames must keep this out of an auto-merge and in front of a human.
        val a = MatchRecord.fromDisplayName("John Smith", phone = "555-123-4567")
        val b = MatchRecord.fromDisplayName("John Jones", phone = "555-123-4567")

        val verdict = scorer.score(a, b)

        assertEquals(MatchDecision.REVIEW, verdict.decision)
        assertFalse(verdict.isConfidentMatch)
    }

    @Test
    fun commonSurnameNameOnly_isInsufficient() {
        // "Two John Smiths with nothing else in common come back INSUFFICIENT, not MATCH."
        val frequentSmith = ProbabilisticScorer(
            frequency = { field, value -> if (field == "surname" && value == "smith") 50 else 1 }
        )
        val a = MatchRecord.fromDisplayName("John Smith")
        val b = MatchRecord.fromDisplayName("John Smith")

        assertEquals(MatchDecision.INSUFFICIENT, frequentSmith.score(a, b).decision)
    }

    @Test
    fun rareSurnameNameOnly_stillNotAutoMerged() {
        // Even an uncommon name, with no corroborating field, must not auto-merge on its own.
        val rare = ProbabilisticScorer(
            frequency = { field, value -> if (field == "surname" && value == "quillon") 2 else 1 }
        )
        val a = MatchRecord.fromDisplayName("Zephyr Quillon")
        val b = MatchRecord.fromDisplayName("Zephyr Quillon")

        assertFalse(rare.score(a, b).isConfidentMatch)
    }

    @Test
    fun firstNamesOnly_isInsufficient() {
        val a = MatchRecord(firstName = "John")
        val b = MatchRecord(firstName = "John")

        assertEquals(MatchDecision.INSUFFICIENT, scorer.score(a, b).decision)
    }

    @Test
    fun conflictingSurnamesNoOtherEvidence_isNoMatch() {
        val a = MatchRecord.fromDisplayName("John Smith")
        val b = MatchRecord.fromDisplayName("John Jones")

        assertEquals(MatchDecision.NO_MATCH, scorer.score(a, b).decision)
    }

    @Test
    fun nicknameVariantSurnameMatch_isConfidentMatch() {
        val nicknamed = ProbabilisticScorer(nicknames = { name ->
            if (name == "jim") listOf("james") else emptyList()
        })
        val a = MatchRecord(firstName = "Jim", lastName = "Smith")
        val b = MatchRecord(firstName = "James", lastName = "Smith")

        assertEquals(MatchDecision.MATCH, nicknamed.score(a, b).decision)
    }

    @Test
    fun verdictCarriesPerFieldAuditTrail() {
        val a = MatchRecord.fromDisplayName("John Smith", phone = "555-123-4567")
        val b = MatchRecord.fromDisplayName("John Smith", phone = "555-123-4567")

        val verdict = scorer.score(a, b)

        assertTrue(verdict.contributions.any { it.field == "phone" })
        assertTrue(verdict.contributions.any { it.field == "name" })
        assertTrue(verdict.probability in 0.0..1.0)
    }
}
