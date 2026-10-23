package com.hereliesaz.cleanunderwear.match

/**
 * Pulls person facts out of free text around a name hit: middle name, age,
 * birth year, city/state and (for obituaries) survivors.
 *
 * Sources that publish prose — obituaries, news stories, booking logs — never
 * hand us fields, but they reliably phrase the same facts the same few ways
 * ("John Smith, 45, of Metairie", "1961 – 2026", "survived by his wife, Mary").
 * Each occurrence of the contact's name becomes one [CandidateRecord]; facts
 * are read only from a window around that occurrence so two people on the
 * same page don't bleed into each other.
 */
object RecordExtractor {

    private const val WINDOW_BEFORE = 200
    private const val WINDOW_AFTER = 700

    fun extract(
        text: String,
        firstName: String,
        lastName: String,
        kind: EventKind,
        sourceId: String,
        sourceLabel: String,
        evidenceUrl: String?
    ): List<CandidateRecord> {
        // Match on accent-folded text (same length, so offsets carry over to the original).
        val original = text
        @Suppress("NAME_SHADOWING")
        val text = StringSimilarity.foldDiacritics(text)
        val firstQ = Regex.escape(StringSimilarity.foldDiacritics(firstName))
        val lastQ = Regex.escape(StringSimilarity.foldDiacritics(lastName))
        // "First [middle tokens] Last" (up to 2 middle tokens) or "Last, First".
        // The inverted form captures no middle name: on rosters the next token is
        // as often a column ("SMITH, JOHN  Booked …") as a middle name, and a
        // misread middle name would wrongly reject the record.
        val forward = Regex("(?i)\\b$firstQ\\b((?:\\s+[A-Za-z][A-Za-z.'-]*){0,2}?)\\s+\\b$lastQ\\b")
        val inverted = Regex("(?i)\\b$lastQ\\b\\s*,\\s*\\b$firstQ\\b()")
        // Roster tables often print "SMITH JOHN" in adjacent columns with no comma.
        // Too ambiguous in prose, so only for custody rosters.
        val columns = Regex("(?i)\\b$lastQ\\s+$firstQ\\b()")

        val hits = (forward.findAll(text) + inverted.findAll(text) +
            (if (kind == EventKind.CUSTODY) columns.findAll(text) else emptySequence()))
            .sortedBy { it.range.first }
            .toList()
        val out = mutableListOf<CandidateRecord>()
        var lastEnd = -1
        for (hit in hits) {
            // One record per distinct mention block; repeated mentions of the
            // same person within the window add nothing.
            if (hit.range.first < lastEnd) continue
            val start = (hit.range.first - WINDOW_BEFORE).coerceAtLeast(0)
            val end = (hit.range.last + 1 + WINDOW_AFTER).coerceAtMost(text.length)
            lastEnd = hit.range.last + 1 + WINDOW_AFTER / 2
            val after = text.substring(hit.range.last + 1, end)
            val window = text.substring(start, end)

            val (birthYear, deathYear) = lifespan(window)
            val place = place(after)
            out += CandidateRecord(
                sourceId = sourceId,
                sourceLabel = sourceLabel,
                kind = kind,
                firstName = firstName,
                lastName = lastName,
                middleName = middleOf(hit.groupValues.getOrNull(1)),
                age = age(after) ?: if (birthYear != null && deathYear != null) deathYear - birthYear else null,
                birthYear = birthYear ?: bornYear(window) ?: if (kind == EventKind.CUSTODY) rosterBirthYear(after) else null,
                city = place?.city,
                state = place?.state,
                relatives = if (kind == EventKind.DEATH) survivors(after) else emptyList(),
                inferredRelatives = if (kind == EventKind.DEATH) inferredSurvivors(after, lastName) else emptyList(),
                eventYear = deathYear,
                recordKey = evidenceUrl,
                evidenceUrl = evidenceUrl,
                snippet = "..." + original.substring(start, end).replace(Regex("\\s+"), " ").trim() + "..."
            )
        }
        return out
    }

    /** A single alphabetic middle token or initial; anything longer is noise. */
    internal fun middleOf(raw: String?): String? {
        val tokens = raw?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: return null
        if (tokens.size != 1) return null
        val t = tokens[0].trimEnd('.')
        if (t.isEmpty() || !t.all { it.isLetter() }) return null
        if (t.lowercase() in NOT_MIDDLE) return null
        return t
    }

    private val AGE_PATTERNS = listOf(
        Regex("^\\s*,\\s*(\\d{1,3})\\s*,"),                       // "John Smith, 45, of …"
        Regex("^\\s*\\((\\d{1,3})\\)"),                            // "John Smith (45)"
        Regex("\\b(\\d{1,3})-year-old\\b", RegexOption.IGNORE_CASE),
        Regex("\\baged?\\s*:?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bat the age of\\s+(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    )

    internal fun age(after: String): Int? {
        val head = after.take(250)
        for (p in AGE_PATTERNS) {
            val v = p.find(head)?.groupValues?.get(1)?.toIntOrNull()
            if (v != null && v in 1..120) return v
        }
        return null
    }

    private val LIFESPAN = Regex("\\b(1[89]\\d{2}|20\\d{2})\\s*[-–—~]\\s*(1[89]\\d{2}|20\\d{2})\\b")
    private val BORN = Regex("\\bborn\\b[^.]{0,80}?\\b(1[89]\\d{2}|20\\d{2})\\b", RegexOption.IGNORE_CASE)

    internal fun lifespan(window: String): Pair<Int?, Int?> {
        val m = LIFESPAN.find(window) ?: return null to null
        val b = m.groupValues[1].toInt()
        val d = m.groupValues[2].toInt()
        return if (d >= b && d - b <= 120) b to d else null to null
    }

    private val BIRTH_LABEL = Regex("(?i)\\bbirth\\s*(?:year|date)?\\s*:?\\s*(?:\\d{1,2}/\\d{1,2}/)?((?:19|20)\\d{2})\\b")
    private val SLASH_DATE = Regex("\\b\\d{1,2}/\\d{1,2}/((?:19|20)\\d{2})\\b")
    private val BARE_YEAR = Regex("^[\\s|,;:-]*((?:19|20)\\d{2})\\b")

    /**
     * A roster's DOB or birth-year column right after the name ("SMITH JOHN
     * 1/22/1965", "Smith, John Edward 1977"). Only dates at least 14 years old
     * count, so a booking date in the same row is not read as a birth date.
     */
    internal fun rosterBirthYear(after: String, currentYear: Int = java.time.Year.now().value): Int? {
        val head = after.take(120)
        val year = BIRTH_LABEL.find(head)?.groupValues?.get(1)?.toIntOrNull()
            ?: SLASH_DATE.find(head)?.groupValues?.get(1)?.toIntOrNull()
            ?: BARE_YEAR.find(after.substringAfter(' ', after).take(30))?.groupValues?.get(1)?.toIntOrNull()
            ?: BARE_YEAR.find(head)?.groupValues?.get(1)?.toIntOrNull()
        return year?.takeIf { it in 1900..(currentYear - 14) }
    }

    internal fun bornYear(window: String): Int? =
        BORN.find(window)?.groupValues?.get(1)?.toIntOrNull()

    private val OF_PLACE = Regex(
        "\\b(?:of|from|in)\\s+([A-Z][a-zA-Z.'-]+(?:\\s+[A-Z][a-zA-Z.'-]+){0,2})(?:\\s*,\\s*([A-Z][a-zA-Z.]+(?:\\s+[A-Z][a-z]+)?))?"
    )

    // Obituary headlines: "John Smith Obituary (1925 - 2026) - San Antonio, TX - Publisher".
    private val DASH_PLACE = Regex(
        "\\s[-–—]\\s([A-Z][a-zA-Z.'-]+(?:\\s+[A-Z][a-zA-Z.'-]+){0,2}),\\s*([A-Z]{2})\\b"
    )

    /**
     * The first place named after the name: "of/from/in City[, State]" or a
     * headline's " - City, ST". State as a 2-letter code.
     */
    internal fun place(after: String): Place? {
        val head = after.take(300)
        val dash = DASH_PLACE.find(head)?.takeIf { UsStates.code(it.groupValues[2]) != null }
        val of = OF_PLACE.find(head)
        if (dash != null && (of == null || dash.range.first < of.range.first)) {
            return Place(city = dash.groupValues[1].trim(), state = UsStates.code(dash.groupValues[2]))
        }
        val m = of ?: return null
        val name = m.groupValues[1].trim()
        if (name.lowercase() in NOT_PLACE) return null
        val stateOnly = UsStates.code(name)
        if (stateOnly != null && m.groupValues[2].isBlank()) return Place(city = null, state = stateOnly)
        return Place(city = name, state = UsStates.code(m.groupValues[2]))
    }

    data class Place(val city: String?, val state: String?)

    private val SURVIVED_BY = Regex(
        "(?i)\\b(?:survived by|leaves behind|is survived by|preceded in death by)\\b(.{0,600}?)(?:\\.\\s+[A-Z]|$)"
    )
    private val FULL_NAME = Regex("\\b([A-Z][a-z]+(?:\\s+[A-Z]\\.)?(?:\\s+\\([A-Z][a-z]+\\))?\\s+[A-Z][a-z]+(?:-[A-Z][a-z]+)?)\\b")
    private val RELATION_FIRST = Regex(
        "(?i)\\b(?:wife|husband|spouse|son|daughter|sons|daughters|brother|sister|mother|father|children|grandchildren)\\b[,:]?\\s+([A-Z][a-z]+)\\b(?!\\s+[A-Z][a-z])"
    )

    /** Full names from "survived by …" clauses, as written. */
    internal fun survivors(after: String): List<String> {
        val clause = survivorClause(after) ?: return emptyList()
        val names = linkedSetOf<String>()
        FULL_NAME.findAll(clause).forEach { m ->
            val n = m.groupValues[1].replace(Regex("\\s+\\([A-Z][a-z]+\\)"), "")
            if (n.split(' ').first().lowercase() !in NOT_NAME) names += n
        }
        return names.toList()
    }

    /**
     * Survivors named by first name only after a relation word ("his wife, Mary"), completed
     * with the deceased's surname, the usual convention in obituaries. Kept apart from
     * [survivors]: the surname is our guess, not the record's.
     */
    internal fun inferredSurvivors(after: String, lastName: String): List<String> {
        val clause = survivorClause(after) ?: return emptyList()
        val full = survivors(after).toSet()
        return RELATION_FIRST.findAll(clause)
            .map { it.groupValues[1] }
            .filter { it.lowercase() !in NOT_NAME }
            .map { "$it $lastName" }
            .filter { it !in full }
            .distinct()
            .toList()
    }

    private fun survivorClause(after: String): String? =
        SURVIVED_BY.findAll(after).joinToString(" ") { it.groupValues[1] }.takeIf { it.isNotBlank() }

    private val NOT_MIDDLE = setOf("jr", "sr", "ii", "iii", "iv", "the", "and", "of", "was", "is")
    private val NOT_PLACE = setOf("the", "his", "her", "their", "a", "an", "this", "custody", "jail", "prison")
    private val NOT_NAME = setOf(
        "his", "her", "their", "the", "and", "loving", "beloved", "many", "several", "numerous",
        "grandchildren", "children", "nieces", "nephews", "cousins", "friends", "great"
    )
}

/** US state names and postal codes → postal code. */
object UsStates {
    private val NAMES = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR", "california" to "CA",
        "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE", "florida" to "FL", "georgia" to "GA",
        "hawaii" to "HI", "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
        "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME", "maryland" to "MD",
        "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN", "mississippi" to "MS",
        "missouri" to "MO", "montana" to "MT", "nebraska" to "NE", "nevada" to "NV", "new hampshire" to "NH",
        "new jersey" to "NJ", "new mexico" to "NM", "new york" to "NY", "north carolina" to "NC",
        "north dakota" to "ND", "ohio" to "OH", "oklahoma" to "OK", "oregon" to "OR", "pennsylvania" to "PA",
        "rhode island" to "RI", "south carolina" to "SC", "south dakota" to "SD", "tennessee" to "TN",
        "texas" to "TX", "utah" to "UT", "vermont" to "VT", "virginia" to "VA", "washington" to "WA",
        "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY", "district of columbia" to "DC"
    )
    private val CODES = NAMES.values.toSet()

    fun code(s: String?): String? {
        val t = s?.trim()?.trimEnd('.') ?: return null
        if (t.length == 2 && t.uppercase() in CODES) return t.uppercase()
        return NAMES[t.lowercase()]
    }
}
