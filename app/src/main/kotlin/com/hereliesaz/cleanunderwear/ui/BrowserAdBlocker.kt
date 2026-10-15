package com.hereliesaz.cleanunderwear.ui

import java.util.Locale

/**
 * Known advertising delivery domains for the user-visible browser.
 *
 * Matching DNS labels rather than URL substrings avoids suppressing legitimate
 * pages whose hostnames merely contain an advertiser's name. First-party pages,
 * login and CAPTCHA requests remain untouched. Background scrapers do not use
 * this filter.
 */
internal object BrowserAdBlocker {
    private val adDomains = setOf(
        "2mdn.net",
        "adform.net",
        "adnxs.com",
        "adsrvr.org",
        "adservice.google.com",
        "amazon-adsystem.com",
        "bidswitch.net",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "indexww.com",
        "lijit.com",
        "media.net",
        "moatads.com",
        "openx.net",
        "outbrain.com",
        "pubmatic.com",
        "revcontent.com",
        "rubiconproject.com",
        "sharethrough.com",
        "smartadserver.com",
        "sovrn.com",
        "taboola.com",
        "teads.tv",
        "yieldmo.com"
    )

    /** Returns true for an ad domain or one of its subdomains, never a lookalike. */
    fun blocks(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return false
        return adDomains.any { normalized == it || normalized.endsWith(".$it") }
    }
}
