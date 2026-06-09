package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetLite
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.match.MatchRecord
import com.hereliesaz.cleanunderwear.match.ProbabilisticScorer
import com.hereliesaz.cleanunderwear.match.StringSimilarity
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
 */
class DeduplicateTargetsUseCase @Inject constructor(
    private val repository: TargetRepository
) {
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
            onProgress(0.1f, "Scanning registry for duplicates ($offset)...")
        }

        val collisions = groups.values.filter { it.size > 1 }
        if (collisions.isEmpty()) {
            onProgress(1f, "No duplicates found.")
            return 0
        }

        val scorer = ProbabilisticScorer(
            frequency = { field, value ->
                when (field) {
                    "phone" -> phoneFreq[value] ?: 1
                    "email" -> emailFreq[value] ?: 1
                    "surname" -> surnameFreq[value] ?: 1
                    else -> 1
                }
            }
        )

        var processed = 0
        var merged = 0
        var ambiguousSkipped = 0
        val totalSteps = collisions.size

        // 2. Resolve collisions (only hydrating full rows for actual candidates)
        for (idGroup in collisions) {
            val fullGroup = repository.getTargetsByIds(idGroup)
            if (fullGroup.size < 2) continue

            if (fullGroup.size > MAX_PAIRWISE_GROUP) {
                // A block this large is a low-quality collision (a recycled value or a very
                // common name shared by many rows). Pairwise scoring would be O(n^2) and these
                // are exactly the rows we must not blindly fuse — leave them for manual review.
                ambiguousSkipped += fullGroup.size
                processed++
                onProgress(processed.toFloat() / totalSteps, "Skipping ambiguous block (${fullGroup.size} rows)...")
                continue
            }

            for (component in confidentMatchComponents(fullGroup, scorer)) {
                if (component.size < 2) continue

                val winner = pickWinnerFull(component)
                val losers = component.filter { it.id != winner.id }
                val mergedFull = losers.fold(winner) { acc, loser -> mergeFields(acc, loser) }
                repository.updateTarget(mergedFull)
                for (loser in losers) {
                    repository.deleteTarget(loser)
                    merged++
                }
            }

            processed++
            onProgress(
                processed.toFloat() / totalSteps,
                "Resolving duplicates ($processed/$totalSteps)..."
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
        scorer: ProbabilisticScorer
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
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (scorer.score(records[i], records[j]).isConfidentMatch) union(i, j)
            }
        }

        return group.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { group[it] } }
    }

    private fun toMatchRecord(t: Target): MatchRecord = MatchRecord.fromDisplayName(
        displayName = t.displayName,
        phone = t.phoneNumber,
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

    /** Surname frequency key, normalized identically to the scorer's surname down-weight lookup. */
    private fun surnameKey(displayName: String): String? =
        StringSimilarity.normalizeNameToken(MatchRecord.fromDisplayName(displayName).lastName)

    private fun pickWinnerFull(group: List<Target>): Target {
        return group.maxByOrNull { fullFieldScore(it) } ?: group.first()
    }

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
        val phone = t.phoneNumber?.filter { it.isDigit() }?.takeIf { it.length >= 7 }
        if (phone != null) return "phone:$phone"
        val email = t.email?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
        if (email != null) return "email:$email"
        return "name:${t.displayName.lowercase().trim()}"
    }

    private fun mergeFields(into: Target, from: Target): Target {
        val mergedSources = mergeSources(into.sourceAccount, from.sourceAccount)
        return into.copy(
            displayName = preferNonPlaceholder(into.displayName, from.displayName),
            phoneNumber = into.phoneNumber ?: from.phoneNumber,
            email = into.email ?: from.email,
            areaCode = into.areaCode ?: from.areaCode,
            jurisdiction = into.jurisdiction ?: from.jurisdiction,
            residenceInfo = into.residenceInfo ?: from.residenceInfo,
            lockupUrl = into.lockupUrl ?: from.lockupUrl,
            obituaryUrl = into.obituaryUrl ?: from.obituaryUrl,
            sourceAccount = mergedSources,
            lastScrapedTimestamp = maxOf(into.lastScrapedTimestamp, from.lastScrapedTimestamp),
            lastStatusChangeTimestamp = maxOf(into.lastStatusChangeTimestamp, from.lastStatusChangeTimestamp),
            lastVerificationSnippet = into.lastVerificationSnippet ?: from.lastVerificationSnippet
        )
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
        /** Above this candidate-block size we skip pairwise scoring and leave rows for review. */
        const val MAX_PAIRWISE_GROUP = 500
    }
}
