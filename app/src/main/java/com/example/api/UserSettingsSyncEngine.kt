package com.example.api

import android.content.Context

/**
 * UserSettingsSyncEngine
 *
 * Forwarding wrapper for [SameUserMultiDeviceSyncManager].
 * Manages same user settings synchronization across devices.
 */
object UserSettingsSyncEngine {

    fun pushSettingsToCloud(context: Context, email: String) {
        SameUserMultiDeviceSyncManager.pushSettingsToCloud(context, email)
    }

    fun pullSettingsFromCloud(context: Context, email: String, onComplete: (() -> Unit)? = null) {
        SameUserMultiDeviceSyncManager.pullSettingsFromCloud(context, email, onComplete)
    }

    fun startListeningForRemoteSettingsUpdates(context: Context, email: String) {
        // Handled as part of SameUserMultiDeviceSyncManager.startLiveSync
    }

    fun stopListening(context: Context, email: String) {
        // Handled as part of SameUserMultiDeviceSyncManager.stopLiveSync
    }
}
