package com.hereliesaz.cleanunderwear.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(RUNNING_CHANNEL, "Checks running", android.app.NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the records check runs."
                }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, RUNNING_CHANNEL)
            .setSmallIcon(com.hereliesaz.cleanunderwear.R.drawable.ic_stat_briefs)
            .setContentTitle("Checking records")
            .setContentText("Your contacts are being looked up in public records.")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(RUNNING_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(RUNNING_NOTIFICATION, notification)
        }
    }

    companion object {
        private const val RUNNING_CHANNEL = "checks_running"
        private const val RUNNING_NOTIFICATION = 4101

        /** Output key: the run's summary, shown to the user when they started the check. */
        const val SUMMARY = "summary"

        /** Progress keys: 0..1 done, what is happening now, and what has turned up so far. */
        const val PROGRESS = "progress"
        const val DESCRIPTION = "description"
        const val FINDINGS = "findings"
    }

    override suspend fun doWork(): Result {
        // A long contact list takes longer than the ~10 minutes a background job is given; as a
        // foreground task (a quiet ongoing notification) it can finish. Android can refuse that
        // when the app is in the background; the check then runs as before, and what it doesn't
        // reach comes first next time.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log("Records check runs in the background (${e.javaClass.simpleName})")
        }
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
