package com.hereliesaz.cleanunderwear.network

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * For any OkHttp request bound for cyberbackgroundchecks.com or
 * smartbackgroundchecks.com, rewrites the User-Agent to the system
 * WebView's default UA (i.e. the user's actual browser identity) and
 * attaches the cookies from [android.webkit.CookieManager] (i.e. the
 * user's actual session). Any Set-Cookie response headers are mirrored
 * back into CookieManager so the in-app WebView and any OkHttp scraper
 * stay in sync.
 *
 * Per the requirement: "for cyberbackgroundchecks.com and
 * smartbackgroundchecks.com, you MUST use the user's header." A
 * hardcoded desktop-Chrome UA is what these sites' bot-defense is
 * tuned to catch; the WebView UA matches the device the user is
 * actually holding, and the bridged cookie jar carries any captcha
 * solve / login the user has already performed in BrowserScreen.
 */
@Singleton
class WebViewIdentityInterceptor @Inject constructor(
    @ApplicationContext private val context: Context
) : Interceptor {

    // WebSettings.getDefaultUserAgent spins up a transient WebView the
    // first time it's called; cache the result so per-request overhead
    // stays at one map lookup. Falls back to a generic Chrome string when
    // (a) Android throws (no usable WebView), or (b) the JVM unit-test env
    // returns null per `unitTests.isReturnDefaultValues = true`.
    private val cachedUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_UA
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isBridgedHost(original.url.host)) return chain.proceed(original)

        val urlString = original.url.toString()
        val cookieHeader = try {
            CookieManager.getInstance().getCookie(urlString).orEmpty()
        } catch (e: Exception) {
            ""
        }

        val builder = original.newBuilder()
            .header("User-Agent", cachedUserAgent)
        if (cookieHeader.isNotBlank()) builder.header("Cookie", cookieHeader)
        val request = builder.build()

        val response = chain.proceed(request)

        // Mirror Set-Cookie back to CookieManager so a subsequent
        // BrowserScreen load picks up any session change OkHttp just made.
        try {
            val cm = CookieManager.getInstance()
            response.headers("Set-Cookie").forEach { cm.setCookie(urlString, it) }
            cm.flush()
        } catch (e: Exception) {
            // best-effort bridge; nothing here should ever break the response
        }

        return response
    }

    private fun isBridgedHost(host: String): Boolean {
        val h = host.lowercase()
        return BRIDGED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private companion object {
        // Hosts whose requests must ride on the user's WebView identity.
        // Keep tight — every host added here pays the per-request
        // CookieManager lookup and disables OkHttp's own cookie/UA setup.
        private val BRIDGED_HOSTS = setOf(
            "cyberbackgroundchecks.com",
            "smartbackgroundchecks.com",
        )

        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.82 Mobile Safari/537.36"
    }
}
