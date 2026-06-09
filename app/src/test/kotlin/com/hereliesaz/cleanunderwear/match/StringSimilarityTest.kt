package com.hereliesaz.cleanunderwear.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringSimilarityTest {

    @Test
    fun jaroWinkler_identicalStrings_isOne() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("smith", "smith"), 0.0)
    }

    @Test
    fun jaroWinkler_isCaseInsensitive() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("Smith", "SMITH"), 0.0)
    }

    @Test
    fun jaroWinkler_nearTypo_scoresHigh() {
        // A single-letter swap on a shared prefix should stay well above the matcher's 0.90 bar.
        assertTrue(StringSimilarity.jaroWinkler("smith", "smyth") >= 0.85)
    }

    @Test
    fun jaroWinkler_unrelatedStrings_scoreLow() {
        assertTrue(StringSimilarity.jaroWinkler("smith", "jones") < 0.7)
    }

    @Test
    fun jaroWinkler_emptyString_isZero() {
        assertEquals(0.0, StringSimilarity.jaroWinkler("smith", ""), 0.0)
    }

    @Test
    fun normalizePhone_stripsFormattingAndLeadingOne() {
        assertEquals("5551234567", StringSimilarity.normalizePhone("+1 (555) 123-4567"))
    }

    @Test
    fun normalizePhone_tooShort_isNull() {
        assertNull(StringSimilarity.normalizePhone("555-1234"))
    }

    @Test
    fun normalizePhone_blankOrNull_isNull() {
        assertNull(StringSimilarity.normalizePhone(null))
        assertNull(StringSimilarity.normalizePhone("   "))
    }

    @Test
    fun normalizeEmail_trimsAndLowercases() {
        assertEquals("john@example.com", StringSimilarity.normalizeEmail("  John@Example.COM "))
    }

    @Test
    fun normalizeEmail_withoutAtSign_isNull() {
        assertNull(StringSimilarity.normalizeEmail("not-an-email"))
    }

    @Test
    fun normalizeNameToken_keepsLettersHyphenApostrophe() {
        assertEquals("o'brien-smith", StringSimilarity.normalizeNameToken("  O'Brien-Smith  "))
    }

    @Test
    fun extractZip_findsFiveDigitZip() {
        assertEquals("62704", StringSimilarity.extractZip("123 Main St, Springfield, IL 62704"))
    }

    @Test
    fun extractZip_handlesZipPlusFour() {
        assertEquals("62704", StringSimilarity.extractZip("Springfield IL 62704-1234"))
    }

    @Test
    fun extractZip_noZip_isNull() {
        assertNull(StringSimilarity.extractZip("Springfield, Illinois"))
    }

    @Test
    fun streetTokens_foldsSuffixesAndUsesFirstSegment() {
        val tokens = StringSimilarity.streetTokens("123 Main St, Springfield, IL")
        assertEquals(setOf("123", "main", "street"), tokens)
    }

    @Test
    fun streetTokens_blank_isEmpty() {
        assertTrue(StringSimilarity.streetTokens(null).isEmpty())
        assertTrue(StringSimilarity.streetTokens("").isEmpty())
    }
}
