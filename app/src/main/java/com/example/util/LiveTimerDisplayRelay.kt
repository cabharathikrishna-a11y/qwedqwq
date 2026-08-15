package com.example.util

import android.content.Context
import android.util.Log
import com.example.service.KeepAliveService
import com.example.widget.WidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Dedicated Live Timer Display Relay & Continuous Monitor.
 *
 * Single Responsibility:
 * Continuously inspects the exact time text and state being displayed in the Timer Tab every second,
 * and immediately broadcasts/relays those live values to:
 * 1. OSD (On-Screen Display Floating Overlay)
 * 2. Home Screen App Widgets (WidgetManager)
 * 3. System Status / Live Foreground Notification (LiveTimerNotificationManager / KeepAliveService)
 */
object LiveTimerDisplayRelay {

    private const val TAG = "LiveTimerDisplayRelay"

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var reactiveObserversJob: Job? = null

    private var applicationContext: Context? = null

    data class TimerDisplaySnapshot(
        val formattedTimeText: String,
        val rawSeconds: Int,
        val modeName: String,
        val isRunning: Boolean,
        val isPaused: Boolean,
        val isMinusTimer: Boolean,
        val phaseLabel: String,
        val taskTitle: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _liveDisplayTimeText = MutableStateFlow("00:00")
    val liveDisplayTimeText: StateFlow<String> = _liveDisplayTimeText.asStateFlow()

    private val _liveDisplaySnapshot = MutableStateFlow<TimerDisplaySnapshot?>(null)
    val liveDisplaySnapshot: StateFlow<TimerDisplaySnapshot?> = _liveDisplaySnapshot.asStateFlow()

    /**
     * Initializes and starts the continuous 1-second display monitor.
     */
    fun start(context: Context) {
        val appContext = context.applicationContext
        this.applicationContext = appContext

        FocusTimerManager.init(appContext)

        if (tickerJob == null || tickerJob?.isActive == false) {
            startTickerLoop(appContext)
        }

        if (reactiveObserversJob == null || reactiveObserversJob?.isActive == false) {
            startReactiveObservers(appContext)
        }

        Log.d(TAG, "LiveTimerDisplayRelay initialized and running.")
    }

    /**
     * Calculates the exact text that is currently displayed in the Timer tab.
     */
    fun computeCurrentDisplaySnapshot(): TimerDisplaySnapshot {
        val isMinusActive = FocusTimerManager.isMinusTimerActive.value
        val isTimerOn = FocusTimerManager.isTimerRunning.value
        val timerSecsLeft = FocusTimerManager.timerSecondsLeft.value
        val totalTimerDurationSecs = FocusTimerManager.timerDurationMinutes.value * 60
        val isFocusPhase = FocusTimerManager.isFocusPhase.value

        val isStopwatchOn = FocusTimerManager.isStopwatchActive.value
        val stopwatchSecs = FocusTimerManager.stopwatchSeconds.value
        val isTabFocusTimer = FocusTimerManager.isTabFocusTimerSelected.value

        val attachedTaskTitle = FocusTimerManager.attachedTask.value?.title ?: "Focus Session"

        val (formattedText, rawSecs, mode, isRunning, isPaused, isMinus, phase) = when {
            isMinusActive -> {
                val sec = FocusTimerManager.minusTimerSeconds.value
                val hours = sec / 3600
                val mins = (sec % 3600) / 60
                val secs = sec % 60
                val text = if (hours > 0) {
                    String.format(Locale.US, "-%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "-%02d:%02d", mins, secs)
                }
                TimerDisplaySnapshot(
                    formattedTimeText = text,
                    rawSeconds = sec,
                    modeName = "MINUS_TIMER",
                    isRunning = true,
                    isPaused = false,
                    isMinusTimer = true,
                    phaseLabel = "Overtime ⏳",
                    taskTitle = attachedTaskTitle
                )
            }
            !isFocusPhase -> {
                // Break countdown phase
                val hours = timerSecsLeft / 3600
                val mins = (timerSecsLeft % 3600) / 60
                val secs = timerSecsLeft % 60
                val text = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", mins, secs)
                }
                val isPausedState = !isTimerOn && (timerSecsLeft > 0 || FocusTimerManager.isPaused.value)
                TimerDisplaySnapshot(
                    formattedTimeText = text,
                    rawSeconds = timerSecsLeft,
                    modeName = "BREAK_COUNTDOWN",
                    isRunning = isTimerOn,
                    isPaused = isPausedState,
                    isMinusTimer = false,
                    phaseLabel = "Break ☕",
                    taskTitle = attachedTaskTitle
                )
            }
            isTabFocusTimer -> {
                // Focus Pomodoro Timer Countdown
                val hours = timerSecsLeft / 3600
                val mins = (timerSecsLeft % 3600) / 60
                val secs = timerSecsLeft % 60
                val text = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", timerSecsLeft / 60, secs)
                }
                val hasProgress = timerSecsLeft < totalTimerDurationSecs && timerSecsLeft > 0
                val isPausedState = !isTimerOn && (hasProgress || FocusTimerManager.isPaused.value)
                TimerDisplaySnapshot(
                    formattedTimeText = text,
                    rawSeconds = timerSecsLeft,
                    modeName = "POMODORO_TIMER",
                    isRunning = isTimerOn,
                    isPaused = isPausedState,
                    isMinusTimer = false,
                    phaseLabel = "Focusing 🎯",
                    taskTitle = attachedTaskTitle
                )
            }
            else -> {
                // Stopwatch Mode
                val hours = stopwatchSecs / 3600
                val mins = (stopwatchSecs % 3600) / 60
                val secs = stopwatchSecs % 60
                val text = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", mins, secs)
                }
                val hasProgress = stopwatchSecs > 0
                val isPausedState = !isStopwatchOn && (hasProgress || FocusTimerManager.isPaused.value)
                TimerDisplaySnapshot(
                    formattedTimeText = text,
                    rawSeconds = stopwatchSecs,
                    modeName = "STOPWATCH",
                    isRunning = isStopwatchOn,
                    isPaused = isPausedState,
                    isMinusTimer = false,
                    phaseLabel = "Stopwatch ⏱️",
                    taskTitle = attachedTaskTitle
                )
            }
        }

        return TimerDisplaySnapshot(
            formattedTimeText = formattedText,
            rawSeconds = rawSecs,
            modeName = mode,
            isRunning = isRunning,
            isPaused = isPaused,
            isMinusTimer = isMinus,
            phaseLabel = phase,
            taskTitle = attachedTaskTitle
        )
    }

    /**
     * Primary 1-second continuous monitoring loop.
     */
    private fun startTickerLoop(context: Context) {
        tickerJob?.cancel()
        tickerJob = monitorScope.launch {
            Log.d(TAG, "Ticker loop started: monitoring every 1s")
            while (isActive) {
                try {
                    syncAndRelayToOutputs(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in relay ticker tick: ${e.message}", e)
                }
                delay(1000)
            }
        }
    }

    /**
     * Instant reactive triggers whenever underlying state values change.
     */
    private fun startReactiveObservers(context: Context) {
        reactiveObserversJob?.cancel()
        reactiveObserversJob = monitorScope.launch {
            launch {
                FocusTimerManager.isTimerRunning.collect { syncAndRelayToOutputs(context) }
            }
            launch {
                FocusTimerManager.isStopwatchActive.collect { syncAndRelayToOutputs(context) }
            }
            launch {
                FocusTimerManager.isMinusTimerActive.collect { syncAndRelayToOutputs(context) }
            }
            launch {
                FocusTimerManager.isTabFocusTimerSelected.collect { syncAndRelayToOutputs(context) }
            }
            launch {
                FocusTimerManager.isPaused.collect { syncAndRelayToOutputs(context) }
            }
            launch {
                FocusTimerManager.isFocusPhase.collect { syncAndRelayToOutputs(context) }
            }
        }
    }

    /**
     * Performs atomic reading of display text, updates StateFlows, and tells values to OSD, widgets, and notifications.
     */
    fun syncAndRelayToOutputs(context: Context) {
        val snapshot = computeCurrentDisplaySnapshot()
        _liveDisplayTimeText.value = snapshot.formattedTimeText
        _liveDisplaySnapshot.value = snapshot

        val appContext = context.applicationContext

        // 1. Tell OSD (Floating On-Screen Display)
        try {
            FocusTimerManager.updateOverlayTextAndState()
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating OSD overlay: ${e.message}")
        }

        // 2. Tell Home Screen Widgets
        try {
            WidgetManager.updateAllWidgets(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating widgets: ${e.message}")
        }

        // 3. Tell Live Foreground Notification
        try {
            LiveTimerNotificationManager.updateNotification(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating notification: ${e.message}")
        }
    }

    /**
     * Manual force trigger.
     */
    fun forceSync(context: Context) {
        syncAndRelayToOutputs(context)
    }

    /**
     * Stops the continuous monitor.
     */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        reactiveObserversJob?.cancel()
        reactiveObserversJob = null
        Log.d(TAG, "LiveTimerDisplayRelay stopped.")
    }
}
