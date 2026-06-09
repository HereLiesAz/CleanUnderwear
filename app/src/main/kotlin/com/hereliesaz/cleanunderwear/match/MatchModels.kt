package com.hereliesaz.cleanunderwear.match

/**
 * Shared entity-resolution vocabulary. One matcher serves BOTH problems in the
 * app:
 *
 *  - **Harvest dedup** — is this `Target` the same person as that `Target`?
 *  - **Enrichment confirmation** — is this cyberbackgroundchecks result card the
 *    same person as the contact we searched for?
 *
 * Both reduce to: given record A and candidate B, score whether they are one
 * person, and — crucially — say when there is *not enough data to safely
 * decide*. That third answer ([MatchDecision.INSUFFICIENT]) is what stops the
 * app fusing two "John Smith"s on a name alone, or attaching a stranger's
 * record to a contact because a phone number was recycled.
 */
enum class MatchDecision {
    /** Confident same person — safe to merge / fill. */
    MATCH,

    /** Confident different people — leave apart. */
    NO_MATCH,

    /**
     * The available evidence is consistent with a match but too weak to confirm
     * (e.g. only a common first name overlaps). Surface to the user; never
     * auto-merge or auto-fill.
     */
    REVIEW,

    /**
     * Not enough comparable, discriminating data exists to *ever* reach a match
     * on these two records — even if every present field agreed perfectly the
     * score could not clear the bar. This is the literal "is there enough data
     * to safely assume the same person?" answer: no.
     */
    INSUFFICIENT
}

/** Per-field audit line so a verdict is explainable, not a black box. */
data class FieldContribution(
    val field: String,
    val level: String,
    val weightBits: Double
)

/**
 * The outcome of comparing two records.
 *
 * @param probability calibrated P(same person) in [0,1] under the configured prior.
 * @param weightBits the Fellegi–Sunter match weight (sum of per-field log2
 *   likelihood ratios). Positive = evidence for, negative = against.
 * @param bestCaseWeightBits the weight this pair *could* reach if every field
 *   present in both records agreed perfectly. Drives the INSUFFICIENT verdict.
 */
data class MatchVerdict(
    val decision: MatchDecision,
    val probability: Double,
    val weightBits: Double,
    val bestCaseWeightBits: Double,
    val contributions: List<FieldContribution>
) {
    val isConfidentMatch: Boolean get() = decision == MatchDecision.MATCH
}

/**
 * Tunable knobs. The defaults are hand-set priors (a cold-start
 * Fellegi–Sunter model that needs no labels); a future learned scorer can
 * supply fitted weights behind the same [MatchScorer] interface without any
 * caller changing.
 */
data class MatchConfig(
    /** Probability at or above which we declare MATCH. */
    val matchThreshold: Double = 0.95,
    /** Probability at or below which we declare NO_MATCH. */
    val nonMatchThreshold: Double = 0.05,

    // Per-field weights, in bits (log2 likelihood ratio).
    val phoneAgree: Double = 9.0,
    val phoneDisagree: Double = -0.5,        // people own several numbers — mild
    val emailAgree: Double = 9.0,
    val emailDisagree: Double = -0.5,
    val nameFull: Double = 4.5,              // first(+nickname) & last both match
    val namePartial: Double = 1.5,           // surname match w/ initial, or first-only overlap
    val nameConflict: Double = -6.0,         // both have full names, surnames clearly differ
    val addressFull: Double = 4.0,           // street + zip
    val addressPartial: Double = 1.0,        // city or zip only
    val addressConflict: Double = -0.3,
    val areaCodeAgree: Double = 0.6,
    val areaCodeDisagree: Double = -0.2,
    val dobAgree: Double = 6.0,
    val dobDisagree: Double = -6.0,

    /** Floor an agree-weight can be pushed to by frequency down-weighting. */
    val minAgreeAfterFrequency: Double = 0.4
)

/**
 * Field keys shared across the frequency-down-weighting boundary. The scorer asks
 * `frequency(field, value)` for these, and every caller that supplies corpus counts must key
 * its tallies by the same strings — so they live here once rather than as bare literals that a
 * typo or refactor on either side could silently diverge.
 */
object MatchField {
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val SURNAME = "surname"
}

/**
 * The one interface every consumer depends on. Swap [ProbabilisticScorer]
 * (cold-start, no labels) for a future LearnedScorer (GBDT/TFLite over the same
 * comparison features) without touching dedup or enrichment.
 */
interface MatchScorer {
    fun score(a: MatchRecord, b: MatchRecord): MatchVerdict
}
