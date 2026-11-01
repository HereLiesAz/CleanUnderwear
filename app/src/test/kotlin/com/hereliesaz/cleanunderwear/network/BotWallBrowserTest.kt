package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the visible browser takes for a wall. A wrong "yes" pauses every lookup on a page that is
 * showing its results, so these pages are the ones a real CyberBackgroundChecks lookup shows.
 */
class BotWallBrowserTest {

    // Cloudflare's passive bot-detection beacon, which it adds to ordinary pages behind it.
    private val beacon = """<script>(function(){var a=document.createElement('script');
        a.src='/cdn-cgi/challenge-platform/h/b/scripts/jsd/abc123def456/main.js';
        document.getElementsByTagName('head')[0].appendChild(a);})();</script>"""

    private val resultsPage = """<html><head><script src="https://challenges.cloudflare.com/turnstile/v0/api.js"></script></head>
        <body><h4>60 results for <b>Bradley Blue</b></h4>
        <div class="card"><h2>Bradley Byron Blue</h2><p>Age: 66</p><p>Lives at 1309 E Beach Blvd, Pass Christian, MS</p>
        <p>Related to Diane Beth Blue, James Benjamin Blue</p></div>$beacon</body></html>"""

    private val zeroResults = """<html><head><script src="https://www.google.com/recaptcha/api.js"></script></head>
        <body><h4>0 results for <b>bblue@lfcu.com</b></h4>
        <div class="card"><h2>Public Records</h2><p>Unfortunately we did not find any results.</p></div>$beacon</body></html>"""

    @Test
    fun aResultsPageWithCloudflaresPassiveBeaconIsNotAWall() {
        assertFalse(BotWall.suspected(resultsPage))
        assertFalse(BotWall.detected(resultsPage))
        assertNull(BotWall.reason(resultsPage))
    }

    @Test
    fun aZeroResultsPageWithTheBeaconIsAnAnswerNotAWall() {
        assertFalse(BotWall.suspected(zeroResults))
        assertTrue(BotWall.saysNoResults(zeroResults))
    }

    @Test
    fun theInteractiveChallengeStillIs() {
        val challenge = """<html><head><title>Just a moment...</title></head><body>
            <script>window._cf_chl_opt={};var a=document.createElement('script');
            a.src='/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1?ray=8f1';</script></body></html>"""
        assertTrue(BotWall.suspected(challenge))
        assertTrue(BotWall.detected(challenge))
        assertEquals("challenge page markers", BotWall.reason(challenge))
    }

    @Test
    fun theChallengeScriptAloneIsEnoughEvenOnALongPage() {
        val long = "<html><body>" + "<p>Some text about people and places.</p>".repeat(80) +
            "<script src='/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1'></script></body></html>"
        assertTrue(BotWall.suspected(long))
        assertTrue(BotWall.detected(long))
    }

    @Test
    fun challengeWordingIsReadFromWhatThePersonSeesNotFromScripts() {
        val seen = "<html><body><h1>Verify you are human</h1>" + "<p>Searching public records.</p>".repeat(60) + "</body></html>"
        assertTrue(BotWall.suspected(seen))
        assertTrue(BotWall.detected(seen))
        val inScript = "<html><body><div class='person-card'><h2>Jane Doe</h2></div>" +
            "<script>var msg = 'verify you are human';</script></body></html>"
        assertFalse(BotWall.suspected(inScript))
    }

    @Test
    fun aTurnstileWidgetOnAnEmptyPageStillPauses() {
        val widget = "<html><body><div class=\"cf-turnstile\" data-sitekey=\"x\"></div></body></html>"
        assertTrue(BotWall.suspected(widget))
        assertNotNull(BotWall.reason(widget))
    }
}
