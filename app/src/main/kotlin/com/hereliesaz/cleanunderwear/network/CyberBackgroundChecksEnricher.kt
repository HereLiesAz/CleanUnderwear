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

        // The first result card. CSS classes drift across CBC redesigns,
        // so try a handful of plausible roots. If none match, the page
        // isn't a result page (CBC's /notfound chrome would otherwise feed
        // garbage to mergeAll via the generic h2/h3 fallback we used to
        // apply when the card lookup missed).
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
        val consensus = consensusFindings(results)

        val nameWasPlaceholder = isPlaceholderName(target.displayName)

        val merged = target.copy(
            displayName = if (nameWasPlaceholder && consensus.name != null) consensus.name else target.displayName,
            phoneNumber = target.phoneNumber ?: consensus.phone,
            residenceInfo = target.residenceInfo ?: consensus.address,
            areaCode = target.areaCode ?: consensus.phone?.filter { it.isDigit() }?.takeLast(10)?.take(3),
        )
        DiagnosticLogger.log("CYBG mergeAll: ${target.displayName} → ${merged.displayName} (${results.size} sources)")
        return merged
    }

    /**
     * Verify-before-merge. Like [mergeAll], but only adopts findings into the
     * target when the candidate result is corroborated as the *same person*,
     * and records why (or why not) in [Target.enrichmentProvenance].
     *
     * Verification basis:
     *  - **Unique-identifier lookups win.** A card returned for an exact phone
     *    or email is very likely the right person, so it verifies — UNLESS the
     *    card also carries a name that conflicts with the contact's existing
     *    real name (a recycled phone number returning a stranger), in which case
     *    it is rejected.
     *  - **Name consistency.** When the contact already has a real name, a
     *    candidate whose name shares the same last name + first name (or first
     *    initial) verifies.
     *  - **Placeholder contacts** (no real name yet) are only ever resolved by a
     *    unique-identifier lookup; a bare name/address search on a placeholder
     *    is too weak to assign an identity, so it is not adopted.
     *
     * Unverified results are NOT written into the registry or system contacts —
     * the caller should leave the contact UNVERIFIED and surface the provenance.
     */
    fun enrich(target: Target, results: List<Pair<BrowserMission, Findings>>): EnrichmentOutcome {
        if (results.isEmpty()) {
            val prov = "no results parsed"
            return EnrichmentOutcome(target.copy(enrichmentProvenance = prov), verified = false, provenance = prov)
        }

        val consensus = consensusFindings(results)
        val modes = results.map { modeLabel(it.first) }.distinct().joinToString("+")
        val (verified, basis) = verifySamePerson(target, results, consensus)

        return if (verified) {
            val nameWasPlaceholder = isPlaceholderName(target.displayName)
            val prov = "$modes · verified ($basis)"
            val merged = target.copy(
                displayName = if (nameWasPlaceholder && consensus.name != null) consensus.name else target.displayName,
                phoneNumber = target.phoneNumber ?: consensus.phone,
                residenceInfo = target.residenceInfo ?: consensus.address,
                areaCode = target.areaCode ?: consensus.phone?.filter { it.isDigit() }?.takeLast(10)?.take(3),
                enrichmentProvenance = prov
            )
            DiagnosticLogger.log("CYBG enrich(verified): ${target.displayName} → ${merged.displayName} [$prov]")
            EnrichmentOutcome(merged, verified = true, provenance = prov)
        } else {
            val prov = "$modes · NOT merged ($basis)"
            DiagnosticLogger.log("CYBG enrich(rejected): ${target.displayName} [$prov]")
            EnrichmentOutcome(target.copy(enrichmentProvenance = prov), verified = false, provenance = prov)
        }
    }

    /** Result of [enrich]: the (possibly unchanged) target, whether the candidate
     *  was verified as the same person, and a human-readable provenance string. */
    data class EnrichmentOutcome(
        val target: Target,
        val verified: Boolean,
        val provenance: String
    )

    private fun priorityOf(m: BrowserMission): Int = when (m) {
        is BrowserMission.CbcByPhone -> 4
        is BrowserMission.CbcByEmail -> 3
        is BrowserMission.CbcByAddress -> 2
        is BrowserMission.CbcByName -> 1
        else -> 0
    }

    private fun modeLabel(m: BrowserMission): String = when (m) {
        is BrowserMission.CbcByPhone -> "phone"
        is BrowserMission.CbcByEmail -> "email"
        is BrowserMission.CbcByAddress -> "address"
        is BrowserMission.CbcByName -> "name"
        else -> "other"
    }

    /** Consensus across results: the value seen most often wins; ties break on
     *  source priority (phone > email > address > name). Shared by [mergeAll]
     *  and [enrich]. */
    private fun consensusFindings(results: List<Pair<BrowserMission, Findings>>): Findings {
        fun <T : Any> consensus(extract: (Findings) -> T?): T? {
            val grouped = results.mapNotNull { (m, f) ->
                extract(f)?.let { it to priorityOf(m) }
            }.groupBy({ it.first }, { it.second })
            return grouped.entries.maxWithOrNull(
                compareBy<Map.Entry<T, List<Int>>> { it.value.size }
                    .thenBy { it.value.max() }
            )?.key
        }
        return Findings(
            name = consensus { it.name },
            address = consensus { it.address },
            phone = consensus { it.phone }
        )
    }

    private fun isPlaceholderName(name: String): Boolean =
        name.isBlank() || name == "Unnamed Entity" || name.startsWith("Unnamed Entity (")

    /**
     * Decides whether [results] describe the same person as [target]. Returns
     * (verified, basis-for-logging). Pure Kotlin — reuses [NameValidator] from
     * the same package, no Android dependency, so it stays unit-testable.
     */
    private fun verifySamePerson(
        target: Target,
        results: List<Pair<BrowserMission, Findings>>,
        consensus: Findings
    ): Pair<Boolean, String> {
        val keyedByUniqueId = results.any {
            it.first is BrowserMission.CbcByPhone || it.first is BrowserMission.CbcByEmail
        }
        val candidateNames = (results.mapNotNull { it.second.name } + listOfNotNull(consensus.name)).distinct()
        val targetHasRealName = NameValidator.isVerifiable(target.displayName)

        if (targetHasRealName) {
            val targetTokens = NameValidator.tokenize(target.displayName)
            val consistent = candidateNames.filter { nameConsistent(targetTokens, it) }
            val conflicting = candidateNames.filter { !nameConsistent(targetTokens, it) }
            return when {
                consistent.isNotEmpty() -> true to "name match: ${consistent.first()}"
                candidateNames.isEmpty() && keyedByUniqueId ->
                    true to "phone/email lookup, card carried no conflicting name"
                conflicting.isNotEmpty() ->
                    false to "name conflict: card '${conflicting.first()}' ≠ ${target.displayName}"
                else -> false to "uncorroborated"
            }
        }

        // Placeholder / unnamed contact: only a unique-identifier lookup can
        // safely assign an identity. A bare name/address card is too weak.
        return if (keyedByUniqueId) {
            true to "phone/email lookup resolved placeholder identity"
        } else {
            false to "placeholder contact, no unique-identifier lookup to corroborate"
        }
    }

    /**
     * Two names are consistent if they share the same last name and a
     * compatible first name, case-insensitively. First names match when they
     * are equal, when one is a prefix of the other ("Jon" / "Jonathan"), or
     * when one side is a single-letter initial sharing that letter ("J" / "John").
     *
     * Deliberately NOT a bare first-initial match: "James Smith" and
     * "John Smith" share the initial 'J' and the surname but are different
     * people, and merging them would defeat the whole point of verify-before-merge.
     */
    private fun nameConsistent(targetTokens: List<String>, candidate: String): Boolean {
        val cand = NameValidator.tokenize(candidate)
        if (targetTokens.size < 2 || cand.size < 2) return false
        val tFirst = targetTokens.first().lowercase()
        val tLast = targetTokens.last().lowercase()
        val cFirst = cand.first().lowercase()
        val cLast = cand.last().lowercase()
        val lastMatch = tLast == cLast
        val firstMatch = tFirst == cFirst ||
            tFirst.startsWith(cFirst) ||
            cFirst.startsWith(tFirst) ||
            ((tFirst.length == 1 || cFirst.length == 1) && tFirst.firstOrNull() == cFirst.firstOrNull())
        return lastMatch && firstMatch
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
    }
}
