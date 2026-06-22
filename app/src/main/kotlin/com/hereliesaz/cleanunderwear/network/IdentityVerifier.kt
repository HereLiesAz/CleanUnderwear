package com.hereliesaz.cleanunderwear.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a target's name appears in a fetched roster/locator page.
 *
 * The matcher requires *both* a first and a last token to appear in proximity
 * with word-boundary anchors, so a contact named "B" never matches the letter
 * "B" inside "Bob" on some unrelated booking page.
 */
@Singleton
class IdentityVerifier @Inject constructor(
    private val researchAgent: OnDeviceResearchAgent
) {
    fun verifyIdentity(documentText: String, targetName: String): VerificationResult {
        return when (val v = NameValidator.validate(targetName)) {
            is NameValidator.Result.Skip -> VerificationResult(
                isMatch = false,
                snippet = null,
                skipped = true,
                skipReason = v.reason
            )
            is NameValidator.Result.Ok -> matchProximity(documentText, v.first, v.last)
        }
    }

    private fun matchProximity(
        documentText: String,
        firstName: String,
        lastName: String
    ): VerificationResult {
        val firstNameCandidates = (listOf(firstName) + researchAgent.getNicknames(firstName))
            .filter { NameValidator.isAcceptableToken(it) }
            .distinct()

        val lastQ = Regex.escape(lastName)
        for (firstAlt in firstNameCandidates) {
            val firstQ = Regex.escape(firstAlt)
            // "First [up to 3 intervening tokens] Last"
            val proximity = Regex("(?i)\\b$firstQ\\b(?:\\s+\\S+){0,3}\\s+\\b$lastQ\\b")
            // "Last, First"
            val inverted = Regex("(?i)\\b$lastQ\\b\\s*,\\s*\\b$firstQ\\b")

            val match = proximity.find(documentText) ?: inverted.find(documentText)
            if (match != null) {
                return VerificationResult(
                    isMatch = true,
                    snippet = extractSnippet(documentText, match.value, match.range.first)
                )
            }
        }
        return VerificationResult(isMatch = false, snippet = null)
    }

    /**
     * Verifies a target against the Federal Bureau of Prisons inmate-locator
     * JSON API (`bop.gov/PublicInfo/execute/inmateloc?...&output=json`).
     *
     * The BOP API returns records in `{"InmateLocator":[{...}]}` shape with
     * `nameFirst` / `nameLast` as *separate* compact (no-whitespace) fields, so
     * the text-proximity matcher in [verifyIdentity] cannot match it — names are
     * emitted last-then-first with punctuation between them. This path parses the
     * JSON structurally and matches on the discrete name fields instead.
     *
     * It also enforces *current custody*: the locator returns released inmates
     * too (those carry a non-blank `actRelDate`), and a person released years ago
     * must NOT flip a contact to INCARCERATED. Only a record with a blank actual
     * release date counts as a match.
     */
    fun verifyBopInmateJson(
        jsonBody: String,
        targetName: String,
        corroboration: Corroboration = Corroboration(),
        dismissedKeys: Set<String> = emptySet()
    ): VerificationResult {
        val name = when (val v = NameValidator.validate(targetName)) {
            is NameValidator.Result.Skip -> return VerificationResult(
                isMatch = false, snippet = null, skipped = true, skipReason = v.reason
            )
            is NameValidator.Result.Ok -> v
        }

        val records = try {
            val root = JsonParser.parseString(jsonBody)
            if (!root.isJsonObject) return VerificationResult(isMatch = false, snippet = null)
            inmateArray(root.asJsonObject)
        } catch (e: Exception) {
            // Malformed body (HTML error page, captcha wall, truncated JSON):
            // treat as a clean miss, not a crash — the queue keeps moving.
            return VerificationResult(isMatch = false, snippet = null)
        } ?: return VerificationResult(isMatch = false, snippet = null)

        val firstCandidates = (listOf(name.first) + researchAgent.getNicknames(name.first))
            .filter { NameValidator.isAcceptableToken(it) }
            .map { it.lowercase() }
            .distinct()
        val targetLast = name.last.lowercase()

        for (element in records) {
            if (!element.isJsonObject) continue
            val record = element.asJsonObject
            val recFirst = stringField(record, "nameFirst")
            val recLast = stringField(record, "nameLast")
            if (recFirst.isBlank() || recLast.isBlank()) continue

            // Exact name match (not token-contains): a compound record first
            // name like "JOHN-PAUL" must NOT match a contact "John". Nickname
            // expansion is still honored via firstCandidates.
            val recFirstNorm = recFirst.lowercase().trim().replace(Regex("\\s+"), " ")
            val firstMatch = recFirstNorm in firstCandidates
            val lastMatch = tokensOf(recLast).contains(targetLast)
            if (!firstMatch || !lastMatch) continue

            // Released inmates carry an actual release date; only current custody
            // counts. Blank/absent actRelDate == still in BOP custody.
            if (stringField(record, "actRelDate").isNotBlank()) continue

            // Records the user already marked "not a match" must not resurface.
            val inmateNum = stringField(record, "inmateNum")
            if (inmateNum.isNotBlank() && inmateNum in dismissedKeys) continue

            // Corroborate against enrichment data. A clear conflict (different
            // middle name / age) means this same-name record is NOT our contact
            // — skip it and keep scanning. This is what removes false positives.
            val recMiddle = stringField(record, "nameMiddle")
            val recAge = stringField(record, "age")
            val corro = assessCorroboration(corroboration, recMiddle, recAge)
            if (corro.conflict) continue

            val facility = stringField(record, "faclName").ifBlank { "a federal facility" }
            val projRel = stringField(record, "projRelDate")
            val snippet = buildString {
                append(recFirst.trim())
                if (recMiddle.isNotBlank()) append(' ').append(recMiddle.trim())
                append(' ').append(recLast.trim())
                if (recAge.isNotBlank()) append(", age ").append(recAge)
                append(" — BOP custody at ").append(facility)
                if (projRel.isNotBlank()) append(" (proj. release ").append(projRel).append(')')
                if (inmateNum.isNotBlank()) append(" [#").append(inmateNum).append(']')
                corroboration.area?.takeIf { it.isNotBlank() }?.let {
                    append(". Contact area: ").append(it)
                }
                append(". Corroboration: ").append(corro.basis)
                append(". Verify this is the right person before confirming.")
            }
            return VerificationResult(
                isMatch = true,
                snippet = snippet,
                matchKey = inmateNum.ifBlank { null },
                basis = corro.basis
            )
        }
        return VerificationResult(isMatch = false, snippet = null)
    }

    private data class CorroborationResult(val conflict: Boolean, val basis: String)

    /**
     * Compares enrichment-sourced identity data against a candidate record.
     * Returns `conflict = true` only on a *confident* disagreement (different
     * middle-name initial, or ages differing by more than a year), which rejects
     * the record. Agreement is reported in [CorroborationResult.basis] to drive
     * the confidence shown in review; absent data is neither agreement nor
     * conflict — the match simply stays "name only".
     */
    private fun assessCorroboration(
        c: Corroboration,
        recMiddle: String,
        recAge: String
    ): CorroborationResult {
        val signals = mutableListOf<String>()

        val cMid = c.middleName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val rMid = recMiddle.trim().lowercase().takeIf { it.isNotBlank() }
        if (cMid != null && rMid != null) {
            if (cMid.first() != rMid.first()) {
                return CorroborationResult(true, "middle-name conflict ($cMid ≠ $rMid)")
            }
            signals += "middle name"
        }

        val cAge = approxAge(c.dob)
        val rAge = recAge.toIntOrNull()
        if (cAge != null && rAge != null) {
            if (kotlin.math.abs(cAge - rAge) > 1) {
                return CorroborationResult(true, "age conflict ($cAge ≠ $rAge)")
            }
            signals += "age"
        }

        val basis = if (signals.isEmpty()) {
            "name only (uncorroborated)"
        } else {
            signals.joinToString(" + ") + " corroborated"
        }
        return CorroborationResult(false, basis)
    }

    /**
     * Best-effort age from a free-form DOB/age string: a bare 1–120 integer is
     * treated as an age; otherwise a 4-digit birth year yields an approximate
     * age. Returns null when nothing usable is present.
     */
    private fun approxAge(dob: String?): Int? {
        val s = dob?.trim()?.takeIf { it.isNotBlank() } ?: return null
        s.toIntOrNull()?.let { if (it in 1..120) return it }
        val year = Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull()
        if (year != null) {
            val age = java.time.Year.now().value - year
            if (age in 0..120) return age
        }
        // CBC's `.age` field is the literal "Age 45" — pull a bare number out so
        // age corroboration isn't silently skipped. (Checked after the year rule
        // so a 4-digit birth year is never misread as an age.)
        Regex("\\b\\d{1,3}\\b").find(s)?.value?.toIntOrNull()?.let { if (it in 1..120) return it }
        return null
    }

    /** Case-insensitively locate the `InmateLocator` array in the BOP payload. */
    private fun inmateArray(root: JsonObject): JsonArray? {
        root.entrySet().firstOrNull { it.key.equals("InmateLocator", ignoreCase = true) }
            ?.value?.let { if (it.isJsonArray) return it.asJsonArray }
        return null
    }

    private fun stringField(obj: JsonObject, key: String): String {
        val entry = obj.entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        return if (entry != null && entry.isJsonPrimitive) entry.asString.trim() else ""
    }

    private fun tokensOf(value: String): List<String> =
        value.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }

    private fun extractSnippet(fullText: String, match: String, matchIndex: Int = -1): String {
        val idx = if (matchIndex >= 0) matchIndex else fullText.indexOf(match, ignoreCase = true)
        if (idx == -1) return match

        val start = (idx - 100).coerceAtLeast(0)
        val end = (idx + match.length + 100).coerceAtMost(fullText.length)

        return "..." + fullText.substring(start, end).replace("\n", " ").trim() + "..."
    }

    data class VerificationResult(
        val isMatch: Boolean,
        val snippet: String?,
        val skipped: Boolean = false,
        val skipReason: String? = null,
        /**
         * Stable identifier of the matched record (BOP inmate number) so the
         * pipeline can remember a user-dismissed false positive and not
         * resurface it. Null for the text-proximity path.
         */
        val matchKey: String? = null,
        /**
         * Human-readable corroboration basis for a match (e.g. "middle name +
         * age corroborated" or "name only (uncorroborated)"). Drives the
         * confidence shown in the review UI.
         */
        val basis: String? = null
    ) {
        companion object {
            fun fetchFailed() = VerificationResult(isMatch = false, snippet = null)
        }
    }

    /**
     * Identity data we can corroborate a same-name roster hit against, sourced
     * from a contact's CyberBackgroundChecks enrichment. All optional — when a
     * field is absent we simply can't use it (the match stays a low-confidence
     * "name only" candidate); when present and it *conflicts*, the record is
     * rejected outright, which is what kills the common-name false positives.
     */
    data class Corroboration(
        val middleName: String? = null,
        val dob: String? = null,
        val area: String? = null
    )
}
