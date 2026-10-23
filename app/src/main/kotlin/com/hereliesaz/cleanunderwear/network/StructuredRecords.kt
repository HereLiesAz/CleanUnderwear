package com.hereliesaz.cleanunderwear.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.match.UsStates
import org.jsoup.Jsoup

/**
 * Reads person records out of structured search results, so a new state
 * inmate locator can be added to `sources.json` without code:
 *
 *  - JSON APIs, via dot paths in [Source.jsonFields];
 *  - HTML result tables, via header-text regexes in [Source.tableColumns].
 *
 * Both reduce each result to a row of named fields (first, last, middle,
 * name, age, dob, status, id, facility, city, state) and share one builder.
 * Only rows whose first and last name equal the contact's are returned. Rows
 * whose status/row text matches [Source.releasedPattern], or fails
 * [Source.custodyPattern], are dropped: a person released years ago is not
 * in custody.
 */
object StructuredRecords {

    fun fromJson(
        body: String,
        source: Source,
        first: String,
        last: String,
        kind: EventKind,
        evidenceUrl: String
    ): List<CandidateRecord> {
        val root = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val f = source.jsonFields
        val records = resolve(root, f["records"].orEmpty())
            .flatMap { if (it.isJsonArray) it.asJsonArray.toList() else listOf(it) }
            .filter { it.isJsonObject }
        return records.mapNotNull { rec ->
            val row = f.filterKeys { it != "records" }.mapValues { (_, path) ->
                resolve(rec, path).firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
            }
            build(row, rec.toString(), source, first, last, kind, evidenceUrl)
        }
    }

    /**
     * Reads records from pages that aren't tables or JSON, via regexes in
     * [Source.recordRegex] (splits the page into one match per record) and
     * [Source.fieldRegexes] (field key → regex whose last group is the value,
     * matched within each record block). Same filtering and [CandidateRecord]
     * shape as the other parsers.
     */
    fun fromRegex(
        html: String,
        source: Source,
        first: String,
        last: String,
        kind: EventKind,
        evidenceUrl: String
    ): List<CandidateRecord> {
        val recordRe = source.recordRegex?.let { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }
            ?: return emptyList()
        val fieldRes = source.fieldRegexes.mapValues { (_, p) -> Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }
        return recordRe.findAll(html).mapNotNull { m ->
            val block = m.value
            val row = fieldRes.mapValues { (_, re) ->
                re.find(block)?.groupValues?.lastOrNull()?.let { Jsoup.parse(it).text().trim() }
            }
            build(row, Jsoup.parse(block).text(), source, first, last, kind, evidenceUrl)
        }.toList()
    }

    /**
     * Whether the oldest record on a regex-records page is still inside [Source.maxAgeDays], so
     * the next page (sorted newest first) may hold more. A page with no dated records ends it.
     */
    fun reachesIntoWindow(html: String, source: Source): Boolean {
        val days = source.maxAgeDays ?: return true
        val recordRe = source.recordRegex?.let { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) } ?: return false
        val dateRe = source.fieldRegexes["date"]?.let { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) } ?: return false
        val oldest = recordRe.findAll(html)
            .mapNotNull { m -> dateRe.find(m.value)?.groupValues?.lastOrNull()?.let(::parseDate) }
            .minOrNull() ?: return false
        return !oldest.isBefore(today().minusDays(days.toLong()))
    }

    fun fromTable(
        html: String,
        source: Source,
        first: String,
        last: String,
        kind: EventKind,
        evidenceUrl: String
    ): List<CandidateRecord> {
        val patterns = source.tableColumns.mapValues { (_, p) -> Regex(p, RegexOption.IGNORE_CASE) }
        val out = mutableListOf<CandidateRecord>()
        for (table in Jsoup.parse(html).select("table")) {
            val rows = table.select("tr")
            // The header is the first all-<th> row; data rows may also lead with a
            // <th> (Texas puts each name in a row header), so cells are th + td.
            val headerRow = rows.firstOrNull { r -> r.select("td").isEmpty() && r.select("th").isNotEmpty() }
                ?: rows.firstOrNull() ?: continue
            val headers = headerRow.select("th, td").map { it.text().trim() }
            val index = patterns.mapValues { (_, re) -> headers.indexOfFirst { re.containsMatchIn(it) } }
                .filterValues { it >= 0 }
            if (!index.containsKey("name") && !(index.containsKey("first") && index.containsKey("last"))) continue
            for (tr in rows) {
                if (tr === headerRow) continue
                // Responsive tables repeat the column label inside each cell
                // (<div class="col-header">First Name</div>John); drop it.
                val cells = tr.select("th, td").map { cell ->
                    val c = cell.clone()
                    c.select("[class*=header], [class*=label]").remove()
                    c.text().trim()
                }
                if (cells.isEmpty()) continue
                val row = index.mapValues { (_, i) -> cells.getOrNull(i) }
                build(row, cells.joinToString(" "), source, first, last, kind, evidenceUrl)?.let { out += it }
            }
        }
        return out
    }

    private fun build(
        rawRow: Map<String, String?>,
        rowText: String,
        source: Source,
        first: String,
        last: String,
        kind: EventKind,
        evidenceUrl: String
    ): CandidateRecord? {
        val row = rawRow.mapValues { it.value?.trim()?.takeIf { v -> v.isNotEmpty() } }
        val rawName = row["name"]?.let { n ->
            source.namePattern?.let { p -> Regex(p, RegexOption.IGNORE_CASE).find(n)?.groupValues?.getOrNull(1) } ?: n
        }
        val parsed = rawName?.let { splitName(it, source.nameLastFirst) }
        val recFirst = row["first"] ?: parsed?.first ?: return null
        val recLast = row["last"] ?: parsed?.third ?: return null
        val recMiddle = row["middle"] ?: parsed?.second
        val fold = StringSimilarity::foldDiacritics
        if (!fold(recFirst.substringBefore(' ')).equals(fold(first), ignoreCase = true)) return null
        if (fold(recLast).split(' ', '-').none { it.equals(fold(last), ignoreCase = true) }) return null

        // With a status field configured, only it decides (an empty release date
        // means still held); without one, the whole row's text does.
        val statusText = if (rawRow.containsKey("status")) row["status"] else rowText
        source.releasedPattern?.let { p ->
            if (statusText != null && Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(statusText)) return null
        }
        source.custodyPattern?.let { p ->
            if (statusText == null || !Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(statusText)) return null
        }

        source.caseTypePattern?.let { p ->
            val type = row["case_type"] ?: return null
            if (!Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(type)) return null
        }
        source.maxAgeDays?.let { days ->
            val date = row["date"]?.let(::parseDate) ?: return null
            if (date.isBefore(today().minusDays(days.toLong()))) return null
        }

        val id = row["id"]
        val age = row["age"]?.let { Regex("\\d{1,3}").find(it)?.value?.toIntOrNull() }?.takeIf { it in 1..120 }
        val birthYear = row["dob"]?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val snippet = listOfNotNull(
            listOfNotNull(recFirst, recMiddle, recLast).joinToString(" "),
            age?.let { "age $it" },
            row["dob"]?.let { "DOB $it" },
            row["case_type"],
            row["date"]?.let { "filed $it" },
            row["charges"],
            row["status"],
            row["facility"],
            id?.let { "#$it" }
        ).joinToString(" · ")
        // A link to the record itself, when the source's rows carry one, beats the search page.
        val recordUrl = row["url"]?.let { absolute(it, source.urlTemplate) } ?: evidenceUrl
        return CandidateRecord(
            sourceId = source.id,
            sourceLabel = source.label,
            kind = kind,
            firstName = first,
            lastName = last,
            middleName = recMiddle?.substringBefore(' ')?.trimEnd('.'),
            age = age,
            birthYear = birthYear,
            city = row["city"],
            state = UsStates.code(row["state"]),
            recordKey = id?.let { "${source.id}:$it" }
                ?: com.hereliesaz.cleanunderwear.match.RecordExtractor.personKey(
                    source.id, recFirst, recMiddle, recLast, birthYear ?: age, row["city"]
                ),
            evidenceUrl = recordUrl,
            snippet = snippet
        )
    }

    /** [href] made absolute against the scheme and host of [base]. */
    internal fun absolute(href: String, base: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val origin = Regex("^(https?://[^/]+)").find(base)?.value ?: return href
        return origin + (if (href.startsWith("/")) href else "/$href")
    }

    /** Generational suffixes, which rosters print where a middle name would go ("SMITH, JOHN JR"). */
    private val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "v")

    /** Overridable clock for tests. */
    internal var today: () -> java.time.LocalDate = { java.time.LocalDate.now() }

    private val DATE_FORMATS = listOf("M/d/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "M-d-yyyy", "MM-dd-yyyy", "MMM d, yyyy", "MMMM d, yyyy")
        .map { java.time.format.DateTimeFormatter.ofPattern(it, java.util.Locale.US) }

    /** First date in [s] in any common US format; time parts and trailing text ignored. */
    internal fun parseDate(s: String): java.time.LocalDate? {
        val token = Regex("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}|[A-Z][a-z]+\\.? \\d{1,2}, \\d{4}")
            .find(s)?.value?.replace(".", "") ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { f ->
            try {
                java.time.LocalDate.parse(token, f)
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
        }
    }

    /**
     * Splits a combined name into (first, middle, last): "LAST, FIRST MIDDLE"
     * when it has a comma, otherwise "First [Middle] Last".
     */
    internal fun splitName(name: String, lastFirst: Boolean = false): Triple<String, String?, String>? {
        if (name.contains(',')) {
            val lastPart = name.substringBefore(',').trim()
            val rest = name.substringAfter(',').trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it.trimEnd('.').lowercase() !in NAME_SUFFIXES }
            if (lastPart.isEmpty() || rest.isEmpty()) return null
            return Triple(rest[0], rest.getOrNull(1), lastPart)
        }
        val tokens = name.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .filterIndexed { i, t -> i == 0 || t.trimEnd('.').lowercase() !in NAME_SUFFIXES }
        if (tokens.size < 2) return null
        if (lastFirst) return Triple(tokens[1], tokens.getOrNull(2), tokens[0])
        return Triple(tokens.first(), tokens.getOrNull(1)?.takeIf { tokens.size > 2 }, tokens.last())
    }

    /**
     * Resolves a dot path ("data.results", "name.first"). An empty path is the
     * element itself; a path segment over an array fans out across its items.
     */
    internal fun resolve(element: JsonElement, path: String): List<JsonElement> {
        var current = listOf(element)
        for (segment in path.split('.').filter { it.isNotEmpty() }) {
            current = current.flatMap { el ->
                val items = if (el.isJsonArray) (el as JsonArray).toList() else listOf(el)
                items.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    item.asJsonObject.entrySet().firstOrNull { it.key.equals(segment, ignoreCase = true) }?.value
                }
            }
        }
        return current.filter { !it.isJsonNull }
    }
}
