package com.hereliesaz.cleanunderwear.match

/**
 * Pure-JVM string similarity + field normalization used by the matcher. Kept
 * dependency-free so it unit-tests without Robolectric, same as NameValidator.
 */
object StringSimilarity {

    /** Jaro–Winkler similarity in [0,1]. 1.0 = identical. */
    fun jaroWinkler(s1: String, s2: String): Double {
        val a = s1.lowercase()
        val b = s2.lowercase()
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val jaro = jaro(a, b)
        // Winkler bonus for a shared prefix (up to 4 chars).
        var prefix = 0
        val maxPrefix = minOf(4, minOf(a.length, b.length))
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(a: String, b: String): Double {
        val matchDistance = (maxOf(a.length, b.length) / 2) - 1
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)

        var matches = 0
        for (i in a.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var t = 0.0
        var k = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) t += 0.5
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t) / m) / 3.0
    }

    /**
     * A comparable phone key: the last ten digits, or all of them for an 8-9 digit number. Taking
     * the tail drops whatever prefix differs between formats (a US "1", a "+49" country code, a
     * trunk "0"), so "+1 555…" and "(555) …", or "0151 1234 5678" and "+49 151 1234 5678", agree,
     * while two numbers that differ anywhere in their subscriber part stay apart.
     */
    fun normalizePhone(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length >= 10 -> digits.takeLast(10)
            digits.length >= 8 -> digits
            else -> null
        }
    }

    /**
     * The North American area code of [raw], or null when it isn't a NANP number: a "+"-prefixed
     * number with a country code other than 1, or one that isn't 10 digits (11 with a leading 1).
     */
    fun nanpAreaCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("+") && !trimmed.startsWith("+1")) return null
        if (trimmed.startsWith("00")) return null
        val digits = trimmed.filter { it.isDigit() }
        val national = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            else -> return null
        }
        return national.take(3).takeIf { it[0] in '2'..'9' }
    }

    fun normalizeEmail(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.contains("@") && it.isNotBlank() }

    /**
     * [s] with accents removed, one character for one ("José" → "Jose"), so offsets into the
     * folded text are offsets into the original. Rosters are mostly unaccented capitals while
     * phone books keep the accents; both sides are folded before comparing.
     *
     * Only a character that is a base letter plus combining marks is folded. Anything else is
     * left alone: "ß" and "æ" have no decomposition, and a Hangul syllable decomposes into
     * conjoining Jamo (not marks), so taking the first piece would turn "김" into a lone Jamo.
     */
    fun foldDiacritics(s: String): String {
        if (s.all { it.code < 128 }) return s
        val out = StringBuilder(s.length)
        for (c in s) {
            out.append(if (c.code < 128) c else baseOf(c))
        }
        return out.toString()
    }

    private fun baseOf(c: Char): Char {
        val nfd = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        if (nfd.length < 2 || isCombiningMark(nfd[0])) return c
        for (i in 1 until nfd.length) if (!isCombiningMark(nfd[i])) return c
        return nfd[0]
    }

    private fun isCombiningMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** Lowercased letter/hyphen/apostrophe token, for name comparison. */
    fun normalizeNameToken(raw: String?): String? =
        raw?.filter { it.isLetter() || it == '-' || it == '\'' }
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    /** Pull a 5-digit ZIP out of free-form address text. */
    fun extractZip(address: String?): String? {
        if (address.isNullOrBlank()) return null
        return Regex("\\b(\\d{5})(?:-\\d{4})?\\b").find(address)?.groupValues?.get(1)
    }

    /**
     * Street tokens (lowercased words of the first comma-segment, common
     * suffixes folded) for coarse address overlap. "123 Main St" →
     * [123, main, street].
     */
    fun streetTokens(address: String?): Set<String> {
        if (address.isNullOrBlank()) return emptySet()
        val firstSegment = address.split(",").firstOrNull().orEmpty()
        return firstSegment
            .split(Regex("\\s+"))
            .map { it.lowercase().filter { c -> c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .map { STREET_SUFFIXES[it] ?: it }
            .toSet()
    }

    private val STREET_SUFFIXES = mapOf(
        "st" to "street", "rd" to "road", "ave" to "avenue", "blvd" to "boulevard",
        "dr" to "drive", "ln" to "lane", "ct" to "court", "hwy" to "highway",
        "pkwy" to "parkway", "apt" to "apartment"
    )
}
