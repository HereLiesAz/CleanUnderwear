package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.ContactHarvester
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
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
        repository.insertTargets(processedTargets)
    }

    suspend fun processManualTargets(manualTargets: List<Target>) {
        val processed = processIntelligence(manualTargets)
        repository.insertTargets(processed)
    }

    private fun processIntelligence(targets: List<Target>): List<Target> {
        val uniqueTargets = mutableMapOf<String, Target>()
        var rejectedNameCount = 0

        targets.forEach { target ->
            // Use phone number or email as a primary key for de-duplication
            val key = target.phoneNumber ?: target.email ?: target.displayName

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
