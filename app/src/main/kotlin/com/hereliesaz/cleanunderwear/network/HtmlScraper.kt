package com.hereliesaz.cleanunderwear.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Plain-HTTP scraper for sources that render server-side and respond to a single GET / POST.
 * For JS-driven roster pages, use [WebViewScraper] instead.
 */
class HtmlScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val verifier: IdentityVerifier
) {
    suspend fun scrapeMugshots(url: String, targetName: String): IdentityVerifier.VerificationResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(sanitize(url))
                    .header("User-Agent", DESKTOP_UA)
                    .build()
                fetch(request, targetName)
            } catch (e: Exception) {
                Log.e("HtmlScraper", "Failed to fetch $url", e)
                IdentityVerifier.VerificationResult.fetchFailed()
            }
        }

    suspend fun scrapePost(
        url: String,
        formFields: Map<String, String>,
        targetName: String
    ): IdentityVerifier.VerificationResult = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder().apply {
                formFields.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url(sanitize(url))
                .header("User-Agent", DESKTOP_UA)
                .post(body)
                .build()
            fetch(request, targetName)
        } catch (e: Exception) {
            Log.e("HtmlScraper", "Failed to POST $url", e)
            IdentityVerifier.VerificationResult.fetchFailed()
        }
    }

    /**
     * Fetches a JSON inmate-locator endpoint (currently the Federal BOP API) and
     * verifies the target structurally via [IdentityVerifier.verifyBopInmateJson].
     * Unlike [scrapeMugshots] this must NOT run the body through Jsoup — the
     * compact JSON would be flattened into unsearchable text and the discrete
     * name fields lost.
     */
    suspend fun scrapeBopInmate(
        url: String,
        targetName: String,
        corroboration: IdentityVerifier.Corroboration = IdentityVerifier.Corroboration(),
        dismissedKeys: Set<String> = emptySet()
    ): IdentityVerifier.VerificationResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(sanitize(url))
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext IdentityVerifier.VerificationResult.fetchFailed()
                verifier.verifyBopInmateJson(response.body.string(), targetName, corroboration, dismissedKeys)
            } catch (e: Exception) {
                Log.e("HtmlScraper", "Failed to fetch BOP JSON $url", e)
                IdentityVerifier.VerificationResult.fetchFailed()
            }
        }

    /**
     * GETs (or POSTs, with [formFields]) a server-rendered page and returns its
     * visible text, or null on any failure. Used by the candidate pipeline,
     * which extracts and verifies records itself rather than taking the first
     * name hit.
     */
    suspend fun fetchText(url: String, formFields: Map<String, String>? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder()
                    .url(sanitize(url))
                    .header("User-Agent", DESKTOP_UA)
                if (formFields != null) {
                    builder.post(FormBody.Builder().apply { formFields.forEach { (k, v) -> add(k, v) } }.build())
                }
                val response = okHttpClient.newCall(builder.build()).execute()
                if (!response.isSuccessful) return@withContext null
                Jsoup.parse(response.body.string()).text()
            } catch (e: Exception) {
                Log.e("HtmlScraper", "Failed to fetch text $url", e)
                null
            }
        }

    private fun fetch(request: Request, targetName: String): IdentityVerifier.VerificationResult {
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return IdentityVerifier.VerificationResult.fetchFailed()
        val html = response.body.string()
        val document = Jsoup.parse(html)
        return verifier.verifyIdentity(document.text(), targetName)
    }

    private fun sanitize(url: String): String =
        if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}
