package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.match.CandidateRecord
import com.hereliesaz.cleanunderwear.match.ContactProfile
import com.hereliesaz.cleanunderwear.match.EventKind
import com.hereliesaz.cleanunderwear.match.MatchTier
import com.hereliesaz.cleanunderwear.match.MatchVerifier
import com.hereliesaz.cleanunderwear.match.UsStates
import com.hereliesaz.cleanunderwear.network.IdentityVerifier
import com.hereliesaz.cleanunderwear.network.NameValidator
import com.hereliesaz.cleanunderwear.network.SourceSearch
import com.hereliesaz.cleanunderwear.network.Source
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceKind
import com.hereliesaz.cleanunderwear.network.SourceUrlBuilder
import com.hereliesaz.cleanunderwear.ui.NotificationHelper
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import com.hereliesaz.cleanunderwear.util.SystemContactSyncer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * Phase 4 of the pipeline: monitor each READY target against curated sources
 * from [SourceCatalog]. The scraper never falls back to a search engine and
 * never writes a non-catalog URL to [Target.lockupUrl] / [Target.obituaryUrl].
 *
 * Every automatable source is queried; each name hit becomes a
 * [CandidateRecord] and is scored by [MatchVerifier] before anything reaches
 * the user. A [MatchTier.STRONG] match becomes POSSIBLE_MATCH (notified); a
 * [MatchTier.WEAK] one becomes WEAK_MATCH (shown, flagged, not notified);
 * rejected records are dropped. Nothing is ever set to INCARCERATED or
 * DECEASED without the user confirming, and a status the user confirmed is
 * never overwritten.
 *
 * A source that couldn't be read (network error, error status, bot wall) is
 * not evidence of absence: when no match turns up and any source failed, the
 * contact keeps its status and evidence and is re-checked sooner.
 *
 * Each result is written onto the row as it stands when the lookup finishes,
 * not the snapshot read when the run started, and is dropped if the user
 * changed the contact's status or dismissed records in the meantime.
 */
class ScrapeTargetsUseCase @Inject constructor(
    private val repository: TargetRepository,
    private val verifier: IdentityVerifier,
    private val sourceCatalog: SourceCatalog,
    private val notifications: NotificationHelper,
    private val contactSyncer: SystemContactSyncer,
    private val sourceSearch: SourceSearch
) {
    private val semaphore = Semaphore(3)
    private val matchVerifier = MatchVerifier()

    suspend operator fun invoke(onProgress: (Float, String) -> Unit = { _, _ -> }) = coroutineScope {
        val now = System.currentTimeMillis()
        val targetsToProcess = repository.getReadyDueTargets(now)

        if (targetsToProcess.isEmpty()) {
            onProgress(1f, "Registry is up to date")
            return@coroutineScope
        }

        // Survivor names in obituaries are checked against the user's other contacts.
        val allNames = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0)
            .map { it.displayName.trim() }
            .filter { it.contains(' ') }
            .toSet()

        val total = targetsToProcess.size
        val completionLock = Mutex()
        var completedCount = 0

        val emitMutex = Mutex()
        suspend fun emit(currentName: String, step: String) {
            emitMutex.withLock {
                val frac = completedCount.toFloat() / total
                onProgress(frac, "${completedCount + 1}/$total · $currentName · $step")
            }
        }

        targetsToProcess.map { target ->
            async {
                semaphore.withPermit {
                    processTarget(target, now, allNames) { step -> emit(target.displayName, step) }
                    completionLock.withLock { completedCount++ }
                    onProgress(
                        completedCount.toFloat() / total,
                        "$completedCount/$total · ${target.displayName} · done"
                    )
                }
            }
        }.awaitAll()

        onProgress(1f, "Daily check completed")
    }

    private suspend fun processTarget(
        target: Target,
        now: Long,
        allNames: Set<String>,
        emitStep: suspend (String) -> Unit
    ) {
        try {
            // A status the user confirmed stands until they change it.
            if (target.status == TargetStatus.INCARCERATED || target.status == TargetStatus.DECEASED) {
                commit(target) { current ->
                    current.copy(
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (current.checkFrequencyHours * 3600000L)
                    )
                }
                contactSyncer.syncToSystem(target)
                return
            }
            // Pre-flight: even though Triage already filters unverifiable names
            // out of READY, defensively skip here too. No scrape, no status flip.
            val nameResult = NameValidator.validate(target.displayName)
            if (nameResult !is NameValidator.Result.Ok) {
                emitStep("name not verifiable — skipping (${(nameResult as NameValidator.Result.Skip).reason})")
                commit(target) { current ->
                    current.copy(
                        status = TargetStatus.UNVERIFIED,
                        monitorabilityState = MonitorabilityState.NEEDS_ENRICHMENT,
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (current.checkFrequencyHours * 3600000L)
                    )
                }
                return
            }
            val firstName = nameResult.first
            val lastName = nameResult.last

            // Every state the contact is tied to: resolved locale, the phone's
            // area code, and the state written in their address.
            val tiedStates = listOfNotNull(
                sourceCatalog.resolveStateForAreaCode(target.areaCode),
                stateOf(target.residenceInfo)
            )
            val lockupSources = (
                sourceCatalog.lockupSourcesFor(target.areaCode, target.residenceInfo) +
                    tiedStates.flatMap { sourceCatalog.stateLockupSources(it) }
                )
                .distinctBy { it.id }
                .filter { it.kind != SourceKind.MANUAL_LANDING }
            val obituarySources = sourceCatalog
                .obituarySourcesFor(target.areaCode, target.residenceInfo)
                .filter { it.kind != SourceKind.MANUAL_LANDING }

            // Honesty gate (defensive — Triage already routes contacts with no
            // automatable source to NO_AUTOMATED_SOURCE before they reach the
            // READY scrape pool). If one slips through, flag it visibly instead
            // of silently persisting an unchanged MONITORING status that looks
            // like "checked, nothing found". The operator can then run the
            // in-app source chips manually. Triage re-evaluates each pipeline
            // run, so the contact returns to READY if the catalog later gains an
            // automatable source for its area.
            if (lockupSources.isEmpty() && obituarySources.isEmpty()) {
                emitStep("no automated source for area ${target.areaCode ?: "?"} — manual check required")
                commit(target) { current ->
                    current.copy(
                        monitorabilityState = MonitorabilityState.NO_AUTOMATED_SOURCE,
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (current.checkFrequencyHours * 3600000L)
                    )
                }
                return
            }

            // Corroboration data captured during CBC enrichment, plus the set of
            // records the user already rejected for this contact.
            val locale = sourceCatalog.resolveLocale(target.areaCode, target.residenceInfo)
            val corroboration = IdentityVerifier.Corroboration(
                middleName = target.middleName,
                dob = target.dateOfBirth,
                area = target.areaCode ?: target.residenceInfo,
                state = locale.state
            )
            val dismissedKeys = target.dismissedMatchKeys
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

            // Gather every candidate from every automatable source, then verify.
            val candidates = mutableListOf<CandidateRecord>()
            val failedSources = mutableListOf<String>()
            if (lockupSources.isEmpty()) {
                emitStep("no automated lockup source for area ${target.areaCode ?: "?"}")
            }
            val searches = lockupSources.map { it to EventKind.CUSTODY } + obituarySources.map { it to EventKind.DEATH }
            for ((source, kind) in searches) {
                val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                if (evidenceUrl in dismissedKeys) continue
                emitStep("checking ${source.label}")
                val result = sourceSearch.search(source, kind, firstName, lastName, corroboration, dismissedKeys)
                candidates += result.records
                if (result.failed) failedSources += source.label
            }
            if (failedSources.isNotEmpty()) {
                DiagnosticLogger.log(
                    "${target.displayName}: couldn't read ${failedSources.joinToString()}",
                    DiagnosticLogger.LogEntry.LogLevel.WARN
                )
            }

            emitStep("verifying ${candidates.size} record${if (candidates.size == 1) "" else "s"}")
            val profile = ContactProfile(
                firstName = firstName,
                lastName = lastName,
                middleName = target.middleName,
                age = verifier.approxAge(target.dateOfBirth),
                city = cityOf(target.residenceInfo),
                state = stateOf(target.residenceInfo) ?: locale.state,
                otherContactNames = allNames - target.displayName.trim()
            )
            val best = matchVerifier.verifyAll(
                profile,
                candidates.filter { it.recordKey == null || it.recordKey !in dismissedKeys }
            ).firstOrNull()

            // No match, but a source couldn't be read: that's not a clean "nothing found", so the
            // contact keeps its status and evidence and is looked at again sooner.
            val inconclusive = best == null && failedSources.isNotEmpty()
            val nextCheck = now + if (inconclusive) {
                minOf(target.checkFrequencyHours, RETRY_HOURS) * 3600000L
            } else {
                target.checkFrequencyHours * 3600000L
            }
            if (inconclusive) {
                emitStep("couldn't read ${failedSources.size} source${if (failedSources.size == 1) "" else "s"} — will retry")
                commit(target) { current -> current.copy(lastScrapedTimestamp = now, nextScheduledCheck = nextCheck) }
                return
            }

            val newStatus = when (best?.tier) {
                MatchTier.STRONG -> TargetStatus.POSSIBLE_MATCH
                MatchTier.WEAK -> TargetStatus.WEAK_MATCH
                else -> TargetStatus.MONITORING
            }
            if (best != null) {
                DiagnosticLogger.log(
                    "${best.tier} ${best.candidate.kind} match: ${target.displayName} via " +
                        "${best.candidate.sourceId} (p=${"%.2f".format(best.confidence)})",
                    DiagnosticLogger.LogEntry.LogLevel.WARN
                )
            }
            val isDeath = best?.candidate?.kind == EventKind.DEATH

            emitStep("persisting findings")
            // Every source was read. A match replaces the evidence; no match means the record is
            // gone (released, or never there), so the old evidence is cleared with the status.
            val updatedTarget = commit(target) { current ->
                current.copy(
                    status = newStatus,
                    lastScrapedTimestamp = now,
                    lockupUrl = best?.candidate?.evidenceUrl?.takeIf { !isDeath },
                    obituaryUrl = best?.candidate?.evidenceUrl?.takeIf { isDeath },
                    nextScheduledCheck = nextCheck,
                    lastStatusChangeTimestamp = if (newStatus != current.status) now else current.lastStatusChangeTimestamp,
                    lastVerificationSnippet = best?.candidate?.snippet,
                    matchConfidence = best?.confidence,
                    matchKind = best?.candidate?.kind?.name,
                    matchReasons = best?.reasons?.joinToString("\n")
                )
            } ?: return

            if (newStatus != target.status && shouldNotify(target.status, newStatus)) {
                emitStep("notifying status change")
                notifications.notifyStatusChange(updatedTarget, target.status)
            }

            emitStep("syncing back to system contacts")
            contactSyncer.syncToSystem(updatedTarget)
        } catch (e: Exception) {
            DiagnosticLogger.log(
                "Scrape failed for ${target.displayName}: ${e.javaClass.simpleName}: ${e.message}",
                DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
        }
    }

    /**
     * Applies [change] to the row as it is now. Skips (returning null) if the contact was deleted
     * or the user changed its status or dismissed records since [snapshot] was read; their action
     * wins over this run's, and the next run starts from it.
     */
    private suspend fun commit(snapshot: Target, change: (Target) -> Target): Target? {
        val current = repository.getTargetById(snapshot.id) ?: return null
        if (current.status != snapshot.status || current.dismissedMatchKeys != snapshot.dismissedMatchKeys) {
            DiagnosticLogger.log("${snapshot.displayName}: changed during the check; keeping the user's change")
            return null
        }
        return change(current).also { repository.updateTarget(it) }
    }

    /**
     * Strong matches and a strong match clearing are notified; weak matches, and a weak match
     * clearing, are only shown in the app. A contact's first check (or first since its name became
     * checkable) settling into monitoring is routine and silent; a first check that finds a strong
     * match is not.
     */
    private fun shouldNotify(old: TargetStatus, new: TargetStatus): Boolean = when {
        new == TargetStatus.WEAK_MATCH -> false
        old == TargetStatus.WEAK_MATCH && new == TargetStatus.MONITORING -> false
        (old == TargetStatus.UNKNOWN || old == TargetStatus.UNVERIFIED) && new == TargetStatus.MONITORING -> false
        else -> true
    }

    private companion object {
        /** Upper bound on the wait before re-checking a contact whose sources couldn't all be read. */
        const val RETRY_HOURS = 6
    }

    /** City from residenceInfo ("City, Region, Zip"); null when it holds only one part. */
    private fun cityOf(residence: String?): String? {
        val parts = residence?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        return parts.firstOrNull()?.takeIf { parts.size >= 2 && it.none(Char::isDigit) }
    }

    /** State from the parts after the city, so a city named "Washington" isn't read as WA. */
    private fun stateOf(residence: String?): String? =
        residence?.split(',')?.map { it.trim() }?.drop(1)
            ?.firstNotNullOfOrNull { UsStates.code(it) ?: UsStates.code(it.split(' ').first()) }

}
