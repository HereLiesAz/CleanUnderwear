package com.hereliesaz.cleanunderwear.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SourceKind {
    /** URL template accepts {first}/{last} slots; lowest false-positive risk. */
    QUERY_TEMPLATE,

    /** Static roster page; only used when the strict name match passes. */
    ROSTER_PAGE,

    /** Open-in-browser only — never used as automated evidence. */
    MANUAL_LANDING
}

enum class RenderMode { BASIC, WEBVIEW }

/**
 * How a fetched response is interpreted by the scraper.
 *  - [HTML]: server-rendered markup, verified via text-proximity name matching.
 *  - [BOP_JSON]: the Federal BOP inmate-locator JSON API, verified structurally
 *    (and custody-filtered) via [IdentityVerifier.verifyBopInmateJson].
 *  - [NEWS_RSS]: a news-search RSS feed; each item is classified as a death or
 *    arrest report and read by [NewsFeed]. One source yields both kinds.
 *  - [JSON_RECORDS]: a JSON API returning person records; fields are mapped by
 *    [Source.jsonFields] and read by [StructuredRecords].
 *  - [HTML_TABLE]: a results table; columns are mapped by header text via
 *    [Source.tableColumns] and read by [StructuredRecords].
 *  - [REGEX_RECORDS]: a non-table/non-JSON page; [Source.recordRegex] splits
 *    it into records and [Source.fieldRegexes] pull each field.
 */
enum class ResultFormat { HTML, BOP_JSON, NEWS_RSS, JSON_RECORDS, HTML_TABLE, REGEX_RECORDS }

enum class SourceScope { COUNTY, STATE, AREA_CODE, MULTI_STATE }

enum class SourceCategory { LOCKUP, OBITUARY, IDENTITY }

/**
 * One curated source from `sources.json`. Construct via [SourceCatalog].
 */
data class Source(
    val id: String,
    val label: String,
    /**
     * The one county a county jail covers ("Acadia Parish, LA"), in the same form as the
     * `counties` keys; null for a statewide or national source. It is searched for everyone in
     * its state (people are jailed away from home), but only counts as the contact's own jail,
     * and as evidence of place, in that county.
     */
    val county: String? = null,
    val kind: SourceKind,
    val method: String,
    val urlTemplate: String,
    val formFields: Map<String, String>,
    val render: RenderMode,
    val resultFormat: ResultFormat,
    val renderSettleMs: Int,
    val readySelector: String?,
    val evidenceUrlTemplate: String,
    val scope: SourceScope,
    val appliesToAreaCodes: Set<String>,
    val coversCountry: String?,
    /**
     * For [ResultFormat.JSON_RECORDS]: dot paths to the record list and to each
     * field within a record. Keys: records, first, last, middle, age, dob,
     * status, id, facility, city, state.
     */
    val jsonFields: Map<String, String> = emptyMap(),
    /**
     * For [ResultFormat.HTML_TABLE]: field key → regex matched against column
     * header text. Same keys as [jsonFields] minus records; "name" holds a
     * combined name ("LAST, FIRST MIDDLE" or "First Middle Last").
     */
    val tableColumns: Map<String, String> = emptyMap(),
    /** For [ResultFormat.REGEX_RECORDS]: splits the page into one match per record. */
    val recordRegex: String? = null,
    /** For [ResultFormat.REGEX_RECORDS]: field key → regex whose last group is the value. */
    val fieldRegexes: Map<String, String> = emptyMap(),
    /**
     * A URL to GET, with the search's cookies, after the search request, for
     * sites that store the search in the session and serve results separately.
     */
    val resultsUrl: String? = null,
    /** Force HTTP/1.1; some state servers drop HTTP/2 clients. */
    val http1: Boolean = false,
    /**
     * What a hit on this source reports, overriding the list it sits in. Court
     * case searches sit with the lockup sources but report ARREST (charged),
     * not CUSTODY.
     */
    val eventKind: com.hereliesaz.cleanunderwear.match.EventKind? = null,
    /** Regex whose group 1 extracts the person's name from the "name" field (e.g. "State v. (JOHN SMITH)"). */
    val namePattern: String? = null,
    /** The "name" field is "LAST FIRST MIDDLE" with no comma. */
    val nameLastFirst: Boolean = false,
    /** Regex (case-insensitive) a row's case_type must match, e.g. criminal case types. */
    val caseTypePattern: String? = null,
    /** Rows whose "date" field (filing/booking date) is older than this are dropped. */
    val maxAgeDays: Int? = null,
    /**
     * A page to GET before the search, for forms that need a per-session token
     * (ASP.NET __VIEWSTATE, CSRF). Its hidden inputs and cookies are sent with
     * the search request; [formFields] override them.
     */
    val tokenPage: String? = null,
    /** Form posted after [tokenPage] (a disclaimer "I accept"); the token is read from its reply. */
    val tokenForm: Map<String, String> = emptyMap(),
    /** Where [tokenForm] is posted; defaults to [tokenPage]. */
    val tokenPostUrl: String? = null,
    /** Regex (case-insensitive) on a record's text or status marking a person no longer held. */
    val releasedPattern: String? = null,
    /**
     * Regex (case-insensitive) a record's text or status must match to count as
     * in custody, for sources that list everyone and mark the held ("Secure").
     */
    val custodyPattern: String? = null,
    /** Extra request headers. */
    val headers: Map<String, String> = emptyMap(),
    /** Raw request body template (e.g. JSON) with {first}/{last}; sent instead of form fields. */
    val bodyTemplate: String? = null,
    val contentType: String? = null,
    /**
     * Appended to the search URL, with {page} filled, to fetch further result pages: an obituary
     * site's first page can span only days for a common name. Pages run from [firstExtraPage]
     * for at most [maxExtraPages], and stop at the first page with no record inside [maxAgeDays].
     */
    val pageSuffix: String? = null,
    val firstExtraPage: Int = 2,
    val maxExtraPages: Int = 0
)

/**
 * Result of resolving a contact's geography. All fields optional — fall through
 * the levels when issuing source lookups: county → state → multi-state.
 */
/**
 * What a contact is checked against: every lockup and obituary source for their locale and
 * for each state they're tied to (the phone's area code, the address). [lockupAutomated] says
 * whether any jail or prison search runs on its own; obituaries and news alone don't cover custody.
 */
data class SearchPlan(
    val lockup: List<Source>,
    val obituary: List<Source>,
    val tiedStates: List<String>,
    /** The contact's county, when their ZIP or area code names one. */
    val ownCounty: String? = null
) {
    val automated: Boolean get() = (lockup + obituary).any { it.kind != SourceKind.MANUAL_LANDING }

    /**
     * Whether a jail or prison search that covers the contact runs on its own: a statewide one,
     * or their own county's jail. Another county's jail elsewhere in the state is still searched,
     * but doesn't count: it says nothing about the jail they'd most likely be in. Nor does the
     * federal prison locator: it's searched for everyone, and federal custody is the rare case.
     */
    val lockupAutomated: Boolean
        get() = lockup.any {
            it.kind != SourceKind.MANUAL_LANDING && it.scope != SourceScope.MULTI_STATE &&
                (it.county == null || it.county == ownCounty)
        }
}

data class LocaleResolution(
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null
)

/**
 * Loads `sources.json` once and answers per-locale source lookups.
 *
 * The catalog routes most-specific-first. A contact in 504 with a 70112 ZIP
 * resolves to (city=New Orleans, county=Orleans Parish/LA, state=LA,
 * country=US), and `lockupSourcesFor` returns the parish sheriff source
 * before the state DOC source before the multi-state VINELink fallback.
 */
@Singleton
class SourceCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class RawSource(
        val id: String? = null,
        val label: String? = null,
        val county: String? = null,
        val kind: String? = null,
        val method: String? = null,
        val url_template: String? = null,
        val form_fields: Map<String, String>? = null,
        val render: String? = null,
        val result_format: String? = null,
        val render_settle_ms: Int? = null,
        val ready_selector: String? = null,
        val evidence_url_template: String? = null,
        val scope: String? = null,
        val applies_to_area_codes: List<String>? = null,
        val covers_country: String? = null,
        val json_fields: Map<String, String>? = null,
        val table_columns: Map<String, String>? = null,
        val record_regex: String? = null,
        val field_regexes: Map<String, String>? = null,
        val results_url: String? = null,
        val http1: Boolean? = null,
        val event_kind: String? = null,
        val case_type_pattern: String? = null,
        val name_pattern: String? = null,
        val name_last_first: Boolean? = null,
        val max_age_days: Int? = null,
        val token_page: String? = null,
        val token_form: Map<String, String>? = null,
        val token_post_url: String? = null,
        val released_pattern: String? = null,
        val custody_pattern: String? = null,
        val headers: Map<String, String>? = null,
        val body_template: String? = null,
        val content_type: String? = null,
        val page_suffix: String? = null,
        val first_extra_page: Int? = null,
        val max_extra_pages: Int? = null
    )

    private data class CountyEntry(
        val state: String? = null,
        val lockup_sources: List<RawSource>? = null,
        val obituary_sources: List<RawSource>? = null
    )

    private data class StateEntry(
        val country: String? = null,
        val lockup_sources: List<RawSource>? = null,
        val obituary_sources: List<RawSource>? = null
    )

    private data class AreaCodeMetro(
        val primary_county: String? = null,
        val primary_city: String? = null
    )

    private data class SourcesFile(
        val version: Int? = null,
        val area_codes: Map<String, String>? = null,
        val area_code_metros: Map<String, AreaCodeMetro>? = null,
        val zip_to_county: Map<String, String>? = null,
        val counties: Map<String, CountyEntry>? = null,
        val states: Map<String, StateEntry>? = null,
        val multi_state: List<RawSource>? = null,
        val multi_state_obituary: List<RawSource>? = null,
        val identity_sources: List<RawSource>? = null
    )

    private val areaCodeToState: Map<String, String>
    private val areaCodeToMetro: Map<String, AreaCodeMetro>
    private val zipToCounty: Map<String, String>
    private val countyData: Map<String, CountyEntry>
    private val stateData: Map<String, StateEntry>
    private val multiStateLockup: List<Source>
    private val multiStateObituary: List<Source>

    private val countyLockup: Map<String, List<Source>>
    private val countyObituary: Map<String, List<Source>>
    private val stateLockup: Map<String, List<Source>>
    private val stateObituary: Map<String, List<Source>>
    private val identitySourceList: List<Source>

    private val catalogPrefixes: List<String>

    init {
        val parsed: SourcesFile = try {
            val raw = context.assets.open("sources.json")
                .bufferedReader()
                .use { it.readText() }
            Gson().fromJson(raw, SourcesFile::class.java)
        } catch (e: JsonSyntaxException) {
            DiagnosticLogger.log(
                "SourceCatalog: malformed sources.json — ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
            SourcesFile()
        } catch (e: Exception) {
            DiagnosticLogger.log(
                "SourceCatalog: failed to load sources.json — ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
            SourcesFile()
        }

        areaCodeToState = parsed.area_codes.orEmpty()
        areaCodeToMetro = parsed.area_code_metros.orEmpty()
        zipToCounty = parsed.zip_to_county.orEmpty()
        countyData = parsed.counties.orEmpty()
        stateData = parsed.states.orEmpty()

        countyLockup = countyData.mapValues { (_, entry) ->
            entry.lockup_sources.orEmpty().mapNotNull { it.toSource() }
        }
        countyObituary = countyData.mapValues { (_, entry) ->
            entry.obituary_sources.orEmpty().mapNotNull { it.toSource() }
        }
        stateLockup = stateData.mapValues { (_, entry) ->
            entry.lockup_sources.orEmpty().mapNotNull { it.toSource() }
        }
        stateObituary = stateData.mapValues { (_, entry) ->
            entry.obituary_sources.orEmpty().mapNotNull { it.toSource() }
        }

        // multi_state list applies to lockup by default; obituary multi-state
        // is now wired from the `multi_state_obituary` JSON block. It ships
        // empty until a per-name obituary source with a deep-linkable,
        // server-rendered (non-captcha) result page is curated — adding one
        // there is all it takes to turn the DECEASED auto-path on, with no
        // code change. Operator-launch-only obit sources stay MANUAL_LANDING
        // and are filtered out of the auto scrape (see ScrapeTargetsUseCase).
        multiStateLockup = parsed.multi_state.orEmpty().mapNotNull { it.toSource() }
        multiStateObituary = parsed.multi_state_obituary.orEmpty().mapNotNull { it.toSource() }

        // identity_sources are country-agnostic identity-correlation services
        // (face recognition, reverse image search, name-based OSINT) ported
        // from doxray. They never auto-scrape — every entry is MANUAL_LANDING.
        identitySourceList = parsed.identity_sources.orEmpty().mapNotNull { it.toSource() }

        catalogPrefixes = collectPrefixes()
    }

    fun resolveStateForAreaCode(areaCode: String?): String? {
        if (areaCode.isNullOrBlank()) return null
        return areaCodeToState[areaCode]
    }

    fun countryFor(state: String?): String? = state?.let { stateData[it]?.country }

    /**
     * Resolves a contact's geography most-specific-first. Tries to extract a
     * 5-digit ZIP from [residenceInfo] and look up its county; if that fails,
     * falls back to the [areaCode]'s primary metro.
     */
    fun resolveLocale(areaCode: String?, residenceInfo: String?): LocaleResolution {
        val zip = extractZip(residenceInfo)
        val countyFromZip = zip?.let { zipToCounty[it] }
        val metro = areaCode?.let { areaCodeToMetro[it] }

        val county = countyFromZip ?: metro?.primary_county
        val city = metro?.primary_city
        val stateFromCounty = county?.let { countyData[it]?.state }
        val stateFromAreaCode = areaCode?.let { areaCodeToState[it] }
        val state = stateFromCounty ?: stateFromAreaCode ?: com.hereliesaz.cleanunderwear.match.UsStates.fromAddress(residenceInfo)
        val country = countryFor(state)

        return LocaleResolution(
            city = city,
            county = county,
            state = state,
            country = country
        )
    }

    /**
     * Returns sources for the contact's locale, ordered most-specific to least.
     * County sources first, then state, then any multi-state covering the
     * resolved country.
     */
    fun lockupSourcesFor(areaCode: String?, residenceInfo: String? = null): List<Source> =
        sourcesForLocale(resolveLocale(areaCode, residenceInfo), SourceCategory.LOCKUP)

    /**
     * State-level lockup sources for [state] (a 2-letter code). Used to add the
     * prison search of every state a contact is tied to, not only the one
     * their locale resolves to (a Texas number on a Louisiana address checks both).
     */
    fun stateLockupSources(state: String): List<Source> =
        stateLockup[state.uppercase()].orEmpty().filter { it.scope == SourceScope.STATE }

    fun obituarySourcesFor(areaCode: String?, residenceInfo: String? = null): List<Source> =
        sourcesForLocale(resolveLocale(areaCode, residenceInfo), SourceCategory.OBITUARY)

    /**
     * True if the contact's locale resolves to at least one source the daily
     * vigil can actually fetch + verify on its own — i.e. a lockup or obituary
     * source whose kind is not [SourceKind.MANUAL_LANDING]. When this is false,
     * the contact can only be checked by the operator via the in-app source
     * chips; the auto scrape marks it
     * [com.hereliesaz.cleanunderwear.data.MonitorabilityState.NO_AUTOMATED_SOURCE]
     * rather than silently reporting "no change".
     */
    fun hasAutomatableSourceFor(areaCode: String?, residenceInfo: String? = null): Boolean =
        searchPlan(areaCode, residenceInfo).automated

    /**
     * Every source a contact is checked against: their locale's, plus the state-level prison
     * search of each state they're tied to (area code, address), so a Texas number on a
     * Louisiana address checks both. Triage and the daily check both use this, so a contact
     * is never routed as "no automated source" while the check would have found one.
     */
    fun searchPlan(areaCode: String?, residenceInfo: String? = null): SearchPlan {
        val tied = listOfNotNull(
            resolveStateForAreaCode(areaCode),
            com.hereliesaz.cleanunderwear.match.UsStates.fromAddress(residenceInfo)
        ).distinct()
        val lockup = (lockupSourcesFor(areaCode, residenceInfo) + tied.flatMap { stateLockupSources(it) })
            .distinctBy { it.id }
        return SearchPlan(lockup, obituarySourcesFor(areaCode, residenceInfo), tied, resolveLocale(areaCode, residenceInfo).county)
    }

    /**
     * Returns the country-agnostic identity-correlation sources (face
     * recognition, reverse image search, name-based OSINT). These are
     * presented to the user as launch-in-browser chips — the catalog never
     * auto-scrapes them.
     */
    fun identitySources(): List<Source> = identitySourceList

    private fun sourcesForLocale(locale: LocaleResolution, category: SourceCategory): List<Source> {
        val ordered = mutableListOf<Source>()

        // 1. County-level
        locale.county?.let { county ->
            val perCategory = when (category) {
                SourceCategory.LOCKUP -> countyLockup
                SourceCategory.OBITUARY -> countyObituary
                SourceCategory.IDENTITY -> emptyMap()
            }
            ordered.addAll(perCategory[county].orEmpty())
        }

        // 2. State-level
        locale.state?.let { state ->
            val perCategory = when (category) {
                SourceCategory.LOCKUP -> stateLockup
                SourceCategory.OBITUARY -> stateObituary
                SourceCategory.IDENTITY -> emptyMap()
            }
            val applicable = perCategory[state].orEmpty().filter { source ->
                when (source.scope) {
                    SourceScope.STATE -> true
                    SourceScope.COUNTY -> false // shouldn't appear here, but defensive
                    SourceScope.AREA_CODE -> false
                    SourceScope.MULTI_STATE -> false
                }
            }
            ordered.addAll(applicable)
        }

        // 3. Multi-state / national
        locale.country?.let { country ->
            val multi = when (category) {
                SourceCategory.LOCKUP -> multiStateLockup
                SourceCategory.OBITUARY -> multiStateObituary
                SourceCategory.IDENTITY -> emptyList() // identity is country-agnostic; use identitySources()
            }
            ordered.addAll(multi.filter { it.coversCountry == country })
        }

        return ordered
    }

    /**
     * True if [url] looks like it came out of this catalog. Pattern-matches
     * against the static prefix of every known source URL template.
     */
    fun isFromCatalog(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return catalogPrefixes.any { url.startsWith(it, ignoreCase = true) }
    }

    private fun extractZip(residenceInfo: String?): String? {
        if (residenceInfo.isNullOrBlank()) return null
        val match = ZIP_REGEX.find(residenceInfo) ?: return null
        return match.groupValues[1]
    }

    private fun collectPrefixes(): List<String> {
        val all = mutableListOf<Source>()
        all.addAll(countyLockup.values.flatten())
        all.addAll(countyObituary.values.flatten())
        all.addAll(stateLockup.values.flatten())
        all.addAll(stateObituary.values.flatten())
        all.addAll(multiStateLockup)
        all.addAll(multiStateObituary)
        all.addAll(identitySourceList)
        return all
            .flatMap { listOf(it.urlTemplate, it.evidenceUrlTemplate) }
            .map { staticPrefix(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun staticPrefix(template: String): String {
        val cutAtSlot = template.indexOf('{').let { if (it < 0) template.length else it }
        val cutAtQuery = template.indexOf('?').let { if (it < 0) template.length else it }
        val cut = minOf(cutAtSlot, cutAtQuery)
        return template.substring(0, cut)
    }

    private fun RawSource.toSource(): Source? {
        val id = id ?: return null
        val label = label ?: id
        val kind = parseKind(this.kind) ?: return null
        val urlTemplate = url_template ?: return null
        val render = parseRender(this.render) ?: RenderMode.BASIC
        val resultFormat = parseResultFormat(this.result_format) ?: ResultFormat.HTML
        val scope = parseScope(this.scope) ?: SourceScope.STATE
        val evidenceUrl = evidence_url_template?.takeIf { it.isNotBlank() } ?: urlTemplate

        return Source(
            id = id,
            label = label,
            county = county,
            kind = kind,
            method = method?.uppercase() ?: "GET",
            urlTemplate = urlTemplate,
            formFields = form_fields.orEmpty(),
            render = render,
            resultFormat = resultFormat,
            renderSettleMs = render_settle_ms ?: 1500,
            readySelector = ready_selector,
            evidenceUrlTemplate = evidenceUrl,
            scope = scope,
            appliesToAreaCodes = applies_to_area_codes.orEmpty().toSet(),
            coversCountry = covers_country,
            jsonFields = json_fields.orEmpty(),
            tableColumns = table_columns.orEmpty(),
            recordRegex = record_regex,
            fieldRegexes = field_regexes.orEmpty(),
            resultsUrl = results_url,
            http1 = http1 == true,
            eventKind = event_kind?.let { k ->
                com.hereliesaz.cleanunderwear.match.EventKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) }
            },
            caseTypePattern = case_type_pattern,
            namePattern = name_pattern,
            nameLastFirst = name_last_first == true,
            maxAgeDays = max_age_days,
            tokenPage = token_page,
            tokenForm = token_form.orEmpty(),
            tokenPostUrl = token_post_url,
            releasedPattern = released_pattern,
            custodyPattern = custody_pattern,
            headers = headers.orEmpty(),
            bodyTemplate = body_template,
            contentType = content_type,
            pageSuffix = page_suffix,
            firstExtraPage = first_extra_page ?: 2,
            maxExtraPages = max_extra_pages ?: 0
        )
    }

    private fun parseKind(raw: String?): SourceKind? = when (raw?.uppercase()) {
        "QUERY_TEMPLATE" -> SourceKind.QUERY_TEMPLATE
        "ROSTER_PAGE" -> SourceKind.ROSTER_PAGE
        "MANUAL_LANDING" -> SourceKind.MANUAL_LANDING
        else -> null
    }

    private fun parseRender(raw: String?): RenderMode? = when (raw?.uppercase()) {
        "BASIC" -> RenderMode.BASIC
        "WEBVIEW" -> RenderMode.WEBVIEW
        else -> null
    }

    private fun parseResultFormat(raw: String?): ResultFormat? = when (raw?.uppercase()) {
        "HTML" -> ResultFormat.HTML
        "BOP_JSON" -> ResultFormat.BOP_JSON
        "NEWS_RSS" -> ResultFormat.NEWS_RSS
        "JSON_RECORDS" -> ResultFormat.JSON_RECORDS
        "HTML_TABLE" -> ResultFormat.HTML_TABLE
        "REGEX_RECORDS" -> ResultFormat.REGEX_RECORDS
        else -> null
    }

    private fun parseScope(raw: String?): SourceScope? = when (raw?.uppercase()) {
        "COUNTY" -> SourceScope.COUNTY
        "STATE" -> SourceScope.STATE
        "AREA_CODE" -> SourceScope.AREA_CODE
        "MULTI_STATE" -> SourceScope.MULTI_STATE
        else -> null
    }

    companion object {
        private val ZIP_REGEX = Regex("\\b(\\d{5})(?:-\\d{4})?\\b")
    }
}
