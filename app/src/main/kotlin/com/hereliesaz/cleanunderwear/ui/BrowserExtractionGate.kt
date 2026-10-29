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
 *
 * Three further things narrow what a page's own scripts can do with the bridge:
 *  - Its name is random per browser ([bridgeName]), not a fixed `HTMLOUT` that a hostile page
 *    could wrap ahead of time to read the token off the call.
 *  - The injected script ([tagScript]) fetches the bridge and holds the token and mission key in
 *    a closure of its own, so the mission scripts never touch a global a page could have wrapped.
 *  - A result is accepted only with the token AND the mission key it was minted for, and only
 *    once ([accept]): a late or replayed dump, from another mission or another page, is dropped.
 * A page that enumerates every global before the script runs could still find the bridge; the
 * WebView offers no way to hide it from page scripts short of document-start injection (AndroidX
 * WebKit), which this app does not depend on.
 */
internal class BrowserExtractionGate(val bridgeName: String = newBridgeName()) {
    private val random = java.security.SecureRandom()
    private var pending: String? = null
    private var pendingKey: String? = null
    private var finished = false

    fun reset() {
        pending = null
        pendingKey = null
        finished = false
    }

    fun invalidate() {
        pending = null
        pendingKey = null
    }

    /** Mints the token for one extraction of mission [missionKey] ("batch-index"); null once finished. */
    fun beginExtraction(missionKey: String): String? {
        if (finished) return null
        val bytes = ByteArray(16).also(random::nextBytes)
        pendingKey = missionKey
        return bytes.joinToString("") { "%02x".format(it) }.also { pending = it }
    }

    /**
     * Consumes at most one matching callback; duplicate or late results lose. Both the token and
     * the mission key must be the ones [beginExtraction] minted.
     */
    fun accept(token: String?, missionKey: String?): Boolean {
        if (finished || token == null || missionKey == null) return false
        if (pending != token || pendingKey != missionKey) return false
        pending = null
        pendingKey = null
        return true
    }

    fun finish() {
        pending = null
        pendingKey = null
        finished = true
    }

    /**
     * Wraps [script] so every dump it makes is bound to this extraction: the call becomes one to
     * a closure that hands the bridge the token and [missionKey] along with the page, so a late
     * dump from an earlier page is ignored and the mission script never names the bridge.
     */
    fun tagScript(script: String, token: String, missionKey: String): String {
        require(script.contains(DUMP_CALL)) { "Mission script has no HTML dump callback" }
        require(token.isNotEmpty() && token.all { it in '0'..'9' || it in 'a'..'f' }) { "Bad extraction token" }
        require(missionKey.isNotEmpty() && missionKey.all { it.isDigit() || it == '-' }) { "Bad mission key" }
        require(bridgeName.matches(BRIDGE_NAME)) { "Bad bridge name" }
        return "(function(){\n" +
            "var cuBridge = window['$bridgeName'];\n" +
            "if (!cuBridge) return;\n" +
            "var cuDump = function(html){ cuBridge.dumpTagged('$token', '$missionKey', html); };\n" +
            script.replace(DUMP_CALL, "cuDump(") +
            "\n})();"
    }

    companion object {
        private const val DUMP_CALL = "window.HTMLOUT.dump("
        private val BRIDGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** A fresh, unguessable name for the JavaScript bridge of one browser. */
        fun newBridgeName(): String {
            val bytes = ByteArray(8).also(java.security.SecureRandom()::nextBytes)
            return "cu" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
