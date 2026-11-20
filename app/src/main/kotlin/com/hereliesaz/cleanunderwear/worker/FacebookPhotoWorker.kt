package com.hereliesaz.cleanunderwear.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hereliesaz.cleanunderwear.util.FacebookPhotoSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Refresh only photos the user explicitly linked. Facebook may expire CDN URLs;
 * a failed download leaves both the old image and its original backup untouched.
 */
@HiltWorker
class FacebookPhotoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val photos: FacebookPhotoSync
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        photos.refreshAll()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
