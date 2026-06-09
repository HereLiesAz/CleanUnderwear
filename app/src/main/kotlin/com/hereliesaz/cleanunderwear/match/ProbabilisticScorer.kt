package com.hereliesaz.cleanunderwear.match

import kotlin.math.log2
import kotlin.math.pow

/**
 * Cold-start Fellegi–Sunter scorer. No training data: each comparable field
 * contributes a log2 likelihood-ratio weight; the weights sum to a match weight
 * that converts to a calibrated probability.
 *
 * Two design choices make this *safe* for the app's stakes:
 *
 *  1. **Frequency down-weighting.** Agreeing on a *rare* value (a specific
 *     phone, an uncommon surname) keeps full weight; agreeing on a *common* one
 *     ("John Smith", or a recycled number shared by many rows) is penalised by
 *     its self-information. Two "John Smith"s with nothing else in common come
 *     back INSUFFICIENT, not MATCH.
 *
 *  2. **Sufficiency ceiling.** We also compute the *best possible* weight if
 *     every present field agreed. If even that can't clear the match bar, the
 *     verdict is INSUFFICIENT — the literal "not enough data to decide" answer.
 *
 * A clear surname conflict between two fully-named records carries a strong
 * negative weight, so a shared (possibly recycled) phone never auto-merges
 * people whose names contradict — it lands in REVIEW for a human instead.
 *
 * @param nicknames maps a first name to its known variants (Jim→James …);
 *   defaults to none. Production wires [com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent.getNicknames].
 * @param frequency how many records in the corpus carry this (field,value);
 *   defaults to 1 (treat every value as unique → no penalty).
 */
class ProbabilisticScorer(
    private val config: MatchConfig = MatchConfig(),
    private val nicknames: (String) -> List<String> = { emptyList() },
    private val frequency: (field: String, value: String) -> Int = { _, _ -> 1 }
) : MatchScorer {

    private enum class Level { AGREE, PARTIAL, DISAGREE, CONFLICT, ABSENT }

    override fun score(a: MatchRecord, b: MatchRecord): MatchVerdict {
        val contributions = mutableListOf<FieldContribution>()
        var weight = 0.0
        var bestCase = 0.0

        fun apply(field: String, level: Level, agreeWeight: Double, disagreeWeight: Double) {
            if (level == Level.ABSENT) return
            // The best this field could ever contribute if it agreed perfectly.
            bestCase += agreeWeight.coerceAtLeast(0.0)
            val w = when (level) {
                Level.AGREE -> agreeWeight
                Level.PARTIAL -> (agreeWeight * 0.5)
                Level.DISAGREE -> disagreeWeight
                Level.CONFLICT -> disagreeWeight
                Level.ABSENT -> 0.0
            }
            weight += w
            contributions += FieldContribution(field, level.name, w)
        }

        // --- phone (exact, frequency-aware) ---
        val phoneA = StringSimilarity.normalizePhone(a.phone)
        val phoneB = StringSimilarity.normalizePhone(b.phone)
        if (phoneA != null && phoneB != null) {
            val agrees = phoneA == phoneB
            apply(
                "phone",
                if (agrees) Level.AGREE else Level.DISAGREE,
                if (agrees) downweight("phone", phoneA, config.phoneAgree) else config.phoneAgree,
                config.phoneDisagree
            )
        }

        // --- email (exact, frequency-aware) ---
        val emailA = StringSimilarity.normalizeEmail(a.email)
        val emailB = StringSimilarity.normalizeEmail(b.email)
        if (emailA != null && emailB != null) {
            val agrees = emailA == emailB
            apply(
                "email",
                if (agrees) Level.AGREE else Level.DISAGREE,
                if (agrees) downweight("email", emailA, config.emailAgree) else config.emailAgree,
                config.emailDisagree
            )
        }

        // --- name (nickname-aware, frequency-aware on the surname) ---
        val nameLevel = compareName(a, b)
        val surname = StringSimilarity.normalizeNameToken(a.lastName)
        val nameAgreeWeight = when (nameLevel) {
            Level.AGREE -> surname?.let { downweight("surname", it, config.nameFull) } ?: config.nameFull
            else -> config.nameFull
        }
        apply("name", nameLevel, nameAgreeWeight, config.nameConflict)
        // PARTIAL name uses namePartial directly rather than half of full.
        if (nameLevel == Level.PARTIAL) {
            // undo the generic PARTIAL (half-full) and substitute namePartial
            val idx = contributions.indexOfLast { it.field == "name" }
            if (idx >= 0) {
                weight -= contributions[idx].weightBits
                weight += config.namePartial
                bestCase += config.namePartial - nameAgreeWeight.coerceAtLeast(0.0)
                contributions[idx] = contributions[idx].copy(weightBits = config.namePartial)
            }
        }

        // --- address ---
        val addressLevel = compareAddress(a, b)
        apply("address", addressLevel, config.addressFull, config.addressConflict)
        if (addressLevel == Level.PARTIAL) {
            val idx = contributions.indexOfLast { it.field == "address" }
            if (idx >= 0) {
                weight -= contributions[idx].weightBits
                weight += config.addressPartial
                contributions[idx] = contributions[idx].copy(weightBits = config.addressPartial)
            }
        }

        // --- area code (weak corroboration) ---
        if (!a.areaCode.isNullOrBlank() && !b.areaCode.isNullOrBlank()) {
            val agrees = a.areaCode == b.areaCode
            apply(
                "areaCode",
                if (agrees) Level.AGREE else Level.DISAGREE,
                config.areaCodeAgree,
                config.areaCodeDisagree
            )
        }

        // --- dob ---
        if (!a.dob.isNullOrBlank() && !b.dob.isNullOrBlank()) {
            val agrees = a.dob.trim() == b.dob.trim()
            apply(
                "dob",
                if (agrees) Level.AGREE else Level.DISAGREE,
                config.dobAgree,
                config.dobDisagree
            )
        }

        val probability = bitsToProbability(weight)
        val bestCaseProbability = bitsToProbability(bestCase)

        val decision = when {
            probability >= config.matchThreshold -> MatchDecision.MATCH
            probability <= config.nonMatchThreshold -> MatchDecision.NO_MATCH
            bestCaseProbability < config.matchThreshold -> MatchDecision.INSUFFICIENT
            else -> MatchDecision.REVIEW
        }

        return MatchVerdict(
            decision = decision,
            probability = probability,
            weightBits = weight,
            bestCaseWeightBits = bestCase,
            contributions = contributions
        )
    }

    /**
     * Subtract the value's self-information (log2 of how many records share it)
     * from an agree-weight, floored. freq 1 → no penalty; a value shared by many
     * rows loses most of its discriminating power.
     */
    private fun downweight(field: String, value: String, agreeWeight: Double): Double {
        val count = frequency(field, value).coerceAtLeast(1)
        if (count == 1) return agreeWeight
        val penalty = log2(count.toDouble())
        return (agreeWeight - penalty).coerceAtLeast(config.minAgreeAfterFrequency)
    }

    private fun compareName(a: MatchRecord, b: MatchRecord): Level {
        val firstA = StringSimilarity.normalizeNameToken(a.firstName)
        val lastA = StringSimilarity.normalizeNameToken(a.lastName)
        val firstB = StringSimilarity.normalizeNameToken(b.firstName)
        val lastB = StringSimilarity.normalizeNameToken(b.lastName)

        // Both have surnames: the discriminating case.
        if (lastA != null && lastB != null) {
            val lastSim = StringSimilarity.jaroWinkler(lastA, lastB)
            val firstMatch = firstNamesMatch(firstA, firstB)
            val initialMatch = firstA != null && firstB != null &&
                firstA.first() == firstB.first()
            return when {
                lastSim >= 0.90 && firstMatch -> Level.AGREE
                lastSim >= 0.90 && (firstA == null || firstB == null) -> Level.PARTIAL
                lastSim >= 0.90 && initialMatch -> Level.PARTIAL
                lastSim < 0.85 && firstA != null && firstB != null -> Level.CONFLICT
                else -> Level.ABSENT // ambiguous (e.g. surname typo, missing first) — no evidence
            }
        }

        // Only first names available: weak corroboration at best, never a conflict
        // (we may simply not know the nickname linking them).
        if (firstA != null && firstB != null) {
            return if (firstNamesMatch(firstA, firstB)) Level.PARTIAL else Level.ABSENT
        }

        return Level.ABSENT
    }

    private fun firstNamesMatch(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        if (StringSimilarity.jaroWinkler(a, b) >= 0.92) return true
        val variantsA = (nicknames(a) + a).map { it.lowercase() }.toSet()
        val variantsB = (nicknames(b) + b).map { it.lowercase() }.toSet()
        return variantsA.intersect(variantsB).isNotEmpty()
    }

    private fun compareAddress(a: MatchRecord, b: MatchRecord): Level {
        if (a.address.isNullOrBlank() || b.address.isNullOrBlank()) return Level.ABSENT
        val zipA = StringSimilarity.extractZip(a.address)
        val zipB = StringSimilarity.extractZip(b.address)
        val streetA = StringSimilarity.streetTokens(a.address)
        val streetB = StringSimilarity.streetTokens(b.address)
        val streetOverlap = if (streetA.isEmpty() || streetB.isEmpty()) 0.0
        else streetA.intersect(streetB).size.toDouble() / minOf(streetA.size, streetB.size)

        return when {
            zipA != null && zipA == zipB && streetOverlap >= 0.5 -> Level.AGREE
            (zipA != null && zipA == zipB) || streetOverlap >= 0.5 -> Level.PARTIAL
            zipA != null && zipB != null && zipA != zipB && streetOverlap == 0.0 -> Level.CONFLICT
            else -> Level.ABSENT
        }
    }

    /** Posterior probability from a match weight in bits, under a 0.5 prior. */
    private fun bitsToProbability(bits: Double): Double {
        val odds = 2.0.pow(bits)
        return odds / (1.0 + odds)
    }
}
