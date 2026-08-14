package com.example.widget

import android.content.Context

/**
 * Facade object for backwards compatibility. All calls delegate directly to WidgetManager.
 */
object WidgetUpdater {

    fun getPendingIntentFlags(isMutable: Boolean = false): Int {
        return WidgetManager.getPendingIntentFlags(isMutable)
    }

    fun requestPinWidget(context: Context, providerClass: Class<*>) {
        WidgetManager.requestPinWidget(context, providerClass)
    }

    fun updateFriendsFocusWidget(context: Context, statusText: String? = null) {
        WidgetManager.updateFriendsFocusWidget(context, statusText)
    }

    fun updateStopwatchWidget(context: Context, isPartialUpdate: Boolean = false) {
        WidgetManager.updateStopwatchWidget(context, isPartialUpdate)
    }

    fun updatePomodoroWidget(context: Context, isPartialUpdate: Boolean = false) {
        WidgetManager.updatePomodoroWidget(context, isPartialUpdate)
    }

    fun updateAllWidgets(context: Context) {
        WidgetManager.updateAllWidgets(context)
    }

    fun calculateTodayTotalFocusSeconds(context: Context): Int {
        return WidgetManager.fetchTodayTotalFocusSeconds(context)
    }

    fun updateTotalFocusTimeWidget(context: Context, isPartialUpdate: Boolean = false) {
        WidgetManager.updateTotalFocusTimeWidget(context, isPartialUpdate)
    }

    fun formatJournalDateToDdMmYy(dateStr: String, timestamp: Long): String {
        return WidgetManager.formatJournalDateToDdMmYy(dateStr, timestamp)
    }

    fun updatePhotoShowerWidget(context: Context, forceNext: Boolean = false) {
        WidgetManager.updatePhotoShowerWidget(context, forceNext)
    }
}
