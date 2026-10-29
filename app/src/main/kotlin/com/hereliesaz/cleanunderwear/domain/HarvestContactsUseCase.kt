package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.ContactHarvester
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.match.StringSimilarity
import com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import javax.inject.Inject

class HarvestContactsUseCase @Inject constructor(
    private val harvester: ContactHarvester,
    private val repository: TargetRepository,
    private val researchAgent: OnDeviceResearchAgent
) {
    /** What a read of the phone's contacts did, for the person who started it. */
    data class Result(val read: Int, val removed: Int)

    suspend operator fun invoke(allowedSources: Set<String> = emptySet()): Result {
        // 1. Harvest raw data
        val freshMeat = harvester.harvestContacts(allowedSources)
        DiagnosticLogger.log("Harvested ${freshMeat.size} raw contacts from enabled sources.")
        
        // 2. De-duplicate and apply AI name validation
        val processedTargets = processIntelligence(freshMeat)
        DiagnosticLogger.log("Consolidation complete: ${processedTargets.size} unique targets verified.")
        
        // 3. Commit to the ledger
        repository.insertTargets(withoutKnownRows(processedTargets))

        // 4. Contacts deleted from the phone stop being checked.
        val removed = if (allowedSources.isEmpty()) pruneDeleted(freshMeat) else 0
        return Result(freshMeat.size, removed)
    }

    suspend fun processManualTargets(manualTargets: List<Target>) {
        val processed = processIntelligence(manualTargets)
        repository.insertTargets(withoutKnownRows(processed))
    }

    /**
     * Removes rows that came only from the phone's contacts and are no longer there: the user
     * deleted them, so they shouldn't be checked or raise alerts. Rows from an import, added by
     * hand, or with a confirmed status are kept. When much of the list seems to have vanished at
     * once (an account removed or not yet synced), nothing is removed: that's a gap in the read,
     * not a decision.
     */
    private suspend fun pruneDeleted(harvested: List<Target>): Int {
        if (harvested.isEmpty()) return 0
        val keys = mutableSetOf<String>()
        harvested.forEach { h ->
            StringSimilarity.normalizePhone(h.phoneNumber)?.let { keys += "p:$it" }
            StringSimilarity.normalizeEmail(h.email)?.let { keys += "e:$it" }
            keys += "n:" + h.displayName.trim().lowercase()
        }
        val fromPhone = repository.getTargetWorkInfoPaged(limit = Int.MAX_VALUE, offset = 0).filter { row ->
            val sources = row.sourceAccount.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            sources.isNotEmpty() && sources.all { it in PHONE_BUCKETS }
        }
        val gone = fromPhone.filter { row ->
            row.status != TargetStatus.DECEASED && row.status != TargetStatus.INCARCERATED &&
                listOfNotNull(
                    StringSimilarity.normalizePhone(row.phoneNumber)?.let { "p:$it" },
                    StringSimilarity.normalizePhone(row.stalePhone)?.let { "p:$it" },
                    StringSimilarity.normalizeEmail(row.email)?.let { "e:$it" },
                    "n:" + row.displayName.trim().lowercase()
                ).none { it in keys }
        }
        if (gone.isEmpty()) return 0
        if (gone.size > fromPhone.size * MAX_PRUNE_SHARE) {
            DiagnosticLogger.log("${gone.size} of ${fromPhone.size} contacts weren't in this read; not removing any (an account may be missing)")
            return 0
        }
        // Chunked: SQLite on API 26-29 allows only 999 bound variables per statement.
        gone.map { it.id }.chunked(ID_CHUNK).forEach { ids ->
            repository.getTargetsByIds(ids).forEach { repository.deleteTarget(it) }
        }
        DiagnosticLogger.log("Removed ${gone.size} contact(s) no longer in the phone's contacts")
        return gone.size
    }

    private companion object {
        /** The account buckets [ContactHarvester] files phone-book rows under; imports use other labels. */
        val PHONE_BUCKETS = setOf("Google", "Meta", "Apple", "Device")
        const val MAX_PRUNE_SHARE = 0.25
        const val ID_CHUNK = 900
    }

    /**
     * Drops rows with nothing but a name (social imports) that the registry already holds under
     * the same name from the same account. Dedup can't merge name-only pairs, so without this
     * every re-import would add another copy of every friend.
     */
    private suspend fun withoutKnownRows(rows: List<Target>): List<Target> {
        val registry = repository.getTargetWorkInfoPaged(limit = Int.MAX_VALUE, offset = 0)
        val knownNameOnly = registry
            .filter { it.phoneNumber.isNullOrBlank() && it.email.isNullOrBlank() }
            .map { nameOnlyKey(it.displayName, it.sourceAccount) }
            .toSet()
        // A number the user replaced (adopted a newer one) or marked out of date (then it is still
        // the row's own number, [phoneIsStale]) is still in the phone book under that person;
        // reading it back must not bring it back as a new contact.
        val replaced = registry
            .filter { it.stalePhone != null }
            .mapNotNull { row -> StringSimilarity.normalizePhone(row.stalePhone)?.let { it + "|" + row.displayName.trim().lowercase() } }
            .toSet()
        return rows.filterNot {
            (it.phoneNumber.isNullOrBlank() && it.email.isNullOrBlank() && nameOnlyKey(it.displayName, it.sourceAccount) in knownNameOnly) ||
                StringSimilarity.normalizePhone(it.phoneNumber)?.let { p -> p + "|" + it.displayName.trim().lowercase() } in replaced
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
                // Reject strings that can't be a person's name (a rule-based check; there is no model)
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
                "Name filter: $rejectedNameCount candidate(s) reclassified as Unnamed Entity."
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
