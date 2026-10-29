package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.util.CyberBackgroundChecks

/**
 * What a user-visible [com.hereliesaz.cleanunderwear.ui.BrowserScreen] session
 * should accomplish. The screen loads [initialUrl] in a real WebView (so the
 * user's session cookies, captcha solves, and headers all come from a real
 * browser), then on user request injects [extractionScript] and returns the
 * captured HTML back to the caller.
 *
 * Two flows exist today:
 *
 *   - **CyberBackgroundChecks** (4 search modes): used to fill in contacts'
 *     details. Per the user, every contact has at least one searchable data
 *     point, so the enricher tries phone → email → address → name in order.
 *     Each search's results list is read whole, every person on it, and the
 *     user chooses which (if any) is the contact after the run.
 *
 *   - **Imports** (Facebook friends, WhatsApp chats, Instagram followers, Google Contacts):
 *     replace the prior covert mbasic.facebook scrape. The user must be signed in for these to
 *     work, so they sign in in the browser itself ([requiresSignIn]): the screen leaves the page
 *     to them, un-scrimmed, until it is the page to read.
 *
 * Missions are POJOs; [com.hereliesaz.cleanunderwear.ui.MainViewModel] owns
 * the current mission state and [com.hereliesaz.cleanunderwear.ui.BrowserScreen]
 * consumes it.
 */
sealed class BrowserMission {
    /** URL the BrowserScreen loads first. */
    abstract val initialUrl: String

    /** Short label shown in the screen's app bar. */
    abstract val label: String

    /**
     * JavaScript to inject when the user taps "Run automation". Receives the
     * page DOM and posts the HTML (or parsed JSON) back via
     * `window.HTMLOUT.dump(...)` — see BrowserScreen's JavascriptInterface.
     *
     * Default returns the entire document HTML; missions that want a tighter
     * payload can override.
     */
    open val extractionScript: String =
        "(function(){window.HTMLOUT.dump(document.documentElement.outerHTML);})();"

    /**
     * When false, BrowserScreen loads the URL and steps aside — no auto-run
     * of [extractionScript], no 30s watchdog. The user browses freely (system Back steps back
     * through the pages they visit) until they close the page. Use for chips that just need to
     * land the user on the right page in CleanUnderwear's WebView (so the
     * site sees the device's real UA + cookies) rather than the system browser.
     */
    open val autoExtract: Boolean = true

    /**
     * When true, BrowserScreen re-runs [extractionScript] after each in-page
     * navigation (rather than once): a script that walks to the page it reads
     * (Instagram, Google), or a search whose results arrive only after a
     * CAPTCHA or a redirect (the CBC lookups).
     */
    open val rerunAfterNavigation: Boolean = false

    /**
     * How long the screen waits for [extractionScript] to hand back a page before unmasking and
     * offering retry / skip / abort. Imports that wait for the user to sign in (or scan a QR
     * code) get minutes, not seconds.
     */
    open val watchdogMs: Long = 30_000L

    /**
     * True for the four imports (Facebook, WhatsApp, Instagram, Google), which read a page only a
     * signed-in user gets. For these BrowserScreen looks at the page first ([PageProbe]): while
     * [isSignInPage] says it is a sign-in / link-your-phone page, it lifts the scrim, shows
     * [signInPrompt] and leaves the page to the user, running [extractionScript] only once the
     * page is the one to read. [watchdogMs] counts from then, so waiting for the user never
     * trips it. A dump that turns out to be a sign-in page anyway ([looksSignedOut]) hands the
     * page back the same way instead of being imported as "nothing found".
     */
    open val requiresSignIn: Boolean = false

    /** What to tell the user while the page waits for them to sign in; only read if [requiresSignIn]. */
    open val signInPrompt: String = ""

    /** Whether [page] is a sign-in (or link-your-phone) page rather than the page to read. */
    open fun isSignInPage(page: PageProbe): Boolean = false

    /** Whether [html], dumped by [extractionScript], is a sign-in page rather than the data. */
    open fun looksSignedOut(html: String): Boolean = requiresSignIn && SignInPages.hasLoginForm(html)

    /**
     * Browse-only mission: open [initialUrl] in the in-app WebView, no
     * automation, no extraction. Used for the doxray identity-correlation
     * chips that must run through the user's WebView session (CBC and
     * SmartBGC bot-block raw HTTP / non-WebView UAs).
     */
    data class OpenInBrowser(
        override val initialUrl: String,
        override val label: String
    ) : BrowserMission() {
        override val autoExtract: Boolean = false
    }

    data class CbcByPhone(val phone: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getPhoneSearchUrl(phone)
        override val label: String = "Identity lookup · phone"
        override val rerunAfterNavigation: Boolean = true
    }

    data class CbcByEmail(val email: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getEmailSearchUrl(email)
        override val label: String = "Identity lookup · email"
        override val rerunAfterNavigation: Boolean = true
    }

    data class CbcByAddress(val address: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getAddressSearchUrl(address)
        override val label: String = "Identity lookup · address"
        override val rerunAfterNavigation: Boolean = true
    }

    data class CbcByName(val displayName: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getNameSearchUrl(displayName)
        override val label: String = "Identity lookup · name"
        override val rerunAfterNavigation: Boolean = true
    }

    /**
     * WhatsApp Web: the user links WhatsApp here (WhatsApp Web shows a QR code, and under it
     * "Link with phone number", which is the way to link the phone the app is running on), then
     * the chat list is read.
     */
    object HarvestWhatsApp : BrowserMission() {
        override val initialUrl: String = com.hereliesaz.cleanunderwear.data.WhatsAppHarvester.URL
        override val label: String = "Import WhatsApp chats"
        override val extractionScript: String = com.hereliesaz.cleanunderwear.data.WhatsAppHarvester.SCRIPT
        // The script's own longest run (waiting for the list, then scrolling it) plus a margin, so
        // the script always hands back what it has before the watchdog calls it stuck.
        override val watchdogMs: Long = com.hereliesaz.cleanunderwear.data.WhatsAppHarvester.MAX_RUN_MS + 90_000L
        override val requiresSignIn: Boolean = true
        override val signInPrompt: String =
            "Link WhatsApp here. Under the code, tap \"Link with phone number\", then enter the code it shows in " +
                "WhatsApp on this phone (Settings, Linked devices, Link a device). Or scan the code with a second " +
                "device. The import carries on by itself once your chats appear."
        // Linked means the chat list is on the page; until then it is the QR / link page (or an
        // "unsupported browser" page on another host), both the user's to deal with.
        override fun isSignInPage(page: PageProbe): Boolean = !page.hasChatList
    }

    /** Instagram: the user signs in here if needed; the script walks to their followers list. */
    object HarvestInstagram : BrowserMission() {
        override val initialUrl: String = com.hereliesaz.cleanunderwear.data.InstagramHarvester.URL
        override val label: String = "Import Instagram followers"
        override val extractionScript: String = com.hereliesaz.cleanunderwear.data.InstagramHarvester.SCRIPT
        override val rerunAfterNavigation: Boolean = true
        override val watchdogMs: Long = 300_000L
        override val requiresSignIn: Boolean = true
        override val signInPrompt: String =
            "Sign in to Instagram here. The import carries on by itself once you're in."
        override fun isSignInPage(page: PageProbe): Boolean =
            page.hasPasswordField || SignInPages.isInstagramSignInPath(page.path)
    }

    /** Google Contacts: the user signs in to Google here if needed, then the list is read. */
    object HarvestGoogleContacts : BrowserMission() {
        override val initialUrl: String = com.hereliesaz.cleanunderwear.data.GoogleContactsHarvester.URL
        override val label: String = "Import Google Contacts"
        override val extractionScript: String = com.hereliesaz.cleanunderwear.data.GoogleContactsHarvester.SCRIPT
        override val rerunAfterNavigation: Boolean = true
        override val watchdogMs: Long = 300_000L
        override val requiresSignIn: Boolean = true
        override val signInPrompt: String =
            "Sign in to Google here. The import carries on by itself once your contacts appear."
        // Anywhere but the contacts page is Google asking something of the user: the sign-in
        // itself, a "verify it's you" step, a consent screen.
        override fun isSignInPage(page: PageProbe): Boolean =
            page.hasPasswordField || (page.host.isNotEmpty() && page.host != "contacts.google.com")
    }

    object HarvestFacebookFriends : BrowserMission() {
        override val initialUrl: String = "https://mbasic.facebook.com/me/friends"
        override val label: String = "Import Facebook friends"
        override val extractionScript: String = FACEBOOK_HARVEST_SCRIPT
        // The script goes to the friends list itself if sign-in leaves the user somewhere else.
        override val rerunAfterNavigation: Boolean = true
        override val watchdogMs: Long = 300_000L
        override val requiresSignIn: Boolean = true
        override val signInPrompt: String =
            "Sign in to Facebook here. The import carries on by itself once you're in."
        override fun isSignInPage(page: PageProbe): Boolean =
            page.hasPasswordField || SignInPages.isFacebookSignInPath(page.path)
    }
}

/**
 * Pagination + dump script for mbasic.facebook.com/me/friends. Walks every
 * "See more friends" link, accumulating each batch into a single HTML blob,
 * then dumps via window.HTMLOUT.dump. This is the same logic the prior covert
 * FacebookHarvester used; the only change is which WebView runs it.
 *
 * It only ever reads the friends list: on a sign-in page it does nothing (BrowserScreen has
 * normally handed that page to the user already, and runs this again after the next page load),
 * and anywhere else it goes to the friends list first, a few times at most.
 */
private val FACEBOOK_HARVEST_SCRIPT = """
(function(){
  if (document.querySelector('input[name=pass], #login_form')) return;
  if (window.location.href.indexOf('friends') < 0) {
    var hops = 0;
    try { hops = parseInt(window.sessionStorage.getItem('cuFriendsHops') || '0', 10); } catch (e) {}
    if (hops < 3) {
      try { window.sessionStorage.setItem('cuFriendsHops', String(hops + 1)); } catch (e) {}
      window.location.href = '/me/friends';
    }
    return;
  }
  var collected = document.documentElement.outerHTML;
  // Every page fetched once, and a hard page cap, so a "more" link that keeps pointing at a
  // page already seen ends the walk instead of looping.
  var visited = {};
  visited[window.location.pathname + window.location.search] = true;
  var pages = 0;
  function nextBatch() {
    var more = document.querySelector('a[href*="/friends"]:not([href*="profile"])');
    var href = more ? more.getAttribute('href') : null;
    if (!href || visited[href] || ++pages > 50) {
      window.HTMLOUT.dump(collected);
      return;
    }
    visited[href] = true;
    fetch(href, { credentials: 'include' })
      .then(function(r){ return r.text(); })
      .then(function(html){
        collected += '\n<!-- next-page -->\n' + html;
        var doc = new DOMParser().parseFromString(html, 'text/html');
        document.body.innerHTML = doc.body.innerHTML;
        setTimeout(nextBatch, 800);
      })
      .catch(function(){ window.HTMLOUT.dump(collected); });
  }
  setTimeout(nextBatch, 500);
})();
""".trimIndent()

/**
 * What the browser screen can tell about the page in front of the user without reading its
 * content: enough for a mission to say whether it is a sign-in / link-your-phone page (so the
 * screen hands the page to the user instead of scrimming it and running the script on it).
 * [SCRIPT] gathers it; [parse] reads what that returns.
 */
data class PageProbe(
    val hasPasswordField: Boolean,
    val hasQrCode: Boolean,
    val hasChatList: Boolean,
    val url: String,
) {
    /** Lower-case host of [url], or "" when it has none (about:blank, a parse miss). */
    val host: String get() = hostAndPath(url).first

    /** Path of [url] ("/" when it has none). */
    val path: String get() = hostAndPath(url).second

    companion object {
        /**
         * Evaluated in the page; returns `password|canvas|grid|url` with 1/0 flags. The password
         * selectors match [SignInPages.hasLoginForm]'s, so the page and the HTML it dumps agree.
         */
        const val SCRIPT =
            "(function(){var d=document;" +
                "function q(s){return d.querySelector(s)?1:0;}" +
                "return q('input[type=password],input[name=pass],#login_form')+'|'+q('canvas')+'|'+q('[role=grid]')+'|'+location.href;" +
                "})()"

        /**
         * Reads the value `evaluateJavascript` hands back for [SCRIPT] (a JSON string literal, or
         * "null"); null when it isn't one.
         */
        fun parse(raw: String?): PageProbe? {
            val body = raw?.trim()?.removeSurrounding("\"")?.replace("\\/", "/") ?: return null
            val parts = body.split('|', limit = 4)
            if (parts.size < 4) return null
            return PageProbe(
                hasPasswordField = parts[0] == "1",
                hasQrCode = parts[1] == "1",
                hasChatList = parts[2] == "1",
                url = parts[3]
            )
        }

        private fun hostAndPath(url: String): Pair<String, String> {
            val rest = url.substringAfter("://", "")
            if (rest.isEmpty()) return "" to ""
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            val host = authority.substringAfter('@').substringBefore(':').lowercase()
            val after = rest.substring(authority.length)
            val path = if (after.startsWith("/")) after.substringBefore('?').substringBefore('#') else "/"
            return host to path
        }
    }
}

/** Recognising a sign-in page from the HTML a script dumped; pure, so it can be tested. */
object SignInPages {
    /**
     * Whether [html] is a page with a password / login form on it. The HTML is `outerHTML`, which
     * always writes attributes double-quoted, so a substring check is enough and cheap on the
     * megabyte pages a followers or contacts list produces.
     */
    fun hasLoginForm(html: String): Boolean =
        html.contains("type=\"password\"", ignoreCase = true) ||
            html.contains("name=\"pass\"", ignoreCase = true) ||
            html.contains("id=\"login_form\"", ignoreCase = true)

    /** Paths on facebook.com (any subdomain) that are sign-in, checkpoint or recovery steps. */
    fun isFacebookSignInPath(path: String): Boolean =
        FACEBOOK_SIGN_IN_PATHS.any { path.startsWith(it) }

    /** Paths on instagram.com that are sign-in, sign-up or challenge steps. */
    fun isInstagramSignInPath(path: String): Boolean =
        INSTAGRAM_SIGN_IN_PATHS.any { path.startsWith(it) }

    private val FACEBOOK_SIGN_IN_PATHS = listOf("/login", "/checkpoint", "/recover", "/two_step_verification", "/two_factor")
    private val INSTAGRAM_SIGN_IN_PATHS = listOf("/accounts/login", "/accounts/emailsignup", "/challenge", "/accounts/suspended")
}
