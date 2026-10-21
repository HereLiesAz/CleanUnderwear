package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbcParseFallbackTest {

    private val enricher = CyberBackgroundChecksEnricher()

    @Test
    fun jsonLdPerson_fillsFieldsTheSelectorsMiss() {
        val html = """
            <html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebPage","name":"Profile"},
              {"@type":"Person","name":"Dana Marie Reyes","telephone":["(504) 555-0909"],
               "birthDate":"1980-04-02",
               "address":{"@type":"PostalAddress","streetAddress":"12 Elm St",
                 "addressLocality":"New Orleans","addressRegion":"LA","postalCode":"70130"}}
            ]}
            </script></head><body><div class="x-profile">whatever</div></body></html>
        """.trimIndent()

        val f = enricher.parseFindings(html)
        assertNotNull(f)
        assertEquals("Dana Marie Reyes", f!!.name)
        assertEquals("Marie", f.middleName)
        assertEquals("(504)555-0909".filter { it.isDigit() }, f.phone)
        assertEquals("12 Elm St, New Orleans, LA, 70130", f.address)
        assertEquals("1980-04-02", f.dob)
    }

    @Test
    fun microdataPerson_isRead() {
        val html = """
            <html><body><div itemscope itemtype="https://schema.org/Person">
              <span itemprop="name">Dana Reyes</span>
              <span itemprop="telephone">504-555-0909</span>
            </div></body></html>
        """.trimIndent()
        val f = enricher.parseFindings(html)!!
        assertEquals("Dana Reyes", f.name)
        assertEquals("5045550909", f.phone)
    }

    @Test
    fun malformedJsonLd_isIgnored() {
        val html = """<html><head><script type="application/ld+json">{not json</script></head>
            <body><h1>No Results Found</h1></body></html>"""
        assertNull(enricher.parseFindings(html))
    }

    @Test
    fun lastCommaFirst_cardName_verifies() {
        val target = Target(displayName = "Dana Reyes", phoneNumber = "5045550303")
        val card = BrowserMission.CbcByName("Dana Reyes") to
            CyberBackgroundChecksEnricher.Findings("REYES, DANA M", "New Orleans, LA", null)
        val outcome = enricher.enrich(target, listOf(card))
        assertTrue(outcome.provenance, outcome.verified)
        assertEquals("New Orleans, LA", outcome.target.residenceInfo)
    }

    @Test
    fun describeParse_reportsPageShapeWhenNothingFound() {
        val html = """<html><head><title>Dana Reyes - Profile</title></head>
            <body><h1 class="hdr-person">Dana Reyes</h1><div class="resident-addr">x</div></body></html>"""
        val line = enricher.describeParse(html, enricher.parseFindings(html))
        assertTrue(line, line.startsWith("nothing found"))
        assertTrue(line, line.contains("hdr-person"))
        assertTrue(line, line.contains("resident-addr"))
    }

    @Test
    fun lastCommaFirst_isStoredInNaturalOrder_withTheRightMiddleName() {
        val html = """<html><body><div class="person-card"><span class="name">REYES, DANA MARIE</span></div></body></html>"""
        val f = enricher.parseFindings(html)!!
        assertEquals("DANA MARIE REYES", f.name)
        assertEquals("MARIE", f.middleName)
    }

    @Test
    fun aRelativesPersonRecord_neverFillsTheCardsFields() {
        val html = """
            <html><head><script type="application/ld+json">
            [{"@type":"Person","name":"Luis Okafor","telephone":"504-555-1111","birthDate":"1950"},
             {"@type":"Person","name":"Dana Reyes","telephone":"504-555-0909"}]
            </script></head>
            <body><div class="person-card"><span class="name">Dana Reyes</span></div></body></html>
        """.trimIndent()
        val f = enricher.parseFindings(html)!!
        assertEquals("5045550909", f.phone)
        assertNull(f.dob)
    }

    @Test
    fun schemaOrgUrlType_andEmptyFirstPerson_areHandled() {
        val html = """
            <html><head><script type="application/ld+json">
            [{"@type":"Person"},{"@type":"https://schema.org/Person","name":"Dana Reyes","birthDate":"1980"}]
            </script></head><body></body></html>
        """.trimIndent()
        val f = enricher.parseFindings(html)!!
        assertEquals("Dana Reyes", f.name)
        assertEquals("1980", f.dob)
    }

    @Test
    fun deeplyNestedJsonLd_doesNotCrash() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        val html = """<html><head><script type="application/ld+json">$deep</script></head><body></body></html>"""
        assertNull(enricher.parseFindings(html))
    }

    @Test
    fun describeParse_neverLogsThePageTitle() {
        val html = """<html><head><title>Dana Reyes, Age 44</title></head><body><h1>x</h1></body></html>"""
        val line = enricher.describeParse(html, null)
        assertTrue(line, !line.contains("Dana"))
        assertTrue(line, line.contains("title=yes"))
    }
}
