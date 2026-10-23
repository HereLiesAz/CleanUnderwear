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

    /** Strict check for automated fetches: a challenge page, or a CAPTCHA with nothing else on it. */
    fun detected(body: String): Boolean {
        if (body.isBlank()) return true
        val lower = body.lowercase()
        if (CHALLENGE_MARKERS.any { it in lower }) return true
        if (WIDGET_MARKERS.none { it in lower }) return false
        return Jsoup.parse(body).text().length < NEAR_EMPTY_TEXT
    }

    /**
     * Loose check for the visible browser, where a false positive only pauses for the user to
     * look: any CAPTCHA mention or access-denied page counts.
     */
    fun suspected(body: String): Boolean {
        if (detected(body)) return true
        val lower = body.lowercase()
        return "captcha" in lower || "access denied" in lower
    }
}
