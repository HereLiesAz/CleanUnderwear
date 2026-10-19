package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher
import com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher.Findings
import com.hereliesaz.cleanunderwear.network.NameValidator
import javax.inject.Inject

/**
 * What the lookup found after a number was marked stale or a merge was split: who holds the old
 * number now, and a verified replacement number or address, if any. Nothing here is applied until
 * the user accepts it.
 */
data class AlternateInfo(
    val targetId: Int,
    val name: String,
    /** The number that was judged stale; null after a split that left the contact without one. */
    val stalePhone: String?,
    /** Name on the card the old number currently resolves to, if the lookup returned one. */
    val holderName: String?,
    /** True when that name is not the contact: the number now belongs to someone else. */
    val holderDiffers: Boolean,
    val phone: String?,
    val address: String?,
    /** Why the replacement was or wasn't trusted (the enricher's provenance). */
    val basis: String
) {
    val hasReplacement: Boolean get() = phone != null || address != null
}

/**
 * Turns enrichment results into alternates for a suspect contact. Runs the same CyberBackgroundChecks
 * missions Resolve uses (so the user's real browser session does the work), then judges them with the
 * enricher's verify-before-merge rules: a card is adopted only when it corroborates as the same person.
 *
 * The old number is still searched, on purpose. Whoever it returns is its current holder, and a
 * holder with a different name is the clearest sign the number was recycled. That card is evidence
 * about the old number only and never a source of replacement data.
 */
class AlternateFinder @Inject constructor(
    private val enricher: CyberBackgroundChecksEnricher
) {
    fun missions(target: Target): List<BrowserMission> = enricher.pickAllMissions(target)

    fun assess(
        target: Target,
        stalePhone: String?,
        results: List<Pair<BrowserMission, Findings>>
    ): AlternateInfo {
        val staleNumber = StringSimilarity.normalizePhone(stalePhone)
        val holderCards = results.filter { (m, _) ->
            m is BrowserMission.CbcByPhone && staleNumber != null &&
                StringSimilarity.normalizePhone(m.phone) == staleNumber
        }
        val ownCards = results - holderCards.toSet()

        val holder = holderCards.firstNotNullOfOrNull { it.second.name }
        val holderDiffers = holder != null && NameValidator.isVerifiable(target.displayName) &&
            !enricher.namesConsistent(target.displayName, holder)

        if (ownCards.isEmpty()) {
            return AlternateInfo(
                target.id, target.displayName, stalePhone, holder, holderDiffers,
                phone = null, address = null, basis = "no other search found anything"
            )
        }

        // Judge the remaining cards as if the contact had no number: whatever they carry is new.
        val outcome = enricher.enrich(target.copy(phoneNumber = null), ownCards)
        val phone = outcome.target.phoneNumber
            ?.takeIf { outcome.verified && StringSimilarity.normalizePhone(it) != staleNumber }
        val address = outcome.target.residenceInfo
            ?.takeIf { outcome.verified && target.residenceInfo.isNullOrBlank() }

        return AlternateInfo(
            target.id, target.displayName, stalePhone, holder, holderDiffers,
            phone = phone, address = address, basis = outcome.provenance
        )
    }
}
