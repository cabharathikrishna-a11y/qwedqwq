package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Context
import com.example.util.AppBlockHelper
import com.example.util.FocusTimerManager

class NotificationBlockerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationBlocker", "Notification blocker service connected!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val context = applicationContext
        val packageName = sbn.packageName ?: return

        // --- INSTAGRAM NOTIFICATION FILTERING & SUPPRESSION ---
        if (packageName == "com.instagram.android") {
            if (AppBlockHelper.isIgFilterNotificationsEnabled(context)) {
                // Check if Instagram or AntiGram Web App is currently OPEN on the phone ("and if open dont send")
                if (AppBlockHelper.isInstagramOpen(context)) {
                    Log.d("NotificationBlocker", "Instagram or AntiGram is OPEN! Suppressing incoming Instagram notification.")
                    try {
                        cancelNotification(sbn.key)
                    } catch (e: Exception) {
                        Log.e("NotificationBlocker", "Error cancelling notification while Instagram is open: ${e.message}")
                    }
                    return
                }

                val extras = sbn.notification.extras
                val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val subText = extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                val ticker = sbn.notification?.tickerText?.toString() ?: ""

                // Filter out Reel / Post / Video notifications and allow ONLY Direct Chat messages
                if (AppBlockHelper.isInstagramReelOrNonMessageNotification(title, text, subText, ticker)) {
                    Log.d("NotificationBlocker", "Dismissed Reel/Video/Post Instagram notification: '$title' - '$text'")
                    try {
                        cancelNotification(sbn.key)
                    } catch (e: Exception) {
                        Log.e("NotificationBlocker", "Error cancelling reel notification: ${e.message}")
                    }
                    return
                } else {
                    Log.d("NotificationBlocker", "Allowed Instagram Direct Chat message notification: '$title' - '$text'")
                    // Keep message notification
                    return
                }
            }
        }

        if (!AppBlockHelper.isFocusGuardEnabled(context)) return

        // 1. Check if focusing is active (timer or stopwatch running and we are in focus phase)
        val isTimerActive = FocusTimerManager.isTimerRunning.value || FocusTimerManager.isStopwatchActive.value
        val isFocusPhase = FocusTimerManager.isFocusPhase.value
        val isFocusing = isTimerActive && isFocusPhase

        if (!isFocusing) return

        // 2. Check if strict mode is on
        val strictPrefs = context.getSharedPreferences("strict_mode_prefs", Context.MODE_PRIVATE)
        val strictEnabled = strictPrefs.getBoolean("strict_mode_enabled", true)
        if (!strictEnabled) return

        // 3. Check if the package is blocked in strict mode
        if (AppBlockHelper.isPackageBlockedInStrictMode(context, packageName)) {
            Log.d("NotificationBlocker", "Intercepted notification from $packageName during Focus phase! Dismissing...")
            
            // Extract title and text to store them
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "New Notification"
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

            // Save the notification so we can release/re-post it later
            AppBlockHelper.saveBlockedNotification(context, packageName, title, text)

            // Cancel (dismiss) the notification
            cancelNotification(sbn.key)
        }
    }
}
