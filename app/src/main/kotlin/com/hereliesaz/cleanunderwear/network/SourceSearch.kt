package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.match.RecordExtractor
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import javax.inject.Inject

/**
 * Runs one catalog [Source] for one contact and returns the records naming
 * them, before verification. Dispatches on [Source.resultFormat]: news RSS,
 * the BOP JSON locator, mapped JSON APIs and HTML tables, or free text read
 * by [RecordExtractor].
 */
/**
 * What one source said: the records naming the contact, and whether the source could be read
 * at all. A [failed] search found nothing because it saw nothing, which is not "no record".
 */
data class SearchResult(val records: List<CandidateRecord>, val failed: Boolean = false) {
    companion object {
        val FAILED = SearchResult(emptyList(), failed = true)
    }
}

class SourceSearch @Inject constructor(
    private val basicScraper: HtmlScraper
) {
    /**
     * Queries one source and returns every record on it that names the contact.
     * The BOP locator is parsed structurally (with its own custody and
     * corroboration gates); every other source is fetched as text and read by
     * [RecordExtractor].
     */
    suspend fun search(
        source: Source,
        kind: EventKind,
        first: String,
        last: String,
        corroboration: IdentityVerifier.Corroboration,
        dismissedKeys: Set<String>
    ): SearchResult {
        @Suppress("NAME_SHADOWING")
        val kind = source.eventKind ?: kind
        // Rosters store names unaccented; search and compare on the folded form.
        @Suppress("NAME_SHADOWING")
        val first = StringSimilarity.foldDiacritics(first)
        @Suppress("NAME_SHADOWING")
        val last = StringSimilarity.foldDiacritics(last)
        val fetchUrl = try {
            SourceUrlBuilder.buildFetchUrl(source, first, last)
        } catch (e: IllegalArgumentException) {
            // The name can't be put into this source's query; there was nothing to look at.
            return SearchResult(emptyList())
        }
        val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, first, last)
        val text = when (source.render) {
            RenderMode.BASIC -> when {
                source.resultFormat == ResultFormat.NEWS_RSS -> {
                    // A news feed reports deaths and arrests alike; NewsFeed
                    // classifies each item, so [kind] is not used here.
                    val xml = basicScraper.fetchBody(fetchUrl) ?: return SearchResult.FAILED
                    return SearchResult(NewsFeed.candidates(xml, first, last, source.id, source.label))
                }
                source.resultFormat == ResultFormat.BOP_JSON -> {
                    val r = basicScraper.scrapeBopInmate(fetchUrl, "$first $last", corroboration, dismissedKeys)
                    if (r.failed) return SearchResult.FAILED
                    return SearchResult(listOfNotNull(r.record?.copy(evidenceUrl = evidenceUrl)))
                }
                else -> {
                    val body = basicScraper.fetchSource(
                        source,
                        fetchUrl,
                        SourceUrlBuilder.buildFormFields(source, first, last),
                        SourceUrlBuilder.buildBody(source, first, last)
                    ) ?: return SearchResult.FAILED
                    val looksJson = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
                    if (!looksJson && BotWall.detected(body)) {
                        return SearchResult.FAILED
                    }
                    when (source.resultFormat) {
                        ResultFormat.JSON_RECORDS ->
                            return SearchResult(StructuredRecords.fromJson(body, source, first, last, kind, evidenceUrl))
                        ResultFormat.HTML_TABLE ->
                            return SearchResult(StructuredRecords.fromTable(body, source, first, last, kind, evidenceUrl))
                        ResultFormat.REGEX_RECORDS -> {
                            val records = StructuredRecords.fromRegex(body, source, first, last, kind, evidenceUrl) +
                                laterPages(source, fetchUrl, body, first, last, kind, evidenceUrl)
                            return SearchResult(records.distinctBy { it.recordKey })
                        }
                        else -> Unit
                    }
                    org.jsoup.Jsoup.parse(body).text()
                }
            }
            // No automated source renders in a WebView; a catalog entry that asks for one can't
            // be checked unattended, which is a failed lookup, not an empty one.
            RenderMode.WEBVIEW -> {
                com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log("${source.id}: WebView rendering isn't automated; not checked")
                null
            }
        } ?: return SearchResult.FAILED
        val released = source.releasedPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val custody = source.custodyPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
        return SearchResult(RecordExtractor.extract(text, first, last, kind, source.id, source.label, evidenceUrl)
            // On a roster the status sits just after the name (within one row).
            .filter { c ->
                val snip = c.snippet.orEmpty()
                val at = snip.indexOf(last, ignoreCase = true)
                val row = if (at < 0) "" else snip.substring(at).take(ROW_CHARS)
                (released == null || !released.containsMatchIn(row)) &&
                    (custody == null || custody.containsMatchIn(row))
            })
    }

    /**
     * Records from the result pages after the first, while each page still reaches into the
     * source's date window. A page that can't be read just ends the walk: the first page was
     * read, so the search as a whole didn't fail.
     */
    private suspend fun laterPages(
        source: Source,
        fetchUrl: String,
        firstPage: String,
        first: String,
        last: String,
        kind: EventKind,
        evidenceUrl: String
    ): List<CandidateRecord> {
        val suffix = source.pageSuffix ?: return emptyList()
        if (source.maxExtraPages <= 0 || !StructuredRecords.reachesIntoWindow(firstPage, source)) return emptyList()
        val out = mutableListOf<CandidateRecord>()
        for (page in source.firstExtraPage until source.firstExtraPage + source.maxExtraPages) {
            val body = basicScraper.fetchSource(
                source,
                fetchUrl + suffix.replace("{page}", page.toString()),
                SourceUrlBuilder.buildFormFields(source, first, last),
                SourceUrlBuilder.buildBody(source, first, last)
            )?.takeUnless { BotWall.detected(it) } ?: break
            out += StructuredRecords.fromRegex(body, source, first, last, kind, evidenceUrl)
            if (!StructuredRecords.reachesIntoWindow(body, source)) break
        }
        return out
    }

    private companion object {
        /** How far past a roster name its row (status, facility) is assumed to run. */
        const val ROW_CHARS = 200
    }
}
