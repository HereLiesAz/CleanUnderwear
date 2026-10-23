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

    /** Runs the pipeline; returns the daily check's summary for the person who asked for it. */
    suspend operator fun invoke(onProgress: (Float, String) -> Unit): ScrapeTargetsUseCase.Summary =
        coordinator.runExclusive("auto-pipeline") {
            val pipeline = PipelineProgress(phases, onProgress)

            pipeline.beginPhase("Deduplicating")
            val merged = deduplicate { sub, desc -> pipeline.emit(sub, desc) }
            DiagnosticLogger.log("Pipeline: dedup merged $merged duplicate row(s)")

            pipeline.beginPhase("Triaging")
            val triageResult = triage { sub, desc -> pipeline.emit(sub, desc) }
            DiagnosticLogger.log(
                "Pipeline: triage → ${triageResult.ready} ready / ${triageResult.needsEnrichment} need enrichment"
            )

            pipeline.beginPhase("Monitoring")
            val summary = scrape { sub, desc -> pipeline.emit(sub, desc) }

            pipeline.completeAll(summary.describe())
            summary
        }
}
