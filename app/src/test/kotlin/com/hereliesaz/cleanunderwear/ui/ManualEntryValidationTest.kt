package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Add someone" form: what it lets through, and which problem it names first. */
class ManualEntryValidationTest {

    @Test
    fun aFullNameWithAPhoneOrAnEmailPasses() {
        assertNull(validateManualEntry("Pat Doe", "555 010 2030", ""))
        assertNull(validateManualEntry("Pat Doe", "", "pat@example.org"))
        assertNull(validateManualEntry("  Pat Doe ", "(555) 010-2030", "pat@example.org"))
    }

    @Test
    fun aOneWordNameIsRefusedAsTheDialogSaysAFullNameIsNeeded() {
        assertEquals(ManualEntryProblem.NAME, validateManualEntry("Madonna", "5550102030", ""))
        assertEquals(ManualEntryProblem.NAME, validateManualEntry("", "5550102030", ""))
        assertEquals(ManualEntryProblem.NAME, validateManualEntry("B", "5550102030", ""))
    }

    @Test
    fun oneOfPhoneAndEmailIsNeeded() {
        assertEquals(ManualEntryProblem.NO_PHONE_OR_EMAIL, validateManualEntry("Pat Doe", " ", ""))
    }

    @Test
    fun aPhoneThatIsNotANumberIsRefused() {
        assertEquals(ManualEntryProblem.PHONE, validateManualEntry("Pat Doe", "abc", ""))
        assertEquals(ManualEntryProblem.PHONE, validateManualEntry("Pat Doe", "12345", ""))
        assertEquals(ManualEntryProblem.PHONE, validateManualEntry("Pat Doe", "555 010 2030 ext 4", ""))
        assertEquals(ManualEntryProblem.PHONE, validateManualEntry("Pat Doe", "1234567890123456", ""))
    }

    @Test
    fun phoneFormatsPeopleReallyTypeAreAccepted() {
        listOf("+1 555 010 2030", "555.010.2030", "555-0102", "(555) 010-2030", "+44 20 7946 0958").forEach {
            assertTrue(it, looksLikePhone(it))
        }
        assertFalse(looksLikePhone("555+0102030"))
    }

    @Test
    fun anEmailNeedsAnAtAndADottedDomain() {
        assertEquals(ManualEntryProblem.EMAIL, validateManualEntry("Pat Doe", "", "pat"))
        assertEquals(ManualEntryProblem.EMAIL, validateManualEntry("Pat Doe", "", "pat@example"))
        assertEquals(ManualEntryProblem.EMAIL, validateManualEntry("Pat Doe", "5550102030", "pat@@example.org"))
        assertTrue(looksLikeEmail("pat.doe+news@mail.example.org"))
    }
}
