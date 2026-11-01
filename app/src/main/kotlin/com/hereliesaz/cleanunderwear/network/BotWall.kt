package com.hereliesaz.cleanunderwear.network

import org.jsoup.Jsoup

/**
 * Recognises anti-bot interstitials (Cloudflare challenges and Turnstile, CAPTCHAs, WAF blocks).
 * Such a page can arrive with a 2xx status, so a fetch that returns one has failed: it says
 * nothing about whether the record exists, and must not be read as "no record found".
 */
object BotWall {
    /**
     * Markers in the page source that only a challenge or block page carries.
     *
     * Cloudflare puts a passive bot-detection script on ordinary pages behind it
     * (`/cdn-cgi/challenge-platform/.../scripts/jsd/main.js`), and Turnstile's own script may sit on
     * a plain search form. Neither makes a page a wall, so the bare `/cdn-cgi/challenge` and
     * `challenges.cloudflare.com` are not markers: a results page carrying them was taken for a
     * block every time. What marks the interactive challenge is its `orchestrate` script.
     */
    private val CHALLENGE_MARKERS = listOf(
        "cf-browser-verification",
        "cf_chl_",
        "<title>just a moment...</title>",
        "attention required! | cloudflare",
        "_incapsula_resource",
    )

    /** The interactive challenge's script path, as opposed to the passive `scripts/jsd` beacon. */
    private val CHALLENGE_SCRIPT = Regex("""challenge-platform/[^"'\s>]*orchestrate""")

    /** What a challenge page says to the visitor; read from the visible text, not the source. */
    private val CHALLENGE_PHRASES = listOf(
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

    /** Whether the page source carries a marker only a challenge or block page has. */
    private fun hasChallengeMarker(lower: String): Boolean =
        CHALLENGE_MARKERS.any { it in lower } || CHALLENGE_SCRIPT.containsMatchIn(lower)

    /** Strict check for automated fetches: a challenge page, or a CAPTCHA with nothing else on it. */
    fun detected(body: String): Boolean {
        if (body.isBlank()) return true
        val lower = body.lowercase()
        if (hasChallengeMarker(lower)) return true
        val text = visibleText(body)
        if (CHALLENGE_PHRASES.any { it in text.lowercase() }) return true
        if (WIDGET_MARKERS.none { it in lower }) return false
        if (NO_RESULTS.containsMatchIn(text)) return false
        return text.length < NEAR_EMPTY_TEXT
    }

    /**
     * Check for the visible browser, where a false positive pauses for the user to look: a
     * challenge page, a CAPTCHA widget on the page, or a CAPTCHA or access-denied message the
     * user can see. A script that merely loads a CAPTCHA library doesn't count: sites load one on
     * ordinary pages, a zero-results page among them, and the page then answered the search.
     */
    fun suspected(body: String): Boolean = reason(body) != null

    /**
     * Why [suspected] says so, in a few words for the activity log (never the page's own text);
     * null when the page is not a wall. A wrong call here pauses every lookup, so the log names
     * the reason.
     */
    fun reason(body: String): String? {
        if (body.isBlank()) return "empty page"
        val lower = body.lowercase()
        if (hasChallengeMarker(lower)) return "challenge page markers"
        val doc = Jsoup.parse(body)
        val text = doc.text()
        val textLower = text.lowercase()
        if (CHALLENGE_PHRASES.any { it in textLower }) return "challenge wording"
        if (NO_RESULTS.containsMatchIn(text)) return null
        if (doc.selectFirst(WIDGET_ELEMENTS) != null) return "a CAPTCHA widget on the page"
        if ("captcha" in textLower) return "the word CAPTCHA on the page"
        if ("access denied" in textLower) return "access denied wording"
        return null
    }

    /** Elements a CAPTCHA puts on the page (as opposed to a script that could show one). */
    private const val WIDGET_ELEMENTS =
        ".g-recaptcha:not([data-size=invisible]), .h-captcha:not([data-size=invisible]), .cf-turnstile, iframe[src*=recaptcha][src*=anchor], iframe[src*=hcaptcha], iframe[src*=turnstile]"

    private fun visibleText(body: String): String = Jsoup.parse(body).text()
}
