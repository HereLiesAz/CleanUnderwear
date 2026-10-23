package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The given name and surname a search runs on, whatever the contact card wraps them in. */
class NameValidatorSearchFormTest {

    private fun ok(raw: String) = NameValidator.validate(raw) as NameValidator.Result.Ok

    @Test
    fun suffixesAreDropped() {
        assertEquals("Smith", ok("John Smith III").last)
        assertEquals("Smith", ok("John Smith Jr.").last)
    }

    @Test
    fun titlesAndAnnotationsAreDropped() {
        assertEquals("Bob", ok("Uncle Bob Smith").first)
        assertEquals("John", ok("Dr John Smith").first)
        assertEquals("Smith", ok("John Smith (work)").last)
    }

    @Test
    fun lastNameFirstIsReordered() {
        val r = ok("Smith, John")
        assertEquals("John", r.first)
        assertEquals("Smith", r.last)
    }

    @Test
    fun twoLetterNamesAreNames() {
        assertEquals("Li", ok("Wei Li").last)
        assertEquals("Ng", ok("Kim Ng").last)
        assertTrue(NameValidator.validate("Mr Smith") is NameValidator.Result.Skip)
    }
}
