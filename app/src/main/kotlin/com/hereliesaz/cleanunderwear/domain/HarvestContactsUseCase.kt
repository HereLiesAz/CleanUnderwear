package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.ContactHarvester
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import javax.inject.Inject

class HarvestContactsUseCase @Inject constructor(
    private val harvester: ContactHarvester,
    private val repository: TargetRepository,
    private val researchAgent: OnDeviceResearchAgent
) {
    suspend operator fun invoke(allowedSources: Set<String> = emptySet()) {
        // 1. Harvest raw data
        val freshMeat = harvester.harvestContacts(allowedSources)
        DiagnosticLogger.log("Harvested ${freshMeat.size} raw contacts from enabled sources.")
        
        // 2. De-duplicate and apply AI name validation
        val processedTargets = processIntelligence(freshMeat)
        DiagnosticLogger.log("Consolidation complete: ${processedTargets.size} unique targets verified.")
        
        // 3. Commit to the ledger
        repository.insertTargets(withoutKnownNameOnlyRows(processedTargets))
    }

    suspend fun processManualTargets(manualTargets: List<Target>) {
        val processed = processIntelligence(manualTargets)
        repository.insertTargets(withoutKnownNameOnlyRows(processed))
    }

    /**
     * Drops rows with nothing but a name (social imports) that the registry already holds under
     * the same name from the same account. Dedup can't merge name-only pairs, so without this
     * every re-import would add another copy of every friend.
     */
    private suspend fun withoutKnownNameOnlyRows(rows: List<Target>): List<Target> {
        if (rows.none { it.phoneNumber.isNullOrBlank() && it.email.isNullOrBlank() }) return rows
        val known = repository.getTargetWorkInfoPaged(limit = Int.MAX_VALUE, offset = 0)
            .filter { it.phoneNumber.isNullOrBlank() && it.email.isNullOrBlank() }
            .map { nameOnlyKey(it.displayName, it.sourceAccount) }
            .toSet()
        return rows.filterNot {
            it.phoneNumber.isNullOrBlank() && it.email.isNullOrBlank() &&
                nameOnlyKey(it.displayName, it.sourceAccount) in known
        }
    }

    private fun nameOnlyKey(name: String, source: String?) =
        name.trim().lowercase() + "|" + source.orEmpty().trim().lowercase()

    private fun processIntelligence(targets: List<Target>): List<Target> {
        val uniqueTargets = mutableMapOf<String, Target>()
        var rejectedNameCount = 0

        targets.forEach { target ->
            // Same person if the number (or email) and the name agree; two people sharing a
            // landline stay two rows, and formatting differences don't split one person.
            val contactKey = StringSimilarity.normalizePhone(target.phoneNumber)
                ?: StringSimilarity.normalizeEmail(target.email)
                ?: target.sourceAccount.orEmpty()
            val key = contactKey + "|" + target.displayName.trim().lowercase()

            val existing = uniqueTargets[key]
            if (existing != null) {
                // Merge info if duplicate found (prefer non-null fields)
                uniqueTargets[key] = existing.copy(
                    email = existing.email ?: target.email,
                    residenceInfo = existing.residenceInfo ?: target.residenceInfo,
                    sourceAccount = mergeSources(existing.sourceAccount, target.sourceAccount)
                )
            } else {
                // Validate if it's a real name using LiteRT
                val isRealName = researchAgent.validatePersonName(target.displayName)
                val finalTarget = if (!isRealName && target.displayName != "Unnamed Entity") {
                    rejectedNameCount++
                    target.copy(displayName = "Unnamed Entity (${target.displayName})")
                } else target

                uniqueTargets[key] = finalTarget
            }
        }

        if (rejectedNameCount > 0) {
            DiagnosticLogger.log(
                "AI name filter: $rejectedNameCount candidate(s) reclassified as Unnamed Entity."
            )
        }
        return uniqueTargets.values.toList()
    }

    private fun mergeSources(s1: String?, s2: String?): String? {
        val sources = mutableSetOf<String>()
        s1?.split(", ")?.let { sources.addAll(it) }
        s2?.split(", ")?.let { sources.addAll(it) }
        return if (sources.isEmpty()) null else sources.joinToString(", ")
    }
}
