package com.hereliesaz.cleanunderwear.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hereliesaz.cleanunderwear.domain.RunPipelineUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The daily check. Runs the pipeline (dedup → triage → scrape; enrichment is user-started and
 * never runs here) and streams progress to whoever is observing this WorkRequest.
 */
@HiltWorker
class ScrapingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runPipeline: RunPipelineUseCase
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /** Output key: the run's summary, shown to the user when they started the check. */
        const val SUMMARY = "summary"

        /** Progress keys: 0..1 done, what is happening now, and what has turned up so far. */
        const val PROGRESS = "progress"
        const val DESCRIPTION = "description"
        const val FINDINGS = "findings"
    }

    override suspend fun doWork(): Result {
        return try {
            // Each progress report replaces the last whole, so both halves go every time.
            var progress = 0f
            var description = ""
            var findings = emptyArray<String>()
            fun report() {
                setProgressAsync(workDataOf(PROGRESS to progress, DESCRIPTION to description, FINDINGS to findings))
            }
            val summary = runPipeline(
                onProgress = { p, d -> progress = p; description = d; report() },
                onFindings = { lines -> findings = lines.toTypedArray(); report() }
            )
            Result.success(workDataOf(SUMMARY to summary.describe()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the OS (time limit, constraints lost). Each contact checked so far is
            // saved, and the next run starts with those waiting longest.
            throw e
        } catch (e: Exception) {
            com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
                "Daily check failed: ${e.javaClass.simpleName}", com.hereliesaz.cleanunderwear.util.DiagnosticLogger.LogEntry.LogLevel.ERROR
            )
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
