package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object OverlayPermissionHelper {
    private const val TAG = "OverlayPermissionHelper"

    /**
     * Checks if the app has permission to draw on-screen overlays (floating timer).
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking canDrawOverlays: ${e.message}", e)
                false
            }
        } else {
            true
        }
    }

    /**
     * Opens the optimal overlay/floating window permission screen for the device manufacturer,
     * with tailored support for Oppo / ColorOS, Realme, OnePlus, Xiaomi, Vivo, and Samsung.
     */
    fun openOverlaySettings(context: Context) {
        val packageName = context.packageName
        val manufacturer = Build.MANUFACTURER.lowercase()

        // 1. Oppo / Realme / OnePlus (ColorOS / OxygenOS / RealmeUI) Floating Window intents
        if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
            val oppoIntents = listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.floatwindow.FloatWindowListActivity")),
                Intent().setComponent(ComponentName("com.coloros.floatwindow", "com.coloros.floatingwindow.FloatWindowPermissionActivity")),
                Intent("com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity")
            )

            for (intent in oppoIntents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) != null) {
                        Log.d(TAG, "Launching Oppo ColorOS Float Window intent: ${intent.component}")
                        context.startActivity(intent)
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed Oppo intent ${intent.component}: ${e.message}")
                }
            }
        }

        // 2. Standard Android M+ Draw Over Apps settings with package URI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed ACTION_MANAGE_OVERLAY_PERMISSION with package URI: ${e.message}")
            }

            // 3. Fallback without package URI
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed ACTION_MANAGE_OVERLAY_PERMISSION generic: ${e.message}")
            }
        }

        // 4. Final Fallback: App Details Settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed all overlay settings intents: ${e.message}", e)
        }
    }
}
