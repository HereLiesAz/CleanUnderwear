package com.hereliesaz.cleanunderwear.network

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlScraperTest {
    @Test
    fun scrapeMugshots_networkErrorOnFetch_returnsNoMatch() = runBlocking {
        val okHttpClient = OkHttpClient()
        val verifier = mockk<IdentityVerifier>()
        val scraper = HtmlScraper(okHttpClient, verifier)
        // Resolvable hostname is well-formed; the test exercises the
        // catch-on-fetch-failure path, not URL syntax validation.
        val result = scraper.scrapeMugshots("https://invalid.url.that.does.not.exist.example", "John Doe")
        assertFalse(result.isMatch)
    }
}
