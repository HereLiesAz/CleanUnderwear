package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSourceTest {

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
