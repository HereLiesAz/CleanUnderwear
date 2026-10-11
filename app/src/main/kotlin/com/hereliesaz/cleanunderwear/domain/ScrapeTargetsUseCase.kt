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
import com.hereliesaz.cleanunderwear.match.RecordExtractor
import com.hereliesaz.cleanunderwear.match.UsStates
import com.hereliesaz.cleanunderwear.network.HtmlScraper
import com.hereliesaz.cleanunderwear.network.IdentityVerifier
import com.hereliesaz.cleanunderwear.network.NameValidator
import com.hereliesaz.cleanunderwear.network.NewsFeed
import com.hereliesaz.cleanunderwear.network.RenderMode
import com.hereliesaz.cleanunderwear.network.ResultFormat
import com.hereliesaz.cleanunderwear.network.Source
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.network.SourceKind
import com.hereliesaz.cleanunderwear.network.SourceUrlBuilder
import com.hereliesaz.cleanunderwear.network.WebViewScraper
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
 */
class ScrapeTargetsUseCase @Inject constructor(
    private val repository: TargetRepository,
    private val basicScraper: HtmlScraper,
    private val stealthScraper: WebViewScraper,
    private val verifier: IdentityVerifier,
    private val sourceCatalog: SourceCatalog,
    private val notifications: NotificationHelper,
    private val contactSyncer: SystemContactSyncer
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
                repository.updateTarget(
                    target.copy(
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (target.checkFrequencyHours * 3600000L)
                    )
                )
                return
            }
            // Pre-flight: even though Triage already filters unverifiable names
            // out of READY, defensively skip here too. No scrape, no status flip.
            val nameResult = NameValidator.validate(target.displayName)
            if (nameResult !is NameValidator.Result.Ok) {
                emitStep("name not verifiable — skipping (${(nameResult as NameValidator.Result.Skip).reason})")
                repository.updateTarget(
                    target.copy(
                        status = TargetStatus.UNVERIFIED,
                        monitorabilityState = MonitorabilityState.NEEDS_ENRICHMENT,
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (target.checkFrequencyHours * 3600000L)
                    )
                )
                return
            }
            val firstName = nameResult.first
            val lastName = nameResult.last

            val lockupSources = sourceCatalog
                .lockupSourcesFor(target.areaCode, target.residenceInfo)
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
                repository.updateTarget(
                    target.copy(
                        monitorabilityState = MonitorabilityState.NO_AUTOMATED_SOURCE,
                        lastScrapedTimestamp = now,
                        nextScheduledCheck = now + (target.checkFrequencyHours * 3600000L)
                    )
                )
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
            if (lockupSources.isEmpty()) {
                emitStep("no automated lockup source for area ${target.areaCode ?: "?"}")
            }
            for (source in lockupSources) {
                val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                if (evidenceUrl in dismissedKeys) continue
                emitStep("checking ${source.label}")
                candidates += candidatesFrom(source, EventKind.CUSTODY, firstName, lastName, corroboration, dismissedKeys)
            }
            for (source in obituarySources) {
                val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                if (evidenceUrl in dismissedKeys) continue
                emitStep("checking ${source.label}")
                candidates += candidatesFrom(source, EventKind.DEATH, firstName, lastName, corroboration, dismissedKeys)
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
            val discoveredLockupUrl = best?.candidate?.evidenceUrl?.takeIf { !isDeath }
            val discoveredObitUrl = best?.candidate?.evidenceUrl?.takeIf { isDeath }
            val verificationSnippet = best?.candidate?.snippet

            var statusChangeTimestamp = target.lastStatusChangeTimestamp
            if (newStatus != target.status && target.status != TargetStatus.UNKNOWN) {
                // Weak matches are shown in the app but never notified, nor is a
                // weak match clearing back to monitoring.
                val silent = newStatus == TargetStatus.WEAK_MATCH ||
                    (target.status == TargetStatus.WEAK_MATCH && newStatus == TargetStatus.MONITORING)
                if (!silent) {
                    emitStep("notifying status change")
                    notifications.notifyStatusChange(
                        target.copy(status = newStatus, matchKind = best?.candidate?.kind?.name),
                        target.status
                    )
                }
                statusChangeTimestamp = now
            }

            emitStep("persisting findings")
            // Preserve any prior catalog-derived URL on this target (it may
            // already point to a verified evidence page from a previous run);
            // only overwrite when the current run produced a fresh match.
            val updatedTarget = target.copy(
                status = newStatus,
                lastScrapedTimestamp = now,
                lockupUrl = discoveredLockupUrl,
                obituaryUrl = discoveredObitUrl,
                nextScheduledCheck = now + (target.checkFrequencyHours * 3600000L),
                lastStatusChangeTimestamp = statusChangeTimestamp,
                lastVerificationSnippet = verificationSnippet,
                matchConfidence = best?.confidence,
                matchKind = best?.candidate?.kind?.name,
                matchReasons = best?.reasons?.joinToString("\n")
            )
            repository.updateTarget(updatedTarget)

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
     * Queries one source and returns every record on it that names the contact.
     * The BOP locator is parsed structurally (with its own custody and
     * corroboration gates); every other source is fetched as text and read by
     * [RecordExtractor].
     */
    private suspend fun candidatesFrom(
        source: Source,
        kind: EventKind,
        first: String,
        last: String,
        corroboration: IdentityVerifier.Corroboration,
        dismissedKeys: Set<String>
    ): List<CandidateRecord> {
        val fetchUrl = try {
            SourceUrlBuilder.buildFetchUrl(source, first, last)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, first, last)
        val text = when (source.render) {
            RenderMode.BASIC -> when {
                source.resultFormat == ResultFormat.NEWS_RSS -> {
                    // A news feed reports deaths and arrests alike; NewsFeed
                    // classifies each item, so [kind] is not used here.
                    val xml = basicScraper.fetchBody(fetchUrl) ?: return emptyList()
                    return NewsFeed.candidates(xml, first, last, source.id, source.label)
                }
                source.resultFormat == ResultFormat.BOP_JSON -> {
                    val r = basicScraper.scrapeBopInmate(fetchUrl, "$first $last", corroboration, dismissedKeys)
                    return listOfNotNull(r.record?.copy(evidenceUrl = evidenceUrl))
                }
                source.method.uppercase() == "POST" ->
                    basicScraper.fetchText(fetchUrl, SourceUrlBuilder.buildFormFields(source, first, last))
                else -> basicScraper.fetchText(fetchUrl)
            }
            RenderMode.WEBVIEW -> stealthScraper.scrapeGhostTown(
                url = fetchUrl,
                settleMs = source.renderSettleMs,
                readySelector = source.readySelector
            )?.text()
        } ?: return emptyList()
        return RecordExtractor.extract(text, first, last, kind, source.id, source.label, evidenceUrl)
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
