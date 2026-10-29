package com.hereliesaz.cleanunderwear.ui

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus

/*
 * What "Not them" does to a contact, and how it is taken back. Plain functions of a Target, so the
 * round trip (dismiss, undo, forget) can be tested without a screen.
 */

/** The records the user has said are not this contact (a comma-separated list on the row). */
internal fun dismissedKeys(target: Target): List<String> =
    target.dismissedMatchKeys?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/**
 * [target] with its current match rejected: back to daily checks, with the record remembered as
 * dismissed so the same record is never raised again. The record's own key is used, so only this
 * record is dismissed; matches stored before keys existed fall back to the inmate number in the
 * snippet, then the evidence URL.
 */
internal fun dismissed(target: Target, evidenceUrl: String?): Target {
    val key = target.matchRecordKey ?: extractInmateKey(target.lastVerificationSnippet) ?: evidenceUrl
    val keys = (dismissedKeys(target) + listOfNotNull(key)).distinct().joinToString(",")
    return target.copy(
        status = TargetStatus.MONITORING,
        dismissedMatchKeys = keys.ifEmpty { null },
        lastVerificationSnippet = null,
        lockupUrl = null,
        obituaryUrl = null,
        matchConfidence = null,
        matchKind = null,
        matchReasons = null,
        matchRecordKey = null
    )
}

/**
 * Takes a dismissal back: [current] gets the match it had in [before] (the contact as it was when
 * the user pressed "Not them") and the dismissed list it had then, so the record is no longer
 * suppressed. Anything else on [current], such as a detail a check filled in meanwhile, is kept.
 */
internal fun restoreDismissal(current: Target, before: Target): Target = current.copy(
    status = before.status,
    dismissedMatchKeys = before.dismissedMatchKeys,
    lastVerificationSnippet = before.lastVerificationSnippet,
    lockupUrl = before.lockupUrl,
    obituaryUrl = before.obituaryUrl,
    matchConfidence = before.matchConfidence,
    matchKind = before.matchKind,
    matchReasons = before.matchReasons,
    matchRecordKey = before.matchRecordKey
)

/**
 * Forgets every dismissed record: the next check may raise them again. A match already showing,
 * and the status, are left as they are.
 */
internal fun forgetDismissals(target: Target): Target = target.copy(dismissedMatchKeys = null)

/**
 * Pulls the record identifier the verifier embedded as "[#id]" in a possible-
 * match snippet, so "Not them" can remember exactly which record to suppress.
 */
internal fun extractInmateKey(snippet: String?): String? =
    snippet?.let { Regex("\\[#([^\\]]+)]").find(it)?.groupValues?.getOrNull(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }
