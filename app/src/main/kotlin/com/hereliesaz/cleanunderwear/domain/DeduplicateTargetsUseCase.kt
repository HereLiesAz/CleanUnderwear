package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.ContributionCodec
import com.hereliesaz.cleanunderwear.data.MergeLedger
import com.hereliesaz.cleanunderwear.data.MergeRecord
import com.hereliesaz.cleanunderwear.data.MergeSignature
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.data.TargetLite
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetSnapshot
import com.hereliesaz.cleanunderwear.match.MatchField
import com.hereliesaz.cleanunderwear.match.MatchRecord
import com.hereliesaz.cleanunderwear.match.ProbabilisticScorer
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import javax.inject.Inject

/**
 * Walks the entire repository and merges duplicate Targets into one canonical record per
 * identity.
 *
 * The cheap first pass uses [TargetLite] so we never load the heavy verification-snippet/URL
 * columns for the bulk of rows that won't collide. Sharing a normalized phone, email, or
 * (when neither is present) display name only makes two rows *candidates*; it does not, on its
 * own, mean they are the same person.
 *
 * The actual merge decision is delegated to the [ProbabilisticScorer]. A recycled phone number
 * shared by two people whose surnames clearly differ, or a pair of rows that agree on nothing
 * but a common name, no longer force-merge strangers — they are left apart for a human. Within
 * each candidate block we therefore partition the rows into *confident-match components* (rows
 * the scorer rates [com.hereliesaz.cleanunderwear.match.MatchDecision.MATCH] connect; everything
 * weaker stays separate) and merge only inside each component.
 *
 * The scorer's frequency down-weighting is fed by corpus-wide counts gathered during the scan,
 * so agreeing on a *rare* value keeps full weight while agreeing on a common one ("John Smith",
 * a recycled number) is penalised by its self-information. That is what makes a name-only
 * collision resolve to "not enough data" instead of a wrong merge.
 *
 * The "winning" record within a component is whichever full row carries the most intel; the
 * losers' unique fields are merged into the winner before the losers are deleted.
 *
 * Every merge that could be wrong is written to the [MergeLedger] first (the absorbed row, what it
 * changed on the winner, and the scorer's verdict), so the review screen can find bad merges and
 * undo them. Merging a row into an exact copy of itself, which is what every re-harvest produces,
 * is not recorded: there is nothing there to get wrong or to restore. Pairs the user has split are
 * never merged again.
 */
class DeduplicateTargetsUseCase(
    private val repository: TargetRepository,
    /**
     * Candidate blocks larger than this are skipped rather than scored pairwise. The heuristic:
     * a block this big is almost always a low-quality collision (a recycled value or a very common
     * name shared by many rows), where pairwise scoring is O(n^2) *and* the rows are exactly the
     * ambiguous ones we must not blindly fuse. Exposed so it can be tuned (or driven from config)
     * as block characteristics evolve, and lowered in tests.
     */
    private val maxPairwiseGroup: Int = DEFAULT_MAX_PAIRWISE_GROUP,
    /**
     * First-name → known-variant expansion handed to the [ProbabilisticScorer] so a shared
     * (rare) phone or address can still fuse "Robert Smith" and "Bob Smith". Defaults to no
     * expansion for tests; production supplies [OnDeviceResearchAgent.getNicknames] via the
     * injected constructor below.
     */
    private val nicknames: (String) -> List<String> = { emptyList() },
    /** Where merges are recorded for review and undo. Null skips recording and vetoes (tests). */
    private val ledger: MergeLedger? = null
) {
    @Inject
    constructor(
        repository: TargetRepository,
        researchAgent: OnDeviceResearchAgent,
        ledger: MergeLedger
    ) : this(repository, nicknames = researchAgent::getNicknames, ledger = ledger)

    suspend operator fun invoke(onProgress: (Float, String) -> Unit = { _, _ -> }): Int {
        val groups = mutableMapOf<String, MutableList<Int>>() // identityKey -> list of IDs

        // Corpus-wide value counts that drive the scorer's frequency down-weighting. Gathered
        // in the same chunked pass that builds the candidate blocks so we never re-scan.
        val phoneFreq = HashMap<String, Int>()
        val emailFreq = HashMap<String, Int>()
        val surnameFreq = HashMap<String, Int>()

        var offset = 0
        val chunkSize = 1000

        // 1. Identify collision candidates using memory-efficient chunking
        while (true) {
            val chunk = repository.getTargetsPaged(chunkSize, offset)
            if (chunk.isEmpty()) break

            for (t in chunk) {
                val key = identityKey(t)
                groups.getOrPut(key) { mutableListOf() }.add(t.id)
                tallyFrequencies(t, phoneFreq, emailFreq, surnameFreq)
            }
            offset += chunkSize
            onProgress(0.1f, "Scanning registry for duplicates ($offset)…")
        }

        val collisions = groups.values.filter { it.size > 1 }
        if (collisions.isEmpty()) {
            onProgress(1f, "No duplicates found.")
            return 0
        }

        val scorer = ProbabilisticScorer(
            nicknames = nicknames,
            frequency = { field, value ->
                when (field) {
                    MatchField.PHONE -> phoneFreq[value] ?: 1
                    MatchField.EMAIL -> emailFreq[value] ?: 1
                    MatchField.SURNAME -> surnameFreq[value] ?: 1
                    else -> 1
                }
            }
        )

        val vetoes = ledger?.vetoedPairs().orEmpty()

        var processed = 0
        var merged = 0
        var ambiguousSkipped = 0
        val totalSteps = collisions.size

        // 2. Resolve collisions (only hydrating full rows for actual candidates)
        for (idGroup in collisions) {
            val fullGroup = repository.getTargetsByIds(idGroup)
            if (fullGroup.size < 2) continue

            if (fullGroup.size > maxPairwiseGroup) {
                // A block this large is a low-quality collision (a recycled value or a very
                // common name shared by many rows). Pairwise scoring would be O(n^2) and these
                // are exactly the rows we must not blindly fuse — leave them for manual review.
                ambiguousSkipped += fullGroup.size
                processed++
                onProgress(processed.toFloat() / totalSteps, "Skipping ambiguous block (${fullGroup.size} rows)…")
                continue
            }

            for (component in confidentMatchComponents(fullGroup, scorer, vetoes)) {
                if (component.size < 2) continue

                val winner = pickWinnerFull(component)
                val losers = component.filter { it.id != winner.id }
                // Ledger, survivor and deletions in one transaction: a merge interrupted halfway
                // would leave a ledger entry for a row that still exists, and a split would copy it.
                val applied = repository.inTransaction {
                    // The rows were read before scoring; a decision the user made since (an
                    // archive, a verdict) must not be overwritten by that copy. If any row changed,
                    // this group waits for the next sweep, scored afresh.
                    val now = repository.getTargetsByIds(component.map { it.id }).associateBy { it.id }
                    if (component.any { now[it.id] != it }) return@inTransaction false
                    var acc = winner
                    for (loser in losers) {
                        val next = mergeFields(acc, loser)
                        recordMerge(scorer, survivorBefore = acc, survivorAfter = next, absorbed = loser, winnerId = winner.id)
                        acc = next
                    }
                    repository.updateTarget(acc)
                    for (loser in losers) {
                        ledger?.reassignSurvivor(loser.id, winner.id)
                        repository.deleteTarget(loser)
                    }
                    true
                }
                if (applied) merged += losers.size
            }

            processed++
            onProgress(
                processed.toFloat() / totalSteps,
                "Resolving duplicates ($processed/$totalSteps)…"
            )
        }

        DiagnosticLogger.log(
            "Dedup: collapsed $merged duplicate row(s) across $totalSteps candidate identities" +
                if (ambiguousSkipped > 0) "; left $ambiguousSkipped row(s) in oversized blocks for review" else ""
        )
        return merged
    }

    /**
     * Partition a candidate block into components of rows the scorer is confident denote one
     * person. Two rows join the same component when [ProbabilisticScorer.score] returns
     * [com.hereliesaz.cleanunderwear.match.MatchDecision.MATCH]; weaker verdicts leave them in
     * separate components so they are never merged. Union-find keeps transitive merges correct
     * (A~B and B~C collapse A, B, C together).
     */
    private fun confidentMatchComponents(
        group: List<Target>,
        scorer: ProbabilisticScorer,
        vetoes: Set<String>
    ): List<List<Target>> {
        val n = group.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }

        val records = group.map { toMatchRecord(it) }
        val signatures = if (vetoes.isEmpty()) emptyList() else group.map { MergeSignature.of(it) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                // A pair the user already split stays split, however confident the scorer is.
                if (vetoes.isNotEmpty() && MergeSignature.vetoKey(signatures[i], signatures[j]) in vetoes) continue
                if (scorer.score(records[i], records[j]).isConfidentMatch) union(i, j)
            }
        }

        return group.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { group[it] } }
    }

    /**
     * Write the merge to the ledger, ahead of the update and delete it describes. The verdict is
     * the scorer's for this exact pair, so the review screen can show why the rows were joined.
     */
    private suspend fun recordMerge(
        scorer: ProbabilisticScorer,
        survivorBefore: Target,
        survivorAfter: Target,
        absorbed: Target,
        winnerId: Int
    ) {
        val ledger = ledger ?: return
        // A re-harvested copy that adds nothing and carries no state of its own needs no undo.
        if (isExactCopy(survivorBefore, absorbed) && survivorBefore == survivorAfter && !carriesState(absorbed)) return
        val verdict = scorer.score(toMatchRecord(survivorBefore), toMatchRecord(absorbed))
        ledger.record(
            MergeRecord(
                survivorId = winnerId,
                absorbedJson = TargetSnapshot.toJson(absorbed),
                changesJson = TargetSnapshot.changes(survivorBefore, survivorAfter),
                absorbedName = absorbed.displayName,
                absorbedPhone = absorbed.phoneNumber,
                absorbedEmail = absorbed.email,
                probability = verdict.probability,
                weightBits = verdict.weightBits,
                bestCaseBits = verdict.bestCaseWeightBits,
                contributions = ContributionCodec.encode(verdict.contributions),
                origin = "DEDUP",
                mergedAt = System.currentTimeMillis()
            )
        )
    }

    /** Whether [t] holds anything the user or a check put there, beyond what a fresh import has. */
    private fun carriesState(t: Target): Boolean =
        statusRank(t.status) > statusRank(TargetStatus.MONITORING) ||
            !t.dismissedMatchKeys.isNullOrBlank() || !t.dateOfBirth.isNullOrBlank() || !t.middleName.isNullOrBlank()

    /** Same name, number and email: a re-harvested copy, not a distinct identity. */
    private fun isExactCopy(a: Target, b: Target): Boolean =
        a.displayName.trim().equals(b.displayName.trim(), ignoreCase = true) &&
            StringSimilarity.normalizePhone(a.phoneNumber) == StringSimilarity.normalizePhone(b.phoneNumber) &&
            StringSimilarity.normalizeEmail(a.email) == StringSimilarity.normalizeEmail(b.email)

    private fun toMatchRecord(t: Target): MatchRecord = MatchRecord.fromDisplayName(
        displayName = t.displayName,
        phone = t.usablePhone, // a stale (possibly recycled) number must not vouch for a merge
        email = t.email,
        address = t.residenceInfo,
        areaCode = t.areaCode
    )

    private fun tallyFrequencies(
        t: TargetLite,
        phoneFreq: MutableMap<String, Int>,
        emailFreq: MutableMap<String, Int>,
        surnameFreq: MutableMap<String, Int>
    ) {
        StringSimilarity.normalizePhone(t.phoneNumber)?.let { phoneFreq.merge(it, 1, Int::plus) }
        StringSimilarity.normalizeEmail(t.email)?.let { emailFreq.merge(it, 1, Int::plus) }
        surnameKey(t.displayName)?.let { surnameFreq.merge(it, 1, Int::plus) }
    }

    /**
     * Surname frequency key. Derived from the same [MatchRecord.surnameKey] the scorer reads, so
     * the corpus tally and the down-weight lookup can never drift apart.
     */
    private fun surnameKey(displayName: String): String? =
        MatchRecord.fromDisplayName(displayName).surnameKey

    /**
     * The row that survives: the one holding the weightiest status (a confirmed or pending match,
     * an ignore), then the most complete; ties go to the oldest row.
     */
    private fun pickWinnerFull(group: List<Target>): Target =
        group.sortedBy { it.id }
            .maxWithOrNull(compareBy<Target>({ statusRank(it.status) }, { fullFieldScore(it) }))
            ?: group.first()

    private fun fullFieldScore(t: Target): Int {
        var score = 0
        if (t.displayName.isNotBlank() && t.displayName != "Unnamed Entity") score += 2
        if (!t.phoneNumber.isNullOrBlank()) score++
        if (!t.email.isNullOrBlank()) score++
        if (!t.residenceInfo.isNullOrBlank()) score++
        if (!t.areaCode.isNullOrBlank()) score++
        if (t.lastScrapedTimestamp > 0) score++
        if (t.lockupUrl != null) score++
        if (t.obituaryUrl != null) score++
        return score
    }

    private fun identityKey(t: TargetLite): String {
        // Same normalisation the scorer uses, so "+1 555…" and "(555) …" land in one block.
        val phone = StringSimilarity.normalizePhone(t.phoneNumber)?.takeIf { it.length >= 7 }
        if (phone != null) return "phone:$phone"
        val email = t.email?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
        if (email != null) return "email:$email"
        return "name:${t.displayName.lowercase().trim()}"
    }

    /**
     * Folds [from] into [into]. Identity fields fill gaps; the weightier status wins and brings its
     * match evidence with it; dismissed records are unioned, so a false positive the user rejected
     * on either copy stays rejected. Nothing the absorbed row held is dropped without a trace.
     */
    private fun mergeFields(into: Target, from: Target): Target {
        val mergedSources = mergeSources(into.sourceAccount, from.sourceAccount)
        val statusFrom = if (statusRank(from.status) > statusRank(into.status)) from else into
        val other = if (statusFrom === from) into else from
        // Archiving hides a contact; it doesn't erase its match, which comes back with "Bring back".
        val archived = statusFrom.status == TargetStatus.IGNORED
        return into.copy(
            displayName = preferNonPlaceholder(into.displayName, from.displayName),
            phoneNumber = into.phoneNumber ?: from.phoneNumber,
            stalePhone = into.stalePhone ?: from.stalePhone,
            email = into.email ?: from.email,
            areaCode = into.areaCode ?: from.areaCode,
            jurisdiction = into.jurisdiction ?: from.jurisdiction,
            residenceInfo = into.residenceInfo ?: from.residenceInfo,
            middleName = into.middleName ?: from.middleName,
            dateOfBirth = into.dateOfBirth ?: from.dateOfBirth,
            enrichmentProvenance = into.enrichmentProvenance ?: from.enrichmentProvenance,
            status = statusFrom.status,
            // An archived result keeps what it was before archiving, so "Bring back" restores it;
            // if the archive came from the other copy, this copy's own status is that answer.
            statusBeforeIgnore = if (statusFrom.status == TargetStatus.IGNORED) {
                statusFrom.statusBeforeIgnore ?: other.status.takeIf { it != TargetStatus.IGNORED }
            } else {
                null
            },
            matchRecordKey = statusFrom.matchRecordKey ?: other.matchRecordKey.takeIf { archived },
            unreadSources = statusFrom.unreadSources,
            lockupUrl = statusFrom.lockupUrl ?: into.lockupUrl ?: from.lockupUrl,
            obituaryUrl = statusFrom.obituaryUrl ?: into.obituaryUrl ?: from.obituaryUrl,
            lastVerificationSnippet = statusFrom.lastVerificationSnippet ?: into.lastVerificationSnippet ?: from.lastVerificationSnippet,
            matchConfidence = statusFrom.matchConfidence ?: other.matchConfidence.takeIf { archived },
            matchKind = statusFrom.matchKind ?: other.matchKind.takeIf { archived },
            matchReasons = statusFrom.matchReasons ?: other.matchReasons.takeIf { archived },
            dismissedMatchKeys = unionCsv(into.dismissedMatchKeys, from.dismissedMatchKeys),
            sourceAccount = mergedSources,
            checkFrequencyHours = minOf(into.checkFrequencyHours, from.checkFrequencyHours),
            nextScheduledCheck = minOf(into.nextScheduledCheck, from.nextScheduledCheck),
            lastScrapedTimestamp = maxOf(into.lastScrapedTimestamp, from.lastScrapedTimestamp),
            lastStatusChangeTimestamp = maxOf(into.lastStatusChangeTimestamp, from.lastStatusChangeTimestamp)
        )
    }

    private fun unionCsv(a: String?, b: String?): String? =
        (a.orEmpty().split(',') + b.orEmpty().split(','))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .takeIf { it.isNotEmpty() }?.joinToString(",")

    /** How much a status says about a person: confirmed outranks pending outranks routine. */
    private fun statusRank(status: TargetStatus): Int = when (status) {
        TargetStatus.DECEASED, TargetStatus.INCARCERATED -> 5
        TargetStatus.IGNORED -> 4
        TargetStatus.POSSIBLE_MATCH -> 3
        TargetStatus.WEAK_MATCH -> 2
        TargetStatus.MONITORING -> 1
        TargetStatus.UNVERIFIED, TargetStatus.UNKNOWN -> 0
    }

    private fun preferNonPlaceholder(a: String, b: String): String {
        if (a == "Unnamed Entity" && b != "Unnamed Entity") return b
        if (a.startsWith("Unnamed Entity (") && !b.startsWith("Unnamed Entity (") && b != "Unnamed Entity") return b
        return a
    }

    private fun mergeSources(a: String?, b: String?): String? {
        val parts = mutableSetOf<String>()
        a?.split(", ")?.forEach { if (it.isNotBlank()) parts += it.trim() }
        b?.split(", ")?.forEach { if (it.isNotBlank()) parts += it.trim() }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private companion object {
        /** Default ceiling above which a candidate block is skipped instead of scored pairwise. */
        const val DEFAULT_MAX_PAIRWISE_GROUP = 500
    }
}
