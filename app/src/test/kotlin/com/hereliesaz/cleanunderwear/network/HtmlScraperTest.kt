package com.hereliesaz.cleanunderwear.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hermetic: every request is answered by an interceptor, nothing leaves the machine. */
class HtmlScraperTest {

    private fun clientAnswering(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("text/html".toMediaType()))
                .build()
        }).build()

    @Test
    fun fetchBody_errorStatus_isNull() = runBlocking {
        val scraper = HtmlScraper(clientAnswering(503, "down"), IdentityVerifier())
        assertNull(scraper.fetchBody("https://example.test/feed"))
    }

    @Test
    fun fetchBody_success_returnsBody() = runBlocking {
        val scraper = HtmlScraper(clientAnswering(200, "<rss/>"), IdentityVerifier())
        assertEquals("<rss/>", scraper.fetchBody("https://example.test/feed"))
    }

    @Test
    fun bop_errorStatus_isAFailedLookup() = runBlocking {
        val scraper = HtmlScraper(clientAnswering(429, "slow down"), IdentityVerifier())
        assertTrue(scraper.scrapeBopInmate("https://example.test/bop", "John Smith").failed)
    }

    @Test
    fun botWall_recognisesAChallengePageServedWith201() {
        val turnstile = """<html><head><title>OSCN Turnstile</title></head><body>
            <div class="cf-turnstile" data-sitekey="x"></div></body></html>"""
        assertTrue(BotWall.detected(turnstile))
    }

    @Test
    fun botWall_ignoresASearchFormThatMerelyCarriesACaptcha() {
        val form = "<html><body><form><div class=\"g-recaptcha\"></div></form>" +
            "<table>" + "<tr><td>SMITH, JOHN</td><td>Booked 10/01/2026</td></tr>".repeat(60) + "</table></body></html>"
        assertEquals(false, BotWall.detected(form))
    }

    // CyberBackgroundChecks' zero-results page, as the app's browser shows it: a search form
    // whose page loads a CAPTCHA script, and no CAPTCHA anyone can see.
    private val cbcZeroResults = """<html><head><script src="https://www.google.com/recaptcha/api.js"></script></head>
        <body><form><input value="bblue@lfcu.com"><button>Search</button><button>Clear</button></form>
        <h4>0 results for <b>bblue@lfcu.com</b></h4>
        <div class="card"><h2>Public Records</h2><p>Unfortunately we did not find any results. Please take a look
        at our partner links below to assist in finding what you are looking for.</p></div></body></html>"""

    @Test
    fun botWall_aZeroResultsPageIsAnAnswerNotAWall() {
        assertTrue(BotWall.saysNoResults(cbcZeroResults))
        assertEquals(false, BotWall.detected(cbcZeroResults))
        assertEquals(false, BotWall.suspected(cbcZeroResults))
    }

    @Test
    fun botWall_aCaptchaThePersonCanSeeStillPauses() {
        val visible = "<html><body><p>Please complete the CAPTCHA to continue.</p>" +
            "<p>" + "Search public records by name, address, phone or email. ".repeat(40) + "</p></body></html>"
        assertTrue(BotWall.suspected(visible))
        assertEquals(false, BotWall.saysNoResults(visible))
    }

    @Test
    fun botWall_aCaptchaScriptAloneOnAResultsPageDoesNotPause() {
        val results = "<html><head><script>grecaptcha.ready(function(){})</script></head><body>" +
            "<div class=\"person-card\"><h2>Jane Doe</h2><p>Age 54, Gretna, LA</p></div></body></html>"
        assertEquals(false, BotWall.suspected(results))
    }
}
