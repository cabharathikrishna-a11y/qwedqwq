package com.example.api

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.KeepNote

/**
 * KeepNotesLiveSyncEngine
 *
 * Forwarding wrapper for [SameUserMultiDeviceSyncManager].
 * All Keep Notes cross-device synchronization logic is managed by [SameUserMultiDeviceSyncManager].
 */
object KeepNotesLiveSyncEngine {

    fun startListening(context: Context, email: String, database: AppDatabase) {
        SameUserMultiDeviceSyncManager.startLiveSync(context, email, database)
    }

    fun stopListening(context: Context) {
        SameUserMultiDeviceSyncManager.stopLiveSync(context)
    }

    fun pushNoteToCloud(context: Context, email: String, note: KeepNote, isDeleted: Boolean = false) {
        SameUserMultiDeviceSyncManager.pushKeepNoteToCloud(context, email, note, isDeleted)
    }

    fun deleteNoteFromCloud(context: Context, email: String, note: KeepNote) {
        SameUserMultiDeviceSyncManager.deleteNoteFromCloud(context, email, note)
    }

    fun pullKeepNotesFromCloud(context: Context, email: String, database: AppDatabase) {
        SameUserMultiDeviceSyncManager.fetchAndSyncAllData(context, email, database)
    }

    fun createSignature(title: String, content: String): String {
        return SameUserMultiDeviceSyncManager.createNoteSignature(title, content)
    }

    fun generateNoteKey(note: KeepNote): String {
        return SameUserMultiDeviceSyncManager.generateNoteKey(note)
    }
}
