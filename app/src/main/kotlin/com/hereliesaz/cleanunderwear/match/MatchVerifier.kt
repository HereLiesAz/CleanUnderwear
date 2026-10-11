package com.hereliesaz.cleanunderwear.match

import kotlin.math.abs
import kotlin.math.pow

/** How much a verified match can be trusted. */
enum class MatchTier {
    /** Evidence beyond the name agrees; worth a notification. */
    STRONG,

    /** Name agrees, little else known either way; shown, flagged weak. */
    WEAK,

    /** A field confidently disagrees (age, middle name): not this person. */
    REJECTED
}

data class Verification(
    val candidate: CandidateRecord,
    val tier: MatchTier,
    /** Calibrated P(this record is the contact), 0..1. */
    val confidence: Double,
    /** Human-readable reasons, for, against and absent, in display order. */
    val reasons: List<String>,
    /** Other sources reporting the same kind of event, counted toward [confidence]. */
    val corroboratingSources: List<String> = emptyList()
)

/**
 * Scores a [CandidateRecord] against a [ContactProfile] before anything is
 * shown to the user.
 *
 * Same log-odds idea as [ProbabilisticScorer] (each agreeing field adds bits,
 * each disagreeing field subtracts), but over the fields records actually
 * publish: age at the time of the event, middle name, city/state, and survivor
 * names. A name hit alone starts deeply negative — a national roster or a news
 * search returns many people with any given name — so only corroborating
 * evidence can lift it to [MatchTier.STRONG].
 *
 * A confident disagreement on age or middle name rejects outright: those are
 * the facts that most often separate two people who share a name.
 */
class MatchVerifier(
    private val config: Config = Config(),
    private val currentYear: Int = java.time.Year.now().value
) {

    data class Config(
        /** Prior odds, in bits, that a bare name hit is the contact. */
        val priorBits: Double = -8.0,
        val nameExact: Double = 2.0,
        val middleAgree: Double = 4.0,
        val middleInitialAgree: Double = 2.0,
        val ageAgree: Double = 6.0,
        /** Ages may differ by this much (birthday timing, event date) and still agree. */
        val ageTolerance: Int = 1,
        val cityAgree: Double = 4.0,
        val stateAgree: Double = 1.5,
        val stateDisagree: Double = -1.5,
        /** Per survivor who is also one of the user's contacts. */
        val survivorKnown: Double = 5.0,
        val survivorCap: Double = 10.0,
        /** Per additional independent source reporting the same event kind. */
        val corroboratingSource: Double = 3.0,
        val corroborationCap: Double = 6.0,
        val strongThreshold: Double = 0.80
    )

    fun verify(
        contact: ContactProfile,
        candidate: CandidateRecord,
        corroboratingSources: List<String> = emptyList()
    ): Verification {
        val reasons = mutableListOf("Name matches")
        var bits = config.priorBits + config.nameExact
        for (signal in listOf(middleName(contact, candidate), age(contact, candidate))) {
            if (signal.rejects) return rejected(candidate, reasons + signal.reason!!)
            bits += signal.bits
            signal.reason?.let { reasons += it }
        }
        for (signal in listOf(location(contact, candidate), survivors(contact, candidate))) {
            bits += signal.bits
            signal.reason?.let { reasons += it }
        }
        val others = corroboratingSources.distinct().filter { it != candidate.sourceId }
        if (others.isNotEmpty()) {
            bits += (others.size * config.corroboratingSource).coerceAtMost(config.corroborationCap)
            reasons += "Also reported by ${others.size} other source${if (others.size == 1) "" else "s"}"
        }
        val p = probability(bits)
        val tier = if (p >= config.strongThreshold) MatchTier.STRONG else MatchTier.WEAK
        return Verification(candidate, tier, p, reasons, others)
    }

    /** One field's contribution: bits, an optional reason, or a rejection. */
    private data class Signal(val bits: Double = 0.0, val reason: String? = null, val rejects: Boolean = false)

    /** A different initial, or a different full middle name, means a different person. */
    private fun middleName(contact: ContactProfile, candidate: CandidateRecord): Signal {
        val c = contact.middleName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Signal()
        val r = candidate.middleName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Signal()
        val bothFull = c.length > 1 && r.length > 1
        return when {
            c.first() != r.first() || (bothFull && c != r) ->
                Signal(reason = "Middle name differs ($c vs $r)", rejects = true)
            bothFull -> Signal(config.middleAgree, "Middle name matches")
            else -> Signal(config.middleInitialAgree, "Middle initial matches")
        }
    }

    /**
     * Compares birth years, so an obituary's age-at-death and the contact's
     * current age line up regardless of when each was recorded.
     */
    private fun age(contact: ContactProfile, candidate: CandidateRecord): Signal {
        val c = contact.birthYear ?: contact.age?.let { currentYear - it }
        val r = candidate.birthYear ?: candidate.age?.let { currentYear - it }
        if (c == null || r == null) return Signal(reason = "Age unknown on ${if (c == null) "contact" else "record"}")
        if (abs(c - r) > config.ageTolerance + 1) return Signal(reason = "Age differs (born ~$r vs ~$c)", rejects = true)
        return Signal(config.ageAgree, "Age matches (born ~$r)")
    }

    /** People move, so a different state counts against only mildly. */
    private fun location(contact: ContactProfile, candidate: CandidateRecord): Signal {
        val cCity = contact.city?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rCity = candidate.city?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val cState = contact.state?.uppercase()
        val rState = candidate.state?.uppercase()
        val statesCompatible = cState == null || rState == null || cState == rState
        return when {
            cCity != null && cCity == rCity && statesCompatible ->
                Signal(config.cityAgree, "Same city (${candidate.city})")
            cState != null && cState == rState -> Signal(config.stateAgree, "Same state ($rState)")
            cState != null && rState != null -> Signal(config.stateDisagree, "Different state ($rState vs $cState)")
            else -> Signal()
        }
    }

    /** Survivors the user also knows: the strongest signal an obituary carries. */
    private fun survivors(contact: ContactProfile, candidate: CandidateRecord): Signal {
        if (candidate.relatives.isEmpty() || contact.otherContactNames.isEmpty()) return Signal()
        val known = contact.otherContactNames.map { it.lowercase() }.toSet()
        val hits = candidate.relatives.filter { it.lowercase() in known }
        if (hits.isEmpty()) return Signal()
        return Signal(
            (hits.size * config.survivorKnown).coerceAtMost(config.survivorCap),
            "Survivors include your contacts: ${hits.joinToString()}"
        )
    }

    /**
     * Verifies every candidate, counts cross-source agreement per event kind,
     * and returns the survivors best-first. Rejected candidates are dropped.
     */
    fun verifyAll(contact: ContactProfile, candidates: List<CandidateRecord>): List<Verification> {
        val firstPass = candidates.map { verify(contact, it) }
            .filter { it.tier != MatchTier.REJECTED }
        val sourcesByKind = firstPass.groupBy { it.candidate.kind }
            .mapValues { (_, v) -> v.map { it.candidate.sourceId }.distinct() }
        return firstPass.map { v ->
            val others = sourcesByKind[v.candidate.kind].orEmpty()
            if (others.size > 1) verify(contact, v.candidate, others) else v
        }.sortedByDescending { it.confidence }
    }

    private fun rejected(candidate: CandidateRecord, reasons: List<String>) =
        Verification(candidate, MatchTier.REJECTED, 0.0, reasons)

    private fun probability(bits: Double): Double {
        val odds = 2.0.pow(bits)
        return odds / (1 + odds)
    }
}
