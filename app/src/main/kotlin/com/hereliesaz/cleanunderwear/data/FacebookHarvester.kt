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
        return parseFriendsDocument(Jsoup.parse(html))
    }

    private fun parseFriendsDocument(doc: Document): List<Target> {
        val friends = mutableListOf<Target>()

        // mbasic friend rows are anchors whose href points at the user's
        // profile and whose text is the friend's display name. We exclude
        // navigation chrome by ignoring known noise links.
        val noise = setOf(
            "Friends", "Mutual friends", "Followers", "Following",
            "See all", "See more", "Edit profile", "Home", "Menu"
        )
        val elements = doc.select("a[href]")

        for (element in elements) {
            val href = element.attr("href")
            val looksLikeProfile =
                href.contains("/profile.php?id=") ||
                href.matches(Regex("^/[A-Za-z0-9._-]+(?:/)?(?:\\?[A-Za-z0-9._%=&-]*)?$")) ||
                href.contains("/friends/?profile_id=")
            if (!looksLikeProfile) continue

            val name = element.text().trim()
            if (name.isBlank() || name in noise) continue
            if (name.length < 2) continue

            friends += Target(
                displayName = name,
                sourceAccount = "Meta (Facebook)",
                status = TargetStatus.UNKNOWN
            )
        }

        val deduped = friends.distinctBy { it.displayName }
        DiagnosticLogger.log("Facebook Harvest: parsed ${deduped.size} friends from visible session.")
        return deduped
    }
}
