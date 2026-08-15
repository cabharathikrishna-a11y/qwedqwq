package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.service.KeepAliveService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Unified Live Timer & Stopwatch Notification Manager.
 * 
 * Provides total centralized control over:
 * 1. Fetching live snapshot data for active Stopwatch, Pomodoro Timer, Break, and Minus Timer sessions.
 * 2. Notification button configuration and customization presets.
 * 3. Command registration, PendingIntent generation, and central command execution dispatching.
 * 4. Building and sending rich custom & native notifications.
 */
object LiveTimerNotificationManager {

    private const val TAG = "LiveTimerNotifMgr"

    const val CHANNEL_ID = "lifeos_keepalive_service_channel"
    const val CHANNEL_NAME = "LifeOS Live Focus & Timer Daemon"
    const val NOTIFICATION_ID = 10001

    // =========================================================================
    // 1. REGISTERED COMMAND & ACTION CONSTANTS
    // =========================================================================
    const val ACTION_PAUSE_TIMER = "com.example.service.ACTION_PAUSE_TIMER"
    const val ACTION_RESUME_TIMER = "com.example.service.ACTION_RESUME_TIMER"
    const val ACTION_RESET_TIMER = "com.example.service.ACTION_RESET_TIMER"

    const val ACTION_PAUSE_STOPWATCH = "com.example.service.ACTION_PAUSE_STOPWATCH"
    const val ACTION_RESUME_STOPWATCH = "com.example.service.ACTION_RESUME_STOPWATCH"
    const val ACTION_RESET_STOPWATCH = "com.example.service.ACTION_RESET_STOPWATCH"

    const val ACTION_TOGGLE_TIMER = "com.example.service.ACTION_TOGGLE_TIMER"
    const val ACTION_TOGGLE_STOPWATCH = "com.example.service.ACTION_TOGGLE_STOPWATCH"

    const val ACTION_START_BREAK_FROM_MINUS = "com.example.service.ACTION_START_BREAK_FROM_MINUS"
    const val ACTION_END_MINUS_TIMER = "com.example.service.ACTION_END_MINUS_TIMER"

    const val ACTION_ADD_1_MIN = "com.example.service.ACTION_ADD_1_MIN"
    const val ACTION_ADD_5_MIN = "com.example.service.ACTION_ADD_5_MIN"
    const val ACTION_REFRESH_NOTIFICATION = "com.example.service.ACTION_REFRESH_NOTIFICATION"

    // =========================================================================
    // 2. DATA MODELS & ENUMS
    // =========================================================================
    enum class LiveTimerMode {
        STOPWATCH,
        POMODORO_FOCUS,
        POMODORO_BREAK,
        MINUS_TIMER_OVERTIME,
        IDLE
    }

    enum class NotificationButtonType {
        TOGGLE_PAUSE_RESUME,
        END_SESSION,
        START_BREAK,
        ADD_ONE_MINUTE,
        ADD_FIVE_MINUTES,
        START_STOPWATCH,
        START_TIMER
    }

    data class NotificationActionButtonSpec(
        val type: NotificationButtonType,
        val title: String,
        val iconRes: Int,
        val pendingIntent: PendingIntent,
        val actionString: String,
        val isPrimary: Boolean = false
    )

    data class NotificationButtonPreferences(
        val showAddOneMinuteButton: Boolean = true,
        val showAddFiveMinuteButton: Boolean = false,
        val showEndButton: Boolean = true,
        val showStartBreakButton: Boolean = true,
        val useCustomRemoteViews: Boolean = true
    )

    data class LiveNotificationSnapshot(
        val mode: LiveTimerMode,
        val formattedTime: String,
        val rawSeconds: Int,
        val totalDurationSeconds: Int,
        val progressPercent: Int,
        val isRunning: Boolean,
        val isPaused: Boolean,
        val isMinusTimer: Boolean,
        val taskTitle: String,
        val taskTag: String,
        val phaseLabel: String,
        val contentTitle: String,
        val contentText: String,
        val subText: String,
        val hasActiveSession: Boolean
    )

    private val _currentSnapshot = MutableStateFlow<LiveNotificationSnapshot?>(null)
    val currentSnapshot: StateFlow<LiveNotificationSnapshot?> = _currentSnapshot.asStateFlow()

    private val _buttonPreferences = MutableStateFlow(NotificationButtonPreferences())
    val buttonPreferences: StateFlow<NotificationButtonPreferences> = _buttonPreferences.asStateFlow()

    // =========================================================================
    // 3. DATA FETCHING LOGIC
    // =========================================================================
    /**
     * Atomically fetches a snapshot of the live timer/stopwatch state.
     */
    fun fetchCurrentSnapshot(context: Context): LiveNotificationSnapshot {
        val isMinusActive = FocusTimerManager.isMinusTimerActive.value
        val isTimerOn = FocusTimerManager.isTimerRunning.value
        val timerSecsLeft = FocusTimerManager.timerSecondsLeft.value
        val timerDurationMins = FocusTimerManager.timerDurationMinutes.value
        val totalTimerDurationSecs = timerDurationMins * 60
        val isFocusPhase = FocusTimerManager.isFocusPhase.value

        val isStopwatchOn = FocusTimerManager.isStopwatchActive.value
        val stopwatchSecs = FocusTimerManager.stopwatchSeconds.value
        val isTabFocusTimer = FocusTimerManager.isTabFocusTimerSelected.value

        val attachedTaskTitle = FocusTimerManager.attachedTask.value?.title ?: "Focus Session"
        val attachedTag = FocusTimerManager.attachedTag.value.ifEmpty { "Study" }

        val snapshot = when {
            isMinusActive -> {
                val totalSecs = FocusTimerManager.minusTimerSeconds.value
                val hours = totalSecs / 3600
                val mins = (totalSecs % 3600) / 60
                val secs = totalSecs % 60
                val timeStr = if (hours > 0) {
                    String.format(Locale.US, "-%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "-%02d:%02d", mins, secs)
                }
                LiveNotificationSnapshot(
                    mode = LiveTimerMode.MINUS_TIMER_OVERTIME,
                    formattedTime = timeStr,
                    rawSeconds = totalSecs,
                    totalDurationSeconds = totalSecs,
                    progressPercent = 100,
                    isRunning = true,
                    isPaused = false,
                    isMinusTimer = true,
                    taskTitle = attachedTaskTitle,
                    taskTag = attachedTag,
                    phaseLabel = "Overtime ⏳",
                    contentTitle = "Focus Finished: $timeStr",
                    contentText = "Start Break or End Session • $attachedTaskTitle",
                    subText = "Overtime ⏳",
                    hasActiveSession = true
                )
            }
            isTabFocusTimer -> {
                val hasProgress = timerSecsLeft < totalTimerDurationSecs && timerSecsLeft > 0
                val isPaused = !isTimerOn && (hasProgress || FocusTimerManager.isPaused.value)
                val hours = timerSecsLeft / 3600
                val mins = (timerSecsLeft % 3600) / 60
                val secs = timerSecsLeft % 60
                val timeStr = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", timerSecsLeft / 60, secs)
                }
                val phase = if (isFocusPhase) "Focusing 🎯" else "Break ☕"
                val progress = if (totalTimerDurationSecs > 0) {
                    (((totalTimerDurationSecs - timerSecsLeft).toFloat() / totalTimerDurationSecs) * 100).toInt().coerceIn(0, 100)
                } else 0

                val title = if (isTimerOn) {
                    "Focus Timer: $timeStr ($phase)"
                } else if (isPaused) {
                    "Focus Timer: $timeStr (Paused - $phase)"
                } else {
                    "Focus Timer ($phase)"
                }

                val text = if (isTimerOn) {
                    "Active • $attachedTaskTitle"
                } else if (isPaused) {
                    "Paused • $attachedTaskTitle"
                } else {
                    attachedTaskTitle
                }

                LiveNotificationSnapshot(
                    mode = if (isFocusPhase) LiveTimerMode.POMODORO_FOCUS else LiveTimerMode.POMODORO_BREAK,
                    formattedTime = timeStr,
                    rawSeconds = timerSecsLeft,
                    totalDurationSeconds = totalTimerDurationSecs,
                    progressPercent = progress,
                    isRunning = isTimerOn,
                    isPaused = isPaused,
                    isMinusTimer = false,
                    taskTitle = attachedTaskTitle,
                    taskTag = attachedTag,
                    phaseLabel = phase,
                    contentTitle = title,
                    contentText = text,
                    subText = phase,
                    hasActiveSession = isTimerOn || isPaused
                )
            }
            else -> {
                // Live Stopwatch Mode
                val hasStopwatchProgress = stopwatchSecs > 0
                val isStopwatchPaused = !isStopwatchOn && (hasStopwatchProgress || FocusTimerManager.isPaused.value)
                val hours = stopwatchSecs / 3600
                val mins = (stopwatchSecs % 3600) / 60
                val secs = stopwatchSecs % 60
                val timeStr = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                } else {
                    String.format(Locale.US, "%02d:%02d", mins, secs)
                }
                val phase = "Stopwatch ⏱️"

                val title = if (isStopwatchOn) {
                    "Stopwatch: $timeStr (Focusing 🎯)"
                } else if (isStopwatchPaused) {
                    "Stopwatch: $timeStr (Paused)"
                } else {
                    "Stopwatch"
                }

                val text = if (isStopwatchOn) {
                    "Active • $attachedTaskTitle"
                } else if (isStopwatchPaused) {
                    "Paused • $attachedTaskTitle"
                } else {
                    attachedTaskTitle
                }

                val isActive = isStopwatchOn || isStopwatchPaused
                LiveNotificationSnapshot(
                    mode = if (isActive) LiveTimerMode.STOPWATCH else LiveTimerMode.IDLE,
                    formattedTime = timeStr,
                    rawSeconds = stopwatchSecs,
                    totalDurationSeconds = maxOf(stopwatchSecs, 1),
                    progressPercent = 0,
                    isRunning = isStopwatchOn,
                    isPaused = isStopwatchPaused,
                    isMinusTimer = false,
                    taskTitle = attachedTaskTitle,
                    taskTag = attachedTag,
                    phaseLabel = phase,
                    contentTitle = if (isActive) title else "LifeOS Active System",
                    contentText = if (isActive) text else "Ensuring accurate backgrounds & task scheduling",
                    subText = if (isActive) phase else "LifeOS",
                    hasActiveSession = isActive
                )
            }
        }

        _currentSnapshot.value = snapshot
        return snapshot
    }

    // =========================================================================
    // 4. BUTTON CONFIGURATION MANAGEMENT
    // =========================================================================
    fun loadButtonPreferences(context: Context): NotificationButtonPreferences {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val config = NotificationButtonPreferences(
            showAddOneMinuteButton = prefs.getBoolean("notif_btn_show_add_1m", true),
            showAddFiveMinuteButton = prefs.getBoolean("notif_btn_show_add_5m", false),
            showEndButton = prefs.getBoolean("notif_btn_show_end", true),
            showStartBreakButton = prefs.getBoolean("notif_btn_show_start_break", true),
            useCustomRemoteViews = prefs.getBoolean("notif_use_custom_remote_views", true)
        )
        _buttonPreferences.value = config
        return config
    }

    fun saveButtonPreferences(context: Context, config: NotificationButtonPreferences) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("notif_btn_show_add_1m", config.showAddOneMinuteButton)
            .putBoolean("notif_btn_show_add_5m", config.showAddFiveMinuteButton)
            .putBoolean("notif_btn_show_end", config.showEndButton)
            .putBoolean("notif_btn_show_start_break", config.showStartBreakButton)
            .putBoolean("notif_use_custom_remote_views", config.useCustomRemoteViews)
            .apply()
        _buttonPreferences.value = config
        updateNotification(context)
    }

    /**
     * Resolves the list of configured action buttons for the given state.
     */
    fun resolveConfiguredButtons(
        context: Context,
        snapshot: LiveNotificationSnapshot,
        prefs: NotificationButtonPreferences = loadButtonPreferences(context)
    ): List<NotificationActionButtonSpec> {
        val buttons = mutableListOf<NotificationActionButtonSpec>()

        when (snapshot.mode) {
            LiveTimerMode.MINUS_TIMER_OVERTIME -> {
                if (prefs.showStartBreakButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.START_BREAK,
                            title = "Start Break",
                            iconRes = android.R.drawable.ic_media_play,
                            pendingIntent = getCommandPendingIntent(context, ACTION_START_BREAK_FROM_MINUS),
                            actionString = ACTION_START_BREAK_FROM_MINUS,
                            isPrimary = true
                        )
                    )
                }
                if (prefs.showEndButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.END_SESSION,
                            title = "End",
                            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                            pendingIntent = getCommandPendingIntent(context, ACTION_END_MINUS_TIMER),
                            actionString = ACTION_END_MINUS_TIMER
                        )
                    )
                }
            }
            LiveTimerMode.POMODORO_FOCUS, LiveTimerMode.POMODORO_BREAK -> {
                // Pause / Resume Toggle Button
                val isPlaying = snapshot.isRunning
                val toggleAction = if (isPlaying) ACTION_PAUSE_TIMER else ACTION_RESUME_TIMER
                buttons.add(
                    NotificationActionButtonSpec(
                        type = NotificationButtonType.TOGGLE_PAUSE_RESUME,
                        title = if (isPlaying) "Pause" else "Resume",
                        iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        pendingIntent = getCommandPendingIntent(context, toggleAction),
                        actionString = toggleAction,
                        isPrimary = true
                    )
                )

                // Quick +1m add button for Pomodoro focus mode
                if (snapshot.mode == LiveTimerMode.POMODORO_FOCUS && prefs.showAddOneMinuteButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.ADD_ONE_MINUTE,
                            title = "+1m",
                            iconRes = android.R.drawable.ic_input_add,
                            pendingIntent = getCommandPendingIntent(context, ACTION_ADD_1_MIN),
                            actionString = ACTION_ADD_1_MIN
                        )
                    )
                }

                // Quick +5m add button if enabled
                if (snapshot.mode == LiveTimerMode.POMODORO_FOCUS && prefs.showAddFiveMinuteButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.ADD_FIVE_MINUTES,
                            title = "+5m",
                            iconRes = android.R.drawable.ic_input_add,
                            pendingIntent = getCommandPendingIntent(context, ACTION_ADD_5_MIN),
                            actionString = ACTION_ADD_5_MIN
                        )
                    )
                }

                // End Session Button
                if (prefs.showEndButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.END_SESSION,
                            title = "End",
                            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                            pendingIntent = getCommandPendingIntent(context, ACTION_RESET_TIMER),
                            actionString = ACTION_RESET_TIMER
                        )
                    )
                }
            }
            LiveTimerMode.STOPWATCH -> {
                val isPlaying = snapshot.isRunning
                val toggleAction = if (isPlaying) ACTION_PAUSE_STOPWATCH else ACTION_RESUME_STOPWATCH
                buttons.add(
                    NotificationActionButtonSpec(
                        type = NotificationButtonType.TOGGLE_PAUSE_RESUME,
                        title = if (isPlaying) "Pause" else "Resume",
                        iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        pendingIntent = getCommandPendingIntent(context, toggleAction),
                        actionString = toggleAction,
                        isPrimary = true
                    )
                )

                if (prefs.showEndButton) {
                    buttons.add(
                        NotificationActionButtonSpec(
                            type = NotificationButtonType.END_SESSION,
                            title = "End",
                            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                            pendingIntent = getCommandPendingIntent(context, ACTION_RESET_STOPWATCH),
                            actionString = ACTION_RESET_STOPWATCH
                        )
                    )
                }
            }
            LiveTimerMode.IDLE -> {
                buttons.add(
                    NotificationActionButtonSpec(
                        type = NotificationButtonType.START_STOPWATCH,
                        title = "Start Stopwatch",
                        iconRes = android.R.drawable.ic_media_play,
                        pendingIntent = getCommandPendingIntent(context, ACTION_RESUME_STOPWATCH),
                        actionString = ACTION_RESUME_STOPWATCH
                    )
                )
                buttons.add(
                    NotificationActionButtonSpec(
                        type = NotificationButtonType.START_TIMER,
                        title = "Start Timer",
                        iconRes = android.R.drawable.ic_media_play,
                        pendingIntent = getCommandPendingIntent(context, ACTION_RESUME_TIMER),
                        actionString = ACTION_RESUME_TIMER
                    )
                )
            }
        }

        return buttons
    }

    // =========================================================================
    // 5. COMMAND DISPATCHER & INTENT FACTORIES
    // =========================================================================
    fun getCommandPendingIntent(context: Context, action: String, requestCode: Int = action.hashCode()): PendingIntent {
        val intent = Intent(context.applicationContext, KeepAliveService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(context.applicationContext, requestCode, intent, flags)
    }

    fun getContentPendingIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context.applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TIMER_PAGE", true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context.applicationContext, 9999, launchIntent, flags)
    }

    /**
     * Centrally processes and executes incoming notification commands.
     */
    fun dispatchCommand(context: Context, action: String?): Boolean {
        if (action == null) return false
        Log.d(TAG, "Dispatching notification command: $action")
        val appContext = context.applicationContext

        try {
            when (action) {
                ACTION_PAUSE_TIMER -> {
                    FocusTimerManager.pauseTimer(appContext)
                }
                ACTION_RESUME_TIMER -> {
                    FocusTimerManager.startTimer(appContext, isResuming = true)
                }
                ACTION_RESET_TIMER -> {
                    FocusTimerManager.resetTimer(appContext)
                }
                ACTION_PAUSE_STOPWATCH -> {
                    FocusTimerManager.pauseStopwatch(appContext)
                }
                ACTION_RESUME_STOPWATCH -> {
                    FocusTimerManager.startStopwatch(appContext, isResuming = true)
                }
                ACTION_RESET_STOPWATCH -> {
                    FocusTimerManager.resetStopwatch(appContext)
                }
                ACTION_TOGGLE_TIMER -> {
                    if (FocusTimerManager.isTimerRunning.value) {
                        FocusTimerManager.pauseTimer(appContext)
                    } else {
                        FocusTimerManager.startTimer(appContext, isResuming = true)
                    }
                }
                ACTION_TOGGLE_STOPWATCH -> {
                    if (FocusTimerManager.isStopwatchActive.value) {
                        FocusTimerManager.pauseStopwatch(appContext)
                    } else {
                        FocusTimerManager.startStopwatch(appContext, isResuming = true)
                    }
                }
                ACTION_START_BREAK_FROM_MINUS -> {
                    FocusTimerManager.startBreakFromMinusTimer(appContext)
                }
                ACTION_END_MINUS_TIMER -> {
                    FocusTimerManager.endMinusTimerSession(appContext)
                }
                ACTION_ADD_1_MIN -> {
                    FocusTimerManager.addTimerSeconds(appContext, 60)
                }
                ACTION_ADD_5_MIN -> {
                    FocusTimerManager.addTimerSeconds(appContext, 300)
                }
                ACTION_REFRESH_NOTIFICATION -> {
                    updateNotification(appContext)
                }
                else -> {
                    Log.w(TAG, "Unknown action received: $action")
                    return false
                }
            }

            // Immediately update notification & widgets
            updateNotification(appContext)
            com.example.widget.WidgetManager.updateAllWidgets(appContext)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error executing notification command '$action': ${e.message}", e)
            return false
        }
    }

    // =========================================================================
    // 6. NOTIFICATION BUILDING & SENDING
    // =========================================================================
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps LifeOS background app monitoring & live focus timer active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the complete notification based on current live state.
     */
    fun buildNotification(context: Context): Notification {
        createNotificationChannel(context)
        val snapshot = fetchCurrentSnapshot(context)
        val buttonPrefs = loadButtonPreferences(context)
        val actionButtons = resolveConfiguredButtons(context, snapshot, buttonPrefs)
        val contentPendingIntent = getContentPendingIntent(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .setContentTitle(snapshot.contentTitle)
            .setContentText(snapshot.contentText)
            .setSubText(snapshot.subText)
            .setOngoing(snapshot.hasActiveSession)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        // Custom RemoteViews layout integration
        if (buttonPrefs.useCustomRemoteViews && snapshot.hasActiveSession) {
            try {
                val rvSmall = RemoteViews(context.packageName, R.layout.notification_timer_small).apply {
                    setTextViewText(R.id.notif_timer_text, snapshot.formattedTime)
                    
                    if (snapshot.isMinusTimer) {
                        setTextColor(R.id.notif_timer_text, android.graphics.Color.parseColor("#EF5350"))
                        setImageViewResource(R.id.notif_btn_pause_resume, R.drawable.ic_notif_play)
                        setOnClickPendingIntent(
                            R.id.notif_btn_pause_resume,
                            getCommandPendingIntent(context, ACTION_START_BREAK_FROM_MINUS)
                        )
                    } else {
                        setTextColor(R.id.notif_timer_text, android.graphics.Color.parseColor("#FF3B30"))
                        setImageViewResource(
                            R.id.notif_btn_pause_resume,
                            if (snapshot.isRunning) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
                        )
                        val toggleAction = if (snapshot.mode == LiveTimerMode.STOPWATCH) {
                            if (snapshot.isRunning) ACTION_PAUSE_STOPWATCH else ACTION_RESUME_STOPWATCH
                        } else {
                            if (snapshot.isRunning) ACTION_PAUSE_TIMER else ACTION_RESUME_TIMER
                        }
                        setOnClickPendingIntent(
                            R.id.notif_btn_pause_resume,
                            getCommandPendingIntent(context, toggleAction)
                        )
                    }
                }

                val rvExpanded = RemoteViews(context.packageName, R.layout.notification_timer_expanded).apply {
                    setTextViewText(R.id.notif_exp_timer_text, snapshot.formattedTime)
                    setTextViewText(R.id.notif_exp_task_title, "${snapshot.phaseLabel} • ${snapshot.taskTitle}")

                    if (snapshot.isMinusTimer) {
                        setTextColor(R.id.notif_exp_timer_text, android.graphics.Color.parseColor("#EF5350"))
                        setImageViewResource(R.id.notif_exp_btn_pause_resume, R.drawable.ic_notif_play)
                        setOnClickPendingIntent(
                            R.id.notif_exp_btn_pause_resume,
                            getCommandPendingIntent(context, ACTION_START_BREAK_FROM_MINUS)
                        )
                        setTextViewText(R.id.notif_exp_btn_stop, "End Session")
                        setOnClickPendingIntent(
                            R.id.notif_exp_btn_stop,
                            getCommandPendingIntent(context, ACTION_END_MINUS_TIMER)
                        )
                    } else {
                        setTextColor(R.id.notif_exp_timer_text, android.graphics.Color.parseColor("#FF3B30"))
                        setImageViewResource(
                            R.id.notif_exp_btn_pause_resume,
                            if (snapshot.isRunning) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
                        )
                        val toggleAction = if (snapshot.mode == LiveTimerMode.STOPWATCH) {
                            if (snapshot.isRunning) ACTION_PAUSE_STOPWATCH else ACTION_RESUME_STOPWATCH
                        } else {
                            if (snapshot.isRunning) ACTION_PAUSE_TIMER else ACTION_RESUME_TIMER
                        }
                        val stopAction = if (snapshot.mode == LiveTimerMode.STOPWATCH) ACTION_RESET_STOPWATCH else ACTION_RESET_TIMER

                        setOnClickPendingIntent(
                            R.id.notif_exp_btn_pause_resume,
                            getCommandPendingIntent(context, toggleAction)
                        )
                        setTextViewText(R.id.notif_exp_btn_stop, "End Session")
                        setOnClickPendingIntent(
                            R.id.notif_exp_btn_stop,
                            getCommandPendingIntent(context, stopAction)
                        )
                    }
                }

                builder.setCustomContentView(rvSmall)
                    .setCustomBigContentView(rvExpanded)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Custom RemoteViews, falling back to standard views: ${e.message}", e)
            }
        }

        // Add native NotificationCompat action buttons for wearable/lockscreen/Auto compatibility
        for (actionSpec in actionButtons) {
            builder.addAction(actionSpec.iconRes, actionSpec.title, actionSpec.pendingIntent)
        }

        return builder.build()
    }

    /**
     * Direct notification updater.
     */
    fun updateNotification(context: Context) {
        try {
            KeepAliveService.updateNotification(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger notification update: ${e.message}", e)
        }
    }
}
