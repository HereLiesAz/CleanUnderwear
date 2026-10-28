package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.isStalePhone
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.LookupOption
import com.hereliesaz.cleanunderwear.match.StringSimilarity
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

        // Two page shapes feed this: a results LIST (its first card is read; [parsePeople]
        // reads them all) and one person's own DETAIL page. Prefer a result card; if none,
        // fall back to the document body for the detail page. CSS classes drift across CBC
        // redesigns, so the selectors stay deliberately permissive — and
        // verify-before-merge ([enrich]) guards against adopting a wrong card.
        val root: Element = doc.selectFirst(CARD_SELECTORS) ?: doc.body() ?: return null

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
            ?: DOB_TEXT.find(root.text())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        // Structured data (schema.org Person, as JSON-LD or microdata) doesn't drift with the
        // site's CSS, so it fills whatever the class selectors missed. A page can carry several
        // Person records (relatives, neighbours); only one that is the card's person may fill in.
        val cardName = name?.let { naturalOrder(it) }
        val people = structuredPeople(doc)
        val structured = if (cardName != null) {
            people.firstOrNull { it.name == null || nameConsistent(NameValidator.tokenize(cardName), it.name) }
        } else {
            people.firstOrNull()
        }
        // "Last, First" is written back to the contact and split for the middle name, so store it
        // in natural order.
        val finalName = cardName ?: structured?.name?.let { naturalOrder(it) }
        val finalAddress = address ?: structured?.address
        val finalPhone = phone ?: structured?.phone
        val finalDob = (dob ?: structured?.dob)?.let(::pinAge)

        // Middle name is the token(s) between first and last of the parsed name.
        val middleName = finalName?.let { middleFrom(it) }

        if (finalName == null && finalAddress == null && finalPhone == null && finalDob == null) return null
        return Findings(name = finalName, address = finalAddress, phone = finalPhone, middleName = middleName, dob = finalDob)
    }

    /**
     * Every person a CBC page shows, in the site's order, up to [MAX_PEOPLE]: one [Findings] per
     * result card on a results list. A page with no cards (one person's own page) gives what
     * [parseFindings] reads, or every named schema.org person on it. A card without a name can't
     * be told apart from the others, so it is left out. Empty when the page names nobody.
     */
    fun parsePeople(html: String): List<Findings> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        // A card inside another card (a relatives strip) belongs to that card's person.
        val cards = doc.select(CARD_SELECTORS).filter { card -> card.parents().none { it.`is`(CARD_SELECTORS) } }
        if (cards.isNotEmpty()) return cards.mapNotNull(::readCard).distinctBy(::personKey).take(MAX_PEOPLE)
        val structured = structuredPeople(doc).mapNotNull { p ->
            val name = p.name?.let(::naturalOrder) ?: return@mapNotNull null
            p.copy(name = name, middleName = middleFrom(name), dob = p.dob?.let(::pinAge))
        }
        if (structured.size > 1) return structured.distinctBy(::personKey).take(MAX_PEOPLE)
        return listOfNotNull(parseFindings(html)?.takeIf { it.name != null })
    }

    private fun readCard(card: Element): Findings? {
        val name = (card.selectFirst(NAME_SELECTORS) ?: card.selectFirst("h1, h2, h3, h4"))
            ?.text()?.trim()?.takeIf { it.isNotBlank() }?.let(::naturalOrder) ?: return null
        val dob = card.selectFirst(DOB_SELECTORS)?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: DOB_TEXT.find(card.text())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        return Findings(
            name = name,
            address = card.selectFirst(ADDRESS_SELECTORS)?.text()?.trim()?.takeIf { it.isNotBlank() },
            phone = card.selectFirst(PHONE_SELECTORS)?.text()?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
            middleName = middleFrom(name),
            dob = dob?.let(::pinAge)
        )
    }

    /**
     * The same person listed twice (on one page, or by two searches) has one key: first and last
     * name (a listing may drop the middle), birth year and address.
     */
    private fun personKey(f: Findings): String = listOf(
        f.name?.let { n ->
            NameValidator.tokenize(StringSimilarity.foldDiacritics(n).lowercase())
                .let { t -> if (t.size >= 2) "${t.first()} ${t.last()}" else t.joinToString(" ") }
        },
        f.dob?.let { Regex("(19|20)\\d{2}").find(it)?.value },
        f.address?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
    ).joinToString("|") { it.orEmpty() }

    /**
     * What the user picks from after a run of lookups: every person [found] for [target] across
     * its searches, one entry per person however many searches turned them up, best fit first,
     * each with what fits the contact and what doesn't. Nothing is decided here; the user decides.
     */
    fun options(target: Target, found: List<Pair<BrowserMission, Findings>>): List<LookupOption> {
        val realName = NameValidator.isVerifiable(target.displayName)
        val tokens = if (realName) NameValidator.tokenize(target.displayName) else emptyList()
        val knownBirth = target.dateOfBirth?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        return found.groupBy { personKey(it.second) }.values.map { group ->
            val f = group.first().second
            val by = group.map { modeLabel(it.first) }.distinct()
            val fits = mutableListOf<String>()
            val differs = mutableListOf<String>()
            var score = 0
            if (realName && f.name != null) {
                if (nameConsistent(tokens, f.name)) { fits += "Same name"; score += 2 }
                else { differs += "A different name"; score -= 3 }
            }
            val phone = StringSimilarity.normalizePhone(f.phone)
            when {
                phone != null && phone == StringSimilarity.normalizePhone(target.usablePhone) -> { fits += "Same phone number"; score += 3 }
                StringSimilarity.nanpAreaCode(f.phone)?.let { it == target.areaCode } == true -> { fits += "Same area code"; score += 1 }
            }
            if (target.residenceInfo != null && corroboratesPlace(target, f)) { fits += "Same city or ZIP"; score += 1 }
            val birth = f.dob?.let { Regex("(19|20)\\d{2}").find(it)?.value }
            if (knownBirth != null && birth != null) {
                val gap = kotlin.math.abs(knownBirth.toInt() - birth.toInt())
                if (gap <= 1) { fits += "Same age"; score += 2 } else { differs += "A different age"; score -= 2 }
            }
            if ("phone" in by || "email" in by) {
                fits += "Came up for their ${by.filter { it == "phone" || it == "email" }.joinToString(" and ")}"
                score += 2
            }
            LookupOption(f.name, f.middleName, f.dob, f.address, f.phone, by, fits, differs) to score
        }.sortedByDescending { it.second }.map { it.first }.take(MAX_PEOPLE)
    }

    /**
     * The contact with the details of the person the user picked: gaps filled, nothing the
     * contact already has overwritten (as [merge]), and a note of where the details came from.
     */
    fun adopt(target: Target, option: LookupOption): Target = merge(
        target,
        Findings(option.name, option.address, option.phone, option.middleName, option.dob)
    ).copy(enrichmentProvenance = "${option.foundBy.joinToString("+")} · $CHOSEN_PREFIX ${option.name ?: "a result"}")

    /** The note left when the user said none of the people found is this contact. */
    fun noneOfThem(count: Int): String = "$NONE_PREFIX $count result${if (count == 1) "" else "s"}"

    /**
     * One line for the diagnostic log: what [parseFindings] got from a page, or, when it got
     * nothing, enough of the page's shape (headings, likely class names) to fix the selectors
     * against real markup. Records which fields were found, never their values, and leaves out
     * the page title because people-search titles carry the person's name.
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
        val hasTitle = doc.title().isNotBlank()
        val headings = doc.select("h1, h2").take(3).joinToString(" | ") { it.tagName() + "." + it.className() }
        val classes = doc.select("[class]")
            .flatMap { it.classNames() }
            .filter { CLASS_HINT.containsMatchIn(it) }
            .distinct()
            .take(30)
        val ldJson = doc.select("script[type=application/ld+json]").size
        return "nothing found · title=${if (hasTitle) "yes" else "no"} · headings=[$headings] · ld+json=$ldJson · classes=$classes"
    }

    /** Every schema.org Person on the page that carries at least one usable field, JSON-LD first. */
    private fun structuredPeople(doc: org.jsoup.nodes.Document): List<Findings> {
        val people = mutableListOf<Findings>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val element = try {
                com.google.gson.JsonParser.parseString(script.data())
            } catch (e: Exception) {
                continue
            }
            collectPeople(element, depth = 0, out = people)
        }
        for (item in doc.select("[itemtype*=schema.org/Person]")) {
            fun prop(name: String) = item.selectFirst("[itemprop=$name]")?.let {
                it.attr("content").ifBlank { it.text() }
            }?.trim()?.takeIf { it.isNotBlank() }
            Findings(
                name = prop("name"),
                address = prop("address"),
                phone = prop("telephone")?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
                dob = prop("birthDate")
            ).takeIf { it.hasAnything() }?.let { people += it }
        }
        return people
    }

    private fun Findings.hasAnything() = name != null || address != null || phone != null || dob != null

    private fun isPersonType(v: com.google.gson.JsonElement): Boolean =
        v.isJsonPrimitive && v.asString.let { it == "Person" || it.endsWith("schema.org/Person") }

    /** Depth-capped walk: page-supplied JSON must not be able to exhaust the stack. */
    private fun collectPeople(e: com.google.gson.JsonElement, depth: Int, out: MutableList<Findings>) {
        if (depth > MAX_JSON_DEPTH) return
        if (e.isJsonArray) {
            e.asJsonArray.forEach { collectPeople(it, depth + 1, out) }
            return
        }
        if (!e.isJsonObject) return
        val o = e.asJsonObject
        o.get("@graph")?.let { collectPeople(it, depth + 1, out) }
        val type = o.get("@type") ?: return
        val isPerson = if (type.isJsonArray) type.asJsonArray.any(::isPersonType) else isPersonType(type)
        if (!isPerson) return

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
        Findings(
            name = str("name"),
            address = address,
            phone = str("telephone")?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
            dob = str("birthDate")
        ).takeIf { it.hasAnything() }?.let { out += it }
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
    fun enrich(
        target: Target,
        results: List<Pair<BrowserMission, Findings>>,
        foundNobody: List<BrowserMission> = emptyList()
    ): EnrichmentOutcome {
        if (results.isEmpty()) {
            // A search that said it found nobody is noted as such: it ran, and there is no one.
            val prov = if (foundNobody.isNotEmpty()) {
                "nobody found by " + foundNobody.map(::modeLabel).distinct().joinToString(", ")
            } else {
                "no results parsed"
            }
            return EnrichmentOutcome(target.copy(enrichmentProvenance = prov), verified = false, provenance = prov)
        }

        // A card naming someone else (a recycled number's new owner) is set aside before the
        // consensus, so it can neither verify the merge nor contribute a field to it.
        val (usable, strangers) = if (NameValidator.isVerifiable(target.displayName)) {
            val tokens = NameValidator.tokenize(target.displayName)
            results.partition { (_, f) -> f.name == null || nameConsistent(tokens, f.name) }
        } else {
            results to emptyList()
        }
        if (usable.isEmpty()) {
            val prov = results.map { modeLabel(it.first) }.distinct().joinToString("+") +
                " · NOT merged (name conflict: card '${strangers.first().second.name}' ≠ ${target.displayName})"
            DiagnosticLogger.log("CYBG enrich(rejected): ${target.displayName} [$prov]")
            return EnrichmentOutcome(target.copy(enrichmentProvenance = prov), verified = false, provenance = prov)
        }
        val consensus = consensusFindings(usable)
        val modes = usable.map { modeLabel(it.first) }.distinct().joinToString("+")
        val (verified, rawBasis) = verifySamePerson(target, usable, consensus)
        val basis = if (strangers.isEmpty()) rawBasis else "$rawBasis; ignored ${strangers.size} card(s) naming someone else"

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
            // A name search returns whoever the site ranks first among everyone with that name,
            // so a name-only card needs something else of the contact's on it as well.
            val nameSearchOnly = results.all { it.first is BrowserMission.CbcByName }
            return when {
                conflicting.isNotEmpty() ->
                    false to "name conflict: card '${conflicting.first()}' ≠ ${target.displayName}"
                consistent.isNotEmpty() && !nameSearchOnly -> true to "name match: ${consistent.first()}"
                consistent.isNotEmpty() && results.any { corroborates(target, it.second) } ->
                    true to "name match: ${consistent.first()}, plus a matching phone or place"
                consistent.isNotEmpty() ->
                    false to "name-only card; nothing else on it matches ${target.displayName}"
                candidateNames.isEmpty() && keyedByUniqueId ->
                    true to "phone/email lookup, card carried no conflicting name"
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
     * Whether a name-search card carries something of [target]'s beyond the name: the same
     * phone number, the same area code, or the city/ZIP the contact's address names.
     */
    private fun corroborates(target: Target, f: Findings): Boolean {
        val phone = StringSimilarity.normalizePhone(f.phone)
        if (phone != null && phone == StringSimilarity.normalizePhone(target.usablePhone)) return true
        val cardArea = StringSimilarity.nanpAreaCode(f.phone)
        if (cardArea != null && (cardArea == target.areaCode || cardArea == StringSimilarity.nanpAreaCode(target.phoneNumber))) return true
        return corroboratesPlace(target, f)
    }

    /** Whether [f]'s address names the city or ZIP the contact's address names. */
    private fun corroboratesPlace(target: Target, f: Findings): Boolean {
        val address = f.address?.lowercase() ?: return false
        val known = target.residenceInfo?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.length >= 3 }.orEmpty()
        return known.any { part -> part.none(Char::isDigit) && Regex("\\b${Regex.escape(part)}\\b").containsMatchIn(address) } ||
            Regex("\\b\\d{5}\\b").findAll(target.residenceInfo.orEmpty()).any { it.value in address }
    }

    /**
     * Two names are consistent if they share the same last name and a compatible first name,
     * case- and accent-insensitively. First names match when they are equal, or when one side
     * is a single-letter initial sharing that letter ("J" / "John").
     *
     * A short form also matches its long form when the long one adds three or more letters
     * ("Jon" / "Jonathan", "Ron" / "Ronald"), but not one or two ("Eric" / "Erica",
     * "Don" / "Donna" are different people). Never a bare first-initial match between two
     * full names: "James Smith" and "John Smith"
     * share the initial and the surname but are different people, and merging them would
     * defeat the whole point of verify-before-merge.
     */
    private fun nameConsistent(targetTokens: List<String>, candidate: String): Boolean {
        val cand = NameValidator.tokenize(naturalOrder(candidate))
        if (targetTokens.size < 2 || cand.size < 2) return false
        fun norm(s: String) = StringSimilarity.foldDiacritics(s).lowercase()
        val tFirst = norm(targetTokens.first())
        val tLast = norm(targetTokens.last())
        val cFirst = norm(cand.first())
        val cLast = norm(cand.last())
        val lastMatch = tLast == cLast
        val firstMatch = tFirst == cFirst ||
            longForm(tFirst, cFirst) || longForm(cFirst, tFirst) ||
            ((tFirst.length == 1 || cFirst.length == 1) && tFirst.firstOrNull() == cFirst.firstOrNull())
        return lastMatch && firstMatch
    }

    private fun longForm(short: String, long: String): Boolean =
        short.length >= 3 && long.length - short.length >= 3 && long.startsWith(short)

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
        val area = StringSimilarity.nanpAreaCode(found)
        return if (target.phoneIsStale && adoptPhone(target, found) != target.phoneNumber) area
        else target.areaCode ?: area
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
    fun looksLikeBlock(html: String): Boolean = BotWall.suspected(html)

    /** Whether a lookup page says it found nobody: a finished search with nothing in it. */
    fun foundNobody(html: String): Boolean = BotWall.saysNoResults(html)

    /**
     * A bare age ("45", "Age 45") is true only on the day it was read; stored as-is it would be
     * taken as the current age for years. Pin it to a birth year ("~1981") instead. Dates and
     * years pass through unchanged.
     */
    internal fun pinAge(dob: String, currentYear: Int = java.time.Year.now().value): String {
        if (Regex("(19|20)\\d{2}").containsMatchIn(dob)) return dob
        val age = Regex("\\b(\\d{1,3})\\b").find(dob)?.groupValues?.get(1)?.toIntOrNull() ?: return dob
        return if (age in 1..120) "~${currentYear - age}" else dob
    }

    data class Findings(
        val name: String?,
        val address: String?,
        val phone: String?,
        val middleName: String? = null,
        val dob: String? = null
    )

    companion object {
        /** Starts the note on a contact whose details the user picked from a lookup. */
        const val CHOSEN_PREFIX = "chosen by you:"
        /** Starts the note on a contact the user said none of the lookup's people is. */
        const val NONE_PREFIX = "none was them, of"

        // Class selectors are guesses at CBC's markup and drift with redesigns; the structured-
        // data fallback and [describeParse]'s log line exist for when they miss.
        private const val NAME_SELECTORS =
            ".name, h1.full-name, .full-name, .person-name, [itemprop=name]"
        private const val ADDRESS_SELECTORS =
            ".address, .current-address, .address-current, .person-address, .city-state, [itemprop=address]"
        private const val PHONE_SELECTORS =
            ".phone, .phone-number, [itemprop=telephone], [href^=tel]"
        private const val DOB_SELECTORS =
            ".dob, .date-of-birth, .born, .age, [data-dob], [itemprop=birthDate]"
        private const val MAX_JSON_DEPTH = 8
        private const val CARD_SELECTORS = ".person-card, .result-card, .search-result, .card-person, [data-result-card]"
        /** More than this many people for one contact is a list no one can choose from. */
        const val MAX_PEOPLE = 10

        /**
         * "Age 45", "DOB 03/14/1961", "Born 1961": whole words only ("Page 1", "Usage 5" are
         * not ages), and an age must be adult-plausible so a stray digit isn't stored as one.
         */
        private val DOB_TEXT = Regex(
            "\\b(?:Age|DOB|Born)\\b[:\\s]+(\\d{1,2}/\\d{1,2}/\\d{4}|(?:19|20)\\d{2}|(?:1[89]|[2-9]\\d|1[01]\\d)\\b)",
            RegexOption.IGNORE_CASE
        )
        private val CLASS_HINT = Regex("name|addr|phone|age|dob|birth|person|result|card|resident", RegexOption.IGNORE_CASE)

    }
}
