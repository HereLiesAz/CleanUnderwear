package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.domain.LookupOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoicesSelectionTest {
    private fun candidate(name: String) = LookupOption(
        name = name, middleName = null, dob = null, address = null, phone = null,
        foundBy = listOf("name"), fits = emptyList(), differs = emptyList()
    )

    @Test
    fun savedIndexOutsideCurrentCandidateListCannotSelectSomeoneElse() {
        val list = listOf(candidate("New Result"))
        assertNull(selectedLookupOption(list, 1))
        assertNull(selectedLookupOption(list, -1))
        assertNull(selectedLookupOption(emptyList(), 0))
        assertNull(selectedLookupOption(list, null))
    }

    @Test
    fun validIndexStillSelectsTheIntendedCandidate() {
        val alice = candidate("Alice Jones")
        val bob = candidate("Bob Smith")
        assertEquals(bob, selectedLookupOption(listOf(alice, bob), 1))
        assertEquals(alice, selectedLookupOption(listOf(alice, bob), 0))
    }
}
