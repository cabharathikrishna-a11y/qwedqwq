package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.util.LiveTimerNotificationManager

/**
 * BroadcastReceiver for handling Notification action clicks and commands for live Stopwatch & Pomodoro Timer.
 */
class TimerNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("TimerNotificationReceiver", "onReceive triggered with action: $action")
        LiveTimerNotificationManager.dispatchCommand(context, action)
    }
}
