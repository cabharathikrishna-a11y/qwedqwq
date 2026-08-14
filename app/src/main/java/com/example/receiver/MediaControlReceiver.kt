package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.util.MediaActionCommand
import com.example.util.UnifiedMediaNotificationManager

class MediaControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.action.MEDIA_PLAY_PAUSE"
        const val ACTION_REWIND = "com.example.action.MEDIA_REWIND"
        const val ACTION_FAST_FORWARD = "com.example.action.MEDIA_FAST_FORWARD"
        const val ACTION_CHANGE_SPEED = "com.example.action.MEDIA_CHANGE_SPEED"
        const val ACTION_NEXT = "com.example.action.MEDIA_NEXT"
        const val ACTION_STOP = "com.example.action.MEDIA_STOP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            ACTION_PLAY_PAUSE -> {
                val newIsPlaying = !UnifiedMediaNotificationManager.isPlaying
                UnifiedMediaNotificationManager.updateState(
                    context = context,
                    title = UnifiedMediaNotificationManager.currentTitle,
                    subtitle = UnifiedMediaNotificationManager.currentSubtitle,
                    playing = newIsPlaying,
                    speed = UnifiedMediaNotificationManager.currentSpeed,
                    playerType = UnifiedMediaNotificationManager.activePlayerType
                )
                UnifiedMediaNotificationManager.emitCommand(MediaActionCommand.PlayPause)
            }
            ACTION_REWIND -> {
                UnifiedMediaNotificationManager.emitCommand(MediaActionCommand.Rewind)
            }
            ACTION_FAST_FORWARD -> {
                UnifiedMediaNotificationManager.emitCommand(MediaActionCommand.FastForward)
            }
            ACTION_CHANGE_SPEED -> {
                UnifiedMediaNotificationManager.cycleSpeed(context)
            }
            ACTION_NEXT -> {
                UnifiedMediaNotificationManager.emitCommand(MediaActionCommand.NextOrSkipAd)
            }
            ACTION_STOP -> {
                UnifiedMediaNotificationManager.dismissNotification(context)
                UnifiedMediaNotificationManager.emitCommand(MediaActionCommand.Stop)
            }
        }
    }
}
