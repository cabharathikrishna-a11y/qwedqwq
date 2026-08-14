package com.example.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized System Time Service that fetches and maintains system time from the device / StableTime
 * in a SINGLE master ticker loop. Broadcasts reactive StateFlows to widgets, OSD floating overlays,
 * timer components, full-screen clocks, and UI views across the entire application.
 *
 * Eliminates duplicate infinite coroutine tick loops, memory allocations, and CPU lag from
 * multiple components querying System.currentTimeMillis() and instantiating SimpleDateFormat concurrently.
 */
object SystemTimeService {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Central reactive state flows
    private val _currentTimeMs = MutableStateFlow(StableTime.currentTimeMillis())
    val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()

    private val _currentTimeSec = MutableStateFlow(StableTime.currentTimeMillis() / 1000)
    val currentTimeSec: StateFlow<Long> = _currentTimeSec.asStateFlow()

    private val _formattedTime12h = MutableStateFlow("") // e.g., "9:42 AM"
    val formattedTime12h: StateFlow<String> = _formattedTime12h.asStateFlow()

    private val _formattedTime24h = MutableStateFlow("") // e.g., "09:42"
    val formattedTime24h: StateFlow<String> = _formattedTime24h.asStateFlow()

    private val _formattedTimeWithSeconds = MutableStateFlow("") // e.g., "09:42:15 AM"
    val formattedTimeWithSeconds: StateFlow<String> = _formattedTimeWithSeconds.asStateFlow()

    private val _todayDateString = MutableStateFlow("") // e.g., "2026-08-09"
    val todayDateString: StateFlow<String> = _todayDateString.asStateFlow()

    // Reusable cached date formatters with thread safety
    private val sdf12h = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val sdf24h = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val sdfWithSec = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
    private val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Volatile
    private var isStarted = false

    /**
     * Start or initialize the central time ticking service.
     */
    fun startService(context: Context? = null) {
        if (isStarted) return
        synchronized(this) {
            if (isStarted) return
            isStarted = true
        }

        if (context != null) {
            try {
                TimeEngine.initializeNtp(context.applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("SystemTimeService", "Error initializing NTP: ${e.message}")
            }
        }

        // Initialize initial cached values immediately
        val initialMs = StableTime.currentTimeMillis()
        val initialDate = Date(initialMs)
        _currentTimeMs.value = initialMs
        _currentTimeSec.value = initialMs / 1000
        _formattedTime12h.value = synchronized(sdf12h) { sdf12h.format(initialDate) }
        _formattedTime24h.value = synchronized(sdf24h) { sdf24h.format(initialDate) }
        _formattedTimeWithSeconds.value = synchronized(sdfWithSec) { sdfWithSec.format(initialDate) }
        _todayDateString.value = synchronized(sdfDate) { sdfDate.format(initialDate) }

        // Master ticker loop
        serviceScope.launch {
            var lastSecond = -1L
            var lastMinute = -1
            var lastDay = ""

            while (isActive) {
                val nowMs = StableTime.currentTimeMillis()
                val nowSec = nowMs / 1000
                _currentTimeMs.value = nowMs
                _currentTimeSec.value = nowSec

                if (nowSec != lastSecond) {
                    lastSecond = nowSec
                    val date = Date(nowMs)

                    _formattedTimeWithSeconds.value = synchronized(sdfWithSec) { sdfWithSec.format(date) }

                    val currentMinute = (nowMs / 60000).toInt()
                    if (currentMinute != lastMinute) {
                        lastMinute = currentMinute
                        _formattedTime12h.value = synchronized(sdf12h) { sdf12h.format(date) }
                        _formattedTime24h.value = synchronized(sdf24h) { sdf24h.format(date) }
                    }

                    val currentDateStr = synchronized(sdfDate) { sdfDate.format(date) }
                    if (currentDateStr != lastDay) {
                        lastDay = currentDateStr
                        _todayDateString.value = currentDateStr
                    }
                }

                delay(250L) // Efficient 250ms master pulse
            }
        }
        android.util.Log.i("SystemTimeService", "⚡ Central SystemTimeService started successfully!")
    }

    /**
     * Get current system time millis from central provider.
     */
    fun getCurrentMs(): Long {
        val ms = _currentTimeMs.value
        return if (ms > 0) ms else StableTime.currentTimeMillis()
    }

    /**
     * Get current system time seconds from central provider.
     */
    fun getCurrentSec(): Long {
        return getCurrentMs() / 1000
    }

    /**
     * Get cached 12-hour clock time string (e.g. "9:42 AM")
     */
    fun getFormatted12h(): String {
        val cached = _formattedTime12h.value
        if (cached.isNotEmpty()) return cached
        return synchronized(sdf12h) { sdf12h.format(Date(getCurrentMs())) }
    }

    /**
     * Get cached 24-hour clock time string (e.g. "09:42")
     */
    fun getFormatted24h(): String {
        val cached = _formattedTime24h.value
        if (cached.isNotEmpty()) return cached
        return synchronized(sdf24h) { sdf24h.format(Date(getCurrentMs())) }
    }

    /**
     * Get today's date string (yyyy-MM-dd)
     */
    fun getTodayString(): String {
        val cached = _todayDateString.value
        if (cached.isNotEmpty()) return cached
        return synchronized(sdfDate) { sdfDate.format(Date(getCurrentMs())) }
    }

    // Compose helper extensions
    @Composable
    fun rememberTime12h(): String {
        val time by formattedTime12h.collectAsStateWithLifecycle()
        return time.ifEmpty { getFormatted12h() }
    }

    @Composable
    fun rememberTime24h(): String {
        val time by formattedTime24h.collectAsStateWithLifecycle()
        return time.ifEmpty { getFormatted24h() }
    }

    @Composable
    fun rememberTimeWithSeconds(): String {
        val time by formattedTimeWithSeconds.collectAsStateWithLifecycle()
        return time
    }

    @Composable
    fun rememberTimeMs(): Long {
        val ms by currentTimeMs.collectAsStateWithLifecycle()
        return ms
    }
}
