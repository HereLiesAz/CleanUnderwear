package com.hereliesaz.cleanunderwear.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CyberBackgroundChecksTest {

    @Test
    fun phone_tenDigits_formatsAsXxxXxxXxxx() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            CyberBackgroundChecks.getPhoneSearchUrl("(555) 123-4567")
        )
    }

    @Test
    fun phone_elevenDigitsWithLeadingOne_stripsCountryCode() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            CyberBackgroundChecks.getPhoneSearchUrl("+1 555-123-4567")
        )
    }

    @Test
    fun phone_thirteenDigitsWithExtension_stripsCountryCodeAndIgnoresExtension() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            CyberBackgroundChecks.getPhoneSearchUrl("1-555-123-4567 ext. 99")
        )
    }

    @Test
    fun phone_doubleFormattedElevenDigitsLikeOneOne_stripsLeadingOne() {
        // Raw paste oddities like "11 555 123 4567" reduce to digits "115551234567"
        // (length 12, starts with 1) — strip yields the proper 10-digit number.
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            CyberBackgroundChecks.getPhoneSearchUrl("1 1 555 123 4567")
        )
    }

    @Test
    fun phone_tooShort_fallsBackToBasePath() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone",
            CyberBackgroundChecks.getPhoneSearchUrl("555-123")
        )
    }

    @Test
    fun email_preservesAtAndDot_lowercases_andEncodesPlus() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/email/foo.bar%2Btag@example.com",
            CyberBackgroundChecks.getEmailSearchUrl("Foo.Bar+tag@Example.com")
        )
    }

    @Test
    fun email_trimsWhitespace() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/email/jane@example.com",
            CyberBackgroundChecks.getEmailSearchUrl("  Jane@example.com  ")
        )
    }

    @Test
    fun email_missingAtSign_fallsBackToBasePath() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/email",
            CyberBackgroundChecks.getEmailSearchUrl("noatsign")
        )
    }

    // ---- address ----

    @Test
    fun address_fullStreetAddress_stateAndZipCombined() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/123-main-st/new-orleans/la",
            CyberBackgroundChecks.getAddressSearchUrl("123 Main St, New Orleans, LA 70130")
        )
    }

    @Test
    fun address_harvestedCityRegionZip_noStreet() {
        // ContactHarvester assembles structured-postal data as "city, region, zip".
        // The old parser mapped city→street/state→city/zip→state; this must now
        // resolve to city + state.
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/new-orleans/la",
            CyberBackgroundChecks.getAddressSearchUrl("New Orleans, LA, 70130")
        )
    }

    @Test
    fun address_commaLessFreeform_cityStateZip() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/new-orleans/la",
            CyberBackgroundChecks.getAddressSearchUrl("New Orleans LA 70130")
        )
    }

    @Test
    fun address_cityStateNoZip() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/new-orleans/la",
            CyberBackgroundChecks.getAddressSearchUrl("New Orleans, LA")
        )
    }

    @Test
    fun address_streetOnly() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/123-main-st",
            CyberBackgroundChecks.getAddressSearchUrl("123 Main St")
        )
    }

    @Test
    fun address_fullStreetAddressStateAndZipAsSeparateParts() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address/123-main-st/new-orleans/la",
            CyberBackgroundChecks.getAddressSearchUrl("123 Main St, New Orleans, LA, 70130")
        )
    }

    @Test
    fun address_empty_fallsBackToBasePath() {
        assertEquals(
            "https://www.cyberbackgroundchecks.com/address",
            CyberBackgroundChecks.getAddressSearchUrl("   ")
        )
    }
}
