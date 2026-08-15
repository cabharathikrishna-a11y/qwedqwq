package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R

object AppShortcutHelper {

    fun createTimerShortcut(context: Context): ShortcutInfoCompat {
        val timerIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_TIMER_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "TIMER")
            putExtra("SHOW_TIMER_PAGE", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_pin_timer")
            .setShortLabel(context.getString(R.string.shortcut_timer_short))
            .setLongLabel(context.getString(R.string.shortcut_timer_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_timer))
            .setIntent(timerIntent)
            .build()
    }

    fun createJournalShortcut(context: Context): ShortcutInfoCompat {
        val journalIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_JOURNAL_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "JOURNAL")
            putExtra("SHOW_JOURNAL_PAGE", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_pin_journal")
            .setShortLabel(context.getString(R.string.shortcut_journal_short))
            .setLongLabel(context.getString(R.string.shortcut_journal_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_journal))
            .setIntent(journalIntent)
            .build()
    }

    fun createTasksShortcut(context: Context): ShortcutInfoCompat {
        val tasksIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_TASKS_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "TASKS")
            putExtra("SHOW_TASKS_PAGE", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_pin_tasks")
            .setShortLabel(context.getString(R.string.shortcut_tasks_short))
            .setLongLabel(context.getString(R.string.shortcut_tasks_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tasks))
            .setIntent(tasksIntent)
            .build()
    }

    fun createInstagramShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_INSTAGRAM_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "INSTAGRAM_WEB_APP")
            putExtra("OPEN_INSTAGRAM_WEB_APP", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_instagram_web")
            .setShortLabel(context.getString(R.string.shortcut_instagram_short))
            .setLongLabel(context.getString(R.string.shortcut_instagram_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_instagram_shortcut))
            .setIntent(intent)
            .build()
    }

    fun createYouTubeShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_YOUTUBE_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "YOUTUBE_WEB_APP")
            putExtra("OPEN_YOUTUBE_WEB_APP", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_youtube_web")
            .setShortLabel(context.getString(R.string.shortcut_youtube_short))
            .setLongLabel(context.getString(R.string.shortcut_youtube_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_youtube_shortcut))
            .setIntent(intent)
            .build()
    }

    fun createSpotifyShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_SPOTIFY_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "SPOTIFY_WEB_APP")
            putExtra("OPEN_SPOTIFY_WEB_APP", true)
        }
        return ShortcutInfoCompat.Builder(context, "shortcut_spotify_web")
            .setShortLabel(context.getString(R.string.shortcut_spotify_short))
            .setLongLabel(context.getString(R.string.shortcut_spotify_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_spotify_shortcut))
            .setIntent(intent)
            .build()
    }

    fun pinTimerShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createTimerShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin Timer shortcut", e)
        }
    }

    fun pinJournalShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createJournalShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin Journal shortcut", e)
        }
    }

    fun pinTasksShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createTasksShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin Tasks shortcut", e)
        }
    }

    fun pinInstagramShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createInstagramShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin Instagram shortcut", e)
        }
    }

    fun pinYouTubeShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createYouTubeShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin YouTube shortcut", e)
        }
    }

    fun pinSpotifyShortcut(context: Context) {
        try {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, createSpotifyShortcut(context), null)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to pin Spotify shortcut", e)
        }
    }

    fun publishDynamicShortcuts(context: Context) {
        try {
            // Remove legacy dynamic web shortcuts
            ShortcutManagerCompat.removeDynamicShortcuts(
                context,
                listOf(
                    "instagram_web_app_shortcut",
                    "youtube_web_app_shortcut",
                    "spotify_web_app_shortcut",
                    "shortcut_instagram_web",
                    "shortcut_youtube_web",
                    "shortcut_spotify_web"
                )
            )
            // Note: shortcut_timer, shortcut_journal, and shortcut_tasks are declared in res/xml/shortcuts.xml
            // as static manifest shortcuts. Under Android PM, static manifest shortcuts are immutable and
            // must not be passed to setDynamicShortcuts.
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to publish dynamic shortcuts", e)
        }
    }
}
