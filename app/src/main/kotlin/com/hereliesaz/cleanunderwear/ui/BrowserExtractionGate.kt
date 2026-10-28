package com.hereliesaz.cleanunderwear.ui

/**
 * Tracks which in-flight WebView extraction may deliver a result.
 *
 * The visible browser is reused throughout a Resolve run. A previous page can
 * call its JavaScript interface after the next contact has already loaded, so
 * callbacks must be bound to an individual extraction, not just the WebView.
 *
 * Tokens are random, not a counter: the JavaScript bridge is visible to every
 * script and frame on the page, and a guessable token would let any of them
 * hand back a forged page as the result.
 */
internal class BrowserExtractionGate {
    private val random = java.security.SecureRandom()
    private var pending: String? = null
    private var finished = false

    fun reset() {
        pending = null
        finished = false
    }

    fun invalidate() {
        pending = null
    }

    fun beginExtraction(): String? {
        if (finished) return null
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }.also { pending = it }
    }

    /** Consumes at most one matching callback; duplicate or late results lose. */
    fun accept(token: String?): Boolean {
        if (finished || token == null || pending != token) return false
        pending = null
        return true
    }

    fun finish() {
        pending = null
        finished = true
    }

    /** Bind every dump in the mission script to this extraction, so a late dump from an earlier page is ignored. */
    fun tagScript(script: String, token: String, missionKey: String): String {
        require(script.contains(DUMP_CALL)) { "Mission script has no HTML dump callback" }
        require(token.all { it in '0'..'9' || it in 'a'..'f' } && missionKey.all { it.isDigit() || it == '-' })
        return script
            .replace(DUMP_CALL, "window.HTMLOUT.dumpTagged('$token',")
    }

    private companion object {
        const val DUMP_CALL = "window.HTMLOUT.dump("
    }
}
