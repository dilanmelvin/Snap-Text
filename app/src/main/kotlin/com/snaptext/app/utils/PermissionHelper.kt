package com.snaptext.app.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object PermissionHelper {
    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
