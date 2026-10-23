package com.hereliesaz.cleanunderwear.network

/**
 * Decides whether a contact's name is safe to feed into a roster/locator search.
 *
 * Single-letter or single-token names ("B", "Madonna") and stopword-class tokens
 * ("Inmate", "Search") matched substring-wise against any roster page, producing
 * false-positive INCARCERATED hits. Anything that fails this validator is routed
 * to identity enrichment instead of being silently scraped.
 */
object NameValidator {
    private const val MIN_TOKEN_LEN = 2

    /** Two-letter tokens that are words, not names; real two-letter names (Li, Wu, Ng, Do) pass. */
    private val SHORT_NON_NAMES: Set<String> = setOf(
        "of", "to", "in", "on", "at", "by", "is", "it", "me", "my", "we", "us", "or", "no", "so",
        "up", "mr", "ms", "dr", "jr", "sr", "st", "aa", "xx"
    )

    /** Leading words that address or describe the person rather than name them. */
    private val TITLES: Set<String> = setOf(
        "mr", "mrs", "ms", "miss", "mx", "dr", "rev", "prof", "sir", "dame", "fr", "capt", "sgt",
        "uncle", "aunt", "auntie", "grandma", "grandpa", "granny", "nana", "papa", "mom", "dad", "cousin"
    )

    /** Trailing generational and professional suffixes. */
    private val SUFFIXES: Set<String> = setOf("jr", "sr", "ii", "iii", "iv", "v", "md", "phd", "esq", "dds", "rn")

    private val BAD_NAME_TOKENS: Set<String> = setOf(
        // English stopwords
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "had", "her", "was", "one", "our", "out", "day", "get", "has", "him",
        "his", "how", "man", "new", "now", "old", "see", "two", "way", "who",
        "boy", "did", "its", "let", "put", "say", "she", "too", "use",
        // Roster / locator page chrome
        "inmate", "name", "search", "booking", "release", "charge", "case",
        "court", "county", "sheriff", "page", "next", "prev", "results",
        "first", "last", "male", "female", "white", "black", "status",
        "offender", "subject", "race", "gender", "dob", "age", "bond",
        // App-internal placeholders
        "unknown", "unnamed", "entity"
    )

    sealed class Result {
        data class Ok(val first: String, val last: String) : Result()
        data class Skip(val reason: String) : Result()
    }

    /**
     * Splits raw display text into name tokens, stripping punctuation other than
     * hyphens and apostrophes (which legitimately appear in names). "Last, First"
     * is put in natural order; annotations in brackets, leading titles ("Dr",
     * "Uncle") and trailing suffixes ("Jr", "III") are dropped, so the search
     * runs on the given name and surname.
     */
    fun tokenize(rawName: String): List<String> {
        // "Smith, John" (contacts shown last-name-first) reads as "John Smith".
        val natural = rawName.trim().let { n ->
            val before = n.substringBefore(',', "").trim()
            if (before.isNotEmpty() && ' ' !in before) n.substringAfter(',').trim() + " " + before else n
        }
        val tokens = natural
            // "(work)", "[old]": annotations, not names.
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .split(Regex("\\s+"))
            .map { token -> token.filter { it.isLetter() || it == '-' || it == '\'' } }
            .filter { it.isNotEmpty() }
            .toMutableList()
        while (tokens.size > 2 && tokens.first().lowercase() in TITLES) tokens.removeAt(0)
        while (tokens.size > 2 && tokens.last().lowercase() in SUFFIXES) tokens.removeAt(tokens.lastIndex)
        return tokens
    }

    /**
     * Validates a tokenized name. Returns [Result.Ok] with the (first, last) pair
     * if both tokens pass, or [Result.Skip] with a reason describing which rule
     * failed.
     */
    fun validate(tokens: List<String>): Result {
        if (tokens.size < 2) return Result.Skip("mononym")
        val first = tokens.first()
        val last = tokens.last()
        if (!isAcceptableToken(first)) return Result.Skip("first_token_unacceptable")
        if (!isAcceptableToken(last)) return Result.Skip("last_token_unacceptable")
        return Result.Ok(first, last)
    }

    fun validate(rawName: String): Result = validate(tokenize(rawName))

    /**
     * A token is acceptable as a name part if it is at least [MIN_TOKEN_LEN]
     * characters, not a two-letter word, and not in the [BAD_NAME_TOKENS] reject list.
     */
    fun isAcceptableToken(token: String): Boolean {
        if (token.length < MIN_TOKEN_LEN) return false
        val lower = token.lowercase()
        if (token.length == 2 && lower in SHORT_NON_NAMES) return false
        if (BAD_NAME_TOKENS.contains(lower)) return false
        return true
    }

    fun isVerifiable(rawName: String): Boolean = validate(rawName) is Result.Ok
}
