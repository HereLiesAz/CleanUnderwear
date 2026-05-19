package com.hereliesaz.cleanunderwear.network

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the interceptor's *host filtering* logic — the part that's
 * testable without bringing up a real WebView / CookieManager (Android
 * framework classes that JVM unit tests can't construct).
 *
 * The cookie-jar bridge and the WebSettings.getDefaultUserAgent lookup
 * are exercised on-device; this test just confirms that
 *  (a) requests to non-bridged hosts pass through with their headers
 *      untouched, and
 *  (b) bridged hosts (CBC + SmartBGC) trigger the override path even
 *      when the WebView lookup throws.
 */
class WebViewIdentityInterceptorTest {

    private fun interceptor(): WebViewIdentityInterceptor {
        // Context only needs to be non-null; the interceptor's
        // getDefaultUserAgent call is wrapped in try/catch and will fall
        // back to a generic string in this JVM env.
        return WebViewIdentityInterceptor(mockk<Context>(relaxed = true))
    }

    private fun chainFor(request: Request, capture: (Request) -> Unit): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            val r = firstArg<Request>()
            capture(r)
            Response.Builder()
                .request(r)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }
        return chain
    }

    @Test
    fun nonBridgedHost_isPassedThroughUnchanged() {
        val original = Request.Builder()
            .url("https://example.com/something")
            .header("User-Agent", "caller-supplied-ua/1.0")
            .build()
        var sent: Request? = null
        interceptor().intercept(chainFor(original) { sent = it })
        // Same instance — no rewrite happened.
        assertEquals(original, sent)
        assertEquals("caller-supplied-ua/1.0", sent!!.header("User-Agent"))
    }

    @Test
    fun cyberBackgroundChecks_triggersUserAgentRewrite() {
        val original = Request.Builder()
            .url("https://www.cyberbackgroundchecks.com/people/john-smith")
            .header("User-Agent", "caller-supplied-ua/1.0")
            .build()
        var sent: Request? = null
        interceptor().intercept(chainFor(original) { sent = it })
        assertTrue("Bridged host must have its UA rewritten", sent!!.header("User-Agent") != "caller-supplied-ua/1.0")
        // No CookieManager available in the JVM env → no Cookie header.
        assertNull(sent!!.header("Cookie"))
    }

    @Test
    fun smartBackgroundChecks_triggersUserAgentRewrite() {
        val original = Request.Builder()
            .url("https://www.smartbackgroundchecks.com/people/jane-doe")
            .build()
        var sent: Request? = null
        interceptor().intercept(chainFor(original) { sent = it })
        assertTrue("Bridged host must set a UA", !sent!!.header("User-Agent").isNullOrBlank())
    }

    @Test
    fun bridgedHost_subdomain_alsoTriggersRewrite() {
        val original = Request.Builder()
            .url("https://api.cyberbackgroundchecks.com/some/path")
            .build()
        var sent: Request? = null
        interceptor().intercept(chainFor(original) { sent = it })
        assertTrue(!sent!!.header("User-Agent").isNullOrBlank())
    }

    @Test
    fun similarButDifferentHost_isNotRewritten() {
        // "fake-cyberbackgroundchecks.com" should NOT match — host suffix
        // check is dot-anchored.
        val original = Request.Builder()
            .url("https://fake-cyberbackgroundchecks.com/")
            .build()
        var sent: Request? = null
        interceptor().intercept(chainFor(original) { sent = it })
        assertEquals(original, sent)
    }
}
