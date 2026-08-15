package com.example.shortcut

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R

class TimerShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_TIMER_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "TIMER")
            putExtra("SHOW_TIMER_PAGE", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_timer")
            .setShortLabel(getString(R.string.shortcut_timer_short))
            .setLongLabel(getString(R.string.shortcut_timer_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_timer))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

class JournalShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_JOURNAL_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "JOURNAL")
            putExtra("SHOW_JOURNAL_PAGE", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_journal")
            .setShortLabel(getString(R.string.shortcut_journal_short))
            .setLongLabel(getString(R.string.shortcut_journal_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_journal))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

class TasksShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_TASKS_TAB"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "TASKS")
            putExtra("SHOW_TASKS_PAGE", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_tasks")
            .setShortLabel(getString(R.string.shortcut_tasks_short))
            .setLongLabel(getString(R.string.shortcut_tasks_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_tasks))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

class InstagramShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_INSTAGRAM_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "INSTAGRAM_WEB_APP")
            putExtra("OPEN_INSTAGRAM_WEB_APP", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_instagram_web")
            .setShortLabel(getString(R.string.shortcut_instagram_short))
            .setLongLabel(getString(R.string.shortcut_instagram_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_instagram_shortcut))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

class YouTubeShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_YOUTUBE_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "YOUTUBE_WEB_APP")
            putExtra("OPEN_YOUTUBE_WEB_APP", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_youtube_web")
            .setShortLabel(getString(R.string.shortcut_youtube_short))
            .setLongLabel(getString(R.string.shortcut_youtube_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_youtube_shortcut))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

class SpotifyShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.action.OPEN_SPOTIFY_WEB_APP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("NAVIGATE_TO", "SPOTIFY_WEB_APP")
            putExtra("OPEN_SPOTIFY_WEB_APP", true)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_spotify_web")
            .setShortLabel(getString(R.string.shortcut_spotify_short))
            .setLongLabel(getString(R.string.shortcut_spotify_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_spotify_shortcut))
            .setIntent(targetIntent)
            .build()
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
