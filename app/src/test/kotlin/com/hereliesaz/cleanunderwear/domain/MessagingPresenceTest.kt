package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.MessagingPresence
import com.hereliesaz.cleanunderwear.match.FieldContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingPresenceTest {

    private val now = 1_800_000_000_000L

    @Test
    fun builder_readsPhoneRowsAndWhatsAppIds_andIgnoresOtherAccounts() {
        val p = MessagingPresence.Builder().apply {
            add("com.whatsapp", "15045550101@s.whatsapp.net")
            add("org.thoughtcrime.securesms", "+1 (504) 555-0101")
            add("com.google", "504-555-0202")
            add("com.whatsapp", "Message +1 504")
        }.build()
        assertEquals(setOf("WhatsApp", "Signal"), p.appsFor("504-555-0101"))
        assertTrue(p.appsFor("504-555-0202").isEmpty())
        assertEquals(setOf("WhatsApp", "Signal"), p.appsOnDevice)
    }

    private fun merge(survivorApps: Set<String>, absorbedApps: Set<String>) = SuspectDetector.assessMerge(
        MergeCaseInput(
            ledgerId = 1, survivorId = 1,
            survivorName = "Robert Smith", survivorPhone = "504-555-0101", survivorEmail = null,
            absorbedName = "Bob Smith", absorbedPhone = "504-555-0202", absorbedEmail = null,
            probability = 0.97, contributions = listOf(FieldContribution("email", "AGREE", 9.0)),
            mergedAtMillis = now, survivorHistory = null, absorbedHistory = null,
            historyAvailable = false, survivorPhoneSharers = 0,
            survivorApps = survivorApps, absorbedApps = absorbedApps, presenceAvailable = true
        ),
        now
    )!!

    @Test
    fun twoLiveNumbers_pointToTwoPeople_oneLiveToANumberChange() {
        val both = merge(setOf("WhatsApp"), setOf("Signal"))
        assertTrue(both.suspicion.any { it.text.contains("both lines are live") })

        val one = merge(setOf("WhatsApp"), emptySet())
        assertTrue(one.defense.any { it.text.contains("retired for a new one") })
        assertTrue(one.suspicion.none { it.text.contains("both lines are live") })
    }

    private fun stale(apps: Set<String>, otherHolder: String? = null, replacement: ReplacementCandidate? = null) =
        SuspectDetector.assessStale(
            StaleCaseInput(
                targetId = 3, name = "Dana Reyes", phone = "504-555-0303", email = null,
                lookupFailed = false, history = null, historyAvailable = false, historyCoverageMillis = null,
                sharers = emptyList(), replacement = replacement,
                apps = apps, appsOnDevice = setOf("WhatsApp"), otherHolder = otherHolder
            ),
            now
        )

    @Test
    fun anotherPersonOnThePhoneLookup_flagsTheNumber() {
        val case = stale(emptySet(), otherHolder = "Pat Okafor")!!
        assertTrue(case.suspicion.any { it.text.contains("Pat Okafor") && it.weight >= 3.0 })
        assertEquals(Lean.LIKELY_PROBLEM, case.lean)
    }

    @Test
    fun registeredNumber_isDefended_unregisteredAloneIsBelowTheBar() {
        assertNull(stale(setOf("WhatsApp")))
        assertNull(stale(emptySet()))
        val defended = stale(setOf("WhatsApp"), otherHolder = "Pat Okafor")!!
        assertTrue(defended.defense.any { it.text.contains("in service") })
    }

    @Test
    fun liveMergedInNumber_isOfferedAsReplacement() {
        val case = stale(emptySet(), replacement = ReplacementCandidate("504-555-0909", "Dana R", null, setOf("WhatsApp")))!!
        assertEquals("504-555-0909", case.replacementPhone)
        assertTrue(case.suspicion.any { it.text.contains("live line") })
    }

    @Test
    fun otherHolder_isReadOnlyFromPhoneLookups() {
        assertEquals("Pat Okafor", SuspectReview.otherHolder("phone+name · NOT merged (name conflict: card 'Pat Okafor' ≠ Dana Reyes)"))
        assertNull(SuspectReview.otherHolder("name · NOT merged (name conflict: card 'Pat Okafor' ≠ Dana Reyes)"))
        assertNull(SuspectReview.otherHolder("phone · verified (name match: Dana Reyes)"))
        assertNull(SuspectReview.otherHolder(null))
    }
}
