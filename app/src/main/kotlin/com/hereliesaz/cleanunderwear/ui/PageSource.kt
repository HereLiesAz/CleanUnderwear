package com.hereliesaz.cleanunderwear.ui

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

    /** Whether [html] has enough in it to be a page someone was shown, not a blank one. */
    fun looksDrawn(html: String?): Boolean = html != null && html.length >= MIN_READABLE_CHARS
}
