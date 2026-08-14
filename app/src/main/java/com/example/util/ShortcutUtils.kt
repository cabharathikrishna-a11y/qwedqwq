package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity

object ShortcutUtils {

    fun createInstagramShortcut(context: Context, forcePinPrompt: Boolean = false): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("OPEN_INSTAGRAM_WEB_APP", true)
                putExtra("NAVIGATE_TO", "INSTAGRAM_WEB_APP")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val iconBitmap = createInstagramLogoBitmap()

            val shortcut = ShortcutInfoCompat.Builder(context, "instagram_web_app_shortcut")
                .setShortLabel("Instagram")
                .setLongLabel("Instagram")
                .setIcon(IconCompat.createWithBitmap(iconBitmap))
                .setIntent(intent)
                .build()

            // Push dynamic shortcut to app launcher icon menu
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

            // Pin to home screen ONLY ONCE unless forcePinPrompt is requested manually
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "shortcut_pin_prompted_instagram"
            val hasPrompted = prefs.getBoolean(key, false)

            if ((forcePinPrompt || !hasPrompted) && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                prefs.edit().putBoolean(key, true).apply()
            }
            true
        } catch (e: Exception) {
            Log.e("ShortcutUtils", "Error creating Instagram shortcut", e)
            false
        }
    }

    fun createInstagramLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Instagram Signature Gradient Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(
            Color.parseColor("#833AB4"), // Purple
            Color.parseColor("#FD1D1D"), // Red / Pink
            Color.parseColor("#FCB045")  // Orange / Yellow
        )
        val shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors, null, Shader.TileMode.CLAMP)
        paint.shader = shader

        // Rounded square background canvas
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // 2. White Camera Outline
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 12f
        }

        val cameraMargin = 42f
        val cameraRect = RectF(cameraMargin, cameraMargin, size - cameraMargin, size - cameraMargin)
        val cameraRadius = 28f
        canvas.drawRoundRect(cameraRect, cameraRadius, cameraRadius, strokePaint)

        // Center Lens Circle
        val center = size / 2f
        val lensRadius = 26f
        canvas.drawCircle(center, center, lensRadius, strokePaint)

        // Top Right Flash Dot
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val dotX = size - cameraMargin - 20f
        val dotY = cameraMargin + 20f
        val dotRadius = 7f
        canvas.drawCircle(dotX, dotY, dotRadius, fillPaint)

        return bitmap
    }

    fun createYouTubeShortcut(context: Context, forcePinPrompt: Boolean = false): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("OPEN_YOUTUBE_WEB_APP", true)
                putExtra("NAVIGATE_TO", "YOUTUBE_WEB_APP")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val iconBitmap = createYouTubeLogoBitmap()

            val shortcut = ShortcutInfoCompat.Builder(context, "youtube_web_app_shortcut")
                .setShortLabel("YouTube")
                .setLongLabel("YouTube")
                .setIcon(IconCompat.createWithBitmap(iconBitmap))
                .setIntent(intent)
                .build()

            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "shortcut_pin_prompted_youtube"
            val hasPrompted = prefs.getBoolean(key, false)

            if ((forcePinPrompt || !hasPrompted) && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                prefs.edit().putBoolean(key, true).apply()
            }
            true
        } catch (e: Exception) {
            Log.e("ShortcutUtils", "Error creating YouTube shortcut", e)
            false
        }
    }

    fun createYouTubeLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Red Rounded Rect Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0000")
        }
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // White Play Triangle
        val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(size * 0.38f, size * 0.30f)
            lineTo(size * 0.72f, size * 0.50f)
            lineTo(size * 0.38f, size * 0.70f)
            close()
        }
        canvas.drawPath(path, playPaint)

        return bitmap
    }

    fun createSpotifyShortcut(context: Context, forcePinPrompt: Boolean = false): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("OPEN_SPOTIFY_WEB_APP", true)
                putExtra("NAVIGATE_TO", "SPOTIFY_WEB_APP")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val iconBitmap = createSpotifyLogoBitmap()

            val shortcut = ShortcutInfoCompat.Builder(context, "spotify_web_app_shortcut")
                .setShortLabel("Spotify")
                .setLongLabel("Spotify")
                .setIcon(IconCompat.createWithBitmap(iconBitmap))
                .setIntent(intent)
                .build()

            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "shortcut_pin_prompted_spotify"
            val hasPrompted = prefs.getBoolean(key, false)

            if ((forcePinPrompt || !hasPrompted) && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                prefs.edit().putBoolean(key, true).apply()
            }
            true
        } catch (e: Exception) {
            Log.e("ShortcutUtils", "Error creating Spotify shortcut", e)
            false
        }
    }

    fun createSpotifyLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Spotify Green Circle Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
        }
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // Black Sound Waves / Arcs
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }

        val center = size / 2f
        // Top wave
        val rect1 = RectF(center - 55f, center - 45f, center + 55f, center + 45f)
        canvas.drawArc(rect1, 205f, 130f, false, wavePaint)

        // Middle wave
        val rect2 = RectF(center - 42f, center - 25f, center + 42f, center + 25f)
        canvas.drawArc(rect2, 205f, 130f, false, wavePaint)

        // Bottom wave
        val rect3 = RectF(center - 30f, center - 5f, center + 30f, center + 5f)
        canvas.drawArc(rect3, 205f, 130f, false, wavePaint)

        return bitmap
    }

    fun createKeepNotesLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Yellow Amber Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
        }
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // White Note Sheet
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val noteRect = RectF(48f, 40f, 144f, 152f)
        canvas.drawRoundRect(noteRect, 12f, 12f, notePaint)

        // Lines on note
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D97706")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(64f, 70f, 128f, 70f, linePaint)
        canvas.drawLine(64f, 96f, 128f, 96f, linePaint)
        canvas.drawLine(64f, 122f, 104f, 122f, linePaint)

        return bitmap
    }

    fun createTasksLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Blue Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B82F6")
        }
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // White Checkmark Path
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(52f, 96f)
            lineTo(84f, 128f)
            lineTo(140f, 68f)
        }
        canvas.drawPath(path, checkPaint)

        return bitmap
    }

    fun createTimerLogoBitmap(): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Red Focus Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
        }
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = 40f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        // White Clock Circle & Hands
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 12f
        }
        val center = size / 2f
        canvas.drawCircle(center, center, 50f, strokePaint)

        // Hands
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(center, center, center, center - 30f, handPaint)
        canvas.drawLine(center, center, center + 22f, center, handPaint)

        return bitmap
    }
}

