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

    /** Strip to a comparable 10-digit phone, dropping a leading US "1". */
    fun normalizePhone(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var digits = raw.filter { it.isDigit() }
        while (digits.length >= 11 && digits.startsWith("1")) digits = digits.substring(1)
        return digits.takeIf { it.length >= 10 }?.take(10)
    }

    fun normalizeEmail(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.contains("@") && it.isNotBlank() }

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
