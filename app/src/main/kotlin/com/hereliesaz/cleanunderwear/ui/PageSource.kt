package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.network.BotWall
import org.jsoup.Jsoup
import java.net.URI

/**
 * Reading a page straight from the browser, without the JavaScript bridge: the page's HTML comes
 * back as the result of [READ_SCRIPT]. A lookup page needs nothing more than its own source, so
 * it can be read this way even when the bridge, or the page's own "finished loading" event,
 * doesn't come (ads and trackers keep a page "loading" for a long time after it has drawn).
 */
internal object PageSource {
    /** The page as it stands now, tags and all. */
    const val READ_SCRIPT = "document.documentElement.outerHTML"

    /** The page has stopped loading its document and has drawn some text: worth reading. */
    const val READY_SCRIPT =
        "(document.readyState !== 'loading' && !!document.body && document.body.innerText.length > 200)"

    /**
     * What a page looks like from inside, for the log: `characters|state|title|browser`. The title
     * has its bars taken out, so the four parts split cleanly.
     */
    const val DESCRIBE_SCRIPT =
        "(function(){var b=document.body;return (b?b.innerText.length:-1)+'|'+document.readyState+'|'+" +
            "(document.title||'').replace(/\\|/g,'/').slice(0,60)+'|'+navigator.userAgent;})()"

    /** A page source shorter than this has drawn nothing worth reading yet. */
    const val MIN_READABLE_CHARS = 2_000

    /**
     * What `evaluateJavascript` hands back for a string result: the JSON text of it, or the word
     * `null` when there was none. Null when there is nothing to read.
     */
    fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            com.google.gson.Gson().fromJson(raw, String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** What [DESCRIBE_SCRIPT] says of a page. */
    data class Described(val characters: Int, val state: String, val title: String, val browser: String) {
        /** Nothing worth reading is drawn: no body, or hardly any text. */
        val blank: Boolean get() = characters < BLANK_BELOW
    }

    /** Fewer characters of visible text than this is a page that has drawn nothing. */
    const val BLANK_BELOW = 50

    /** Reads what [DESCRIBE_SCRIPT] handed back; null when it isn't in that shape. */
    fun describe(raw: String?): Described? {
        val parts = decode(raw)?.split('|', limit = 4) ?: return null
        if (parts.size < 4) return null
        return Described(parts[0].toIntOrNull() ?: return null, parts[1], parts[2], parts[3])
    }

    /** Whether [html] has enough in it to be a page someone was shown, not a blank one. */
    fun looksDrawn(html: String?): Boolean = html != null && html.length >= MIN_READABLE_CHARS

    /** Compare the full lookup URL, including query: a previous contact is not this page. */
    fun sameDocumentUrl(expected: String, actual: String?): Boolean {
        if (actual.isNullOrBlank()) return false
        return try {
            val a = URI(expected)
            val b = URI(actual)
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                a.path.trimEnd('/') == b.path.trimEnd('/') &&
                a.rawQuery == b.rawQuery
        } catch (e: IllegalArgumentException) { false }
    }

    /** Ignore a loading shell until a person card, detail, no-results page or wall is present. */
    fun looksCbcContent(html: String?, detail: Boolean): Boolean {
        if (html.isNullOrBlank()) return false
        val doc = Jsoup.parse(html)
        val selectors = if (detail) {
            ".name-given, h1.full-name, .person-name, a.phone, a.address-current, [itemprop=telephone]"
        } else {
            ".person-card .name, .person-card h2, .person-card .name-given, " +
                ".result-card .name, .result-card h2, .result-card .name-given, " +
                ".search-result .name, .search-result h2, .search-result .name-given, " +
                ".card-person .name, .card-person h2, .card-person .name-given, " +
                "[data-result-card] .name, [data-result-card] h2, .card-hover .name-given"
        }
        if (doc.selectFirst(selectors) != null || BotWall.saysNoResults(html) || BotWall.suspected(html)) return true
        // CBC also publishes records as JSON-LD and schema.org microdata. Use the
        // actual parser rather than guessing which CSS classes must exist.
        return com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher()
            .parsePeople(html).isNotEmpty()
    }
}
