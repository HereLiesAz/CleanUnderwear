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

    // ---- What kind of wall: a check the user can complete, or the site shutting the request out ----

    private val blockPage = """<html><head><title>Attention Required! | Cloudflare</title></head>
        <body><h1>Sorry, you have been blocked</h1>
        <h2>Why have I been blocked?</h2>
        <p>This website is using a security service to protect itself from online attacks. The action you just
        performed triggered the security solution.</p>
        <h2>What can I do to resolve this?</h2>
        <p>You can email the site owner to let them know you were blocked.</p>
        <p>Cloudflare Ray ID: <strong>a55cfa0bcb00b91d</strong></p><p>Performance &amp; security by Cloudflare</p></body></html>"""

    @Test
    fun cloudflaresBlockPageIsBarredNotAChallenge() {
        assertTrue(BotWall.suspected(blockPage))
        assertTrue(BotWall.detected(blockPage))
        assertEquals(BotWall.Wall.BARRED, BotWall.wallOf(blockPage))
    }

    @Test
    fun aBlockPageIsRecognisedByWhatItSaysEvenWithoutTheTitle() {
        val untitled = blockPage.replace("<title>Attention Required! | Cloudflare</title>", "<title>Cloudflare</title>")
        assertEquals("blocked by the site", BotWall.reason(untitled))
        assertEquals(BotWall.Wall.BARRED, BotWall.wallOf(untitled))
    }

    @Test
    fun aRateLimitAndAnAccessDeniedPageAreBarredToo() {
        val limited = "<html><body><h1>Error 1015</h1><p>You are being rate limited</p></body></html>"
        val denied = "<html><body><h1>Access denied</h1><p>Error code 1020. You do not have access to this site.</p></body></html>"
        assertEquals(BotWall.Wall.BARRED, BotWall.wallOf(limited))
        assertEquals(BotWall.Wall.BARRED, BotWall.wallOf(denied))
    }

    @Test
    fun aCheckTheUserCanCompleteIsAChallenge() {
        val justAMoment = "<html><head><title>Just a moment...</title></head><body><p>Checking your browser</p>" +
            "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1?ray=abc\"></script></body></html>"
        val widget = "<html><body><div class=\"cf-turnstile\" data-sitekey=\"x\"></div></body></html>"
        // The older "Attention Required" page is a block page with a check on it: that is a challenge.
        val oldCheck = "<html><head><title>Attention Required! | Cloudflare</title></head><body>" +
            "<h1>One more step</h1><p>Please complete the security check to access the site.</p>" +
            "<div class=\"g-recaptcha\" data-sitekey=\"x\"></div></body></html>"
        assertEquals(BotWall.Wall.CHALLENGE, BotWall.wallOf(justAMoment))
        assertEquals(BotWall.Wall.CHALLENGE, BotWall.wallOf(widget))
        assertEquals(BotWall.Wall.CHALLENGE, BotWall.wallOf(oldCheck))
    }

    @Test
    fun anAnswerOrABlankPageIsNoWall() {
        assertNull(BotWall.wallOf(resultsPage))
        assertNull(BotWall.wallOf(zeroResults))
        assertNull(BotWall.wallOf(""))
    }
}
