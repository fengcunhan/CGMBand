package com.fch.cgmband

import android.content.Context
import com.fch.cgmband.core.Alert
import com.fch.cgmband.core.AlertPolicy
import com.fch.cgmband.core.AlertState
import com.fch.cgmband.core.AlertThresholds
import com.fch.cgmband.core.GlucoseReading
import com.fch.cgmband.core.GlucoseZone
import com.fch.cgmband.core.Trend

/**
 * Turns new readings into vibrating band notifications according to [AlertPolicy].
 * Settings and the last-alert state are persisted so a process restart neither
 * forgets the user's thresholds nor re-alerts immediately.
 */
object AlertMonitor {

    data class Settings(val enabled: Boolean, val thresholds: AlertThresholds)

    private const val PREFS = "cgm_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URGENT_LOW = "urgent_low"
    private const val KEY_LOW = "low"
    private const val KEY_HIGH = "high"
    private const val KEY_ZONE = "state_zone"
    private const val KEY_LAST_ALERT = "state_last_alert"

    /** Call on the main thread for every newly accepted reading. */
    fun onReading(context: Context, reading: GlucoseReading, trend: Trend) {
        val settings = settings(context)
        if (!settings.enabled) return

        val (next, alert) = AlertPolicy.evaluate(
            loadState(context), reading, trend, settings.thresholds, System.currentTimeMillis(),
        )
        saveState(context, next)
        alert?.let { send(context, it) }
    }

    fun sendTest(context: Context, onResult: (Boolean) -> Unit) {
        BandBridge.notify(context, Alert("血糖提醒测试", "收到这条说明手环提醒可用"), onResult)
    }

    fun settings(context: Context): Settings {
        val prefs = prefs(context)
        val defaults = AlertThresholds()
        val thresholds = AlertThresholds.of(
            prefs.getFloat(KEY_URGENT_LOW, defaults.urgentLow.toFloat()).toDouble(),
            prefs.getFloat(KEY_LOW, defaults.low.toFloat()).toDouble(),
            prefs.getFloat(KEY_HIGH, defaults.high.toFloat()).toDouble(),
        ) ?: defaults
        return Settings(prefs.getBoolean(KEY_ENABLED, true), thresholds)
    }

    fun saveSettings(context: Context, settings: Settings) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putFloat(KEY_URGENT_LOW, settings.thresholds.urgentLow.toFloat())
            .putFloat(KEY_LOW, settings.thresholds.low.toFloat())
            .putFloat(KEY_HIGH, settings.thresholds.high.toFloat())
            .apply()
    }

    private fun send(context: Context, alert: Alert) {
        BandBridge.notify(context, alert) { ok ->
            EventLog.add(if (ok) "已向手环发送血糖提醒" else "血糖提醒发送失败")
        }
    }

    private fun loadState(context: Context): AlertState {
        val prefs = prefs(context)
        val zone = prefs.getString(KEY_ZONE, null)
            ?.let { name -> GlucoseZone.entries.firstOrNull { it.name == name } }
            ?: GlucoseZone.IN_RANGE
        val last = prefs.getLong(KEY_LAST_ALERT, -1L).takeIf { it >= 0 }
        return AlertState(zone, last)
    }

    private fun saveState(context: Context, state: AlertState) {
        prefs(context).edit()
            .putString(KEY_ZONE, state.zone.name)
            .putLong(KEY_LAST_ALERT, state.lastAlertAt ?: -1L)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
