package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.isStalePhone
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.LookupOption
import com.hereliesaz.cleanunderwear.domain.PooledPerson
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
            ?.takeIf(::isSearchableEmail)
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

        val email = target.email?.takeIf(::isSearchableEmail)
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

        val address = (root.selectFirst("a.address") ?: root.selectFirst(ADDRESS_SELECTORS))
            ?.text()?.let(::cleanAddress)?.takeIf { it.isNotBlank() }

        val phone = root
            .selectFirst(PHONE_SELECTORS)
            ?.text()?.trim()
            ?.filter { it.isDigit() || it == '+' }
            ?.takeIf { it.length >= 10 }

        // DOB / age — a dedicated field if present, else "Age N" / "DOB ..." text.
        // The age slot reads "Deceased" for someone the site lists as dead: that is no age.
        val dob = root
            .selectFirst(DOB_SELECTORS)
            ?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals("deceased", ignoreCase = true) }
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
        if (cards.isNotEmpty()) return cards.mapNotNull(::readCard).distinctBy(::personKey).take(MAX_POOL)
        val structured = structuredPeople(doc).mapNotNull { p ->
            val name = p.name?.let(::naturalOrder) ?: return@mapNotNull null
            p.copy(name = name, middleName = middleFrom(name), dob = p.dob?.let(::pinAge))
        }
        if (structured.size > 1) return structured.distinctBy(::personKey).take(MAX_POOL)
        return listOfNotNull(parseFindings(html)?.takeIf { it.name != null })
    }

    private fun readCard(card: Element): Findings? {
        val name = (card.selectFirst(NAME_SELECTORS) ?: card.selectFirst("h1, h2, h3, h4"))
            ?.text()?.trim()?.takeIf { it.isNotBlank() }?.let(::naturalOrder) ?: return null
        // The age slot reads "Deceased" for someone the site lists as dead: that is no age, and
        // it is what the user most needs to see about this person.
        val ageText = card.selectFirst(DOB_SELECTORS)?.text()?.trim()?.takeIf { it.isNotBlank() }
        val deceased = ageText.equals("deceased", ignoreCase = true)
        val dob = (if (deceased) null else ageText)
            ?: DOB_TEXT.find(card.text())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        return Findings(
            name = name,
            address = (card.selectFirst("a.address") ?: card.selectFirst(ADDRESS_SELECTORS))
                ?.text()?.let(::cleanAddress)?.takeIf { it.isNotBlank() },
            phone = card.selectFirst(PHONE_SELECTORS)?.text()?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 10 },
            middleName = middleFrom(name),
            dob = dob?.let(::pinAge),
            deceased = deceased,
            // The person's own link, never a relative's (their links are `/detail/` too).
            detailUrl = card.selectFirst("a.name-link[href], a.btn[href*=\"/detail/\"]")?.attr("href")?.let(::safeDetailUrl)
        )
    }

    /**
     * Reads one person's own page: all the phones, emails and addresses it lists, relatives, and
     * the age. The page is one long card of sections: the current address and the address history
     * are `a.address-current` (the full address, ZIP+4 included, over two `span.d-block` lines;
     * the `a.address` links further down are short teasers without a ZIP), phones are `a.phone`
     * (each shown twice), relatives `a.relative`, and the email section is a `.section-label`
     * reading "Email Addresses" over its entries, or "None found". Ad widgets and page chrome are
     * taken out first. Null when the page gave none of it. [describeDetail] says which parts were
     * found, for the log.
     */
    fun parseDetail(html: String): PersonDetails? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, nav, footer, aside, .wam-pro-max-widget").remove()
        val body = doc.body() ?: return null

        val phones = body.select("a.phone, .phone, [itemprop=telephone], a[href^=tel]")
            .map { it.text().filter { c -> c.isDigit() } }
            .filter { it.length in 10..11 }
            .distinct().take(MAX_DETAIL_ITEMS)
        // Emails: mail links, and whatever the "Email Addresses" section lists (it says "None found"
        // when there are none). A masked address ("j***@x.com") is not matched.
        val fromLinks = body.select("a[href^=mailto]").map { it.attr("href").removePrefix("mailto:") }
        val fromSection = body.select(".section-label")
            .filter { it.text().contains("email", ignoreCase = true) }
            .flatMap { label -> EMAIL_IN_TEXT.findAll(label.parent()?.text().orEmpty()).map { it.value }.toList() }
        val emails = (fromLinks + fromSection)
            .mapNotNull { EMAIL_IN_TEXT.find(it)?.value?.lowercase() }
            .distinct().take(MAX_DETAIL_ITEMS)
        // Addresses: the page's own, in order (the current one first). Written the way a result
        // card writes one, "street, city, ST zip".
        val full = body.select("a.address-current").map { a ->
            val lines = a.select("span.d-block").map { it.text().trim() }.filter { it.isNotBlank() }
            cleanAddress(if (lines.size >= 2) lines.joinToString(", ") else a.text())
        }
        val addresses = full.ifEmpty { body.select("a.address").map { cleanAddress(it.text()) } }
            .filter { it.isNotBlank() }
            .distinct().take(MAX_DETAIL_ITEMS)
        val relatives = body.select("a.relative")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct().take(MAX_DETAIL_ITEMS)
        val ageText = body.selectFirst(DOB_SELECTORS)?.text()?.trim()?.takeIf { it.isNotBlank() }
        val deceased = ageText.equals("deceased", ignoreCase = true)
        val dob = (if (deceased) null else ageText)
            ?: DOB_TEXT.find(body.text())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val details = PersonDetails(phones, emails, addresses, relatives, dob?.let(::pinAge), deceased)
        return details.takeIf { phones.isNotEmpty() || emails.isNotEmpty() || addresses.isNotEmpty() || relatives.isNotEmpty() || it.dob != null || deceased }
    }

    /** For the log: which parts a person's page gave, never their values. */
    fun describeDetail(html: String, details: PersonDetails?): String =
        if (details != null) {
            "phones=${details.phones.size} emails=${details.emails.size} addresses=${details.addresses.size} " +
                "relatives=${details.relatives.size} age/dob=${if (details.dob != null || details.deceased) "✓" else "—"}"
        } else {
            val doc = Jsoup.parse(html)
            "nothing read; classes on the page: " + doc.select("[class]").flatMap { it.classNames() }
                .filter { CLASS_HINT.containsMatchIn(it) }.distinct().take(25).joinToString(" ")
        }

    /**
     * The next page of a results list, from the pager under it ("« ‹ › »  Displaying page 1 of 6"),
     * as an address the browser may open; null on the last page, on a list of one page, and when
     * the pager's markup isn't recognised ([describePager] says which, for the log). [pageUrl] is
     * the address of the page being read, which a relative link is made against.
     */
    fun nextListPage(html: String, pageUrl: String? = null): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("a[rel=next][href], link[rel=next][href]")?.attr("href")
            ?.let { safeResultsUrl(it, pageUrl) }?.let { return it }
        val pager = findPager(doc) ?: return null
        if (pager.page >= pager.pages) return null
        val anchors = pager.anchors
        val labelled = anchors.firstOrNull { a ->
            val label = (a.attr("aria-label") + " " + a.attr("title") + " " + a.text()).trim()
            label.contains("next", ignoreCase = true) || a.text().trim() == "\u203A" || a.text().trim() == ">" ||
                a.selectFirst(NEXT_ICON) != null
        }
        // Four controls (first, previous, next, last), or two on page 1 (next, last): by position.
        val next = labelled ?: when {
            anchors.size == 4 -> anchors[2]
            anchors.size == 2 && pager.page == 1 -> anchors[0]
            else -> null
        }
        return next?.attr("href")?.let { safeResultsUrl(it, pageUrl) }
    }

    /** For the log: what the pager looked like, never the page's content. */
    fun describePager(html: String, pageUrl: String? = null): String {
        val doc = Jsoup.parse(html)
        val pager = findPager(doc) ?: return "no pager found"
        val next = nextListPage(html, pageUrl)
        return "pager: page ${pager.page} of ${pager.pages}, ${pager.anchors.size} links, next=${if (next != null) "✓" else "—"}"
    }

    private class Pager(val page: Int, val pages: Int, val anchors: List<Element>)

    /**
     * The element that says "Displaying page X of Y" and the links around it. The numbers may sit
     * in their own tags (`Displaying page <strong>2</strong> of <strong>25</strong>`), so the
     * text is read whole and the innermost element that holds all of it is the label. The link
     * the label itself sits in is not one of the pager's controls.
     */
    private fun findPager(doc: org.jsoup.nodes.Document): Pager? {
        val label = doc.getAllElements().lastOrNull { el ->
            PAGE_OF.containsMatchIn(el.text()) && el.children().none { PAGE_OF.containsMatchIn(it.text()) }
        } ?: return null
        val m = PAGE_OF.find(label.text()) ?: return null
        val own = label.closest("a")
        fun controls(from: Element) = from.select("a[href]").filter { it !== own }
        var scope: Element? = label
        while (scope != null && controls(scope).isEmpty()) scope = scope.parent()
        val anchors = scope?.let { controls(it) }.orEmpty()
        return Pager(m.groupValues[1].toInt(), m.groupValues[2].toInt(), anchors)
    }

    /**
     * A link from a pager, as an address the browser may open: this site's own search-result
     * pages (people, phone, email, address) over https, never a person's page or another site. A
     * query is kept (the page number is usually in it) if it is made of plain characters.
     */
    internal fun safeResultsUrl(href: String, pageUrl: String? = null): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        val absolute = when {
            trimmed.startsWith("?") && pageUrl != null -> pageUrl.substringBefore('?').substringBefore('#') + trimmed
            trimmed.startsWith("/") && !trimmed.startsWith("//") -> "https://www.cyberbackgroundchecks.com$trimmed"
            else -> trimmed
        }
        return try {
            val uri = java.net.URI(absolute)
            if (uri.scheme != "https" || !uri.host.equals("www.cyberbackgroundchecks.com", ignoreCase = true)) return null
            val path = uri.rawPath ?: return null
            if (RESULT_PATHS.none { path.startsWith(it) }) return null
            val query = uri.rawQuery
            if (query != null && (query.length > 200 || !SAFE_QUERY.matches(query))) return null
            "https://www.cyberbackgroundchecks.com$path" + (if (query != null) "?$query" else "")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A link from a result card to a person's page, as an address the browser may open: only this
     * site's own `/detail/` pages, over https, with no query or fragment. A card's link is page
     * content, so anything else (an ad, another site) is not followed.
     */
    internal fun safeDetailUrl(href: String): String? {
        val absolute = if (href.startsWith("/")) "https://www.cyberbackgroundchecks.com$href" else href
        return try {
            val uri = java.net.URI(absolute.trim())
            if (uri.scheme != "https" || !uri.host.equals("www.cyberbackgroundchecks.com", ignoreCase = true)) return null
            val path = uri.rawPath ?: return null
            if (!path.startsWith("/detail/") || path.length <= "/detail/".length) return null
            "https://www.cyberbackgroundchecks.com$path"
        } catch (e: Exception) {
            null
        }
    }

    /** An address without the county note the site puts after it ("[Harrison County]"). */
    private fun cleanAddress(text: String): String = text.replace(Regex("\\s*\\[[^\\]]*]\\s*$"), "").trim()

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
     * One person the searches found: what the list showed of them ([findings]) and which searches
     * turned them up ([by], "phone" / "email" / "address" / "name").
     */
    data class Candidate(val findings: Findings, val by: List<String>)

    /** A candidate with the option it makes and how well it fits; see [rank]. */
    data class Ranked(val candidate: Candidate, val option: LookupOption, val score: Int)

    /**
     * Everyone [found] across a contact's searches as candidates, one per person however many
     * searches turned them up.
     */
    fun candidates(found: List<Pair<BrowserMission, Findings>>): List<Candidate> =
        found.groupBy { personKey(it.second) }.values.map { group ->
            val first = group.first().second
            Candidate(
                first.copy(
                    detailUrl = group.firstNotNullOfOrNull { it.second.detailUrl },
                    deceased = group.any { it.second.deceased }
                ),
                group.map { modeLabel(it.first) }.distinct()
            )
        }

    /**
     * What identifies a person for good: their own page's address when the list gave it, else the
     * name / birth year / address key. Held for everyone shown or kept, so no one is offered twice.
     */
    fun keyOf(f: Findings): String = f.detailUrl ?: personKey(f)

    /**
     * The people on a further page of a results list ([people], from the search [by]) that have not
     * been offered or held yet: the rule that no one is shown twice. Their keys are added to [seen].
     */
    fun unseen(people: List<Findings>, by: String, seen: MutableSet<String>): List<Candidate> =
        people.map { Candidate(it, listOf(by)) }.filter { seen.add(keyOf(it.findings)) }

    /**
     * What of the contact decides whether a person is them: its number, email, address, birth
     * date, middle name and name. Held with the results the user passed over; when it changes,
     * those results are looked at again ([requalified]).
     */
    fun fingerprint(t: Target): String = listOf(
        StringSimilarity.normalizePhone(t.usablePhone),
        t.email?.trim()?.lowercase(),
        t.residenceInfo?.lowercase()?.replace(Regex("\\s+"), " ")?.trim(),
        t.dateOfBirth?.let { Regex("(19|20)\\d{2}").find(it)?.value ?: it },
        t.middleName?.trim()?.lowercase(),
        t.displayName.trim().lowercase()
    ).joinToString("|") { it.orEmpty() }

    /** A person passed over, as a candidate to be scored again. */
    private fun candidateOf(o: LookupOption): Candidate = Candidate(
        Findings(o.name, o.address, o.phone, o.middleName, o.dob, o.deceased, o.detailUrl), o.foundBy
    )

    /** What the person's own page gave when it was read, rebuilt from the option that kept it. */
    private fun detailsOf(o: LookupOption): PersonDetails? =
        if (o.phones == null && o.emails == null && o.otherAddresses == null && o.relatives == null) null
        else PersonDetails(
            phones = o.phones.orEmpty(), emails = o.emails.orEmpty(), addresses = o.otherAddresses.orEmpty(),
            relatives = o.relatives.orEmpty(), dob = o.dob, deceased = o.deceased
        )

    /**
     * Of the people [ruledOut] (shown, and passed over), those that new details about [target]
     * make fit: scored against the contact as it is now, each gained a strong reason it lacked
     * when it was passed over (its phone number or email, its age, its city or ZIP), and has no
     * different name or age against it. Best fit first, with the fresh reasons. Nothing that fit
     * no better than before comes back, so the same people are not suggested again for nothing.
     */
    fun requalified(target: Target, ruledOut: List<LookupOption>): List<LookupOption> {
        if (ruledOut.isEmpty()) return emptyList()
        val details = ruledOut.mapNotNull { o -> o.detailUrl?.let { url -> detailsOf(o)?.let { url to it } } }.toMap()
        val before = ruledOut.associateBy { keyOf(candidateOf(it).findings) }
        return rank(target, ruledOut.map(::candidateOf), details).filter { r ->
            val old = before[keyOf(r.candidate.findings)]?.fits.orEmpty().toSet()
            r.option.fits.any { it !in old && it in STRONG_FITS } &&
                r.option.differs.none { it == "A different name" || it == "A different age" }
        }.map { it.option }
    }

    /** A candidate as it is held in the pool. */
    fun pooled(c: Candidate): PooledPerson = c.findings.let {
        PooledPerson(it.name, it.middleName, it.dob, it.address, it.phone, it.deceased, it.detailUrl, c.by)
    }

    /** A held person as a candidate again. */
    fun candidate(p: PooledPerson): Candidate =
        Candidate(Findings(p.name, p.address, p.phone, p.middleName, p.dob, p.deceased, p.detailUrl), p.foundBy)

    /**
     * What the user picks from after a run of lookups: every person [found] for [target] across
     * its searches, one entry per person however many searches turned them up, best fit first,
     * each with what fits the contact and what doesn't. Nothing is decided here; the user decides.
     */
    fun options(
        target: Target,
        found: List<Pair<BrowserMission, Findings>>,
        details: Map<String, PersonDetails> = emptyMap(),
    ): List<LookupOption> = rank(target, candidates(found), details).map { it.option }.take(MAX_PEOPLE)

    /**
     * Every candidate against [target], best fit first, uncapped: the caller takes a batch to put
     * to the user and holds the rest. [details] (keyed by page address) are what each person's own
     * page added, for those whose page was read.
     */
    fun rank(
        target: Target,
        candidates: List<Candidate>,
        details: Map<String, PersonDetails> = emptyMap(),
    ): List<Ranked> {
        val realName = NameValidator.isVerifiable(target.displayName)
        val tokens = if (realName) NameValidator.tokenize(target.displayName) else emptyList()
        val knownBirth = target.dateOfBirth?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        return candidates.map { c ->
            val f = c.findings
            val url = f.detailUrl
            // What the person's own page added, when it was read: every phone, email and address
            // on file takes part in the match, not just the one the list showed.
            val d = url?.let(details::get)
            val by = c.by
            val fits = mutableListOf<String>()
            val differs = mutableListOf<String>()
            var score = 0
            if (realName && f.name != null) {
                if (nameConsistent(tokens, f.name)) { fits += "Same name"; score += 2 }
                else { differs += "A different name"; score -= 3 }
            }
            val phones = listOfNotNull(f.phone) + d?.phones.orEmpty()
            val knownPhone = StringSimilarity.normalizePhone(target.usablePhone)
            when {
                knownPhone != null && phones.any { StringSimilarity.normalizePhone(it) == knownPhone } -> { fits += "Same phone number"; score += 3 }
                target.areaCode != null && phones.any { StringSimilarity.nanpAreaCode(it) == target.areaCode } -> { fits += "Same area code"; score += 1 }
            }
            val knownEmail = target.email?.trim()?.lowercase()
            if (knownEmail != null && d?.emails.orEmpty().any { it == knownEmail }) { fits += "Same email address"; score += 3 }
            val addresses = listOfNotNull(f.address) + d?.addresses.orEmpty()
            if (target.residenceInfo != null && corroboratesPlace(target, addresses)) { fits += "Same city or ZIP"; score += 1 }
            val dobText = d?.dob ?: f.dob
            val birth = dobText?.let { Regex("(19|20)\\d{2}").find(it)?.value }
            if (knownBirth != null && birth != null) {
                val gap = kotlin.math.abs(knownBirth.toInt() - birth.toInt())
                if (gap <= 1) { fits += "Same age"; score += 2 } else { differs += "A different age"; score -= 2 }
            }
            if ("phone" in by || "email" in by) {
                fits += "Came up for their ${by.filter { it == "phone" || it == "email" }.joinToString(" and ")}"
                score += 2
            }
            Ranked(
                c,
                LookupOption(
                    name = f.name, middleName = f.middleName, dob = dobText, address = f.address, phone = f.phone,
                    foundBy = by, fits = fits, differs = differs,
                    deceased = f.deceased || d?.deceased == true,
                    detailUrl = url,
                    phones = d?.phones, emails = d?.emails,
                    otherAddresses = d?.addresses?.filter { it != f.address },
                    relatives = d?.relatives
                ),
                score
            )
        }.sortedByDescending { it.score }
    }

    /**
     * The contact with the details of the person the user picked: gaps filled, nothing the
     * contact already has overwritten (as [merge]), and a note of where the details came from.
     */
    fun adopt(target: Target, option: LookupOption): Target {
        val merged = merge(
            target,
            Findings(option.name, option.address, option.phone ?: option.phones?.firstOrNull(), option.middleName, option.dob)
        )
        return merged.copy(
            // An email only fills a gap, and only a whole one (a page may show it masked).
            email = merged.email ?: option.emails.orEmpty().firstOrNull { '*' !in it && isSearchableEmail(it) },
            enrichmentProvenance = "${option.foundBy.joinToString("+")} · $CHOSEN_PREFIX ${option.name ?: "a result"}" +
                if (option.deceased) " · listed as deceased" else ""
        )
    }

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

    /** Which search a mission is, for the choices ("phone", "email", "address", "name"). */
    fun modeLabel(m: BrowserMission): String = when (m) {
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
        return corroboratesPlace(target, listOfNotNull(f.address))
    }

    /** Whether [f]'s address names the city or ZIP the contact's address names. */
    private fun corroboratesPlace(target: Target, addresses: List<String>): Boolean {
        val address = addresses.joinToString(" | ") { it.lowercase() }.takeIf { it.isNotBlank() } ?: return false
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
    fun looksLikeBlock(html: String): Boolean {
        // Said in the activity log: a wrong call pauses the lookup, and the reason is what
        // tells a real CAPTCHA from a page that merely carries one.
        val reason = BotWall.reason(html) ?: return false
        DiagnosticLogger.log("CYBG: page looks blocked ($reason)", DiagnosticLogger.LogEntry.LogLevel.WARN)
        return true
    }

    /**
     * Whether the site has shut this request out (Cloudflare's block page, a rate limit): a page
     * with nothing on it for the user to complete. The lookup moves on at once, and the run
     * backs off, rather than waiting for someone who has nothing to do.
     */
    fun isBarred(html: String): Boolean {
        if (BotWall.wallOf(html) != BotWall.Wall.BARRED) return false
        DiagnosticLogger.log("CYBG: the site has blocked this request (${BotWall.reason(html)})", DiagnosticLogger.LogEntry.LogLevel.WARN)
        return true
    }

    /**
     * Whether [email] is worth searching: something before and after one `@`, and a dot in the
     * domain. A bare "@" or "name@" in a contact's email field used to start a search that could
     * only come back empty.
     */
    internal fun isSearchableEmail(email: String): Boolean =
        EMAIL_SHAPE.matches(email.trim())

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
        val dob: String? = null,
        /** The site lists this person as deceased. */
        val deceased: Boolean = false,
        /** The address of this person's own page on the site, when the results list links to it. */
        val detailUrl: String? = null
    )

    /**
     * What one person's own page adds to the teaser a results list shows: every phone, email and
     * address on file, relatives, and the age or birth date. Held with the choices until the user
     * says which person is their contact, then dropped.
     */
    data class PersonDetails(
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val addresses: List<String> = emptyList(),
        val relatives: List<String> = emptyList(),
        val dob: String? = null,
        val deceased: Boolean = false
    )

    companion object {
        /** Something, one `@`, and a domain with at least one dot. */
        private val EMAIL_SHAPE = Regex("""[^@\s]+@[^@\s.]+(\.[^@\s.]+)+""")

        /** An email address inside some text; one the page masks ("j***@x.com") is not matched. */
        private val EMAIL_IN_TEXT = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

        /** Most of any one list (phones, addresses, relatives) kept from a person's page. */
        private const val MAX_DETAIL_ITEMS = 12

        /** Most people's own pages opened, and put to the user, in one batch: best fit first. */
        const val MAX_DETAIL_PAGES = 8

        /** Most people held for one contact across all its searches; past this the list is no one's to choose from. */
        const val MAX_POOL = 100

        /** The reasons that, newly true of a person passed over, bring them back. */
        private val STRONG_FITS = setOf("Same phone number", "Same email address", "Same age", "Same city or ZIP")

        private val PAGE_OF = Regex("""Displaying page\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
        private const val NEXT_ICON = ".fa-angle-right, .fa-chevron-right, .fa-caret-right, .fa-arrow-right, .fa-chevron-circle-right"
        private val RESULT_PATHS = listOf("/people/", "/phone/", "/email/", "/address/")
        private val SAFE_QUERY = Regex("""[A-Za-z0-9=&_%.,+-]*""")

        /** Starts the note on a contact whose details the user picked from a lookup. */
        const val CHOSEN_PREFIX = "chosen by you:"
        /** Starts the note on a contact the user said none of the lookup's people is. */
        const val NONE_PREFIX = "none was them, of"

        // Class selectors are guesses at CBC's markup and drift with redesigns; the structured-
        // data fallback and [describeParse]'s log line exist for when they miss.
        private const val NAME_SELECTORS =
            ".name, .name-given, h1.full-name, .full-name, .person-name, [itemprop=name]"
        private const val ADDRESS_SELECTORS =
            ".address, .current-address, .address-current, .person-address, .city-state, [itemprop=address]"
        private const val PHONE_SELECTORS =
            ".phone, .phone-number, [itemprop=telephone], [href^=tel]"
        private const val DOB_SELECTORS =
            ".dob, .date-of-birth, .born, .age, [data-dob], [itemprop=birthDate]"
        private const val MAX_JSON_DEPTH = 8
        // `.card-hover` is how CyberBackgroundChecks marks a person's card; its ad widgets share the
        // class but have no `.name-given`, so only a card that names someone counts.
        private const val CARD_SELECTORS =
            ".person-card, .result-card, .search-result, .card-person, [data-result-card], .card-hover:has(.name-given)"
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
