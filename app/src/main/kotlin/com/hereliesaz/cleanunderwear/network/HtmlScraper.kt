package com.hereliesaz.cleanunderwear.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Plain-HTTP scraper for sources that render server-side and respond to a single GET / POST.
 * Pages that only render with JavaScript can't be checked unattended; they are left to the
 * visible browser.
 */
class HtmlScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val verifier: IdentityVerifier
) {
    /**
     * Fetches a JSON inmate-locator endpoint (currently the Federal BOP API) and
     * verifies the target structurally via [IdentityVerifier.verifyBopInmateJson].
     * This must NOT run the body through Jsoup — the
     * compact JSON would be flattened into unsearchable text and the discrete
     * name fields lost.
     */
    suspend fun scrapeBopInmate(
        url: String,
        targetName: String,
        corroboration: IdentityVerifier.Corroboration = IdentityVerifier.Corroboration(),
        dismissedKeys: Set<String> = emptySet()
    ): IdentityVerifier.VerificationResult {
        val first = fetchBop(url, targetName, corroboration, dismissedKeys)
        if (first.isMatch || !first.capped) return first
        // The locator stops at 100 rows ordered by inmate number, so a common name's newer
        // inmates fall past the cut. Narrow by what enrichment knows and ask again.
        val narrowing = corroboration.middleName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&nameMiddle=" + java.net.URLEncoder.encode(it, "UTF-8") }
            ?: verifier.approxAge(corroboration.dob)?.let { "&age=$it" }
            // Nothing to narrow by also means nothing to corroborate a hit with, and an
            // uncorroborated BOP hit is never surfaced, so the cut loses nothing.
            ?: return first
        return fetchBop(url + narrowing, targetName, corroboration, dismissedKeys)
    }

    private suspend fun fetchBop(
        url: String,
        targetName: String,
        corroboration: IdentityVerifier.Corroboration,
        dismissedKeys: Set<String>
    ): IdentityVerifier.VerificationResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(sanitize(url))
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .build()
                val body = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext IdentityVerifier.VerificationResult.fetchFailed()
                    response.body.string()
                }
                verifier.verifyBopInmateJson(body, targetName, corroboration, dismissedKeys)
            } catch (e: Exception) {
                Log.e("HtmlScraper", "Failed to fetch BOP JSON", e)
                IdentityVerifier.VerificationResult.fetchFailed()
            }
        }

    /**
     * Runs one catalog search as configured on [source] and returns the raw
     * response body, or null on failure.
     *
     * With [Source.tokenPage], first GETs that page and carries its hidden
     * form inputs (ASP.NET __VIEWSTATE, CSRF tokens) into the search;
     * [formFields] take precedence over the harvested inputs. Cookies persist
     * across the token page, the search and any redirect.
     * A [Source.bodyTemplate] is sent as the raw body instead of form fields.
     */
    suspend fun fetchSource(
        source: Source,
        url: String,
        formFields: Map<String, String>,
        body: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            // A fresh cookie jar per search: several state locators set a session
            // cookie on the POST and require it on the redirected results GET.
            val client = okHttpClient.newBuilder().cookieJar(SessionCookieJar()).apply {
                if (source.http1) protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            }.build()
            val fields = LinkedHashMap<String, String>()
            source.tokenPage?.let { page ->
                var tokenResponse = client.newCall(
                    Request.Builder().url(sanitize(page)).header("User-Agent", DESKTOP_UA).build()
                ).execute()
                // Sites behind a disclaimer: accept it, and read the token from
                // the page the acceptance returns.
                if (source.tokenForm.isNotEmpty()) {
                    tokenResponse.close()
                    tokenResponse = client.newCall(
                        Request.Builder().url(sanitize(source.tokenPostUrl ?: page))
                            .header("User-Agent", DESKTOP_UA)
                            .header("Referer", sanitize(page))
                            .post(FormBody.Builder().apply { source.tokenForm.forEach { (k, v) -> add(k, v) } }.build())
                            .build()
                    ).execute()
                }
                // Without its token the search would only return the form again.
                val tokenHtml = tokenResponse.use { if (it.isSuccessful) it.body.string() else null }
                    ?: return@withContext null
                val doc = Jsoup.parse(tokenHtml)
                doc.select("input[type=hidden][name]").forEach { fields[it.attr("name")] = it.attr("value") }
            }
            fields.putAll(formFields)

            val builder = Request.Builder().url(sanitize(url)).header("User-Agent", DESKTOP_UA)
            source.headers.forEach { (k, v) -> builder.header(k, v) }
            when {
                body != null -> builder.post(
                    body.toRequestBody((source.contentType ?: "application/json").toMediaTypeOrNull())
                )
                source.method.uppercase() == "POST" ->
                    builder.post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
            }
            val searchBody = client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            } ?: return@withContext null
            val resultsUrl = source.resultsUrl ?: return@withContext searchBody
            client.newCall(
                Request.Builder().url(sanitize(resultsUrl)).header("User-Agent", DESKTOP_UA).build()
            ).execute().use { results -> if (results.isSuccessful) results.body.string() else null }
        } catch (e: Exception) {
            Log.e("HtmlScraper", "Failed source fetch ${source.id}", e)
            null
        }
    }

    /** GETs [url] and returns the raw response body (for XML/JSON feeds), or null on failure. */
    suspend fun fetchBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(sanitize(url)).header("User-Agent", DESKTOP_UA).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        } catch (e: Exception) {
            Log.e("HtmlScraper", "Failed to fetch body", e)
            null
        }
    }

    /** Always HTTPS: a scheme-less URL gets one, and plain http is upgraded. */
    private fun sanitize(url: String): String = when {
        url.startsWith("https://") -> url
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> "https://$url"
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    /** In-memory cookies for one search; discarded with it. */
    private class SessionCookieJar : okhttp3.CookieJar {
        private val cookies = mutableListOf<okhttp3.Cookie>()

        @Synchronized
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain } }
            this.cookies.addAll(cookies)
        }

        @Synchronized
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = cookies.filter { it.matches(url) }
    }
}
