package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses mbasic.facebook.com friend-list HTML into [Target] rows.
 *
 * The HTML must come from a *user-visible* WebView session
 * ([com.hereliesaz.cleanunderwear.ui.BrowserScreen] driving
 * [com.hereliesaz.cleanunderwear.domain.BrowserMission.HarvestFacebookFriends])
 * — not from the covert WebView scraper. Facebook requires the user's real
 * session cookies and frequently surfaces consent / login walls; only a real
 * browser session reliably gets through.
 */
@Singleton
class FacebookHarvester @Inject constructor() {

    fun parseFriendsHtml(html: String): List<Target> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        if (looksLikeLoginWall(doc)) {
            // Distinguish "session missing" from "parser drifted" — both
            // produce zero friends, but only the first is the operator's
            // problem to fix (re-login in the visible WebView).
            DiagnosticLogger.log(
                "Facebook Harvest: visible session is logged out (login wall) — skipping.",
                DiagnosticLogger.LogEntry.LogLevel.WARN
            )
            return emptyList()
        }
        // Modern Facebook renders friends as dynamically loaded profile links; the in-app
        // collector supplies a compact HTML document with that shape. Keep mbasic parsing
        // for older fixtures and site variants.
        val modern = parseSavedFriendsHtml(html)
        return if (modern.isNotEmpty()) modern else parseFriendsDocument(doc)
    }

    /**
     * CoSy-style import of a Facebook friends page saved by the user in desktop Chrome.
     * The file is parsed locally; profile URLs are retained for later explicit photo links.
     * Only anchors associated with the friends list are accepted, not the page's navigation.
     */
    fun parseSavedFriendsHtml(html: String): List<Target> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val friends = linkedMapOf<String, Target>()
        val links = doc.select(
            "[role=list] a[href], [data-pagelet*=Friends] a[href], " +
                "[data-pagelet*=friends] a[href], [aria-label*=Friends] a[href], " +
                "[aria-label*=friends] a[href]"
        )
        for (link in links) {
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val url = try { java.net.URI("https://www.facebook.com/").resolve(href) } catch (_: Exception) { continue }
            if (url.scheme != "https" || url.host?.lowercase() !in setOf("www.facebook.com", "facebook.com", "m.facebook.com")) continue
            val path = url.path?.trim('/').orEmpty()
            val id = if (path == "profile.php") {
                url.rawQuery?.split('&')?.firstOrNull { it.startsWith("id=") }?.substringAfter("id=")
                    ?.takeIf { it.matches(Regex("[0-9]+")) }
            } else path.takeIf {
                it.matches(Regex("[A-Za-z0-9._-]+")) &&
                    it.lowercase() !in setOf("friends", "profile", "home", "watch", "groups", "marketplace", "messages", "notifications", "settings", "search", "login", "help", "pages", "reels", "events", "photo", "photos")
            }
            if (id == null) continue
            val name = link.text().trim().replace(Regex("\\s+"), " ")
            if (name.length < 3 || name.length > 120 || ' ' !in name) continue
            val profile = if (path == "profile.php") "https://www.facebook.com/profile.php?id=$id"
                else "https://www.facebook.com/$id"
            friends.putIfAbsent(profile, Target(
                displayName = name,
                sourceAccount = "Meta (Facebook: $profile)",
                status = TargetStatus.UNKNOWN
            ))
        }
        DiagnosticLogger.log("Facebook saved-page import: parsed ${friends.size} friend profile(s).")
        return friends.values.toList()
    }

    private fun looksLikeLoginWall(doc: Document): Boolean {
        // mbasic's login page exposes a literal #login_form or input[name=pass]
        // even when the URL after redirects is just "/" — checking the DOM is
        // more reliable than parsing the URL the WebView ended up on.
        // (The browser screen now stops on this page before it is ever dumped, see
        // BrowserMission.requiresSignIn; this stays as the backstop for a dump that slips through.)
        return doc.selectFirst("#login_form, form[action*=login], input[name=pass]") != null
    }

    private fun parseFriendsDocument(doc: Document): List<Target> {
        // Keyed by profile link: one entry per account, so two friends with the
        // same name stay two people while a repeated row is counted once.
        val friends = linkedMapOf<String, Target>()

        // mbasic friend rows are anchors whose href points at the user's
        // profile and whose text is the friend's display name. We exclude
        // navigation chrome by ignoring known noise links.
        val noise = setOf(
            "Friends", "Mutual friends", "Followers", "Following",
            "See all", "See more", "Edit profile", "Home", "Menu",
            "Messages", "Notifications", "Marketplace", "Groups", "Watch", "Events", "Pages",
            "Settings", "Bookmarks", "Search", "Log out", "Logout", "Help", "Saved", "Memories"
        )
        // Single-segment paths that are Facebook's own pages, not a friend's profile.
        val reservedPaths = setOf(
            "home.php", "messages", "notifications", "marketplace", "groups", "watch", "events",
            "pages", "settings", "bookmarks", "search", "help", "friends", "menu", "login", "logout.php",
            "saved", "memories", "gaming", "reel", "stories", "privacy", "policies"
        )
        val elements = doc.select("a[href]")

        for (element in elements) {
            val href = element.attr("href")
            val looksLikeProfile =
                href.contains("/profile.php?id=") ||
                href.matches(Regex("^/[A-Za-z0-9._-]+(?:/)?(?:\\?[A-Za-z0-9._%=&-]*)?$")) ||
                href.contains("/friends/?profile_id=")
            if (!looksLikeProfile) continue
            val firstSegment = href.trimStart('/').substringBefore('?').substringBefore('/').lowercase()
            if (!href.contains("profile.php") && firstSegment in reservedPaths) continue

            val name = element.text().trim()
            if (name.isBlank() || name in noise) continue
            if (name.length < 2) continue

            friends.putIfAbsent(
                href.substringBefore('#').trimEnd('/'),
                Target(
                    displayName = name,
                    sourceAccount = "Meta (Facebook)",
                    status = TargetStatus.UNKNOWN
                )
            )
        }

        val deduped = friends.values.toList()
        DiagnosticLogger.log("Facebook Harvest: parsed ${deduped.size} friends from visible session.")
        return deduped
    }
}
