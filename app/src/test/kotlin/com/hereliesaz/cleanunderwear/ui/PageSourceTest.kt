package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSourceTest {

    @Test
    fun resultsAreRequiredBeforeTheLookupAdvances() {
        val shell = "<html><body><nav>" + "Loading search ".repeat(90) + "</nav></body></html>"
        assertFalse(PageSource.looksCbcContent(shell, detail = false))
        val results = shell.replace("</body>", "<div class=\"person-card\"><h2>Jane Roe</h2></div></body>")
        assertTrue(PageSource.looksCbcContent(results, detail = false))
        assertTrue(PageSource.looksCbcContent("<h1>No Results Found</h1>", detail = false))
        assertTrue(PageSource.looksCbcContent("<h1 class=\"full-name\">Jane Roe</h1>", detail = true))
        assertFalse(PageSource.looksCbcContent(shell, detail = true))
        val jsonLd = """<html><head><script type="application/ld+json">
            {"@type":"Person","name":"Jane Roe","telephone":"2125550100"}
            </script></head><body><p>Results are listed in structured data</p></body></html>"""
        assertTrue(PageSource.looksCbcContent(jsonLd, detail = false))
        val microdata = """<html><body><div itemscope itemtype="https://schema.org/Person">
            <span itemprop="name">Jane Roe</span>
            </div></body></html>"""
        assertTrue(PageSource.looksCbcContent(microdata, detail = false))
    }

    @Test
    fun matchingTheMainDocumentRejectsPreviousContactsAndQueries() {
        val current = "https://www.cyberbackgroundchecks.com/phone/5045550101?sort=recent"
        assertTrue(PageSource.sameDocumentUrl(current, current + "#people"))
        assertFalse(PageSource.sameDocumentUrl(current, "https://www.cyberbackgroundchecks.com/phone/5045550199?sort=recent"))
        assertFalse(PageSource.sameDocumentUrl(current, "https://www.cyberbackgroundchecks.com/phone/5045550101?sort=oldest"))
        assertFalse(PageSource.sameDocumentUrl(current, null))
        assertFalse(PageSource.sameDocumentUrl(current, "https://some host/invalid path"))
    }

    @Test
    fun decodesTheJsonStringTheBrowserReturns() {
        assertEquals("<html lang=\"en\">\n<body>café \"quoted\"</body></html>",
            PageSource.decode("\"<html lang=\\\"en\\\">\\n<body>caf\\u00e9 \\\"quoted\\\"</body></html>\""))
    }

    @Test
    fun nothingToReadIsNull() {
        assertNull(PageSource.decode(null))
        assertNull(PageSource.decode("null"))
    }

    @Test
    fun aBlankPageIsNotDrawn() {
        assertFalse(PageSource.looksDrawn(null))
        assertFalse(PageSource.looksDrawn("<html><body></body></html>"))
        assertTrue(PageSource.looksDrawn("<html><body>" + "x".repeat(PageSource.MIN_READABLE_CHARS) + "</body></html>"))
    }

    @Test
    fun describesAPageFromInside() {
        val seen = PageSource.describe("\"1204|complete|Log in / Instagram|Mozilla/5.0 (Linux; Android 14; wv) Chrome/130\"")!!
        assertEquals(1204, seen.characters)
        assertEquals("complete", seen.state)
        assertEquals("Log in / Instagram", seen.title)
        assertEquals("Mozilla/5.0 (Linux; Android 14; wv) Chrome/130", seen.browser)
        assertFalse(seen.blank)
    }

    @Test
    fun aPageWithNoBodyOrBarelyAnyTextIsBlank() {
        assertTrue(PageSource.describe("\"-1|loading||Mozilla/5.0\"")!!.blank)
        assertTrue(PageSource.describe("\"0|complete|Facebook|Mozilla/5.0\"")!!.blank)
        assertTrue(PageSource.describe("\"${PageSource.BLANK_BELOW - 1}|complete||Mozilla/5.0\"")!!.blank)
        assertFalse(PageSource.describe("\"${PageSource.BLANK_BELOW}|complete||Mozilla/5.0\"")!!.blank)
    }

    @Test
    fun aDescriptionInTheWrongShapeIsNull() {
        assertNull(PageSource.describe(null))
        assertNull(PageSource.describe("null"))
        assertNull(PageSource.describe("\"12|complete\""))
        assertNull(PageSource.describe("\"many|complete|title|ua\""))
    }
}
