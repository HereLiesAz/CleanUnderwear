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
 *   - **CyberBackgroundChecks** (4 search modes): used to resolve UNVERIFIED
 *     contacts. Per the user, every contact has at least one searchable data
 *     point, so the enricher tries phone → email → address → name in order.
 *
 *   - **Facebook friends harvest**: replaces the prior covert mbasic.facebook
 *     scrape. The user must be visibly logged in for this to work.
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
     * of [extractionScript], no 30s watchdog. The user browses freely until
     * they tap "back" to dismiss the mission. Use for chips that just need to
     * land the user on the right page in CleanUnderwear's WebView (so the
     * site sees the device's real UA + cookies) rather than the system browser.
     */
    open val autoExtract: Boolean = true

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
    }

    data class CbcByEmail(val email: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getEmailSearchUrl(email)
        override val label: String = "Identity lookup · email"
    }

    data class CbcByAddress(val address: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getAddressSearchUrl(address)
        override val label: String = "Identity lookup · address"
    }

    data class CbcByName(val displayName: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getNameSearchUrl(displayName)
        override val label: String = "Identity lookup · name"
    }

    object HarvestFacebookFriends : BrowserMission() {
        override val initialUrl: String = "https://mbasic.facebook.com/me/friends"
        override val label: String = "Harvest Facebook friends"
        override val extractionScript: String = FACEBOOK_HARVEST_SCRIPT
    }
}

/**
 * Pagination + dump script for mbasic.facebook.com/me/friends. Walks every
 * "See more friends" link, accumulating each batch into a single HTML blob,
 * then dumps via window.HTMLOUT.dump. This is the same logic the prior covert
 * FacebookHarvester used; the only change is which WebView runs it.
 */
private val FACEBOOK_HARVEST_SCRIPT = """
(function(){
  var collected = document.documentElement.outerHTML;
  function nextBatch() {
    var more = document.querySelector('a[href*="/friends"]:not([href*="profile"])');
    if (!more) {
      window.HTMLOUT.dump(collected);
      return;
    }
    var href = more.getAttribute('href');
    if (!href || href === window.location.pathname) {
      window.HTMLOUT.dump(collected);
      return;
    }
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
