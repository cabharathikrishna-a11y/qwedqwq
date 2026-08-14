package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.receiver.MediaControlReceiver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ActivePlayerType {
    IN_BUILT,
    YOUTUBE
}

sealed class MediaActionCommand {
    object PlayPause : MediaActionCommand()
    object Rewind : MediaActionCommand()
    object FastForward : MediaActionCommand()
    data class ChangeSpeed(val speed: Float) : MediaActionCommand()
    object NextOrSkipAd : MediaActionCommand()
    object Stop : MediaActionCommand()
}

object UnifiedMediaNotificationManager {

    const val CHANNEL_ID = "unified_media_controls_channel"
    const val NOTIFICATION_ID = 8881

    private val _actionCommands = MutableSharedFlow<MediaActionCommand>(extraBufferCapacity = 64)
    val actionCommands: SharedFlow<MediaActionCommand> = _actionCommands.asSharedFlow()

    var activePlayerType: ActivePlayerType = ActivePlayerType.YOUTUBE
        private set

    var currentTitle: String = "Life OS Media Player"
        private set

    var currentSubtitle: String = "YouTube & In-Built Player"
        private set

    var isPlaying: Boolean = false
        private set

    var currentSpeed: Float = 1.0f
        private set

    private val availableSpeeds = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)

    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media & YouTube Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Advanced notification controls for YouTube & In-Built Music/Video playback"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateState(
        context: Context,
        title: String,
        subtitle: String,
        playing: Boolean,
        speed: Float,
        playerType: ActivePlayerType
    ) {
        currentTitle = title
        currentSubtitle = subtitle
        isPlaying = playing
        currentSpeed = speed
        activePlayerType = playerType

        showNotification(context)
    }

    fun cycleSpeed(context: Context): Float {
        val currentIndex = availableSpeeds.indexOfFirst { Math.abs(it - currentSpeed) < 0.1f }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % availableSpeeds.size
        currentSpeed = availableSpeeds[nextIndex]
        showNotification(context)
        _actionCommands.tryEmit(MediaActionCommand.ChangeSpeed(currentSpeed))
        return currentSpeed
    }

    fun emitCommand(command: MediaActionCommand) {
        _actionCommands.tryEmit(command)
    }

    fun showNotification(context: Context) {
        initChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent to open Main Activity
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO", if (activePlayerType == ActivePlayerType.YOUTUBE) "YOUTUBE_PLAYER" else "MUSIC_PLAYER")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntents for actions
        val playPausePending = createActionPendingIntent(context, MediaControlReceiver.ACTION_PLAY_PAUSE, 1)
        val rewindPending = createActionPendingIntent(context, MediaControlReceiver.ACTION_REWIND, 2)
        val fastForwardPending = createActionPendingIntent(context, MediaControlReceiver.ACTION_FAST_FORWARD, 3)
        val speedPending = createActionPendingIntent(context, MediaControlReceiver.ACTION_CHANGE_SPEED, 4)
        val nextPending = createActionPendingIntent(context, MediaControlReceiver.ACTION_NEXT, 5)
        val stopPending = createActionPendingIntent(context, MediaControlReceiver.ACTION_STOP, 6)

        val playerSourceBadge = if (activePlayerType == ActivePlayerType.YOUTUBE) "YouTube Player" else "In-Built Player"
        val speedText = if (currentSpeed % 1f == 0f) "${currentSpeed.toInt()}x" else "${currentSpeed}x"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText("$playerSourceBadge • $speedText ⚡ • ${if (isPlaying) "Playing" else "Paused"}")
            .setSubText("$playerSourceBadge ($speedText)")
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // Action 0: Rewind -10s
            .addAction(
                android.R.drawable.ic_media_rew,
                "-10s",
                rewindPending
            )
            // Action 1: Play/Pause
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPausePending
            )
            // Action 2: Fast Forward +10s
            .addAction(
                android.R.drawable.ic_media_ff,
                "+10s",
                fastForwardPending
            )
            // Action 3: Speed Cycle (1x..4x)
            .addAction(
                android.R.drawable.ic_menu_compass,
                "$speedText ⚡",
                speedPending
            )
            // Action 4: Skip/Next
            .addAction(
                android.R.drawable.ic_media_next,
                if (activePlayerType == ActivePlayerType.YOUTUBE) "Skip Ad/Next" else "Next Track",
                nextPending
            )
            // Action 5: Close/Stop
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        isPlaying = false
    }

    private fun createActionPendingIntent(context: Context, actionStr: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MediaControlReceiver::class.java).apply {
            action = actionStr
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
