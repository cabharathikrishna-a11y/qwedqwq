package com.example.util

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object GmsUtils {
    @Volatile
    private var gmsAvailableOverride: Boolean? = null

    fun isGmsAvailable(context: Context): Boolean {
        gmsAvailableOverride?.let { return it }
        return try {
            val pm = context.packageManager
            val pkgInfo = try {
                pm.getPackageInfo("com.google.android.gms", 0)
            } catch (_: Throwable) {
                null
            }
            if (pkgInfo == null) {
                gmsAvailableOverride = false
                return false
            }
            val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            val available = (code == ConnectionResult.SUCCESS)
            if (!available) {
                gmsAvailableOverride = false
            }
            available
        } catch (_: Throwable) {
            gmsAvailableOverride = false
            false
        }
    }

    fun disableGms() {
        gmsAvailableOverride = false
    }

    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        if (!isGmsAvailable(context)) return null
        return try {
            GoogleSignIn.getLastSignedInAccount(context)
        } catch (_: Throwable) {
            null
        }
    }
}
