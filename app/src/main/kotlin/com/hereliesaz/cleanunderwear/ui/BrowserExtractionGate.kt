package com.hereliesaz.cleanunderwear.ui

/**
 * Tracks which in-flight WebView extraction may deliver a result.
 *
 * The visible browser is reused throughout a Resolve run. A previous page can
 * call its JavaScript interface after the next contact has already loaded, so
 * callbacks must be bound to an individual extraction, not just the WebView.
 * Tokens never repeat during one browser lifetime, even when missions reset.
 */
internal class BrowserExtractionGate {
    private var sequence = 0L
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
        sequence += 1
        return sequence.toString().also { pending = it }
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

    /**
     * Bind every dump in the mission script to this extraction. CBC drilling
     * uses a separate sessionStorage key per contact/mode: an interrupted
     * lookup must never suppress the first drill of a later lookup.
     */
    fun tagScript(script: String, token: String, missionKey: String): String {
        require(script.contains(DUMP_CALL)) { "Mission script has no HTML dump callback" }
        require(token.all { it.isDigit() } && missionKey.all { it.isDigit() || it == '-' })
        return script
            .replace(DUMP_CALL, "window.HTMLOUT.dumpTagged('$token',")
            .replace("'cleanunderwear-cbc-drill'", "'cleanunderwear-cbc-drill-$missionKey'")
    }

    private companion object {
        const val DUMP_CALL = "window.HTMLOUT.dump("
    }
}
