package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.util.GoogleContactsSyncManager
import java.util.concurrent.TimeUnit

/**
 * Daily worker to synchronize all contacts data (new and existing contacts,
 * updated DOBs, Instagram & Snapchat custom fields, profile photos)
 * with Google Contacts 2-way once every day.
 */
class DailyContactSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "DailyContactSyncWorker: Starting daily automated 2-way sync with Google Contacts...")
        return try {
            val result = GoogleContactsSyncManager.syncContacts(applicationContext) {
                Log.d(TAG, "DailyContactSyncWorker: Authentication required for background sync.")
            }
            if (result.first) {
                Log.i(TAG, "DailyContactSyncWorker: Daily sync completed successfully: ${result.second}")
                Result.success()
            } else {
                Log.d(TAG, "DailyContactSyncWorker: Daily sync skipped or pending auth: ${result.second}")
                Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DailyContactSyncWorker: Note during daily contacts sync: ${e.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "DailyContactSyncWorker"
        const val WORK_NAME = "DailyGoogleContactsSyncWorker"

        /**
         * Schedules the daily contact sync job to run once per day when network is connected.
         */
        fun scheduleDailySync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = PeriodicWorkRequestBuilder<DailyContactSyncWorker>(
                    1, TimeUnit.DAYS,
                    4, TimeUnit.HOURS // flex interval
                )
                    .setConstraints(constraints)
                    .addTag("DailyContactsSync")
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
                Log.d(TAG, "DailyContactSyncWorker scheduled successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule DailyContactSyncWorker: ${e.message}", e)
            }
        }
    }
}
