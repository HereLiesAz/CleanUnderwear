package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.network.HtmlScraper
import com.hereliesaz.cleanunderwear.network.IdentityVerifier
import com.hereliesaz.cleanunderwear.network.NameValidator
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
 * Sources are tried most-specific-first (county → state → multi-state). The
 * first confirmed match flips the status; otherwise the contact stays in
 * MONITORING.
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

    suspend operator fun invoke(onProgress: (Float, String) -> Unit = { _, _ -> }) = coroutineScope {
        val now = System.currentTimeMillis()
        val targetsToProcess = repository.getReadyDueTargets(now)

        if (targetsToProcess.isEmpty()) {
            onProgress(1f, "Registry is up to date")
            return@coroutineScope
        }

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
                    processTarget(target, now) { step -> emit(target.displayName, step) }
                    completionLock.withLock { completedCount++ }
                    onProgress(
                        completedCount.toFloat() / total,
                        "$completedCount/$total · ${target.displayName} · done"
                    )
                }
            }
        }.awaitAll()

        onProgress(1f, "Daily vigil completed")
    }

    private suspend fun processTarget(
        target: Target,
        now: Long,
        emitStep: suspend (String) -> Unit
    ) {
        try {
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

            var newStatus = TargetStatus.MONITORING
            var discoveredLockupUrl: String? = null
            var discoveredObitUrl: String? = null
            var verificationSnippet: String? = null

            // Corroboration data captured during CBC enrichment, plus the set of
            // records the user already rejected for this contact.
            val corroboration = IdentityVerifier.Corroboration(
                middleName = target.middleName,
                dob = target.dateOfBirth,
                area = target.areaCode ?: target.residenceInfo
            )
            val dismissedKeys = target.dismissedMatchKeys
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

            // Lockup loop. The first name match becomes a *possible* match for
            // the user to confirm — a name hit (even corroborated) never auto-sets
            // INCARCERATED on its own, because name alone cannot prove identity.
            if (lockupSources.isEmpty()) {
                emitStep("no automated lockup source for area ${target.areaCode ?: "?"}")
            } else {
                for (source in lockupSources) {
                    // Sources without a stable record id (county mugshots, state DOC,
                    // WEBVIEW) are dismissed by their evidence URL — skip a source the
                    // user already rejected so it can't re-surface as a POSSIBLE_MATCH.
                    val evidenceUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                    if (evidenceUrl in dismissedKeys) continue
                    emitStep("checking ${source.label}")
                    val result = scrapeAgainstSource(source, firstName, lastName, corroboration, dismissedKeys)
                    if (result.skipped) break
                    if (result.isMatch) {
                        DiagnosticLogger.log(
                            "POSSIBLE MATCH: ${target.displayName} via ${source.id} (${result.basis ?: "name"})",
                            DiagnosticLogger.LogEntry.LogLevel.WARN
                        )
                        newStatus = TargetStatus.POSSIBLE_MATCH
                        discoveredLockupUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                        verificationSnippet = result.snippet
                        break
                    }
                }
            }

            // Obituary loop only if no lockup hit.
            if (newStatus == TargetStatus.MONITORING && obituarySources.isNotEmpty()) {
                for (source in obituarySources) {
                    emitStep("checking ${source.label}")
                    val result = scrapeAgainstSource(source, firstName, lastName)
                    if (result.skipped) break
                    if (result.isMatch) {
                        DiagnosticLogger.log(
                            "OBIT MATCH: ${target.displayName} found via ${source.id}",
                            DiagnosticLogger.LogEntry.LogLevel.WARN
                        )
                        newStatus = TargetStatus.DECEASED
                        discoveredObitUrl = SourceUrlBuilder.buildEvidenceUrl(source, firstName, lastName)
                        verificationSnippet = result.snippet
                        break
                    }
                }
            }

            var statusChangeTimestamp = target.lastStatusChangeTimestamp
            if (newStatus != target.status && target.status != TargetStatus.UNKNOWN) {
                emitStep("notifying status change")
                notifications.notifyStatusChange(target.copy(status = newStatus), target.status)
                statusChangeTimestamp = now
            }

            emitStep("persisting findings")
            // Preserve any prior catalog-derived URL on this target (it may
            // already point to a verified evidence page from a previous run);
            // only overwrite when the current run produced a fresh match.
            val updatedTarget = target.copy(
                status = newStatus,
                lastScrapedTimestamp = now,
                lockupUrl = discoveredLockupUrl ?: keepIfFromCatalog(target.lockupUrl),
                obituaryUrl = discoveredObitUrl ?: keepIfFromCatalog(target.obituaryUrl),
                nextScheduledCheck = now + (target.checkFrequencyHours * 3600000L),
                lastStatusChangeTimestamp = statusChangeTimestamp,
                lastVerificationSnippet = verificationSnippet ?: target.lastVerificationSnippet
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

    private suspend fun scrapeAgainstSource(
        source: Source,
        first: String,
        last: String,
        corroboration: IdentityVerifier.Corroboration = IdentityVerifier.Corroboration(),
        dismissedKeys: Set<String> = emptySet()
    ): IdentityVerifier.VerificationResult {
        val fetchUrl = try {
            SourceUrlBuilder.buildFetchUrl(source, first, last)
        } catch (e: IllegalArgumentException) {
            // Source needs name slots but got blank — should be unreachable
            // because pre-flight rejects unverifiable names, but defend anyway.
            return IdentityVerifier.VerificationResult.fetchFailed()
        }
        val name = "$first $last"

        return when (source.render) {
            RenderMode.BASIC -> when {
                source.resultFormat == ResultFormat.BOP_JSON ->
                    basicScraper.scrapeBopInmate(fetchUrl, name, corroboration, dismissedKeys)
                source.method.uppercase() == "POST" -> {
                    val fields = SourceUrlBuilder.buildFormFields(source, first, last)
                    basicScraper.scrapePost(fetchUrl, fields, name)
                }
                else -> basicScraper.scrapeMugshots(fetchUrl, name)
            }
            RenderMode.WEBVIEW -> {
                val doc = stealthScraper.scrapeGhostTown(
                    url = fetchUrl,
                    settleMs = source.renderSettleMs,
                    readySelector = source.readySelector
                ) ?: return IdentityVerifier.VerificationResult.fetchFailed()
                verifier.verifyIdentity(doc.text(), name)
            }
        }
    }

    private fun keepIfFromCatalog(url: String?): String? =
        url?.takeIf { sourceCatalog.isFromCatalog(it) }
}
