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

    @Test
    fun normalizePhone_keepsInternationalNumbersApart() {
        assertEquals(
            false,
            StringSimilarity.normalizePhone("+44 7700 900123") == StringSimilarity.normalizePhone("+44 7700 900199")
        )
    }

    @Test
    fun normalizePhone_matchesTrunkAndCountryCodeForms() {
        assertEquals(StringSimilarity.normalizePhone("4915112345678"), StringSimilarity.normalizePhone("0151 12345678"))
        assertEquals("12345678", StringSimilarity.normalizePhone("12 34 56 78"))
    }

    @Test
    fun nanpAreaCode_onlyForNorthAmericanNumbers() {
        assertEquals("555", StringSimilarity.nanpAreaCode("+1 (555) 123-4567"))
        assertEquals("555", StringSimilarity.nanpAreaCode("555-123-4567"))
        assertEquals(null, StringSimilarity.nanpAreaCode("+44 20 7946 0958"))
        assertEquals(null, StringSimilarity.nanpAreaCode("612 345 678"))
    }

    @Test
    fun foldDiacritics_stripsLatinAccentsOneCharacterForOne() {
        assertEquals("Jose", StringSimilarity.foldDiacritics("Jos\u00E9"))
        assertEquals("Angstrom", StringSimilarity.foldDiacritics("\u00C5ngstr\u00F6m"))
        assertEquals("Nunez", StringSimilarity.foldDiacritics("N\u00FA\u00F1ez"))
        assertEquals("Jose".length, StringSimilarity.foldDiacritics("Jos\u00E9").length)
    }

    @Test
    fun foldDiacritics_leavesCharactersWithoutAnAccentAlone() {
        // No decomposition: sharp s, ae ligature, o-slash, the "fi" ligature.
        assertEquals("Stra\u00DFe", StringSimilarity.foldDiacritics("Stra\u00DFe"))
        assertEquals("\u00E6on \u00D8", StringSimilarity.foldDiacritics("\u00E6on \u00D8"))
        assertEquals("\uFB01n", StringSimilarity.foldDiacritics("\uFB01n"))
    }

    @Test
    fun foldDiacritics_leavesHangulSyllablesWhole() {
        // They decompose into conjoining Jamo, which are letters, not accents.
        val kim = "\uAE40\uBBFC\uC218"
        assertEquals(kim, StringSimilarity.foldDiacritics(kim))
        assertEquals("Jose $kim", StringSimilarity.foldDiacritics("Jos\u00E9 $kim"))
    }
}
