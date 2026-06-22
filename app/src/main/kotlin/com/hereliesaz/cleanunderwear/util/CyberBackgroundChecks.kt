package com.hereliesaz.cleanunderwear.util

object CyberBackgroundChecks {
    private const val BASE_URL = "https://www.cyberbackgroundchecks.com"

    fun getNameSearchUrl(name: String): String {
        val parts = name.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return "$BASE_URL/name"
        
        val slug = parts.joinToString("-").lowercase()
        return "$BASE_URL/name/$slug"
    }

    fun getPhoneSearchUrl(phone: String): String {
        var digits = phone.filter { it.isDigit() }

        // CBC searches must NEVER include the leading "1" country code.
        // Loop because raw paste can yield "1 1 555..." → "115551234567".
        // Stops once digits drops to 10, so a real 10-digit number that
        // happens to start with "1" (e.g. "1112223333") is left alone.
        while (digits.length >= 11 && digits.startsWith("1")) {
            digits = digits.substring(1)
        }
        if (digits.length < 10) return "$BASE_URL/phone"

        // Anchor on the most-significant 10 digits (area code first), so any
        // trailing extension noise can't shift the area code out of view.
        val core = digits.take(10)
        val formatted = "${core.substring(0, 3)}-${core.substring(3, 6)}-${core.substring(6)}"
        return "$BASE_URL/phone/$formatted"
    }

    /**
     * Builds CBC's `/address/{street}/{city}/{state}` path from whatever shape
     * `residenceInfo` happens to be in. The harvested value is *not* a tidy
     * "street, city, ST zip" string — `ContactHarvester` assembles structured-
     * postal data as `"city, region, zip"` (no street at all), and manual or
     * enrichment-sourced values can be a full street address or a comma-less
     * freeform blob. The old positional parse mapped city→street, state→city,
     * zip→state for the common harvested case, producing a 404 path.
     *
     * Strategy: peel the ZIP and the 2-letter state off the END (the reliable
     * anchors), then read whatever remains as street (optional) + city. Handles:
     *  - "123 Main St, New Orleans, LA 70130"  → /address/123-main-st/new-orleans/la
     *  - "New Orleans, LA, 70130"              → /address/new-orleans/la
     *  - "New Orleans LA 70130" (no commas)    → /address/new-orleans/la
     *  - "123 Main St"                         → /address/123-main-st
     */
    fun getAddressSearchUrl(address: String): String {
        val cleaned = address.trim()
        if (cleaned.isEmpty()) return "$BASE_URL/address"

        // Comma-separated parts, if any. A comma-less freeform string is a
        // single part that the word-level peeling below still untangles.
        val parts = cleaned.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (parts.isEmpty()) return "$BASE_URL/address"

        val zipRegex = Regex("^\\d{5}(-\\d{4})?$")
        fun isState(w: String) = w.length == 2 && w.all { it.isLetter() }

        // Peel a trailing ZIP — its own part ("…, 70130") or the last word of
        // the last part ("LA 70130").
        run {
            val words = parts.last().split(Regex("\\s+")).filter { it.isNotBlank() }
            when {
                words.size == 1 && zipRegex.matches(words[0]) -> parts.removeAt(parts.lastIndex)
                words.size > 1 && zipRegex.matches(words.last()) ->
                    parts[parts.lastIndex] = words.dropLast(1).joinToString(" ")
            }
        }

        // Peel a trailing 2-letter STATE the same way.
        var state = ""
        if (parts.isNotEmpty()) {
            val words = parts.last().split(Regex("\\s+")).filter { it.isNotBlank() }
            when {
                words.size == 1 && isState(words[0]) -> {
                    state = words[0].lowercase()
                    parts.removeAt(parts.lastIndex)
                }
                words.size > 1 && isState(words.last()) -> {
                    state = words.last().lowercase()
                    parts[parts.lastIndex] = words.dropLast(1).joinToString(" ")
                }
            }
        }
        parts.removeAll { it.isBlank() }

        // What survives is street (optional) + city. A leading numeric token
        // marks a street; otherwise the lone remaining part is the city.
        val (street, city) = when {
            parts.isEmpty() -> "" to ""
            parts.size >= 2 -> parts[0] to parts[1]
            parts[0].firstOrNull()?.isDigit() == true -> parts[0] to ""
            else -> "" to parts[0]
        }

        fun slug(s: String) = s.trim().replace(Regex("\\s+"), "-").lowercase()
        val path = buildString {
            slug(street).takeIf { it.isNotEmpty() }?.let { append("/").append(it) }
            slug(city).takeIf { it.isNotEmpty() }?.let { append("/").append(it) }
            state.takeIf { it.isNotEmpty() }?.let { append("/").append(it) }
        }
        return if (path.isEmpty()) "$BASE_URL/address" else "$BASE_URL/address$path"
    }

    fun getEmailSearchUrl(email: String): String {
        // CBC requires the literal email — username@domain.com. Earlier code
        // mangled "@" → "-at-" and "." → "-", which CBC silently rejects.
        val normalized = email.trim().lowercase()
        if (!normalized.contains("@")) return "$BASE_URL/email"
        return "$BASE_URL/email/${encodeEmailPathSegment(normalized)}"
    }

    /**
     * Percent-encode an email for use in a URL path segment, leaving "@" and
     * "." literal (CBC needs to see those) and percent-encoding everything
     * outside the unreserved set. Pure JVM so unit tests don't need Robolectric.
     */
    private fun encodeEmailPathSegment(s: String): String {
        val out = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val keep = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code ||
                c == '~'.code || c == '@'.code
            if (keep) out.append(c.toChar())
            else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }
}
