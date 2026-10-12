package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.match.RecordExtractor
import javax.inject.Inject

/**
 * Runs one catalog [Source] for one contact and returns the records naming
 * them, before verification. Dispatches on [Source.resultFormat]: news RSS,
 * the BOP JSON locator, mapped JSON APIs and HTML tables, or free text read
 * by [RecordExtractor].
 */
class SourceSearch @Inject constructor(
    private val basicScraper: HtmlScraper,
    private val stealthScraper: WebViewScraper
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
    ): List<CandidateRecord> {
        @Suppress("NAME_SHADOWING")
        val kind = source.eventKind ?: kind
        val fetchUrl = try {
            SourceUrlBuilder.buildFetchUrl(source, first, last)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, first, last)
        val text = when (source.render) {
            RenderMode.BASIC -> when {
                source.resultFormat == ResultFormat.NEWS_RSS -> {
                    // A news feed reports deaths and arrests alike; NewsFeed
                    // classifies each item, so [kind] is not used here.
                    val xml = basicScraper.fetchBody(fetchUrl) ?: return emptyList()
                    return NewsFeed.candidates(xml, first, last, source.id, source.label)
                }
                source.resultFormat == ResultFormat.BOP_JSON -> {
                    val r = basicScraper.scrapeBopInmate(fetchUrl, "$first $last", corroboration, dismissedKeys)
                    return listOfNotNull(r.record?.copy(evidenceUrl = evidenceUrl))
                }
                else -> {
                    val body = basicScraper.fetchSource(
                        source,
                        fetchUrl,
                        SourceUrlBuilder.buildFormFields(source, first, last),
                        SourceUrlBuilder.buildBody(source, first, last)
                    ) ?: return emptyList()
                    when (source.resultFormat) {
                        ResultFormat.JSON_RECORDS ->
                            return StructuredRecords.fromJson(body, source, first, last, kind, evidenceUrl)
                        ResultFormat.HTML_TABLE ->
                            return StructuredRecords.fromTable(body, source, first, last, kind, evidenceUrl)
                        else -> Unit
                    }
                    org.jsoup.Jsoup.parse(body).text()
                }
            }
            RenderMode.WEBVIEW -> stealthScraper.scrapeGhostTown(
                url = fetchUrl,
                settleMs = source.renderSettleMs,
                readySelector = source.readySelector
            )?.text()
        } ?: return emptyList()
        val released = source.releasedPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val custody = source.custodyPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
        return RecordExtractor.extract(text, first, last, kind, source.id, source.label, evidenceUrl)
            // On a roster the status sits just after the name (within one row).
            .filter { c ->
                val snip = c.snippet.orEmpty()
                val at = snip.indexOf(last, ignoreCase = true)
                val row = if (at < 0) "" else snip.substring(at).take(ROW_CHARS)
                (released == null || !released.containsMatchIn(row)) &&
                    (custody == null || custody.containsMatchIn(row))
            }
    }

    private companion object {
        /** How far past a roster name its row (status, facility) is assumed to run. */
        const val ROW_CHARS = 200
    }
}
