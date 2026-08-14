package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.util.FocusTimerManager

class TotalFocusTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updateTotalFocusTimeWidget(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updateTotalFocusTimeWidget(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Log.d("TotalFocusTimeWidget", "Widget received action: $action")

        FocusTimerManager.init(context)
        when (action) {
            "com.example.widget.ACTION_REFRESH_TOTAL_FOCUS" -> {
                WidgetManager.updateTotalFocusTimeWidget(context)
            }
            "com.example.widget.ACTION_SHARE_TOTAL_FOCUS" -> {
                val todaySeconds = WidgetManager.fetchTodayTotalFocusSeconds(context)
                val hrs = todaySeconds / 3600
                val mins = (todaySeconds % 3600) / 60
                val secs = todaySeconds % 60
                val formattedTime = if (hrs > 0) {
                    String.format(java.util.Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
                } else {
                    String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
                }
                val readable = when {
                    hrs > 0 -> "${hrs}h ${mins}m ${secs}s"
                    mins > 0 -> "${mins}m ${secs}s"
                    else -> "${secs}s"
                }

                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "My Today Focus Time")
                        putExtra(Intent.EXTRA_TEXT, "🎯 Today's Realtime Focus Time: $formattedTime ($readable)!\nStay focused with Life OS!")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share Focus Time").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Log.e("TotalFocusTimeWidget", "Share failed", e)
                }
            }
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                WidgetManager.updateTotalFocusTimeWidget(context, isPartialUpdate = true)
            }
        }
    }
}
