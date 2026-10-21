package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.isStalePhone
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
    fun pickAllMissions(target: Target, includeStalePhone: Boolean = false): List<BrowserMission> = buildList {
        // A number the user ruled stale returns whoever holds it now, not this contact. Only the
        // alternates lookup asks for it, to learn who that is.
        (if (includeStalePhone) target.phoneNumber else target.usablePhone)
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
        val phone = target.usablePhone
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

        // Two page shapes feed this: a results LIST (a "first result card") and,
        // after drilling into the first result, a person DETAIL page (where the
        // middle name / DOB live). Prefer a result card; if none, fall back to
        // the document body for the detail page. CSS classes drift across CBC
        // redesigns, so the selectors stay deliberately permissive — and
        // verify-before-merge ([enrich]) guards against adopting a wrong card.
        val root: Element = doc.selectFirst(
            ".person-card, .result-card, .search-result, .card-person, [data-result-card]"
        ) ?: doc.body() ?: return null

        // Generic h1/h2/h3 are only trustworthy inside a real result card. When
        // root fell back to doc.body() (e.g. a "No Results Found" page) those
        // headers are page chrome — capturing them would falsely rename the
        // contact, and because CBC lookups are unique-id the bad name can pass
        // verify-before-merge. So restrict the generic fallback to non-body root.
        val name = (root.selectFirst(NAME_SELECTORS)
            ?: if (root !== doc.body()) root.selectFirst("h1, h2, h3") else null)
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val address = root
            .selectFirst(ADDRESS_SELECTORS)
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val phone = root
            .selectFirst(PHONE_SELECTORS)
            ?.text()?.trim()
            ?.filter { it.isDigit() || it == '+' }
            ?.takeIf { it.length >= 10 }

        // DOB / age — a dedicated field if present, else "Age N" / "DOB ..." text.
        val dob = root
            .selectFirst(DOB_SELECTORS)
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("(?:Age|DOB|Born)[:\\s]+([0-9/]{1,10}|\\d{1,3})", RegexOption.IGNORE_CASE)
                .find(root.text())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        // Structured data (schema.org Person, as JSON-LD or microdata) doesn't drift with the
        // site's CSS, so it fills whatever the class selectors missed.
        val structured = structuredPerson(doc)
        val finalName = name ?: structured?.name
        val finalAddress = address ?: structured?.address
        val finalPhone = phone ?: structured?.phone
        val finalDob = dob ?: structured?.dob

        // Middle name is the token(s) between first and last of the parsed name.
        val middleName = finalName?.let { middleFrom(it) }

        if (finalName == null && finalAddress == null && finalPhone == null && finalDob == null) return null
        return Findings(name = finalName, address = finalAddress, phone = finalPhone, middleName = middleName, dob = finalDob)
    }

    /**
     * One line for the diagnostic log: what [parseFindings] got from a page, or, when it got
     * nothing, enough of the page's shape (title, headings, likely class names) to fix the
     * selectors against real markup. Holds no more personal data than the parse itself.
     */
    fun describeParse(html: String, findings: Findings?): String {
        if (findings != null) {
            fun f(label: String, v: String?) = "$label=${if (v == null) "—" else "✓"}"
            return listOf(
                f("name", findings.name), f("address", findings.address), f("phone", findings.phone),
                f("age/dob", findings.dob), f("middle", findings.middleName)
            ).joinToString(" ")
        }
        if (html.isBlank()) return "empty page"
        val doc = Jsoup.parse(html)
        val title = doc.title().take(80)
        val headings = doc.select("h1, h2").take(3).joinToString(" | ") { it.tagName() + "." + it.className() }
        val classes = doc.select("[class]")
            .flatMap { it.classNames() }
            .filter { CLASS_HINT.containsMatchIn(it) }
            .distinct()
            .take(30)
        val ldJson = doc.select("script[type=application/ld+json]").size
        return "nothing found · title='$title' · headings=[$headings] · ld+json=$ldJson · classes=$classes"
    }

    /** schema.org Person from JSON-LD or microdata, if the page carries one. */
    private fun structuredPerson(doc: org.jsoup.nodes.Document): Findings? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val element = try {
                com.google.gson.JsonParser.parseString(script.data())
            } catch (e: Exception) {
                continue
            }
            findPerson(element)?.let { return it }
        }
        val item = doc.selectFirst("[itemtype*=schema.org/Person]") ?: return null
        fun prop(name: String) = item.selectFirst("[itemprop=$name]")?.let {
            it.attr("content").ifBlank { it.text() }
        }?.trim()?.takeIf { it.isNotBlank() }
        return Findings(
            name = prop("name"),
            address = prop("address"),
            phone = prop("telephone")?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
            dob = prop("birthDate")
        ).takeIf { it.name != null || it.address != null || it.phone != null || it.dob != null }
    }

    private fun findPerson(e: com.google.gson.JsonElement): Findings? {
        if (e.isJsonArray) return e.asJsonArray.firstNotNullOfOrNull { findPerson(it) }
        if (!e.isJsonObject) return null
        val o = e.asJsonObject
        o.get("@graph")?.let { graph -> findPerson(graph)?.let { return it } }
        val type = o.get("@type")
        val isPerson = type != null &&
            (if (type.isJsonArray) type.asJsonArray.any { it.isJsonPrimitive && it.asString == "Person" }
            else type.isJsonPrimitive && type.asString == "Person")
        if (!isPerson) return null

        fun str(key: String): String? = o.get(key)?.let { v ->
            when {
                v.isJsonPrimitive -> v.asString
                v.isJsonArray -> v.asJsonArray.firstOrNull { it.isJsonPrimitive }?.asString
                else -> null
            }
        }?.trim()?.takeIf { it.isNotBlank() }

        val address = o.get("address")?.let { a ->
            val first = if (a.isJsonArray) a.asJsonArray.firstOrNull() else a
            when {
                first == null -> null
                first.isJsonPrimitive -> first.asString
                first.isJsonObject -> {
                    val p = first.asJsonObject
                    listOf("streetAddress", "addressLocality", "addressRegion", "postalCode")
                        .mapNotNull { k -> p.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() } }
                        .joinToString(", ")
                        .takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }
        return Findings(
            name = str("name"),
            address = address,
            phone = str("telephone")?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
            dob = str("birthDate")
        )
    }

    /** Middle token(s) of a full name ("John A. Smith" → "A."), or null. */
    private fun middleFrom(fullName: String): String? {
        val tokens = NameValidator.tokenize(fullName)
        return if (tokens.size >= 3) {
            tokens.subList(1, tokens.size - 1).joinToString(" ").takeIf { it.isNotBlank() }
        } else null
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
            phoneNumber = adoptPhone(target, findings.phone),
            residenceInfo = target.residenceInfo ?: findings.address,
            areaCode = areaCodeFor(target, findings.phone),
            middleName = target.middleName ?: findings.middleName,
            dateOfBirth = target.dateOfBirth ?: findings.dob
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
            phoneNumber = adoptPhone(target, consensus.phone),
            residenceInfo = target.residenceInfo ?: consensus.address,
            areaCode = areaCodeFor(target, consensus.phone),
            middleName = target.middleName ?: consensus.middleName,
            dateOfBirth = target.dateOfBirth ?: consensus.dob,
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
                phoneNumber = adoptPhone(target, consensus.phone),
                residenceInfo = target.residenceInfo ?: consensus.address,
                areaCode = areaCodeFor(target, consensus.phone),
                middleName = target.middleName ?: consensus.middleName,
                dateOfBirth = target.dateOfBirth ?: consensus.dob,
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
            phone = consensus { it.phone },
            middleName = consensus { it.middleName },
            dob = consensus { it.dob }
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
        val cand = NameValidator.tokenize(naturalOrder(candidate))
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
     * The number a merge should leave on [target]: the existing one, filled from [found] only when
     * missing, except that a number the user marked stale is replaced by any different number found.
     * Replacing it lapses the stale mark ([Target.phoneIsStale] compares against the live number).
     */
    private fun adoptPhone(target: Target, found: String?): String? = when {
        target.phoneIsStale && found != null &&
            !isStalePhone(found, target.stalePhone) -> found
        else -> target.phoneNumber ?: found
    }

    /** Area code that follows the number [adoptPhone] chose; unchanged unless a stale number was replaced. */
    private fun areaCodeFor(target: Target, found: String?): String? {
        val digits = found?.filter { it.isDigit() }?.takeLast(10)
        return if (target.phoneIsStale && adoptPhone(target, found) != target.phoneNumber) digits?.take(3)
        else target.areaCode ?: digits?.take(3)
    }

    /** Whether [candidate], a name read off a lookup card, is plausibly the same person as [displayName]. */
    fun namesConsistent(displayName: String, candidate: String): Boolean =
        nameConsistent(NameValidator.tokenize(displayName), candidate)

    /**
     * "Smith, John A" → "John A Smith". Only when a single word precedes the comma, so
     * "John Smith, 45" and "John Smith, Jr" are left as they are.
     */
    private fun naturalOrder(name: String): String {
        val comma = name.indexOf(',')
        if (comma <= 0) return name
        val before = name.substring(0, comma).trim()
        if (before.contains(' ')) return name
        return name.substring(comma + 1).trim() + " " + before
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

    data class Findings(
        val name: String?,
        val address: String?,
        val phone: String?,
        val middleName: String? = null,
        val dob: String? = null
    )

    private companion object {
        // Class selectors are guesses at CBC's markup and drift with redesigns; the structured-
        // data fallback and [describeParse]'s log line exist for when they miss.
        const val NAME_SELECTORS =
            ".name, h1.full-name, .full-name, .person-name, .name-given, [itemprop=name]"
        const val ADDRESS_SELECTORS =
            ".address, .current-address, .address-current, .person-address, .city-state, [itemprop=address]"
        const val PHONE_SELECTORS =
            ".phone, .phone-number, [itemprop=telephone], [href^=tel]"
        const val DOB_SELECTORS =
            ".dob, .date-of-birth, .born, .age, [data-dob], [itemprop=birthDate]"
        val CLASS_HINT = Regex("name|addr|phone|age|dob|birth|person|result|card|resident", RegexOption.IGNORE_CASE)

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
