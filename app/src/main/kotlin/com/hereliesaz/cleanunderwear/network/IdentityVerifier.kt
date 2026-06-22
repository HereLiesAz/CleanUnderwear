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
    fun verifyBopInmateJson(jsonBody: String, targetName: String): VerificationResult {
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

            val firstMatch = tokensOf(recFirst).any { it in firstCandidates }
            val lastMatch = tokensOf(recLast).contains(targetLast)
            if (!firstMatch || !lastMatch) continue

            // Released inmates carry an actual release date; only current custody
            // counts. Blank/absent actRelDate == still in BOP custody.
            if (stringField(record, "actRelDate").isNotBlank()) continue

            val facility = stringField(record, "faclName").ifBlank { "a federal facility" }
            val projRel = stringField(record, "projRelDate")
            val snippet = buildString {
                append(recFirst.trim()).append(' ').append(recLast.trim())
                append(" — in BOP custody at ").append(facility)
                if (projRel.isNotBlank()) append(" (proj. release ").append(projRel).append(')')
            }
            return VerificationResult(isMatch = true, snippet = snippet)
        }
        return VerificationResult(isMatch = false, snippet = null)
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
        val skipReason: String? = null
    ) {
        companion object {
            fun fetchFailed() = VerificationResult(isMatch = false, snippet = null)
        }
    }
}
