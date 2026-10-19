package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.CommsHistory
import com.hereliesaz.cleanunderwear.data.MergeLedgerDao
import com.hereliesaz.cleanunderwear.data.MergeSignature
import com.hereliesaz.cleanunderwear.data.MergeState
import com.hereliesaz.cleanunderwear.data.ContributionCodec
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.SuspectDecision
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetSnapshot
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.data.TargetWorkInfo
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import javax.inject.Inject

/** The review queue plus whether call/SMS history could be read to build it. */
data class SuspectReport(val cases: List<SuspectCase>, val historyAvailable: Boolean)

/**
 * The correction step: finds contacts that look wrongly merged or carry a stale number, and
 * applies the user's verdict. Nothing is changed by [find]; every change is a deliberate call to
 * [split], [keepMerged], [markStale], [adoptReplacement] or [dismiss].
 *
 * Callers run the mutating calls inside [PipelineCoordinator.runExclusive] so they never interleave
 * with a dedup pass.
 */
class SuspectReview @Inject constructor(
    private val repository: TargetRepository,
    private val ledger: MergeLedgerDao,
    private val history: CommsHistory
) {

    suspend fun find(onProgress: (Float, String) -> Unit = { _, _ -> }): SuspectReport {
        val now = System.currentTimeMillis()
        onProgress(0.05f, "Reading call and text history...")
        val index = history.load()
        val historyAvailable = index != null
        val coverage = index?.oldestMillis?.let { now - it }

        // One pass over the registry: enough to name every contact and group them by number.
        val byId = HashMap<Int, TargetWorkInfo>()
        val byNumber = HashMap<String, MutableList<TargetWorkInfo>>()
        var offset = 0
        while (true) {
            val chunk = repository.getTargetWorkInfoPaged(CHUNK, offset)
            if (chunk.isEmpty()) break
            for (t in chunk) {
                byId[t.id] = t
                StringSimilarity.normalizePhone(t.phoneNumber)?.let { byNumber.getOrPut(it) { mutableListOf() } += t }
            }
            offset += CHUNK
            onProgress(0.3f, "Scanning registry ($offset)...")
        }

        val decided = ledger.getDecidedKeys().toHashSet()
        val cases = mutableListOf<SuspectCase>()
        val replacements = HashMap<Int, ReplacementCandidate>()

        // Bad merges, from the ledger.
        onProgress(0.5f, "Weighing merges...")
        for (m in ledger.getActiveSummaries()) {
            val survivor = byId[m.survivorId] ?: continue
            val survivorHistory = index?.statsFor(survivor.phoneNumber)
            val absorbedHistory = index?.statsFor(m.absorbedPhone)
            val survivorNumber = StringSimilarity.normalizePhone(survivor.phoneNumber)
            val sharers = survivorNumber?.let { byNumber[it].orEmpty().count { o -> o.id != survivor.id } } ?: 0

            SuspectDetector.assessMerge(
                MergeCaseInput(
                    ledgerId = m.id,
                    survivorId = survivor.id,
                    survivorName = survivor.displayName,
                    survivorPhone = survivor.phoneNumber,
                    survivorEmail = survivor.email,
                    absorbedName = m.absorbedName,
                    absorbedPhone = m.absorbedPhone,
                    absorbedEmail = m.absorbedEmail,
                    probability = m.probability,
                    contributions = ContributionCodec.decode(m.contributions),
                    mergedAtMillis = m.mergedAt,
                    survivorHistory = survivorHistory,
                    absorbedHistory = absorbedHistory,
                    historyAvailable = historyAvailable,
                    survivorPhoneSharers = sharers
                ),
                now
            )?.let { cases += it }

            // An absorbed number with fresher traffic than the one on file is the live line.
            val absorbedNumber = StringSimilarity.normalizePhone(m.absorbedPhone)
            if (absorbedNumber != null && absorbedNumber != survivorNumber && absorbedHistory != null &&
                (survivorHistory == null || absorbedHistory.lastMillis > survivorHistory.lastMillis)
            ) {
                val current = replacements[survivor.id]
                if (current == null || absorbedHistory.lastMillis > current.history.lastMillis) {
                    replacements[survivor.id] = ReplacementCandidate(m.absorbedPhone!!, m.absorbedName, absorbedHistory)
                }
            }
        }

        // Stale numbers, from every contact with one.
        onProgress(0.75f, "Checking numbers...")
        for (t in byId.values) {
            val phone = t.phoneNumber ?: continue
            val number = StringSimilarity.normalizePhone(phone) ?: continue
            if (t.status == TargetStatus.IGNORED) continue
            val sharers = byNumber[number].orEmpty().filter { it.id != t.id }.map { NumberSharer(it.displayName) }

            SuspectDetector.assessStale(
                StaleCaseInput(
                    targetId = t.id,
                    name = t.displayName,
                    phone = phone,
                    email = t.email,
                    lookupFailed = t.monitorabilityState == MonitorabilityState.ENRICHMENT_FAILED,
                    history = index?.statsFor(phone),
                    historyAvailable = historyAvailable,
                    historyCoverageMillis = coverage,
                    sharers = sharers,
                    replacement = replacements[t.id]
                ),
                now
            )?.let { cases += it }
        }

        val open = cases.filter { it.key !in decided }
            .sortedWith(compareByDescending<SuspectCase> { it.net }.thenBy { it.title.lowercase() })
        onProgress(1f, "${open.size} to review.")
        return SuspectReport(open, historyAvailable)
    }

    /**
     * Undo a merge: restore the absorbed row, give the survivor back what the merge overwrote, and
     * remember the pair so the next dedup pass doesn't fuse them again.
     */
    suspend fun split(ledgerId: Int): Boolean {
        val record = ledger.getById(ledgerId) ?: return false
        if (record.state != MergeState.ACTIVE) return false

        val absorbed = TargetSnapshot.fromJson(record.absorbedJson).copy(id = 0)
        val survivor = repository.getTargetById(record.survivorId)
        val restoredSurvivor = survivor?.let { TargetSnapshot.revert(it, record.changesJson) }

        if (restoredSurvivor != null) repository.updateTarget(restoredSurvivor)
        repository.insertTarget(absorbed)

        val a = MergeSignature.of(restoredSurvivor ?: absorbed)
        ledger.markSplit(ledgerId, a, MergeSignature.of(absorbed))
        DiagnosticLogger.log("Split merge #$ledgerId: restored \"${absorbed.displayName}\"")
        return true
    }

    /** The merge was right. Stop asking. */
    suspend fun keepMerged(ledgerId: Int) = ledger.setState(ledgerId, MergeState.KEPT)

    /**
     * The number is dead. Recorded and removed from the queue; the number itself stays on the
     * contact, because deleting it would only bring it back as a duplicate row on the next harvest.
     */
    suspend fun markStale(case: SuspectCase) = decide(case, "STALE")

    /** Swap the contact's number for the live one found among its merged-in numbers. */
    suspend fun adoptReplacement(case: SuspectCase): Boolean {
        val phone = case.replacementPhone ?: return false
        val target = repository.getTargetById(case.targetId) ?: return false
        repository.updateTarget(
            target.copy(
                phoneNumber = phone,
                areaCode = StringSimilarity.normalizePhone(phone)?.take(3) ?: target.areaCode
            )
        )
        decide(case, "ADOPTED")
        return true
    }

    /** The number is fine. Stop asking about it. */
    suspend fun dismiss(case: SuspectCase) = decide(case, "FINE")

    private suspend fun decide(case: SuspectCase, decision: String) =
        ledger.insertDecision(SuspectDecision(case.key, decision, System.currentTimeMillis()))

    private companion object {
        const val CHUNK = 1000
    }
}
