package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity enrichment via cyberbackgroundchecks.com.
 *
 * The site is JS-heavy and bot-defended; the user's real browser session is
 * the only reliable way to reach the result page. So this class does *not*
 * fetch anything — it picks the best [BrowserMission] for a target, and
 * parses the HTML the user-visible [com.hereliesaz.cleanunderwear.ui.BrowserScreen]
 * brings back.
 *
 * Search-mode priority (per the user, "if there's a contact, then at least
 * ONE of those ways is searchable"):
 *
 *   phone → email → address → name
 */
@Singleton
class CyberBackgroundChecksEnricher @Inject constructor() {

    /**
     * Every search this target supports, in priority order
     * (phone → email → address → name). Empty list = caller marks the target
     * ENRICHMENT_FAILED. The order is also the tiebreaker [mergeAll] uses
     * when consensus across results is split.
     */
    fun pickAllMissions(target: Target): List<BrowserMission> = buildList {
        target.phoneNumber
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 10 }
            ?.let { add(BrowserMission.CbcByPhone(it)) }

        target.email
            ?.takeIf { it.isNotBlank() && it.contains("@") }
            ?.let { add(BrowserMission.CbcByEmail(it)) }

        target.residenceInfo
            ?.takeIf { it.isNotBlank() }
            ?.let { add(BrowserMission.CbcByAddress(it)) }

        val nameUsable = target.displayName.isNotBlank() &&
            target.displayName != "Unnamed Entity" &&
            !target.displayName.startsWith("Unnamed Entity (") &&
            target.displayName.split(" ").size >= 2
        if (nameUsable) add(BrowserMission.CbcByName(target.displayName))
    }

    /**
     * Returns the most-precise mission this target supports, or null if none
     * of the four search inputs are present (caller should mark the target
     * ENRICHMENT_FAILED).
     */
    fun pickMission(target: Target): BrowserMission? {
        val phone = target.phoneNumber
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 10 }
        if (phone != null) return BrowserMission.CbcByPhone(phone)

        val email = target.email?.takeIf { it.isNotBlank() && it.contains("@") }
        if (email != null) return BrowserMission.CbcByEmail(email)

        val address = target.residenceInfo?.takeIf { it.isNotBlank() }
        if (address != null) return BrowserMission.CbcByAddress(address)

        val displayName = target.displayName.takeIf {
            it.isNotBlank() &&
                it != "Unnamed Entity" &&
                !it.startsWith("Unnamed Entity (") &&
                it.split(" ").size >= 2
        }
        if (displayName != null) return BrowserMission.CbcByName(displayName)

        return null
    }

    /**
     * Pulls name / address / phone out of a CBC results page. Returns null
     * if the page didn't surface anything useful (no result, captcha wall,
     * etc.).
     */
    fun parseFindings(html: String): Findings? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)

        // CBC redirects unmatched searches to a /notfound page that still
        // renders the site chrome. The chrome contains generic headings
        // ("Search Again", "Background Checks") that our broad h2/h3 selector
        // happily picks up as a "name", which then poisons mergeAll. Bail
        // before that happens.
        if (looksLikeNotFound(doc)) return null

        // The first result card. CSS classes drift across CBC redesigns,
        // so try a handful of plausible roots. If none match, the page
        // isn't a result page (CBC's chrome on a no-match page would
        // otherwise feed garbage to mergeAll via the generic h2/h3 fallback).
        val firstCard: Element = doc.selectFirst(
            ".person-card, .result-card, .search-result, .card-person, [data-result-card]"
        ) ?: return null

        val name = firstCard
            .selectFirst(".name, h1.full-name, .full-name, .person-name, h2, h3")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val address = firstCard
            .selectFirst(".address, .current-address, .person-address, .city-state")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val phone = firstCard
            .selectFirst(".phone, .phone-number, [href^=tel]")
            ?.text()?.trim()
            ?.filter { it.isDigit() || it == '+' }
            ?.takeIf { it.length >= 10 }

        if (name == null && address == null && phone == null) return null
        return Findings(name = name, address = address, phone = phone)
    }

    private fun looksLikeNotFound(doc: org.jsoup.nodes.Document): Boolean {
        val title = doc.title().lowercase()
        if ("not found" in title || "no results" in title || "page not found" in title) return true
        val bodyText = doc.body()?.text()?.lowercase().orEmpty()
        return NOT_FOUND_MARKERS.any { it in bodyText }
    }

    /**
     * Folds [findings] into [target] without trampling fields the registry
     * already knows. Existing values win — enrichment fills gaps, doesn't
     * overwrite user-edited data.
     */
    fun merge(target: Target, findings: Findings): Target {
        val nameWasPlaceholder = target.displayName.isBlank() ||
            target.displayName == "Unnamed Entity" ||
            target.displayName.startsWith("Unnamed Entity (")

        val merged = target.copy(
            displayName = if (nameWasPlaceholder && findings.name != null) findings.name else target.displayName,
            phoneNumber = target.phoneNumber ?: findings.phone,
            residenceInfo = target.residenceInfo ?: findings.address,
            areaCode = target.areaCode ?: findings.phone?.takeLast(10)?.take(3)
        )
        DiagnosticLogger.log("CYBG merge: ${target.displayName} → ${merged.displayName} (phone=${merged.phoneNumber != null}, addr=${merged.residenceInfo != null})")
        return merged
    }

    /**
     * Folds findings from every search method into [target]. Consensus wins —
     * a value seen in more results beats one seen in fewer. Ties break on
     * source priority (phone > email > address > name), matching the order
     * returned by [pickAllMissions].
     *
     * As with [merge], existing target fields are preserved — enrichment
     * only fills gaps, it doesn't trample user-edited data.
     */
    fun mergeAll(target: Target, results: List<Pair<BrowserMission, Findings>>): Target {
        fun priorityOf(m: BrowserMission): Int = when (m) {
            is BrowserMission.CbcByPhone -> 4
            is BrowserMission.CbcByEmail -> 3
            is BrowserMission.CbcByAddress -> 2
            is BrowserMission.CbcByName -> 1
            else -> 0
        }

        fun <T : Any> consensus(extract: (Findings) -> T?): T? {
            val grouped = results.mapNotNull { (m, f) ->
                extract(f)?.let { it to priorityOf(m) }
            }.groupBy({ it.first }, { it.second })
            return grouped.entries.maxWithOrNull(
                compareBy<Map.Entry<T, List<Int>>> { it.value.size }
                    .thenBy { it.value.max() }
            )?.key
        }

        val name = consensus { it.name }
        val address = consensus { it.address }
        val phone = consensus { it.phone }

        val nameWasPlaceholder = target.displayName.isBlank() ||
            target.displayName == "Unnamed Entity" ||
            target.displayName.startsWith("Unnamed Entity (")

        val merged = target.copy(
            displayName = if (nameWasPlaceholder && name != null) name else target.displayName,
            phoneNumber = target.phoneNumber ?: phone,
            residenceInfo = target.residenceInfo ?: address,
            areaCode = target.areaCode ?: phone?.filter { it.isDigit() }?.takeLast(10)?.take(3),
        )
        DiagnosticLogger.log("CYBG mergeAll: ${target.displayName} → ${merged.displayName} (${results.size} sources)")
        return merged
    }

    /**
     * Coarse heuristic: returns true when the page HTML smells like a captcha,
     * anti-bot wall, or completely empty body. BrowserScreen uses this to
     * decide when to surface the "human, please help" pause overlay.
     *
     * Conservative on purpose — false negatives (page looks fine, parser
     * finds nothing) are also handled by [parseFindings] returning null,
     * which the viewmodel records as a per-search miss without halting the
     * whole queue.
     */
    fun looksLikeBlock(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.lowercase()
        return BLOCK_MARKERS.any { it in lower }
    }

    data class Findings(val name: String?, val address: String?, val phone: String?)

    private companion object {
        private val BLOCK_MARKERS = listOf(
            "captcha",
            "are you a robot",
            "verify you are human",
            "unusual traffic",
            "access denied",
            "cf-browser-verification",
            "/cdn-cgi/challenge",
        )

        private val NOT_FOUND_MARKERS = listOf(
            "we couldn't find",
            "we could not find",
            "no results found",
            "no matches found",
            "no records found",
            "404 - page not found",
        )
    }
}
