package com.hereliesaz.cleanunderwear.match

import com.hereliesaz.cleanunderwear.network.NameValidator

/**
 * The normalized, source-agnostic shape both consumers feed the matcher. A
 * harvested `Target`, a cyberbackgroundchecks result card, or a Facebook row
 * all collapse to this — so the comparison logic is written once.
 *
 * Names are stored as first/last tokens (matching how [NameValidator] and the
 * scrape matcher already split display text). A blank/placeholder name yields
 * nulls, so the matcher treats it as "not comparable" rather than a conflict.
 */
data class MatchRecord(
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val areaCode: String? = null,
    val dob: String? = null
) {
    /** True when there is a usable first+last to compare on. */
    val hasUsableName: Boolean
        get() = !firstName.isNullOrBlank() && !lastName.isNullOrBlank()

    companion object {
        private val PLACEHOLDER_PREFIX = "Unnamed Entity"

        /**
         * Build from a display name + optional fields. Splits the name with the
         * same tokenizer the scrape path uses, dropping app placeholders.
         */
        fun fromDisplayName(
            displayName: String?,
            phone: String? = null,
            email: String? = null,
            address: String? = null,
            areaCode: String? = null,
            dob: String? = null
        ): MatchRecord {
            val (first, last) = splitName(displayName)
            return MatchRecord(
                firstName = first,
                lastName = last,
                phone = phone,
                email = email,
                address = address,
                areaCode = areaCode ?: StringSimilarity.normalizePhone(phone)?.take(3),
                dob = dob
            )
        }

        private fun splitName(displayName: String?): Pair<String?, String?> {
            if (displayName.isNullOrBlank()) return null to null
            if (displayName == PLACEHOLDER_PREFIX || displayName.startsWith("$PLACEHOLDER_PREFIX (")) {
                return null to null
            }
            val tokens = NameValidator.tokenize(displayName)
            if (tokens.size < 2) return null to null
            return tokens.first() to tokens.last()
        }
    }
}
