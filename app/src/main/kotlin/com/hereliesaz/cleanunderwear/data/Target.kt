package com.hereliesaz.cleanunderwear.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "targets")
data class Target(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,
    @ColumnInfo(name = "area_code")
    val areaCode: String? = null,
    @ColumnInfo(name = "jurisdiction")
    val jurisdiction: String? = null,
    @ColumnInfo(name = "status")
    val status: TargetStatus = TargetStatus.MONITORING,
    @ColumnInfo(name = "last_scraped_timestamp")
    val lastScrapedTimestamp: Long = 0L,
    @ColumnInfo(name = "source_account")
    val sourceAccount: String? = null,
    @ColumnInfo(name = "residence_info")
    val residenceInfo: String? = null,
    @ColumnInfo(name = "lockup_url")
    val lockupUrl: String? = null,
    @ColumnInfo(name = "obituary_url")
    val obituaryUrl: String? = null,
    @ColumnInfo(name = "check_frequency_hours")
    val checkFrequencyHours: Int = 24,
    @ColumnInfo(name = "next_scheduled_check")
    val nextScheduledCheck: Long = 0L,
    @ColumnInfo(name = "last_status_change_timestamp")
    val lastStatusChangeTimestamp: Long = 0L,
    @ColumnInfo(name = "last_verification_snippet")
    val lastVerificationSnippet: String? = null,
    @ColumnInfo(name = "email")
    val email: String? = null,
    @ColumnInfo(name = "monitorability_state")
    val monitorabilityState: MonitorabilityState = MonitorabilityState.READY,
    /**
     * Human-readable provenance for the last enrichment merge: which search
     * mode(s) sourced the data and whether the candidate identity was verified
     * against this contact. Lets the operator see *why* a value was written and
     * reverse a bad merge. Null until the contact is enriched.
     */
    @ColumnInfo(name = "enrichment_provenance")
    val enrichmentProvenance: String? = null,
    /**
     * Contact's middle name, captured from a CyberBackgroundChecks detail page
     * during enrichment. Used to corroborate a same-name roster/BOP hit before
     * it is ever surfaced as a possible match. Null until enriched.
     */
    @ColumnInfo(name = "middle_name")
    val middleName: String? = null,
    /**
     * Contact's date of birth or age, captured from a CyberBackgroundChecks
     * detail page. Free-form (the site exposes "Age N" and/or a DOB); compared
     * loosely against a roster record's age. Null until enriched.
     */
    @ColumnInfo(name = "date_of_birth")
    val dateOfBirth: String? = null,
    /**
     * CSV of record identifiers (e.g. BOP inmate numbers) the user explicitly
     * marked "not a match" for this contact. The vigil skips these so a
     * dismissed false positive does not resurface on the next run.
     */
    @ColumnInfo(name = "dismissed_match_keys")
    val dismissedMatchKeys: String? = null
)

enum class TargetStatus {
    MONITORING,
    UNVERIFIED,
    /**
     * A name match was found on a roster (e.g. the Federal BOP locator) but it
     * is NOT confirmed — name alone cannot prove identity. The candidate is
     * surfaced to the user (see [Target.lastVerificationSnippet] / [Target.lockupUrl])
     * to confirm (→ [INCARCERATED]) or mark a mismatch. The vigil never sets
     * [INCARCERATED] on its own.
     */
    POSSIBLE_MATCH,
    INCARCERATED,
    DECEASED,
    IGNORED,
    UNKNOWN
}

/**
 * Lightweight projection of [Target] used by the list UI.
 */
/**
 * Lightweight projection of [Target] used by the list UI.
 *
 * This is kept as small as possible to handle registries with 8000+ contacts without 
 * hitting Android's 2MB CursorWindow limit. Extra details for the menu are loaded on-demand.
 */
data class TargetLite(
    @ColumnInfo(name = "id")
    val id: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String?,
    @ColumnInfo(name = "area_code")
    val areaCode: String?,
    @ColumnInfo(name = "status")
    val status: TargetStatus,
    @ColumnInfo(name = "email")
    val email: String?,
    @ColumnInfo(name = "last_status_change_timestamp")
    val lastStatusChangeTimestamp: Long,
    @ColumnInfo(name = "last_scraped_timestamp")
    val lastScrapedTimestamp: Long
)

/**
 * Used for background management tasks (Triage/Dedup) where we need more metadata 
 * than the UI, but still want to avoid heavy verification snippet blobs.
 */
data class TargetWorkInfo(
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "phone_number") val phoneNumber: String?,
    @ColumnInfo(name = "email") val email: String?,
    @ColumnInfo(name = "area_code") val areaCode: String?,
    @ColumnInfo(name = "status") val status: TargetStatus,
    @ColumnInfo(name = "residence_info") val residenceInfo: String?,
    @ColumnInfo(name = "source_account") val sourceAccount: String?,
    @ColumnInfo(name = "monitorability_state") val monitorabilityState: MonitorabilityState,
    @ColumnInfo(name = "last_scraped_timestamp") val lastScrapedTimestamp: Long
)

/**
 * Lightweight projection of source-related fields. Kept around for any caller
 * that wants to inspect stored URLs in bulk without loading verification
 * snippets.
 */
data class TargetSourceInfo(
    @ColumnInfo(name = "id")
    val id: Int,
    @ColumnInfo(name = "area_code")
    val areaCode: String?,
    @ColumnInfo(name = "residence_info")
    val residenceInfo: String?,
    @ColumnInfo(name = "lockup_url")
    val lockupUrl: String?,
    @ColumnInfo(name = "obituary_url")
    val obituaryUrl: String?
)

/**
 * Whether a Target has the minimum identifying data required for the daily monitoring scrape.
 *
 *  READY               — has a usable name + at least one of phone or location/area code
 *  NEEDS_ENRICHMENT    — missing critical fields; queued for cyberbackgroundchecks lookup
 *  ENRICHMENT_FAILED   — cybg lookup returned nothing usable; will retry on a slower cadence
 *  NO_AUTOMATED_SOURCE — has enough identity to scrape, but the contact's locale
 *                        resolves to zero *automatable* sources (everything in the
 *                        catalog for that area is operator-launch-only / MANUAL_LANDING).
 *                        The daily vigil cannot confirm a status change for this contact;
 *                        it must be checked manually via the in-app source chips. Surfaced
 *                        instead of silently logging "no automated source" so the operator
 *                        knows the vigil is not covering this person.
 */
enum class MonitorabilityState {
    READY,
    NEEDS_ENRICHMENT,
    ENRICHMENT_FAILED,
    NO_AUTOMATED_SOURCE
}

