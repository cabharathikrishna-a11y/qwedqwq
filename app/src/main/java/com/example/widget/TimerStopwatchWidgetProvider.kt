package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.util.FocusTimerManager

class TimerStopwatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updateStopwatchWidget(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updateStopwatchWidget(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Log.d("TimerStopwatchWidget", "Widget received broadcast action: $action")
        
        FocusTimerManager.init(context)
        when (action) {
            "com.example.widget.ACTION_STOPWATCH_START_PAUSE" -> {
                val isStopwatchActive = FocusTimerManager.isStopwatchActive.value
                val isPaused = FocusTimerManager.isPaused.value
                val wasStartedFromStopwatch = FocusTimerManager.wasStartedFromStopwatch.value
                val isTimerRunning = FocusTimerManager.isTimerRunning.value

                if (isStopwatchActive && !isPaused) {
                    FocusTimerManager.pauseStopwatch(context)
                } else {
                    if (isTimerRunning || (!wasStartedFromStopwatch && FocusTimerManager.accumulatedSessionTimeMs.value > 0L)) {
                        FocusTimerManager.resetTimer(context, saveSession = true)
                        FocusTimerManager.startStopwatch(context, isResuming = false)
                    } else {
                        val isPausedOrMidSession = isPaused || FocusTimerManager.accumulatedSessionTimeMs.value > 0L || FocusTimerManager.stopwatchSeconds.value > 0
                        FocusTimerManager.startStopwatch(context, isResuming = isPausedOrMidSession)
                    }
                }
                WidgetManager.updateAllWidgets(context)
            }
            "com.example.widget.ACTION_STOPWATCH_BREAK" -> {
                FocusTimerManager.takeBreakFromStopwatch(context)
                WidgetManager.updateAllWidgets(context)
            }
            "com.example.widget.ACTION_STOPWATCH_RESET" -> {
                FocusTimerManager.resetStopwatch(context, saveSession = true)
                WidgetManager.updateAllWidgets(context)
            }
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                WidgetManager.updateStopwatchWidget(context, isPartialUpdate = true)
            }
        }
    }
}
