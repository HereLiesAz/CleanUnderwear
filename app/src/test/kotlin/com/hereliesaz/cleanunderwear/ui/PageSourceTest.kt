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
}
