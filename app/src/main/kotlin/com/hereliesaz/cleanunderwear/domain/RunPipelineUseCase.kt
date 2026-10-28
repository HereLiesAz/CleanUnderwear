package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import javax.inject.Inject

/**
 * Top-level orchestrator. Three sequential pipeline phases.
 *
 *   Phase 1/3 · Deduplicating
 *   Phase 2/3 · Triaging
 *   Phase 3/3 · Monitoring
 *
 * Identity enrichment (resolving UNVERIFIED contacts via cyberbackgroundchecks)
 * is intentionally absent — it's user-initiated, not auto-run. See
 * `MainViewModel.resolveUnverifiedBatch` and `BrowserScreen`.
 */
class RunPipelineUseCase @Inject constructor(
    private val deduplicate: DeduplicateTargetsUseCase,
    private val triage: TriageTargetsUseCase,
    private val scrape: ScrapeTargetsUseCase,
    private val coordinator: PipelineCoordinator
) {
    private val phases = listOf("Deduplicating", "Triaging", "Monitoring")

    /**
     * Runs the pipeline; returns the daily check's summary for the person who asked for it.
     * [onFindings] gets what has turned up so far: duplicates merged, contacts ready, then the
     * check's own tally.
     */
    suspend operator fun invoke(
        onProgress: (Float, String) -> Unit,
        onFindings: (List<String>) -> Unit = {}
    ): ScrapeTargetsUseCase.Summary =
        coordinator.runExclusive("auto-pipeline") {
            val pipeline = PipelineProgress(phases, onProgress)

            pipeline.beginPhase("Deduplicating")
            val merged = deduplicate { sub, desc -> pipeline.emit(sub, desc) }
            DiagnosticLogger.log("Pipeline: dedup merged $merged duplicate row(s)")
            val found = mutableListOf<String>()
            if (merged > 0) found += "Merged $merged duplicate${if (merged == 1) "" else "s"}"
            onFindings(found)

            pipeline.beginPhase("Triaging")
            val triageResult = triage { sub, desc -> pipeline.emit(sub, desc) }
            DiagnosticLogger.log(
                "Pipeline: triage → ${triageResult.ready} ready / ${triageResult.needsEnrichment} need enrichment"
            )
            found += "${triageResult.ready} ready to check" +
                if (triageResult.needsEnrichment > 0) " · ${triageResult.needsEnrichment} need more details first" else ""
            onFindings(found)

            pipeline.beginPhase("Monitoring")
            val summary = scrape(
                onProgress = { sub, desc -> pipeline.emit(sub, desc) },
                onFindings = { lines -> onFindings(lines + found) }
            )

            pipeline.completeAll(summary.describe())
            summary
        }
}
