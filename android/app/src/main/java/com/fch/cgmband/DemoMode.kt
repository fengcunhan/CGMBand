package com.fch.cgmband

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug-only demo data on the dashboard, for README screenshots without publishing real
 * glucose values. Display only: never writes the database, never talks to the band, and
 * alerts keep using real readings. Expires on its own so it cannot be left on by mistake.
 *
 *   adb shell am start -n com.fch.cgmband/.MainActivity --ez demo true    # on for 10 minutes
 *   adb shell am start -n com.fch.cgmband/.MainActivity --ez demo false   # off
 */
object DemoMode {

    private const val EXTRA_DEMO = "demo"
    private const val DURATION_MS = 10 * 60_000L

    @Volatile
    private var expiresAt = 0L

    val active: Boolean
        get() = System.currentTimeMillis() < expiresAt

    /** Applies a `demo` extra from [intent]; ignored unless this is a debuggable build. */
    fun handle(context: Context, intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA_DEMO) || !isDebuggable(context)) return
        expiresAt = if (intent.getBooleanExtra(EXTRA_DEMO, false)) System.currentTimeMillis() + DURATION_MS else 0L
        EventLog.add(if (active) "演示模式已开启（10 分钟后自动关闭）" else "演示模式已关闭")
    }

    private fun isDebuggable(context: Context) =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
