package com.hereliesaz.cleanunderwear.network

import org.jsoup.Jsoup

/**
 * Recognises anti-bot interstitials (Cloudflare challenges and Turnstile, CAPTCHAs, WAF blocks).
 * Such a page can arrive with a 2xx status, so a fetch that returns one has failed: it says
 * nothing about whether the record exists, and must not be read as "no record found".
 */
object BotWall {
    /** Markers that only a challenge or block page carries. */
    private val CHALLENGE_MARKERS = listOf(
        "/cdn-cgi/challenge",
        "challenges.cloudflare.com",
        "cf-browser-verification",
        "cf_chl_",
        "<title>just a moment...</title>",
        "attention required! | cloudflare",
        "_incapsula_resource",
        "are you a robot",
        "verify you are human",
        "unusual traffic from your computer",
    )

    /** Widgets that also sit on ordinary search forms; a wall only when the page is otherwise empty. */
    private val WIDGET_MARKERS = listOf("cf-turnstile", "g-recaptcha", "h-captcha", "hcaptcha", "captcha")
    private const val NEAR_EMPTY_TEXT = 1500

    /**
     * Phrases a search page uses to say it found nobody. A page that says so answered the search:
     * it is a clean "nothing found", whatever CAPTCHA widget or script its search form also carries.
     */
    private val NO_RESULTS = Regex(
        "\\b(no (results|records|matches|matching (records|results)|people|persons|inmates|offenders|cases) (were )?found" +
            "|no results( for| match)|0 (results|records|matches)( found)?|returned no (results|records)" +
            "|did not (return|match|find) any|we (could not|couldn't|can't|cannot|did not|didn't) find|nothing (was )?found" +
            "|there (are|were) no (results|records|matches|articles|obituaries)" +
            "|no (articles|obituaries) (were )?found|did not find any (obituaries|results|records))\\b",
        RegexOption.IGNORE_CASE
    )

    /** Whether the page's visible text says the search found nobody. */
    fun saysNoResults(body: String): Boolean = NO_RESULTS.containsMatchIn(visibleText(body))

    /** Strict check for automated fetches: a challenge page, or a CAPTCHA with nothing else on it. */
    fun detected(body: String): Boolean {
        if (body.isBlank()) return true
        val lower = body.lowercase()
        if (CHALLENGE_MARKERS.any { it in lower }) return true
        if (WIDGET_MARKERS.none { it in lower }) return false
        val text = visibleText(body)
        if (NO_RESULTS.containsMatchIn(text)) return false
        return text.length < NEAR_EMPTY_TEXT
    }

    /**
     * Check for the visible browser, where a false positive pauses for the user to look: a
     * challenge page, a CAPTCHA widget on the page, or a CAPTCHA or access-denied message the
     * user can see. A script that merely loads a CAPTCHA library doesn't count: sites load one on
     * ordinary pages, a zero-results page among them, and the page then answered the search.
     */
    fun suspected(body: String): Boolean {
        if (body.isBlank()) return true
        if (CHALLENGE_MARKERS.any { it in body.lowercase() }) return true
        val doc = Jsoup.parse(body)
        val text = doc.text()
        if (NO_RESULTS.containsMatchIn(text)) return false
        if (doc.selectFirst(WIDGET_ELEMENTS) != null) return true
        val lower = text.lowercase()
        return "captcha" in lower || "access denied" in lower
    }

    /** Elements a CAPTCHA puts on the page (as opposed to a script that could show one). */
    private const val WIDGET_ELEMENTS =
        ".g-recaptcha:not([data-size=invisible]), .h-captcha:not([data-size=invisible]), .cf-turnstile, iframe[src*=recaptcha][src*=anchor], iframe[src*=hcaptcha], iframe[src*=turnstile]"

    private fun visibleText(body: String): String = Jsoup.parse(body).text()
}
