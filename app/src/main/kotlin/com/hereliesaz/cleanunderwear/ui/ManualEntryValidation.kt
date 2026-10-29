package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.network.NameValidator

/** What is wrong with a hand-entered contact, in the order the form asks for things. */
internal enum class ManualEntryProblem {
    /** Not a first and last name the checks can search on ("Madonna", "B"). */
    NAME,

    /** Neither a phone number nor an email. */
    NO_PHONE_OR_EMAIL,

    /** Something typed as a phone number that isn't one ("abc"). */
    PHONE,

    /** Something typed as an email that isn't one. */
    EMAIL
}

/**
 * Checks the "Add someone" form. The name must be one the daily check can use (a first and a last
 * name, as [NameValidator] decides for contacts read from the phone), at least one of phone and
 * email is needed, and whatever is given must look like what it claims to be. Null when the entry
 * is fine. Blank fields count as not given.
 */
internal fun validateManualEntry(name: String, phone: String, email: String): ManualEntryProblem? {
    if (!NameValidator.isVerifiable(name)) return ManualEntryProblem.NAME
    if (phone.isBlank() && email.isBlank()) return ManualEntryProblem.NO_PHONE_OR_EMAIL
    if (phone.isNotBlank() && !looksLikePhone(phone)) return ManualEntryProblem.PHONE
    if (email.isNotBlank() && !looksLikeEmail(email)) return ManualEntryProblem.EMAIL
    return null
}

/**
 * Digits with the punctuation people type around them ("+1 (555) 010-2030", "555.010.2030"; a
 * "+" only first): 7 to 15 digits, which spans a local number to the longest international one.
 */
internal fun looksLikePhone(raw: String): Boolean {
    val text = raw.trim()
    if (!text.all { it.isDigit() || it in "+-.() " }) return false
    if ('+' in text.drop(1)) return false
    return text.count { it.isDigit() } in 7..15
}

/** One "@", something before it, and a dotted domain after it; not a full address parser. */
internal fun looksLikeEmail(raw: String): Boolean =
    Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$").matches(raw.trim())
