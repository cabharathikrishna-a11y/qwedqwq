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
        return ShortcutInfoCompat.Builder(context, "shortcut_timer")
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
        return ShortcutInfoCompat.Builder(context, "shortcut_journal")
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
        return ShortcutInfoCompat.Builder(context, "shortcut_tasks")
            .setShortLabel(context.getString(R.string.shortcut_tasks_short))
            .setLongLabel(context.getString(R.string.shortcut_tasks_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tasks))
            .setIntent(tasksIntent)
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
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    createTimerShortcut(context),
                    createJournalShortcut(context),
                    createTasksShortcut(context)
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("AppShortcutHelper", "Failed to publish dynamic shortcuts", e)
        }
    }
}
