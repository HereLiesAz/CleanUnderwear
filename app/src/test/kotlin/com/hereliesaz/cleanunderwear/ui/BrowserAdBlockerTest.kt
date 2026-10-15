package com.hereliesaz.cleanunderwear.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdBlockerTest {
    @Test
    fun blocksKnownAdHostsAndSubdomains() {
        assertTrue(BrowserAdBlocker.blocks("doubleclick.net"))
        assertTrue(BrowserAdBlocker.blocks("pagead2.googlesyndication.com"))
        assertTrue(BrowserAdBlocker.blocks("securepubads.g.doubleclick.net"))
        assertTrue(BrowserAdBlocker.blocks("ADS.Criteo.COM."))
        assertTrue(BrowserAdBlocker.blocks("adservice.google.com"))
        assertTrue(BrowserAdBlocker.blocks("cdn.taboola.com"))
    }

    @Test
    fun doesNotBlockOrdinaryWebsitesOrLookalikes() {
        assertFalse(BrowserAdBlocker.blocks("google.com"))
        assertFalse(BrowserAdBlocker.blocks("accounts.google.com"))
        assertFalse(BrowserAdBlocker.blocks("facebook.com"))
        assertFalse(BrowserAdBlocker.blocks("smartbackgroundchecks.com"))
        assertFalse(BrowserAdBlocker.blocks("notdoubleclick.net"))
        assertFalse(BrowserAdBlocker.blocks("doubleclick.net.evil.example"))
        assertFalse(BrowserAdBlocker.blocks("ads.example.com"))
        assertFalse(BrowserAdBlocker.blocks(null))
        assertFalse(BrowserAdBlocker.blocks(""))
    }
}
