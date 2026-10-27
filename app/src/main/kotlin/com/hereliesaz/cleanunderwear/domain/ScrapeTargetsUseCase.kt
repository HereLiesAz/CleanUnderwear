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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: TargetRepository,
    private val verifier: IdentityVerifier,
    private val sourceCatalog: SourceCatalog,
    private val notifications: NotificationHelper,
    private val contactSyncer: SystemContactSyncer,
    private val sourceSearch: SourceSearch
) {
    private val semaphore = Semaphore(3)
    private val matchVerifier = MatchVerifier()

    /** What one run did, in words a person can read. */
    data class Summary(val checked: Int, val newMatches: Int, val incomplete: Int) {
        fun describe(): String = when {
            checked == 0 -> "Nothing was due for a check."
            else -> buildString {
                append("Checked $checked contact${if (checked == 1) "" else "s"}")
                append(
                    if (newMatches == 0) ". No new matches"
                    else ". $newMatches possible match${if (newMatches == 1) "" else "es"} to review"
                )
                if (incomplete > 0) append("; $incomplete couldn't be fully checked: some sites didn't answer")
                append('.')
            }
        }
    }

    private val newMatches = java.util.concurrent.atomic.AtomicInteger()
    private val incomplete = java.util.concurrent.atomic.AtomicInteger()
    private val tally = Tally()

    /**
     * What the run has found so far, as lines for the busy screen: how many are checked, each new
     * possible match by name and kind, weak matches, and contacts whose sites didn't answer.
     */
    private class Tally {
        private val matches = mutableListOf<String>()
        private var weak = 0
        private var unread = 0

        @Synchronized fun reset() { matches.clear(); weak = 0; unread = 0 }
        @Synchronized fun match(name: String, kind: EventKind) { matches += "$name: ${kindWords(kind)}" }
        @Synchronized fun weak() { weak++ }
        @Synchronized fun unread() { unread++ }

        @Synchronized fun lines(checked: Int, total: Int): List<String> = buildList {
            add("$checked of $total checked")
            if (matches.isEmpty()) {
                add("No possible matches so far")
            } else {
                add("${matches.size} possible match${if (matches.size == 1) "" else "es"} to review:")
                matches.take(MAX_NAMED).forEach { add(it) }
                if (matches.size > MAX_NAMED) add("and ${matches.size - MAX_NAMED} more")
            }
            if (weak > 0) add("$weak weak match${if (weak == 1) "" else "es"} (no alert; shown in the list)")
            if (unread > 0) add("Some sites didn't answer for $unread contact${if (unread == 1) "" else "s"}")
        }

        private fun kindWords(kind: EventKind) = when (kind) {
            EventKind.DEATH -> "obituary"
            EventKind.ARREST -> "arrest or court record"
            else -> "jail or prison record"
        }

        private companion object { const val MAX_NAMED = 4 }
    }

    /**
     * Runs the check. [onFindings] gets [Tally.lines] after each contact, so the person watching
     * sees what has turned up, not only what is being searched.
     */
    suspend operator fun invoke(
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onFindings: (List<String>) -> Unit = {}
    ): Summary = coroutineScope {
        val now = System.currentTimeMillis()
        newMatches.set(0)
        incomplete.set(0)
        tally.reset()
        // The user's Contact Limit: only the first N contacts (by when they were added) are
        // checked at all; 0 means no limit.
        val limit = context.getSharedPreferences("clean_underwear_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("global_limit", 0)
        val targetsToProcess = repository.getReadyDueTargets(now, limit)

        if (targetsToProcess.isEmpty()) {
            onProgress(1f, "Nothing was due for a check.")
            return@coroutineScope Summary(0, 0, 0)
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
                    val done = completionLock.withLock { ++completedCount }
                    onFindings(tally.lines(done, total))
                    onProgress(
                        completedCount.toFloat() / total,
                        "$completedCount/$total · ${target.displayName} · done"
                    )
                }
            }
        }.awaitAll()

        val summary = Summary(total, newMatches.get(), incomplete.get())
        onProgress(1f, summary.describe())
        summary
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
            val plan = sourceCatalog.searchPlan(target.areaCode, target.residenceInfo)
            val lockupSources = plan.lockup.filter { it.kind != SourceKind.MANUAL_LANDING }
            val obituarySources = plan.obituary.filter { it.kind != SourceKind.MANUAL_LANDING }

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
                // Dismissals made before per-record keys were stored the source's search URL;
                // those still skip the source. Newer ones name the record and filter below.
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
                state = UsStates.fromAddress(target.residenceInfo) ?: locale.state,
                otherContactNames = allNames - target.displayName.trim()
            )
            val verified = matchVerifier.verifyAll(
                profile,
                candidates.filter { it.recordKey == null || it.recordKey !in dismissedKeys }
            )
            val best = verified.firstOrNull()
            val suppressed = candidates.size - verified.size
            if (suppressed > 0) {
                // Records naming the contact that verification set aside (a confident conflict, or
                // nothing beyond the name): logged, so "no match" and "match hidden" can be told apart.
                DiagnosticLogger.log("${target.displayName}: $suppressed record(s) naming them were set aside by verification")
            }

            // A status restored from the contact's note waits for the user: the record behind it
            // may be older than any search reaches, so finding nothing now doesn't clear it.
            val noteClaim = target.status == TargetStatus.POSSIBLE_MATCH &&
                target.matchRecordKey == com.hereliesaz.cleanunderwear.util.ContactNote.RESTORED_KEY
            // No match, but a source couldn't be read: that's not a clean "nothing found", so a
            // pending match keeps its status and evidence and is looked at again sooner. With no
            // match to protect, the check stands (unread sources noted), so a site that is always
            // down can't hold a contact at "Not checked yet".
            val pendingMatch = target.status == TargetStatus.POSSIBLE_MATCH || target.status == TargetStatus.WEAK_MATCH
            val inconclusive = best == null && (noteClaim || (failedSources.isNotEmpty() && pendingMatch))
            val nextCheck = now + if (inconclusive && failedSources.isNotEmpty()) {
                minOf(target.checkFrequencyHours, RETRY_HOURS) * 3600000L
            } else {
                target.checkFrequencyHours * 3600000L
            }
            if (failedSources.isNotEmpty()) {
                incomplete.incrementAndGet()
                tally.unread()
            }
            if (inconclusive) {
                if (failedSources.isNotEmpty()) {
                    emitStep("couldn't read ${failedSources.size} source${if (failedSources.size == 1) "" else "s"} — will retry")
                }
                commit(target) { current ->
                    current.copy(
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = nextCheck,
                        unreadSources = failedSources.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    )
                }
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
            // A match replaces the evidence, whatever else failed. With no match, every source was
            // read: the record is gone (released, or never there), so old evidence goes with it.
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
                    matchReasons = best?.reasons?.joinToString("\n"),
                    matchRecordKey = best?.candidate?.recordKey,
                    unreadSources = failedSources.takeIf { it.isNotEmpty() }?.joinToString(", ")
                )
            } ?: return

            if (newStatus == TargetStatus.POSSIBLE_MATCH && target.status != TargetStatus.POSSIBLE_MATCH) {
                newMatches.incrementAndGet()
                best?.candidate?.kind?.let { tally.match(target.displayName, it) }
            }
            if (newStatus == TargetStatus.WEAK_MATCH && target.status != TargetStatus.WEAK_MATCH) tally.weak()
            if (newStatus != target.status && shouldNotify(target.status, newStatus)) {
                emitStep("notifying status change")
                notifications.notifyStatusChange(updatedTarget, target.status)
            }

            emitStep("syncing back to system contacts")
            contactSyncer.syncToSystem(updatedTarget)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
    private suspend fun commit(snapshot: Target, change: (Target) -> Target): Target? = repository.inTransaction {
        val current = repository.getTargetById(snapshot.id) ?: return@inTransaction null
        if (current.status != snapshot.status || current.dismissedMatchKeys != snapshot.dismissedMatchKeys) {
            DiagnosticLogger.log("${snapshot.displayName}: changed during the check; keeping the user's change")
            return@inTransaction null
        }
        change(current).also { repository.updateTarget(it) }
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

}
