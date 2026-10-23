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
        val kind = classify(item.title) ?: classifyLink(item.link) ?: return@flatMap emptyList()
        // "Judge John Smith sentenced…", "Sheriff John Smith says…": the contact's name in an
        // official role isn't the subject of the arrest or death.
        if (namedInRole("${item.title}. ${item.description}", first, last, kind)) return@flatMap emptyList()
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
            // An age in a news item is the age when it was written.
            val dated = c.copy(eventYear = c.eventYear ?: item.published?.year)
            // Funeral-home publishers often carry their town ("Wilson's Funeral
            // Home | Ava, IL"); use it when the headline names no place.
            if (dated.city == null && dated.state == null && publisherPlace != null) {
                dated.copy(city = publisherPlace.first, state = publisherPlace.second)
            } else {
                dated
            }
        }
    }

    /**
     * Obituary pages whose headline is only the name ("John Louis Smith - The Press Democrat")
     * still say what they are in their address.
     */
    internal fun classifyLink(link: String): EventKind? {
        val l = link.lowercase()
        return if (OBIT_LINK.any { it in l }) EventKind.DEATH else null
    }

    /**
     * Whether every mention of the name in [text] is someone other than the item's subject.
     *
     * Arrest: an official title before the name ("Judge John Smith sentenced…"), or a funeral
     * the name belongs to ("…arrested at the funeral of John Smith"). Death: the person who
     * dies can hold any title ("Longtime coach John Smith dies") and a funeral for them is
     * their notice, so only a titled speaker counts ("Sheriff John Smith said the man died").
     */
    internal fun namedInRole(text: String, first: String, last: String, kind: EventKind = EventKind.ARREST): Boolean {
        val name = Regex("(?i)\\b${Regex.escape(first)}\\b(?:\\s+[A-Za-z.'-]+){0,2}?\\s+\\b${Regex.escape(last)}\\b")
        val mentions = name.findAll(text).toList()
        if (mentions.isEmpty()) return false
        return mentions.all { m ->
            // Leading space: the title may open the text ("Judge John Smith…").
            val before = " " + text.substring(0, m.range.first).trimEnd().lowercase()
            val titled = ROLES.any { before.endsWith(it) }
            if (kind == EventKind.DEATH) {
                titled && SPEAKS.containsMatchIn(text.substring(m.range.last + 1).take(40).lowercase())
            } else {
                titled || FUNERAL_OF.containsMatchIn(before.takeLast(40))
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

    private val OBIT_LINK = listOf("/obituar", "/obit/", "legacy.com", "/tributes/", "dignitymemorial.com", "/memorials/")
    private val ROLES = listOf(
        "judge", "sheriff", "deputy", "officer", "detective", "sgt.", "sergeant", "lt.", "captain",
        "attorney", "prosecutor", "district attorney", "da", "lawyer", "chief", "mayor", "senator",
        "rep.", "representative", "governor", "councilman", "councilwoman", "commissioner",
        "coach", "pastor", "rev.", "reverend", "dr.", "spokesman", "spokeswoman", "trooper"
    ).map { " $it" }
    private val FUNERAL_OF = Regex("(?:funeral|memorial|services?) (?:of|for)\\s*$|attend(?:s|ed)? (?:the )?funeral")
    private val SPEAKS = Regex("^[,\\s]*(?:\\([^)]*\\)\\s*)?(?:says|said|told|confirmed|announced|reported|stated)\\b")
    private val PUBLISHER_PLACE = Regex("\\|\\s*([A-Za-z.' -]+?),\\s*([A-Za-z]{2})\\s*$")
    private val OBITUARY = Regex("\\b(obituary|obituaries|obit|passed away|death notice|funeral|memorial|celebration of life|in loving memory)\\b")
    private val ARREST = Regex("\\b(arrest|arrested|arrests|booked|booking|charged|indicted|jailed|jail|custody|mugshot|sentenced|convicted)\\b")
    private val DEATH = Regex("\\b(dies|died|dead|death|killed|slain)\\b")
}
