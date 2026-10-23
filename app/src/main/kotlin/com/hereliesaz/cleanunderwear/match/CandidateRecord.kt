package com.hereliesaz.cleanunderwear.match

/**
 * What a records hit claims happened to the person it names.
 *
 * CUSTODY comes from a roster or locator (currently held); ARREST from a news
 * report or booking log (arrested, not necessarily still held); DEATH from an
 * obituary or death notice.
 */
enum class EventKind { CUSTODY, ARREST, DEATH }

/**
 * One person-shaped record pulled from a source, before verification.
 *
 * Every field except the name is optional: a BOP record carries age and a
 * middle name, an obituary carries age, city and survivors, a news item may
 * carry only a city. [RecordExtractor] fills these from free text; structured
 * sources fill them directly. [MatchVerifier] scores whatever is present.
 *
 * @param recordKey stable id for "not a match" dismissal (inmate number, or
 *   the evidence URL when the source has no id).
 * @param relatives full names listed as survivors / family ("survived by …").
 * @param inferredRelatives survivors named by first name only ("his wife, Mary"), completed with
 *   the deceased's surname. A guess, so it counts for far less than a name the record spells out.
 * @param eventYear the year of the event [age] refers to (an obituary's death year, a news
 *   item's publication year). Null means "about now".
 */
data class CandidateRecord(
    val sourceId: String,
    val sourceLabel: String,
    val kind: EventKind,
    val firstName: String,
    val lastName: String,
    val middleName: String? = null,
    val age: Int? = null,
    val birthYear: Int? = null,
    val city: String? = null,
    val state: String? = null,
    val relatives: List<String> = emptyList(),
    val inferredRelatives: List<String> = emptyList(),
    val eventYear: Int? = null,
    val recordKey: String? = null,
    val evidenceUrl: String? = null,
    val snippet: String? = null
)

/**
 * What we know about the contact, for comparison against a [CandidateRecord].
 *
 * @param age current age if known (from enrichment); [birthYear] is derived
 *   from it or from a DOB.
 * @param otherContactNames full names of the user's *other* contacts. A
 *   survivor list naming people the user also knows is strong evidence the
 *   obituary is about someone in their circle.
 */
data class ContactProfile(
    val firstName: String,
    val lastName: String,
    val middleName: String? = null,
    val age: Int? = null,
    val birthYear: Int? = null,
    val city: String? = null,
    val state: String? = null,
    val nicknames: List<String> = emptyList(),
    val otherContactNames: Set<String> = emptySet()
)
