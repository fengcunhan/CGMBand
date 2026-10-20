package com.fch.cgmband

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Setup state shared by the dashboard banner and the settings screen. */
object SetupChecks {

    fun isListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(context, CgmNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
