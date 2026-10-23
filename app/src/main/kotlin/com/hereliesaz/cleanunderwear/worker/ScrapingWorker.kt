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
 * The nocturnal dragnet. Runs the full pipeline (dedup → triage → enrich → monitor) and streams
 * granular progress to whoever is observing this WorkRequest.
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
    }

    override suspend fun doWork(): Result {
        val summary = runPipeline { progress, description ->
            setProgressAsync(workDataOf("progress" to progress, "description" to description))
        }
        return Result.success(workDataOf(SUMMARY to summary.describe()))
    }
}
