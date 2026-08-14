package com.example.api

import android.content.Context
import com.example.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppDataLiveSyncEngine
 *
 * Forwarding wrapper for [SameUserMultiDeviceSyncManager].
 * All live cross-device synchronization logic for same-user multi-device setups is managed in [SameUserMultiDeviceSyncManager].
 */
object AppDataLiveSyncEngine {

    fun startListening(context: Context, email: String, database: AppDatabase) {
        SameUserMultiDeviceSyncManager.startLiveSync(context, email, database)
    }

    fun stopListening(context: Context) {
        SameUserMultiDeviceSyncManager.stopLiveSync(context)
    }

    fun pullAllDataFromCloud(context: Context, email: String, database: AppDatabase) {
        SameUserMultiDeviceSyncManager.fetchAndSyncAllData(context, email, database)
    }

    fun pushTaskToCloud(context: Context, email: String, task: Task, isDeleted: Boolean = false) {
        SameUserMultiDeviceSyncManager.pushTaskToCloud(context, email, task, isDeleted)
    }

    fun pushJournalToCloud(context: Context, email: String, entry: JournalEntry, isDeleted: Boolean = false) {
        SameUserMultiDeviceSyncManager.pushJournalToCloud(context, email, entry, isDeleted)
    }

    fun pushHealthToCloud(context: Context, email: String, record: HealthRecord) {
        // FCM triggers handle health updates
    }

    fun pushFileToCloud(context: Context, email: String, file: AppFile, isDeleted: Boolean = false) {
        SameUserMultiDeviceSyncManager.pushFileToCloud(context, email, file, isDeleted)
    }

    fun pushFinanceToCloud(context: Context, email: String, transaction: FinanceTransaction, isDeleted: Boolean = false) {
        SameUserMultiDeviceSyncManager.pushFinanceToCloud(context, email, transaction, isDeleted)
    }

    fun triggerGoogleDriveBackupIfConnected(context: Context, database: AppDatabase) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (com.example.util.GoogleDriveReadManager.hasDrivePermission(context)) {
                    val baos = java.io.ByteArrayOutputStream()
                    val success = com.example.util.DatabaseBackupHelper.exportDataToStream(context, database, baos)
                    if (success) {
                        com.example.util.GoogleDriveUploadManager.uploadAppDataBackup(
                            context = context,
                            database = database,
                            backupJsonString = baos.toString("UTF-8")
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppDataLiveSyncEngine", "Error triggering Google Drive backup", e)
            }
        }
    }
}
