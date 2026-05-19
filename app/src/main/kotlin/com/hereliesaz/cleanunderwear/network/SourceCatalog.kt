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

enum class SourceScope { COUNTY, STATE, AREA_CODE, MULTI_STATE }

enum class SourceCategory { LOCKUP, OBITUARY, IDENTITY }

/**
 * One curated source from `sources.json`. Construct via [SourceCatalog].
 */
data class Source(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val method: String,
    val urlTemplate: String,
    val formFields: Map<String, String>,
    val render: RenderMode,
    val renderSettleMs: Int,
    val readySelector: String?,
    val evidenceUrlTemplate: String,
    val scope: SourceScope,
    val appliesToAreaCodes: Set<String>,
    val coversCountry: String?
)

/**
 * Result of resolving a contact's geography. All fields optional — fall through
 * the levels when issuing source lookups: county → state → multi-state.
 */
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
        val kind: String? = null,
        val method: String? = null,
        val url_template: String? = null,
        val form_fields: Map<String, String>? = null,
        val render: String? = null,
        val render_settle_ms: Int? = null,
        val ready_selector: String? = null,
        val evidence_url_template: String? = null,
        val scope: String? = null,
        val applies_to_area_codes: List<String>? = null,
        val covers_country: String? = null
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
        // is currently empty (no widely useful national obit aggregator that
        // takes per-name queries safely).
        multiStateLockup = parsed.multi_state.orEmpty().mapNotNull { it.toSource() }
        multiStateObituary = emptyList()

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
        val state = stateFromCounty ?: stateFromAreaCode
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

    fun obituarySourcesFor(areaCode: String?, residenceInfo: String? = null): List<Source> =
        sourcesForLocale(resolveLocale(areaCode, residenceInfo), SourceCategory.OBITUARY)

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
        val scope = parseScope(this.scope) ?: SourceScope.STATE
        val evidenceUrl = evidence_url_template?.takeIf { it.isNotBlank() } ?: urlTemplate

        return Source(
            id = id,
            label = label,
            kind = kind,
            method = method?.uppercase() ?: "GET",
            urlTemplate = urlTemplate,
            formFields = form_fields.orEmpty(),
            render = render,
            renderSettleMs = render_settle_ms ?: 1500,
            readySelector = ready_selector,
            evidenceUrlTemplate = evidenceUrl,
            scope = scope,
            appliesToAreaCodes = applies_to_area_codes.orEmpty().toSet(),
            coversCountry = covers_country
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
