package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.util.Base64
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.api.DevicePresenceManager
import com.example.api.PeerLiveSphereManager
import com.example.data.AppDatabase
import com.example.data.JournalEntry
import com.example.util.FocusTimerManager
import com.example.util.StableTime
import com.example.util.SystemTimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Central Widget Manager responsible for managing all widget values,
 * fetching data from the app (Room DB, SharedPreferences, LiveState),
 * and updating home screen widgets efficiently and asynchronously.
 */
object WidgetManager {

    private const val TAG = "WidgetManager"

    // --- DATA MODELS ---

    data class TimelineBlock(
        val startMs: Long,
        val endMs: Long,
        val color: Int
    )

    data class FocusingUserLogo(
        val name: String,
        val avatar: String
    )

    data class JournalPhotoItem(
        val photoUrl: String,
        val title: String,
        val text: String,
        val dateFormatted: String,
        val entryId: Int
    )

    // --- HELPER & INTENT UTILITIES ---

    fun getPendingIntentFlags(isMutable: Boolean = false): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isMutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    /**
     * Programmatically requests the Android Launcher to pin a widget to the Home Screen (Android 8.0+ / API 26+)
     */
    fun requestPinWidget(context: Context, providerClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val myProvider = ComponentName(context, providerClass)
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    9000,
                    Intent(context, providerClass).apply { action = "com.example.widget.ACTION_WIDGET_PINNED" },
                    getPendingIntentFlags()
                )
                appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
            }
        }
    }

    fun fetchWidgetGlassStyle(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("widget_glass_style", "black_glass") ?: "black_glass"
    }

    private fun getBackgroundDrawableRes(context: Context): Int {
        val glassStyle = fetchWidgetGlassStyle(context)
        return if (glassStyle == "clear_glass") R.drawable.widget_background_clear_glass else R.drawable.widget_background_black_glass
    }

    // --- CENTRALIZED DATA FETCHERS ---

    /**
     * Calculates the total focus time in seconds for today across accumulated session,
     * pending review, and active session.
     */
    fun fetchTodayTotalFocusSeconds(context: Context): Int {
        FocusTimerManager.init(context)
        val baseTodaySecs = FocusTimerManager.getTodayFocusSeconds()
        val pendingFocusReview = FocusTimerManager.pendingFocusReview.value
        val todayStr = SystemTimeService.getTodayString()
        val pendingSecs = pendingFocusReview?.let { FocusTimerManager.getOverlapSecondsForDate(it, todayStr) } ?: 0

        val isRunningOrPaused = FocusTimerManager.isTimerRunning.value || FocusTimerManager.isStopwatchActive.value || FocusTimerManager.isPaused.value
        val activeSecs = if (FocusTimerManager.isFocusPhase.value && pendingFocusReview == null && isRunningOrPaused) {
            (FocusTimerManager.accumulatedSessionTimeMs.value / 1000).toInt()
        } else {
            0
        }
        return baseTodaySecs + pendingSecs + activeSecs
    }

    /**
     * Fetches today's completed and active timeline blocks for the Total Focus Time widget.
     */
    suspend fun fetchTodayTimelineBlocks(context: Context): List<TimelineBlock> = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<TimelineBlock>()
        val todayStr = SystemTimeService.getTodayString()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDayMs = cal.timeInMillis
        val endOfDayMs = startOfDayMs + (24 * 3600 * 1000L)

        val colors = intArrayOf(
            Color.parseColor("#FFCC00"), // Yellow
            Color.parseColor("#30B0C7"), // Cyan
            Color.parseColor("#FF3B30"), // Red
            Color.parseColor("#007AFF"), // Blue
            Color.parseColor("#AF52DE"), // Purple
            Color.parseColor("#34C759"), // Green
            Color.parseColor("#FF9500")  // Orange
        )

        try {
            val db = AppDatabase.getInstance(context)
            val records = try {
                db.localHistoryVaultDao().getAllHistoryDirect().filter { it.date_string == todayStr }
            } catch (e: Throwable) {
                emptyList()
            }

            records.forEachIndexed { _, record ->
                val startMs = record.start_time_ms
                val endMs = if (record.end_time_ms > record.start_time_ms) record.end_time_ms else startMs + record.total_focus_ms
                if (startMs < endOfDayMs && endMs > startOfDayMs) {
                    val color = colors[Math.abs((record.subject ?: "").hashCode()) % colors.size]
                    blocks.add(TimelineBlock(startMs, endMs, color))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch today history blocks", e)
        }

        // Active live session block if running right now
        val isRunningOrPaused = FocusTimerManager.isTimerRunning.value || FocusTimerManager.isStopwatchActive.value || FocusTimerManager.isPaused.value
        if (FocusTimerManager.isFocusPhase.value && isRunningOrPaused) {
            val activeSecs = (FocusTimerManager.accumulatedSessionTimeMs.value / 1000).toInt()
            if (activeSecs > 0) {
                val now = System.currentTimeMillis()
                val startMs = maxOf(startOfDayMs, now - (activeSecs * 1000L))
                blocks.add(TimelineBlock(startMs, now, Color.parseColor("#FF3B30")))
            }
        }

        return@withContext blocks
    }

    /**
     * Fetches focusing user logos (myself + peers) for Friends Focus Widget.
     */
    fun fetchFocusingPeers(context: Context): List<FocusingUserLogo> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val focusingLogos = mutableListOf<FocusingUserLogo>()

        FocusTimerManager.init(context)
        val isMeFocusing = (FocusTimerManager.isTimerRunning.value || FocusTimerManager.isStopwatchActive.value)
                && FocusTimerManager.isFocusPhase.value
                && !FocusTimerManager.isPaused.value
                && FocusTimerManager.pendingFocusReview.value == null

        val myEmail = prefs.getString("user_email", "") ?: ""
        val myUsername = prefs.getString("username", "") ?: ""

        if (isMeFocusing) {
            val myName = prefs.getString("username", "")?.ifEmpty { prefs.getString("nickname", "Me") } ?: "Me"
            val myEmoji = prefs.getString("user_emoji", "") ?: ""
            val googleAccount = com.example.util.GmsUtils.getLastSignedInAccount(context)
            val photoUrl = googleAccount?.photoUrl?.toString() ?: prefs.getString("user_photo_url", "") ?: ""
            val myAvatar = when {
                myEmoji.isNotEmpty() && myEmoji != "👤" -> myEmoji
                photoUrl.isNotEmpty() -> photoUrl
                else -> prefs.getString("user_avatar_base64", "") ?: ""
            }
            focusingLogos.add(FocusingUserLogo(myName, myAvatar))
        }

        val activePeers = PeerLiveSphereManager.peerLiveStates.value.filter { (key, peer) ->
            !DevicePresenceManager.isMeUser(
                key = key,
                userId = peer.userId,
                myEmail = myEmail,
                myUsername = myUsername
            ) && peer.status.equals("Focusing", ignoreCase = true)
        }.values

        activePeers.forEach { peer ->
            val peerAvatar = peer.customEmoji ?: ""
            if (focusingLogos.none { it.name.equals(peer.displayName, ignoreCase = true) }) {
                focusingLogos.add(FocusingUserLogo(peer.displayName, peerAvatar))
            }
        }

        return focusingLogos
    }

    /**
     * Fetches journal photos for the Photo Shower widget from local Room database.
     */
    suspend fun fetchJournalPhotoItems(context: Context): List<JournalPhotoItem> = withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(context)
        val journalEntries = database.journalDao().getAllJournalEntriesDirect()
        try {
            WidgetPhotoManager.cleanupOrphanedWidgetCopies(context, journalEntries)
        } catch (_: Exception) {}
        return@withContext extractPhotosFromJournalEntries(journalEntries)
    }

    // --- WIDGET UPDATER FUNCTIONS ---

    /**
     * Updates all widgets registered in the system.
     */
    fun updateAllWidgets(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateFriendsFocusWidget(context)
                updateStopwatchWidget(context)
                updatePomodoroWidget(context)
                updateTotalFocusTimeWidget(context)
                updatePhotoShowerWidget(context)
                updateQuickShortcutsWidget(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating all widgets: ${e.message}", e)
            }
        }
    }

    /**
     * Updates the Friends Focus Widget ("Who is Focusing")
     */
    fun updateFriendsFocusWidget(context: Context, statusText: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, FriendsFocusWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isEmpty()) return@launch

            val bgRes = getBackgroundDrawableRes(context)
            val focusingLogos = fetchFocusingPeers(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_TIMER_PAGE", true)
            }
            val pendingIntent = PendingIntent.getActivity(context, 2001, intent, getPendingIntentFlags())

            val logoIds = arrayOf(
                R.id.focus_logo_1,
                R.id.focus_logo_2,
                R.id.focus_logo_3,
                R.id.focus_logo_4,
                R.id.focus_logo_5
            )

            for (widgetId in allWidgetIds) {
                val largeView = RemoteViews(context.packageName, R.layout.widget_friends_focus).apply {
                    setOnClickPendingIntent(android.R.id.background, pendingIntent)
                    setInt(android.R.id.background, "setBackgroundResource", bgRes)
                    if (focusingLogos.isEmpty()) {
                        setViewVisibility(R.id.focus_logo_idle, View.VISIBLE)
                        val idleBmp = createAvatarLogoBitmap(context, "💤", "Idle", isIdle = true, sizeDp = 44)
                        setImageViewBitmap(R.id.focus_logo_idle, idleBmp)
                        for (id in logoIds) {
                            setViewVisibility(id, View.GONE)
                        }
                    } else {
                        setViewVisibility(R.id.focus_logo_idle, View.GONE)
                        for (i in logoIds.indices) {
                            if (i < focusingLogos.size) {
                                val logo = focusingLogos[i]
                                val bmp = createAvatarLogoBitmap(context, logo.avatar, logo.name, isIdle = false, sizeDp = 44)
                                setImageViewBitmap(logoIds[i], bmp)
                                setViewVisibility(logoIds[i], View.VISIBLE)
                            } else {
                                setViewVisibility(logoIds[i], View.GONE)
                            }
                        }
                    }
                }

                val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val smallLogoIds = arrayOf(
                        R.id.focus_logo_1,
                        R.id.focus_logo_2,
                        R.id.focus_logo_3,
                        R.id.focus_logo_4
                    )
                    val smallView = RemoteViews(context.packageName, R.layout.widget_friends_focus_small).apply {
                        setOnClickPendingIntent(android.R.id.background, pendingIntent)
                        setInt(android.R.id.background, "setBackgroundResource", bgRes)
                        if (focusingLogos.isEmpty()) {
                            setViewVisibility(R.id.focus_logo_idle, View.VISIBLE)
                            val idleBmp = createAvatarLogoBitmap(context, "💤", "Idle", isIdle = true, sizeDp = 36)
                            setImageViewBitmap(R.id.focus_logo_idle, idleBmp)
                            for (id in smallLogoIds) {
                                setViewVisibility(id, View.GONE)
                            }
                        } else {
                            setViewVisibility(R.id.focus_logo_idle, View.GONE)
                            for (i in smallLogoIds.indices) {
                                if (i < focusingLogos.size) {
                                    val logo = focusingLogos[i]
                                    val bmp = createAvatarLogoBitmap(context, logo.avatar, logo.name, isIdle = false, sizeDp = 36)
                                    setImageViewBitmap(smallLogoIds[i], bmp)
                                    setViewVisibility(smallLogoIds[i], View.VISIBLE)
                                } else {
                                    setViewVisibility(smallLogoIds[i], View.GONE)
                                }
                            }
                        }
                    }
                    val viewMap = mapOf(
                        SizeF(140f, 50f) to smallView,
                        SizeF(200f, 80f) to largeView
                    )
                    RemoteViews(viewMap)
                } else {
                    largeView
                }

                appWidgetManager.updateAppWidget(widgetId, finalViews)
            }
        }
    }

    /**
     * Updates the Stopwatch Widget using Chronometer
     */
    fun updateStopwatchWidget(context: Context, isPartialUpdate: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TimerStopwatchWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isEmpty()) return@launch

            val bgRes = getBackgroundDrawableRes(context)

            FocusTimerManager.init(context)
            val isStopwatchActive = FocusTimerManager.isStopwatchActive.value
            val isPaused = FocusTimerManager.isPaused.value
            val wasStartedFromStopwatch = FocusTimerManager.wasStartedFromStopwatch.value

            val isStopwatchMode = isStopwatchActive || (isPaused && wasStartedFromStopwatch)
            val isRunning = isStopwatchActive && !isPaused

            val lastResumeMs = FocusTimerManager.lastResumeTimeMs.value
            val currentChunkMs = if (lastResumeMs != null && isRunning) maxOf(0L, StableTime.currentTimeMillis() - lastResumeMs) else 0L
            val baseAccumulatedMs = if (isStopwatchMode) FocusTimerManager.accumulatedSessionTimeMs.value else 0L
            val totalElapsedMs = if (isStopwatchMode) baseAccumulatedMs + currentChunkMs else 0L
            val seconds = (totalElapsedMs / 1000).toInt()

            val runningBaseTime = SystemClock.elapsedRealtime() - totalElapsedMs
            val staticBaseTime = SystemClock.elapsedRealtime() - (seconds * 1000L)

            val startPauseIntent = Intent(context, TimerStopwatchWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_STOPWATCH_START_PAUSE"
            }
            val startPausePending = PendingIntent.getBroadcast(context, 3001, startPauseIntent, getPendingIntentFlags())

            val breakIntent = Intent(context, TimerStopwatchWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_STOPWATCH_BREAK"
            }
            val breakPending = PendingIntent.getBroadcast(context, 3004, breakIntent, getPendingIntentFlags())

            val resetIntent = Intent(context, TimerStopwatchWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_STOPWATCH_RESET"
            }
            val resetPending = PendingIntent.getBroadcast(context, 3002, resetIntent, getPendingIntentFlags())

            val rootIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_TIMER_PAGE", true)
            }
            val rootPending = PendingIntent.getActivity(context, 3003, rootIntent, getPendingIntentFlags())

            val btnStartPauseText = if (isRunning) "⏸ PAUSE" else if (isPaused && wasStartedFromStopwatch) "▶ RESUME" else "▶ START"
            val btnResetText = if (isRunning || (isPaused && wasStartedFromStopwatch) || seconds > 0) "◼ END" else "◼ RESET"

            for (widgetId in allWidgetIds) {
                val largeView = RemoteViews(context.packageName, R.layout.widget_stopwatch).apply {
                    setInt(android.R.id.background, "setBackgroundResource", bgRes)
                    if (isRunning) {
                        setChronometer(R.id.stopwatch_time_display, runningBaseTime, null, true)
                    } else {
                        val staticText = formatTime(seconds)
                        setChronometer(R.id.stopwatch_time_display, staticBaseTime, null, false)
                        setTextViewText(R.id.stopwatch_time_display, staticText)
                    }

                    setTextViewText(R.id.btn_stopwatch_start_pause, btnStartPauseText)
                    setOnClickPendingIntent(R.id.btn_stopwatch_start_pause, startPausePending)

                    setTextViewText(R.id.btn_stopwatch_reset, btnResetText)
                    setOnClickPendingIntent(R.id.btn_stopwatch_reset, resetPending)
                    if (isRunning || (isPaused && wasStartedFromStopwatch) || seconds > 0) {
                        setViewVisibility(R.id.btn_stopwatch_reset, View.VISIBLE)
                    } else {
                        setViewVisibility(R.id.btn_stopwatch_reset, View.GONE)
                    }

                    if (isRunning) {
                        setViewVisibility(R.id.btn_stopwatch_break, View.VISIBLE)
                        setOnClickPendingIntent(R.id.btn_stopwatch_break, breakPending)
                    } else {
                        setViewVisibility(R.id.btn_stopwatch_break, View.GONE)
                    }

                    setOnClickPendingIntent(R.id.stopwatch_title, rootPending)
                    setOnClickPendingIntent(R.id.stopwatch_time_display, rootPending)
                }

                if (isPartialUpdate) {
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, largeView)
                    continue
                }

                val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val smallView = RemoteViews(context.packageName, R.layout.widget_stopwatch_small).apply {
                        setInt(android.R.id.background, "setBackgroundResource", bgRes)
                        if (isRunning) {
                            setChronometer(R.id.stopwatch_time_display, runningBaseTime, null, true)
                        } else {
                            val staticText = formatTime(seconds)
                            setChronometer(R.id.stopwatch_time_display, staticBaseTime, null, false)
                            setTextViewText(R.id.stopwatch_time_display, staticText)
                        }

                        setTextViewText(R.id.btn_stopwatch_start_pause, btnStartPauseText)
                        setOnClickPendingIntent(R.id.btn_stopwatch_start_pause, startPausePending)
                        setOnClickPendingIntent(R.id.stopwatch_time_display, rootPending)
                    }
                    val viewMap = mapOf(
                        SizeF(140f, 70f) to smallView,
                        SizeF(200f, 100f) to largeView
                    )
                    RemoteViews(viewMap)
                } else {
                    largeView
                }

                appWidgetManager.updateAppWidget(widgetId, finalViews)
            }
        }
    }

    /**
     * Updates the Pomodoro Widget using countdown Chronometer
     */
    fun updatePomodoroWidget(context: Context, isPartialUpdate: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PomodoroWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isEmpty()) return@launch

            val bgRes = getBackgroundDrawableRes(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            FocusTimerManager.init(context)
            val isTimerRunning = FocusTimerManager.isTimerRunning.value
            val isPaused = FocusTimerManager.isPaused.value
            val wasStartedFromStopwatch = FocusTimerManager.wasStartedFromStopwatch.value
            val isFocus = FocusTimerManager.isFocusPhase.value

            val isPomodoroMode = isTimerRunning || (isPaused && !wasStartedFromStopwatch)
            val isRunning = isTimerRunning && !isPaused

            val totalDurationMs = if (isFocus) {
                FocusTimerManager.timerDurationMinutes.value * 60 * 1000L
            } else {
                val bMins = prefs.getInt("break_duration", 5)
                bMins * 60 * 1000L
            }

            val lastResumeMs = FocusTimerManager.lastResumeTimeMs.value
            val currentChunkMs = if (lastResumeMs != null && isRunning) maxOf(0L, StableTime.currentTimeMillis() - lastResumeMs) else 0L
            val baseAccumulatedMs = if (isPomodoroMode) FocusTimerManager.accumulatedSessionTimeMs.value else 0L
            val totalElapsedMs = if (isPomodoroMode) baseAccumulatedMs + currentChunkMs else 0L

            val remainingMs = maxOf(0L, totalDurationMs - totalElapsedMs)
            val displaySecs = (remainingMs / 1000).toInt()

            val runningBaseTime = SystemClock.elapsedRealtime() + remainingMs
            val staticBaseTime = SystemClock.elapsedRealtime() + (displaySecs * 1000L)

            val headerText = if (isFocus) "POMODORO FOCUS 🎯" else "REST BREAK ☕"
            val headerColor = if (isFocus) 0xFF30D158.toInt() else 0xFFFF9500.toInt()
            val btnStartPauseText = if (isRunning) "⏸ PAUSE" else if (isPaused && !wasStartedFromStopwatch) "▶ RESUME" else "▶ START"
            val btnBreakText = if (isFocus) "☕ BREAK" else "⏭ FOCUS"
            val btnResetText = if (isRunning || (isPaused && !wasStartedFromStopwatch)) "◼ END" else "◼ RESET"

            val startPauseIntent = Intent(context, PomodoroWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_POMO_START_PAUSE"
            }
            val startPausePending = PendingIntent.getBroadcast(context, 4001, startPauseIntent, getPendingIntentFlags())

            val breakIntent = Intent(context, PomodoroWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_POMO_BREAK"
            }
            val breakPending = PendingIntent.getBroadcast(context, 4004, breakIntent, getPendingIntentFlags())

            val resetIntent = Intent(context, PomodoroWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_POMO_RESET"
            }
            val resetPending = PendingIntent.getBroadcast(context, 4002, resetIntent, getPendingIntentFlags())

            val rootIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_TIMER_PAGE", true)
            }
            val rootPending = PendingIntent.getActivity(context, 4003, rootIntent, getPendingIntentFlags())

            for (widgetId in allWidgetIds) {
                val largeView = RemoteViews(context.packageName, R.layout.widget_pomodoro).apply {
                    setInt(android.R.id.background, "setBackgroundResource", bgRes)
                    setTextViewText(R.id.pomo_title, headerText)
                    setTextColor(R.id.pomo_title, headerColor)

                    if (isRunning) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setChronometerCountDown(R.id.pomo_time_display, true)
                        }
                        setChronometer(R.id.pomo_time_display, runningBaseTime, null, true)
                    } else {
                        val staticText = formatTime(displaySecs)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setChronometerCountDown(R.id.pomo_time_display, true)
                        }
                        setChronometer(R.id.pomo_time_display, staticBaseTime, null, false)
                        setTextViewText(R.id.pomo_time_display, staticText)
                    }

                    setTextViewText(R.id.btn_pomo_start_pause, btnStartPauseText)
                    setOnClickPendingIntent(R.id.btn_pomo_start_pause, startPausePending)

                    setTextViewText(R.id.btn_pomo_reset, btnResetText)
                    setOnClickPendingIntent(R.id.btn_pomo_reset, resetPending)

                    if (isRunning || !isFocus) {
                        setViewVisibility(R.id.btn_pomo_break, View.VISIBLE)
                        setTextViewText(R.id.btn_pomo_break, btnBreakText)
                        setOnClickPendingIntent(R.id.btn_pomo_break, breakPending)
                    } else {
                        setViewVisibility(R.id.btn_pomo_break, View.GONE)
                    }

                    setOnClickPendingIntent(R.id.pomo_title, rootPending)
                    setOnClickPendingIntent(R.id.pomo_time_display, rootPending)
                }

                if (isPartialUpdate) {
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, largeView)
                    continue
                }

                val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val smallView = RemoteViews(context.packageName, R.layout.widget_pomodoro_small).apply {
                        setInt(android.R.id.background, "setBackgroundResource", bgRes)
                        setTextViewText(R.id.pomo_title, headerText)
                        setTextColor(R.id.pomo_title, headerColor)

                        if (isRunning) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                setChronometerCountDown(R.id.pomo_time_display, true)
                            }
                            setChronometer(R.id.pomo_time_display, runningBaseTime, null, true)
                        } else {
                            val staticText = formatTime(displaySecs)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                setChronometerCountDown(R.id.pomo_time_display, true)
                            }
                            setChronometer(R.id.pomo_time_display, staticBaseTime, null, false)
                            setTextViewText(R.id.pomo_time_display, staticText)
                        }

                        setTextViewText(R.id.btn_pomo_start_pause, btnStartPauseText)
                        setOnClickPendingIntent(R.id.btn_pomo_start_pause, startPausePending)
                        setOnClickPendingIntent(R.id.pomo_time_display, rootPending)
                    }
                    val viewMap = mapOf(
                        SizeF(140f, 70f) to smallView,
                        SizeF(200f, 100f) to largeView
                    )
                    RemoteViews(viewMap)
                } else {
                    largeView
                }

                appWidgetManager.updateAppWidget(widgetId, finalViews)
            }
        }
    }

    /**
     * Updates the Total Focus Time Widget
     */
    fun updateTotalFocusTimeWidget(context: Context, isPartialUpdate: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TotalFocusTimeWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isEmpty()) return@launch

            val bgRes = getBackgroundDrawableRes(context)

            val totalSeconds = fetchTodayTotalFocusSeconds(context)
            val formattedTime = formatWidgetFocusTime(totalSeconds)

            val timelineBlocks = fetchTodayTimelineBlocks(context)
            val timelineBitmap = generateTimelineBitmap(context, timelineBlocks)

            val timerIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_TIMER_PAGE", true)
            }
            val timerPending = PendingIntent.getActivity(context, 4001, timerIntent, getPendingIntentFlags())

            for (widgetId in allWidgetIds) {
                val remoteViews = RemoteViews(context.packageName, R.layout.widget_total_focus_time).apply {
                    setInt(android.R.id.background, "setBackgroundResource", bgRes)
                    setTextViewText(R.id.focus_title, "Today")
                    setTextViewText(R.id.focus_time_display, formattedTime)
                    setImageViewBitmap(R.id.focus_timeline_canvas, timelineBitmap)

                    setOnClickPendingIntent(android.R.id.background, timerPending)
                }
                appWidgetManager.updateAppWidget(widgetId, remoteViews)
            }
        }
    }

    /**
     * Updates the Journal Photo Shower Widget
     */
    fun updatePhotoShowerWidget(context: Context, forceNext: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PhotoShowerWidgetProvider::class.java)
            val allWidgetIds = try {
                appWidgetManager.getAppWidgetIds(thisWidget)
            } catch (_: Exception) {
                intArrayOf()
            }
            if (allWidgetIds.isEmpty()) return@launch

            val bgRes = getBackgroundDrawableRes(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            val photoItemsResult = runCatching {
                fetchJournalPhotoItems(context)
            }

            if (photoItemsResult.isFailure) {
                val ex = photoItemsResult.exceptionOrNull()
                val errorReason = ex?.localizedMessage ?: ex?.message ?: ex?.javaClass?.simpleName ?: "Failed to load journal photos"
                Log.e(TAG, "Error fetching journal photos: $errorReason", ex)
                renderPhotoShowerError(context, appWidgetManager, allWidgetIds, bgRes, errorReason)
                return@launch
            }

            val photoItems = photoItemsResult.getOrDefault(emptyList())

            val nextIntent = Intent(context, PhotoShowerWidgetProvider::class.java).apply {
                action = "com.example.widget.ACTION_PHOTO_SHOWER_NEXT"
            }
            val nextPending = PendingIntent.getBroadcast(context, 5001, nextIntent, getPendingIntentFlags())

            for (widgetId in allWidgetIds) {
                try {
                    val remoteViews = RemoteViews(context.packageName, R.layout.widget_photo_shower).apply {
                        setInt(R.id.photo_shower_root, "setBackgroundResource", bgRes)
                        setViewVisibility(R.id.photo_shower_error_layout, View.GONE)

                        if (photoItems.isEmpty()) {
                            // Explicit "No photos in journal" state
                            setViewVisibility(R.id.photo_shower_image, View.GONE)
                            setViewVisibility(R.id.photo_bottom_shadow, View.GONE)
                            setViewVisibility(R.id.photo_date_container, View.GONE)
                            setViewVisibility(R.id.photo_shower_caption_layout, View.GONE)
                            setViewVisibility(R.id.btn_next_photo, View.GONE)
                            setViewVisibility(R.id.photo_shower_empty_layout, View.VISIBLE)

                            setTextViewText(R.id.photo_shower_empty_title, "No photos in journal")
                            setTextViewText(R.id.photo_shower_empty_desc, "Add photos to your Journal entries to shower them here! Tap to open journal.")

                            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("SHOW_JOURNAL_PAGE", true)
                            }
                            val openAppPending = PendingIntent.getActivity(context, 5002, openAppIntent, getPendingIntentFlags())
                            setOnClickPendingIntent(R.id.photo_shower_root, openAppPending)
                            setOnClickPendingIntent(R.id.photo_shower_empty_layout, openAppPending)
                        } else {
                            setViewVisibility(R.id.photo_shower_empty_layout, View.GONE)
                            setViewVisibility(R.id.photo_shower_image, View.VISIBLE)
                            setViewVisibility(R.id.photo_bottom_shadow, View.VISIBLE)
                            setViewVisibility(R.id.photo_date_container, View.VISIBLE)
                            setViewVisibility(R.id.photo_shower_caption_layout, View.VISIBLE)
                            setViewVisibility(R.id.btn_next_photo, View.VISIBLE)
                            setOnClickPendingIntent(R.id.btn_next_photo, nextPending)

                            var currentIndex = prefs.getInt("journal_photo_widget_index_$widgetId", 0)
                            if (forceNext) {
                                currentIndex = (currentIndex + 1) % photoItems.size
                            } else {
                                currentIndex = currentIndex % photoItems.size
                            }
                            prefs.edit().putInt("journal_photo_widget_index_$widgetId", currentIndex).apply()

                            val currentPhoto = photoItems[currentIndex]

                            setTextViewText(R.id.photo_shower_date, currentPhoto.dateFormatted)
                            setTextViewText(R.id.photo_shower_title, currentPhoto.title)
                            setTextViewText(R.id.photo_shower_text, currentPhoto.text)

                            val widgetCopyFile = WidgetPhotoManager.ensureWidgetCopy(context, currentPhoto.photoUrl, 360, 240)
                            val bitmap = if (widgetCopyFile != null && widgetCopyFile.exists()) {
                                BitmapFactory.decodeFile(widgetCopyFile.absolutePath)
                            } else {
                                decodeJournalPhotoBitmap(context, currentPhoto.photoUrl, 360, 240)
                            }

                            if (bitmap != null) {
                                setImageViewBitmap(R.id.photo_shower_image, bitmap)
                            } else {
                                setImageViewResource(R.id.photo_shower_image, R.drawable.widget_background)
                            }

                            val openJournalIntent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("SHOW_JOURNAL_PAGE", true)
                                putExtra("JOURNAL_ENTRY_ID", currentPhoto.entryId)
                            }
                            val openJournalPending = PendingIntent.getActivity(context, 5000 + widgetId, openJournalIntent, getPendingIntentFlags())
                            setOnClickPendingIntent(R.id.photo_shower_image, openJournalPending)
                            setOnClickPendingIntent(R.id.photo_shower_caption_layout, openJournalPending)
                            setOnClickPendingIntent(R.id.photo_shower_root, openJournalPending)
                        }
                    }
                    appWidgetManager.updateAppWidget(widgetId, remoteViews)
                } catch (singleErr: Exception) {
                    val reason = singleErr.localizedMessage ?: singleErr.message ?: singleErr.javaClass.simpleName
                    Log.e(TAG, "Error updating individual photo shower widget $widgetId: $reason", singleErr)
                    renderPhotoShowerError(context, appWidgetManager, intArrayOf(widgetId), bgRes, reason)
                }
            }
        }
    }

    /**
     * Renders an explicit Error State with reason in the Photo Shower widget instead of letting it fail silently
     */
    private fun renderPhotoShowerError(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray,
        bgRes: Int,
        errorMessage: String
    ) {
        if (widgetIds.isEmpty()) return
        val refreshIntent = Intent(context, PhotoShowerWidgetProvider::class.java).apply {
            action = "com.example.widget.ACTION_PHOTO_SHOWER_REFRESH"
        }
        val refreshPending = PendingIntent.getBroadcast(context, 5003, refreshIntent, getPendingIntentFlags())

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_JOURNAL_PAGE", true)
        }
        val openAppPending = PendingIntent.getActivity(context, 5004, openAppIntent, getPendingIntentFlags())

        for (widgetId in widgetIds) {
            try {
                val remoteViews = RemoteViews(context.packageName, R.layout.widget_photo_shower).apply {
                    setInt(R.id.photo_shower_root, "setBackgroundResource", bgRes)
                    setViewVisibility(R.id.photo_shower_image, View.GONE)
                    setViewVisibility(R.id.photo_bottom_shadow, View.GONE)
                    setViewVisibility(R.id.photo_date_container, View.GONE)
                    setViewVisibility(R.id.photo_shower_caption_layout, View.GONE)
                    setViewVisibility(R.id.photo_shower_empty_layout, View.GONE)
                    setViewVisibility(R.id.btn_next_photo, View.GONE)

                    setViewVisibility(R.id.photo_shower_error_layout, View.VISIBLE)
                    setTextViewText(R.id.photo_shower_error_title, "Photo Widget Error")
                    setTextViewText(R.id.photo_shower_error_reason, "Reason: $errorMessage")

                    setOnClickPendingIntent(R.id.btn_photo_error_retry, refreshPending)
                    setOnClickPendingIntent(R.id.photo_shower_error_layout, openAppPending)
                    setOnClickPendingIntent(R.id.photo_shower_root, openAppPending)
                }
                appWidgetManager.updateAppWidget(widgetId, remoteViews)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to render error layout for widget $widgetId: ${ex.message}", ex)
            }
        }
    }

    // --- GRAPHICS & BITMAP RENDER HELPERS ---

    private fun formatTime(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun formatWidgetFocusTime(totalSeconds: Int): String {
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        return when {
            hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
            hrs > 0 -> "${hrs}h"
            mins > 0 -> "${mins}m"
            else -> "0m"
        }
    }

    fun formatJournalDateToDdMmYy(dateStr: String, timestamp: Long): String {
        val sdfOutput = SimpleDateFormat("dd/MM/yy", Locale.US)
        if (dateStr.isNotBlank()) {
            try {
                val inputFormats = listOf(
                    SimpleDateFormat("yyyy-MM-dd", Locale.US),
                    SimpleDateFormat("yyyy/MM/dd", Locale.US),
                    SimpleDateFormat("dd/MM/yyyy", Locale.US),
                    SimpleDateFormat("dd-MM-yyyy", Locale.US)
                )
                for (fmt in inputFormats) {
                    try {
                        val parsed = fmt.parse(dateStr)
                        if (parsed != null) return sdfOutput.format(parsed)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return if (timestamp > 0L) sdfOutput.format(Date(timestamp)) else sdfOutput.format(Date())
    }

    private fun extractPhotosFromJournalEntries(entries: List<JournalEntry>): List<JournalPhotoItem> {
        val result = mutableListOf<JournalPhotoItem>()
        val sortedEntries = entries.sortedByDescending { if (it.timestamp > 0) it.timestamp else System.currentTimeMillis() }
        for (entry in sortedEntries) {
            val attachments = if (entry.attachmentsJson.isNotEmpty()) entry.attachmentsJson.split(";;") else emptyList()
            val formattedDate = formatJournalDateToDdMmYy(entry.dateString, entry.timestamp)
            val titleText = entry.title.ifBlank { "Journal Entry" }
            val bodyText = entry.text.ifBlank { "Journal note" }

            for (attach in attachments) {
                val trimmed = attach.trim()
                if (trimmed.isEmpty()) continue
                val lower = trimmed.lowercase()

                if (lower.startsWith("author:") || lower.startsWith("folderlink:") || lower.startsWith("loc:") || lower.startsWith("audio:") || lower.startsWith("video:")) {
                    continue
                }

                val isPhoto = lower.startsWith("photo:") ||
                        lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                        lower.startsWith("file://") || lower.startsWith("content://") || lower.startsWith("data:image/") ||
                        lower.startsWith("http://") || lower.startsWith("https://") ||
                        (lower.startsWith("/") && (lower.contains("image") || lower.contains("photo") || lower.contains("dcim") || lower.contains("pictures") || lower.contains("download") || lower.contains("cache")))

                if (isPhoto) {
                    val cleanUrl = if (trimmed.startsWith("photo:", ignoreCase = true)) trimmed.substring(6).trim() else trimmed
                    if (cleanUrl.isNotEmpty()) {
                        result.add(JournalPhotoItem(cleanUrl, titleText, bodyText, formattedDate, entry.id))
                    }
                }
            }
        }
        return result
    }

    private fun createAvatarLogoBitmap(
        context: Context,
        logoTextOrEmoji: String,
        displayName: String,
        isIdle: Boolean = false,
        sizeDp: Int = 44
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).toInt().coerceAtLeast(32)

        if (!isIdle) {
            val decodedBmp = decodeAvatarBitmap(context, logoTextOrEmoji, px)
            if (decodedBmp != null) {
                return decodedBmp
            }
        }

        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = px / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isIdle) {
                Color.argb(50, 255, 255, 255)
            } else {
                val colors = intArrayOf(
                    Color.rgb(16, 185, 129),
                    Color.rgb(59, 130, 246),
                    Color.rgb(139, 92, 246),
                    Color.rgb(236, 72, 153),
                    Color.rgb(245, 158, 11)
                )
                val colorIndex = Math.abs(displayName.hashCode()) % colors.size
                colors[colorIndex]
            }
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = if (isIdle) Color.argb(80, 255, 255, 255) else Color.rgb(16, 185, 129)
        }

        canvas.drawCircle(radius, radius, radius - (1f * density), bgPaint)
        canvas.drawCircle(radius, radius, radius - (1f * density), borderPaint)

        val textToDraw = when {
            isIdle -> "💤"
            logoTextOrEmoji.isNotEmpty() && logoTextOrEmoji != "👤" && logoTextOrEmoji.length <= 8 -> logoTextOrEmoji
            displayName.isNotBlank() -> {
                val parts = displayName.trim().split(" ")
                if (parts.size >= 2) {
                    "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
                } else {
                    displayName.take(2).uppercase()
                }
            }
            else -> "👤"
        }

        val isEmoji = textToDraw.any { 
            Character.getType(it) == Character.SURROGATE.toInt() || 
            Character.getType(it) == Character.OTHER_SYMBOL.toInt() 
        } || textToDraw == "💤" || textToDraw == "🎯" || textToDraw == "👤"

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = if (isEmoji) radius * 1.0f else radius * 0.75f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        val fontMetrics = textPaint.fontMetrics
        val baseline = radius - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(textToDraw, radius, baseline, textPaint)

        return bitmap
    }

    private fun decodeAvatarBitmap(context: Context, avatarStr: String, targetPx: Int): Bitmap? {
        val trimmed = avatarStr.trim()
        if (trimmed.isEmpty() || trimmed == "👤" || trimmed == "🎯" || trimmed == "💤") return null

        try {
            if (trimmed.startsWith("base64:") || trimmed.startsWith("data:image/") || (trimmed.length > 60 && !trimmed.contains(" ") && !trimmed.startsWith("http"))) {
                val rawData = when {
                    trimmed.startsWith("base64:") -> trimmed.substringAfter("base64:")
                    trimmed.contains("base64,") -> trimmed.substringAfter("base64,")
                    else -> trimmed
                }
                val bytes = Base64.decode(rawData, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return getCircularBitmap(bmp, targetPx)
            }

            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val cacheFile = File(context.cacheDir, "widget_avatar_${Math.abs(trimmed.hashCode())}.png")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (bmp != null) return getCircularBitmap(bmp, targetPx)
                }
                val url = URL(trimmed)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doInput = true
                conn.connect()
                val inputStream = conn.inputStream
                val bytes = inputStream.readBytes()
                inputStream.close()
                conn.disconnect()
                cacheFile.writeBytes(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return getCircularBitmap(bmp, targetPx)
            }

            if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
                val path = trimmed.removePrefix("file://")
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) return getCircularBitmap(bmp, targetPx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode avatar bitmap: ${e.message}")
        }
        return null
    }

    private fun getCircularBitmap(src: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, sizePx, sizePx)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val minDim = Math.min(src.width, src.height)
        val srcRect = Rect(
            (src.width - minDim) / 2,
            (src.height - minDim) / 2,
            (src.width + minDim) / 2,
            (src.height + minDim) / 2
        )
        canvas.drawBitmap(src, srcRect, rect, paint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(16, 185, 129)
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, (sizePx / 2f) - 1.5f, borderPaint)

        return output
    }

    private fun generateTimelineBitmap(context: Context, blocks: List<TimelineBlock>): Bitmap {
        val width = 720
        val height = 150
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDayMs = cal.timeInMillis
        val dayDurationMs = 24 * 3600 * 1000f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.textSize = 26f
        paint.textAlign = Paint.Align.CENTER

        val sunX = 20f + 0.5f * (width - 40f)
        canvas.drawText("☀️", sunX, 28f, paint)

        val moonX = 20f + (20f / 24f) * (width - 40f)
        canvas.drawText("🌙", moonX, 28f, paint)

        val trackLeft = 20f
        val trackRight = width - 20f
        val trackTop = 42f
        val trackBottom = 82f
        val trackWidth = trackRight - trackLeft

        val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2C2C2E")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(trackLeft, trackTop, trackRight, trackBottom),
            10f, 10f, trackBgPaint
        )

        val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        blocks.forEach { block ->
            val startFrac = ((block.startMs - startOfDayMs).toFloat() / dayDurationMs).coerceIn(0f, 1f)
            val endFrac = ((block.endMs - startOfDayMs).toFloat() / dayDurationMs).coerceIn(0f, 1f)

            var bLeft = trackLeft + startFrac * trackWidth
            var bRight = trackLeft + endFrac * trackWidth

            if (bRight - bLeft < 5f) {
                bRight = bLeft + 5f
            }

            blockPaint.color = block.color
            canvas.drawRoundRect(
                RectF(bLeft, trackTop, bRight, trackBottom),
                6f, 6f, blockPaint
            )
        }

        val timeLabels = arrayOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "24:00")
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A1A1AA")
            textSize = 19f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#48484A")
            strokeWidth = 2f
        }

        timeLabels.forEachIndexed { i, label ->
            val frac = i / 6f
            val tickX = trackLeft + frac * trackWidth

            textPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                6 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }

            canvas.drawLine(tickX, trackBottom + 2f, tickX, trackBottom + 10f, tickPaint)
            canvas.drawText(label, tickX, trackBottom + 34f, textPaint)
        }

        return bitmap
    }

    private fun getRotationFromExif(inputStream: InputStream): Int {
        return try {
            val exif = ExifInterface(inputStream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun renderScaledRoundedBitmap(src: Bitmap, targetW: Int, targetH: Int, cornerRadiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val dx = (targetW - src.width * scale) / 2f
        val dy = (targetH - src.height * scale) / 2f

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)

        val rectF = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, matrix, paint)

        return output
    }

    private fun decodeJournalPhotoBitmap(context: Context, photoPath: String, targetWidthPx: Int = 360, targetHeightPx: Int = 240): Bitmap? {
        val trimmed = photoPath.trim()
        if (trimmed.isEmpty()) return null

        try {
            var rawBitmap: Bitmap? = null
            var exifRotation = 0

            if (trimmed.startsWith("content://")) {
                val uri = Uri.parse(trimmed)
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        exifRotation = getRotationFromExif(stream)
                    }
                } catch (_: Exception) {}

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } else if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
                val path = trimmed.removePrefix("file://")
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    try {
                        file.inputStream().use { stream ->
                            exifRotation = getRotationFromExif(stream)
                        }
                    } catch (_: Exception) {}

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                }
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val cacheFile = File(context.cacheDir, "journal_photo_widget_${Math.abs(trimmed.hashCode())}.png")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                } else {
                    val url = URL(trimmed)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.doInput = true
                    conn.connect()
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    cacheFile.writeBytes(bytes)

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } else if (trimmed.startsWith("base64:") || trimmed.startsWith("data:image/") || (trimmed.length > 80 && !trimmed.contains(" "))) {
                val rawData = when {
                    trimmed.startsWith("base64:") -> trimmed.substringAfter("base64:")
                    trimmed.contains("base64,") -> trimmed.substringAfter("base64,")
                    else -> trimmed
                }
                val bytes = Base64.decode(rawData, Base64.DEFAULT)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                options.inJustDecodeBounds = false
                rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } else {
                val uri = Uri.parse(trimmed)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            }

            if (rawBitmap != null) {
                val orientedBmp = rotateBitmapIfNeeded(rawBitmap, exifRotation)
                return renderScaledRoundedBitmap(orientedBmp, targetWidthPx, targetHeightPx, 24f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode journal photo bitmap for path $photoPath: ${e.message}")
        }
        return null
    }

    /**
     * Updates the Web Apps & Shortcuts Widget.
     */
    fun updateQuickShortcutsWidget(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, QuickShortcutsWidgetProvider::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (allWidgetIds.isEmpty()) return@launch

                val bgRes = getBackgroundDrawableRes(context)

                // Instagram intent
                val instagramIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_INSTAGRAM_WEB_APP", true)
                    putExtra("NAVIGATE_TO", "INSTAGRAM_WEB_APP")
                }
                val instagramPendingIntent = PendingIntent.getActivity(context, 8001, instagramIntent, getPendingIntentFlags())

                // YouTube intent
                val youtubeIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_YOUTUBE_WEB_APP", true)
                    putExtra("NAVIGATE_TO", "YOUTUBE_WEB_APP")
                }
                val youtubePendingIntent = PendingIntent.getActivity(context, 8002, youtubeIntent, getPendingIntentFlags())

                // Spotify intent
                val spotifyIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_SPOTIFY_WEB_APP", true)
                    putExtra("NAVIGATE_TO", "SPOTIFY_WEB_APP")
                }
                val spotifyPendingIntent = PendingIntent.getActivity(context, 8003, spotifyIntent, getPendingIntentFlags())

                // Keep Notes intent
                val notesIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("NAVIGATE_TO", "KEEP_NOTES")
                }
                val notesPendingIntent = PendingIntent.getActivity(context, 8004, notesIntent, getPendingIntentFlags())

                // Tasks intent
                val tasksIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("SHOW_TASKS_PAGE", true)
                    putExtra("NAVIGATE_TO", "TASKS")
                }
                val tasksPendingIntent = PendingIntent.getActivity(context, 8005, tasksIntent, getPendingIntentFlags())

                // Timer intent
                val timerIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("SHOW_TIMER_PAGE", true)
                    putExtra("NAVIGATE_TO", "TIMER")
                }
                val timerPendingIntent = PendingIntent.getActivity(context, 8006, timerIntent, getPendingIntentFlags())

                val instagramBmp = com.example.util.ShortcutUtils.createInstagramLogoBitmap()
                val youtubeBmp = com.example.util.ShortcutUtils.createYouTubeLogoBitmap()
                val spotifyBmp = com.example.util.ShortcutUtils.createSpotifyLogoBitmap()
                val notesBmp = com.example.util.ShortcutUtils.createKeepNotesLogoBitmap()
                val tasksBmp = com.example.util.ShortcutUtils.createTasksLogoBitmap()
                val timerBmp = com.example.util.ShortcutUtils.createTimerLogoBitmap()

                for (widgetId in allWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_quick_shortcuts).apply {
                        setInt(android.R.id.background, "setBackgroundResource", bgRes)

                        setImageViewBitmap(R.id.img_shortcut_instagram, instagramBmp)
                        setImageViewBitmap(R.id.img_shortcut_youtube, youtubeBmp)
                        setImageViewBitmap(R.id.img_shortcut_spotify, spotifyBmp)
                        setImageViewBitmap(R.id.img_shortcut_notes, notesBmp)
                        setImageViewBitmap(R.id.img_shortcut_tasks, tasksBmp)
                        setImageViewBitmap(R.id.img_shortcut_timer, timerBmp)

                        setOnClickPendingIntent(R.id.btn_shortcut_instagram, instagramPendingIntent)
                        setOnClickPendingIntent(R.id.btn_shortcut_youtube, youtubePendingIntent)
                        setOnClickPendingIntent(R.id.btn_shortcut_spotify, spotifyPendingIntent)
                        setOnClickPendingIntent(R.id.btn_shortcut_notes, notesPendingIntent)
                        setOnClickPendingIntent(R.id.btn_shortcut_tasks, tasksPendingIntent)
                        setOnClickPendingIntent(R.id.btn_shortcut_timer, timerPendingIntent)
                    }
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update quick shortcuts widget", e)
            }
        }
    }
}
