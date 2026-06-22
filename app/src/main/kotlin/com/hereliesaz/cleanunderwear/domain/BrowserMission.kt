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
     * When true, BrowserScreen re-runs [extractionScript] after each in-page
     * navigation (rather than once), letting a script drill from a results
     * list into the first result's detail page before dumping. See the CBC
     * missions and DRILL_TO_FIRST_RESULT_SCRIPT.
     */
    open val drillToFirstResult: Boolean = false

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
        override val drillToFirstResult: Boolean = true
        override val extractionScript: String = DRILL_TO_FIRST_RESULT_SCRIPT
    }

    data class CbcByEmail(val email: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getEmailSearchUrl(email)
        override val label: String = "Identity lookup · email"
        override val drillToFirstResult: Boolean = true
        override val extractionScript: String = DRILL_TO_FIRST_RESULT_SCRIPT
    }

    data class CbcByAddress(val address: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getAddressSearchUrl(address)
        override val label: String = "Identity lookup · address"
        override val drillToFirstResult: Boolean = true
        override val extractionScript: String = DRILL_TO_FIRST_RESULT_SCRIPT
    }

    data class CbcByName(val displayName: String) : BrowserMission() {
        override val initialUrl: String = CyberBackgroundChecks.getNameSearchUrl(displayName)
        override val label: String = "Identity lookup · name"
        override val drillToFirstResult: Boolean = true
        override val extractionScript: String = DRILL_TO_FIRST_RESULT_SCRIPT
    }

    object HarvestFacebookFriends : BrowserMission() {
        override val initialUrl: String = "https://mbasic.facebook.com/me/friends"
        override val label: String = "Harvest Facebook friends"
        override val extractionScript: String = FACEBOOK_HARVEST_SCRIPT
    }
}

/**
 * CyberBackgroundChecks drill-in script. A CBC search lands on a results LIST;
 * the rich identity data (middle name, DOB) lives on the first result's DETAIL
 * page. This script is list-vs-detail aware and BrowserScreen re-runs it after
 * each navigation (see [BrowserMission.drillToFirstResult]):
 *   - On the list (a result card with a link is present) → navigate to the
 *     first result's detail page.
 *   - On the detail page (or when there are no results) → dump the DOM.
 * Selectors are deliberately permissive — CBC markup drifts, and the
 * downstream verify-before-merge rejects a wrong-person card anyway.
 */
private val DRILL_TO_FIRST_RESULT_SCRIPT = """
(function(){
  var firstLink = document.querySelector(
    '.person-card a[href], .result-card a[href], .search-result a[href],' +
    ' [data-result-card] a[href], a[href*="/person"], a[href*="/people"]'
  );
  var hasList = document.querySelector(
    '.person-card, .result-card, .search-result, [data-result-card]'
  );
  if (hasList && firstLink && firstLink.href) {
    // On a results list: drill into the first result. The navigation triggers
    // another page-finish, on which this script runs again and dumps the detail.
    window.location.href = firstLink.href;
    return;
  }
  // Detail page (or no results): hand the DOM back for parsing.
  window.HTMLOUT.dump(document.documentElement.outerHTML);
})();
""".trimIndent()

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
