package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.match.RecordExtractor
import com.hereliesaz.cleanunderwear.match.UsStates
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns a news-search RSS feed (Google News) into [CandidateRecord]s.
 *
 * News search is the widest net the app has: Google News indexes local papers,
 * TV stations, Legacy.com, Dignity Memorial and individual funeral-home
 * obituaries, so one query per contact reaches arrest and death reports no
 * single roster or obituary site carries. Each item is classified as a DEATH
 * or ARREST report from its headline, then read by [RecordExtractor].
 *
 * Each publisher counts as its own source, so the same event reported by two
 * outlets corroborates in [com.hereliesaz.cleanunderwear.match.MatchVerifier].
 */
object NewsFeed {

    /** Arrest reports older than this are skipped; deaths are kept at any age. */
    const val ARREST_LOOKBACK_DAYS = 365L

    data class Item(
        val title: String,
        val link: String,
        val publisher: String?,
        val published: ZonedDateTime?,
        val description: String
    )

    fun parse(xml: String): List<Item> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            Item(
                title = title,
                link = link,
                publisher = item.selectFirst("source")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                published = item.selectFirst("pubDate")?.text()?.let(::parseDate),
                // The description is escaped HTML (a link plus the publisher name).
                description = Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text()
            )
        }
    }

    /**
     * Death notices first (an obituary that mentions police is still an
     * obituary), then arrests, then plain death words ("dies", "killed").
     * A headline matching none is not about an arrest or a death.
     */
    fun classify(title: String): EventKind? {
        val t = title.lowercase()
        return when {
            OBITUARY.containsMatchIn(t) -> EventKind.DEATH
            ARREST.containsMatchIn(t) -> EventKind.ARREST
            DEATH.containsMatchIn(t) -> EventKind.DEATH
            else -> null
        }
    }

    fun candidates(
        xml: String,
        first: String,
        last: String,
        sourceId: String,
        sourceLabel: String,
        now: ZonedDateTime = ZonedDateTime.now()
    ): List<CandidateRecord> = parse(xml).flatMap { item ->
        val kind = classify(item.title) ?: return@flatMap emptyList()
        val tooOld = kind == EventKind.ARREST && item.published != null &&
            item.published.isBefore(now.minusDays(ARREST_LOOKBACK_DAYS))
        if (tooOld) return@flatMap emptyList()
        val publisher = item.publisher ?: sourceLabel
        val publisherPlace = placeOf(publisher)
        RecordExtractor.extract(
            text = "${item.title}. ${item.description}",
            firstName = first,
            lastName = last,
            kind = kind,
            sourceId = "$sourceId:${publisher.lowercase()}",
            sourceLabel = "$publisher (via $sourceLabel)",
            evidenceUrl = item.link
        ).take(1).map { c ->
            // Funeral-home publishers often carry their town ("Wilson's Funeral
            // Home | Ava, IL"); use it when the headline names no place.
            if (c.city == null && c.state == null && publisherPlace != null) {
                c.copy(city = publisherPlace.first, state = publisherPlace.second)
            } else {
                c
            }
        }
    }

    /** "Name | City, ST" → (City, ST), when ST is a US state. */
    internal fun placeOf(publisher: String): Pair<String, String>? {
        val m = PUBLISHER_PLACE.find(publisher) ?: return null
        val state = UsStates.code(m.groupValues[2]) ?: return null
        val city = m.groupValues[1].trim().replace(Regex("\\b[a-z]")) { it.value.uppercase() }
        return city to state
    }

    private fun parseDate(s: String): ZonedDateTime? = try {
        ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
    } catch (e: Exception) {
        null
    }

    private val PUBLISHER_PLACE = Regex("\\|\\s*([A-Za-z.' -]+?),\\s*([A-Za-z]{2})\\s*$")
    private val OBITUARY = Regex("\\b(obituary|obituaries|obit|passed away|death notice|funeral|memorial|celebration of life|in loving memory)\\b")
    private val ARREST = Regex("\\b(arrest|arrested|arrests|booked|booking|charged|indicted|jailed|jail|custody|mugshot|sentenced|convicted)\\b")
    private val DEATH = Regex("\\b(dies|died|dead|death|killed|slain)\\b")
}
