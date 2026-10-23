package com.hereliesaz.cleanunderwear.util

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteTest {

    private val date: (Long) -> String = { "10/23/2026" }
    private fun t(status: TargetStatus) = Target(
        displayName = "Jane Doe", status = status, lastStatusChangeTimestamp = 1L,
        obituaryUrl = "https://www.echovita.com/us/obituaries/la/gretna/jane-doe-1"
    )

    @Test
    fun possibleMatchesNeverReachTheNote() {
        assertNull(ContactNote.block(t(TargetStatus.POSSIBLE_MATCH), date))
        assertNull(ContactNote.block(t(TargetStatus.WEAK_MATCH), date))
    }

    @Test
    fun theBlockIsUpdatedInPlaceAndTheUsersTextKept() {
        val first = ContactNote.merge("Met at the 2019 reunion.", ContactNote.block(t(TargetStatus.MONITORING), date)!!)
        assertTrue(first.startsWith("Met at the 2019 reunion."))
        val second = ContactNote.merge(first, ContactNote.block(t(TargetStatus.DECEASED), date)!!)
        assertTrue(second.startsWith("Met at the 2019 reunion."))
        assertTrue(second.contains("[Registry Status: Deceased]"))
        assertFalse(second.contains("Monitoring"))
        assertTrue(second.contains("Obit: https://www.echovita.com/"))
        assertTrue(ContactNote.isSigned(second))
    }

    @Test
    fun aLegacyUnsignedBlockIsReplaced() {
        val legacy = "[Registry Status: Monitoring]\nLast Check: 01/02/2025"
        val merged = ContactNote.merge(legacy, ContactNote.block(t(TargetStatus.IGNORED), date)!!)
        assertEquals(ContactNote.block(t(TargetStatus.IGNORED), date), merged)
    }

    @Test
    fun handTypedTextIsNotSigned() {
        assertFalse(ContactNote.isSigned("[Registry Status: Deceased]\nObit: https://x"))
    }
}
