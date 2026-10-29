package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the user's Instagram followers.
 *
 * Runs in the visible browser ([com.hereliesaz.cleanunderwear.domain.BrowserMission.HarvestInstagram]),
 * so the user signs in there if they aren't already: the browser screen leaves the sign-in
 * page to them, and only runs [SCRIPT] once the page is no longer a sign-in page. [SCRIPT] is
 * re-run after every page load:
 *   1) on a sign-in page it does nothing (the screen has normally handed it to the user already);
 *   2) on /accounts/edit/ it reads the user's handle from the form and goes to their followers;
 *   3) on the followers page it scrolls the list dialog until it stops growing, then hands it back;
 *   4) anywhere else (sign-in can land on the home page or a "save your login" step) it goes to
 *      /accounts/edit/ again, a few times at most.
 */
@Singleton
class InstagramHarvester @Inject constructor() {
    companion object {
        const val URL = "https://www.instagram.com/accounts/edit/"

        val SCRIPT = """
        (function() {
            var path = window.location.pathname;
            if (/^\/(accounts\/(login|emailsignup|suspended)|challenge)/.test(path)
                || document.querySelector('input[type="password"]')) return;
            if (path.indexOf('/accounts/edit') === 0) {
                // The form is drawn after the page loads: wait for the handle to be in it.
                var tries = 0;
                (function readHandle() {
                    var field = document.querySelector('input[name="username"]');
                    var handle = field ? field.value : '';
                    if (handle) { window.location.href = '/' + encodeURIComponent(handle) + '/followers/'; return; }
                    if (++tries < 20) setTimeout(readHandle, 750);
                })();
                return;
            }
            if (!/\/followers\/?$/.test(path)) {
                var hops = 0;
                try { hops = parseInt(window.sessionStorage.getItem('cuEditHops') || '0', 10); } catch (e) {}
                if (hops < 3) {
                    try { window.sessionStorage.setItem('cuEditHops', String(hops + 1)); } catch (e) {}
                    window.location.href = '/accounts/edit/';
                }
                return;
            }
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
                        window.HTMLOUT.dump(document.documentElement.outerHTML);
                        return;
                    }
                    setTimeout(() => step(attempts + 1), 1000);
                    return;
                }
                scroller.scrollTop = scroller.scrollHeight;
                const links = scroller.querySelectorAll('a[role="link"]');
                if (links.length === lastCount) {
                    window.HTMLOUT.dump(document.documentElement.outerHTML);
                    return;
                }
                lastCount = links.length;
                setTimeout(() => step(0), 1500);
            }
            step(0);
        })();
    """.trimIndent()
    }

    fun parse(html: String): List<Target> = parseFollowersHtml(Jsoup.parse(html))

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

        // sourceAccount embeds the follower's handle: one entry per account, which
        // drops repeats from overlapping pages without merging two people who
        // happen to share a display name.
        val deduped = followers.distinctBy { it.sourceAccount }
        DiagnosticLogger.log("Instagram Harvest: Identified ${deduped.size} followers.")
        return deduped
    }
}
