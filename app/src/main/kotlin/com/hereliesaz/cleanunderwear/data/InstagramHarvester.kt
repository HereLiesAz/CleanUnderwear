package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.network.WebViewScraper
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes the operator's Instagram following list. Relies on an existing IG session cookie in
 * WebView storage; if absent, the page redirects to login and parsing returns empty.
 *
 * The flow:
 *   1) Land on /accounts/edit/ to discover the operator's username from the form input.
 *   2) Open the followers modal at /<me>/followers/ and scroll the list dialog until exhausted.
 */
@Singleton
class InstagramHarvester @Inject constructor(
    private val scraper: WebViewScraper
) {
    private val DISCOVERY_SCRIPT = """
        (function() {
            const usernameField = document.querySelector('input[name="username"]');
            const value = usernameField ? usernameField.value : '';
            const html = '<meta name="ig-username" content="' + value + '">' +
                document.documentElement.outerHTML;
            AndroidInterface.processHtml(html);
        })();
    """.trimIndent()

    private val FOLLOWERS_SCRIPT = """
        (function() {
            function findScroller() {
                const dialog = document.querySelector('div[role="dialog"]');
                if (!dialog) return null;
                const candidates = dialog.querySelectorAll('div');
                for (const el of candidates) {
                    if (el.scrollHeight > el.clientHeight + 10) return el;
                }
                return null;
            }
            let lastCount = -1;
            function step(attempts) {
                const scroller = findScroller();
                if (!scroller) {
                    if (attempts > 30) {
                        AndroidInterface.processHtml(document.documentElement.outerHTML);
                        return;
                    }
                    setTimeout(() => step(attempts + 1), 1000);
                    return;
                }
                scroller.scrollTop = scroller.scrollHeight;
                const links = scroller.querySelectorAll('a[role="link"]');
                if (links.length === lastCount) {
                    AndroidInterface.processHtml(document.documentElement.outerHTML);
                    return;
                }
                lastCount = links.length;
                setTimeout(() => step(0), 1500);
            }
            step(0);
        })();
    """.trimIndent()

    suspend fun harvestFollowers(): List<Target> {
        val username = discoverUsername() ?: run {
            DiagnosticLogger.log("Instagram Harvest: No active session; skipping.")
            return emptyList()
        }
        val html = scraper.scrapeWithInjection(
            "https://www.instagram.com/$username/followers/",
            FOLLOWERS_SCRIPT
        ) ?: return emptyList()
        return parseFollowersHtml(Jsoup.parse(html))
    }

    private suspend fun discoverUsername(): String? {
        val html = scraper.scrapeWithInjection(
            "https://www.instagram.com/accounts/edit/",
            DISCOVERY_SCRIPT
        ) ?: return null
        val doc = Jsoup.parse(html)
        val meta = doc.select("meta[name=ig-username]").firstOrNull()?.attr("content")?.trim()
        return meta?.takeIf { it.isNotBlank() }
    }

    private fun parseFollowersHtml(doc: Document): List<Target> {
        val followers = mutableListOf<Target>()
        var missedDisplayNames = 0

        // Followers in the dialog render as <a role="link" href="/<handle>/"> with display name
        // appearing in a sibling span. We collect handle + best-effort display name.
        val links = doc.select("div[role=dialog] a[role=link][href]")
        for (link in links) {
            val href = link.attr("href")
            val handle = href.trim('/').takeIf { it.isNotBlank() && !it.contains('/') } ?: continue
            if (handle in setOf("explore", "reels", "direct", "accounts")) continue

            val displayName = link.parent()?.parent()
                ?.select("span")
                ?.firstOrNull { it.text().isNotBlank() && it.text() != handle }
                ?.text()
                ?.trim()
                ?: run {
                    // The two-level parent walk is the brittle bit. When IG
                    // restructures their followers list (it happens), every
                    // follower silently falls back to handle-as-name and we
                    // can't tell from the registry that the parser broke.
                    // Logging the miss makes the failure visible.
                    missedDisplayNames++
                    handle
                }

            followers += Target(
                displayName = displayName,
                sourceAccount = "Meta (Instagram: @$handle)",
                status = TargetStatus.UNKNOWN
            )
        }

        if (missedDisplayNames > 0) {
            DiagnosticLogger.log(
                "Instagram: display-name parser missed $missedDisplayNames of ${links.size} followers — page structure may have changed",
                DiagnosticLogger.LogEntry.LogLevel.WARN
            )
        }

        // sourceAccount embeds the per-follower handle, so it's unique by
        // construction — distinctBy on it would be a no-op. Dedup on the
        // visible name instead, which catches followers that appear twice
        // because pagination overlapped or the page re-rendered mid-scrape.
        val deduped = followers.distinctBy { it.displayName }
        DiagnosticLogger.log("Instagram Harvest: Identified ${deduped.size} followers.")
        return deduped
    }
}
