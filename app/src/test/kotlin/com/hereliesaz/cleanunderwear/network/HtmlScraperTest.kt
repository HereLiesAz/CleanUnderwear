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
}
