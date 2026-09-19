package p2pgate.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import p2pgate.app.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Background deal-status polling (`specs/android-app.md` §2.1 — WorkManager,
 * no FCM). This is the **fallback** evidence channel: while the deal
 * WebSocket is connected the ViewModel learns transitions instantly; when the
 * WS drops (or the app was backgrounded), this worker re-checks the deal and
 * retries with **simple exponential backoff** (30 s base, EXPONENTIAL policy)
 * — the documented stand-in for the spec's adaptive cadence (§4.1: tight while
 * a change is expected, relaxed otherwise). The deal token is read from the
 * local store inside the worker, never passed through Worker input.
 */
class DealPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dealId = inputData.getString(KEY_DEAL_ID) ?: return Result.failure()
        return try {
            AppContainer.get(applicationContext).dealRepository.refresh(dealId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val KEY_DEAL_ID = "dealId"
        private const val MAX_ATTEMPTS = 8

        fun schedule(context: Context, dealId: String) {
            val request = OneTimeWorkRequestBuilder<DealPollWorker>()
                .setInputData(Data.Builder().putString(KEY_DEAL_ID, dealId).build())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("poll_$dealId", ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, dealId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("poll_$dealId")
        }
    }
}
