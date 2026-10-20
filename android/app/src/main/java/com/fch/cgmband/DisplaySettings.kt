package com.fch.cgmband

import android.content.Context
import com.fch.cgmband.core.TargetRange

/** Dashboard preferences: target range for colouring / time-in-range, and the selected chart span. */
object DisplaySettings {

    val CHART_HOURS = listOf(4, 8, 12, 24)

    private const val PREFS = "cgm_display"
    private const val KEY_LOW = "target_low"
    private const val KEY_HIGH = "target_high"
    private const val KEY_HOURS = "chart_hours"

    fun targetRange(context: Context): TargetRange {
        val defaults = TargetRange()
        val prefs = prefs(context)
        return TargetRange.of(
            prefs.getFloat(KEY_LOW, defaults.low.toFloat()).toDouble(),
            prefs.getFloat(KEY_HIGH, defaults.high.toFloat()).toDouble(),
        ) ?: defaults
    }

    fun saveTargetRange(context: Context, range: TargetRange) {
        prefs(context).edit()
            .putFloat(KEY_LOW, range.low.toFloat())
            .putFloat(KEY_HIGH, range.high.toFloat())
            .apply()
    }

    fun chartHours(context: Context): Int =
        prefs(context).getInt(KEY_HOURS, CHART_HOURS.first()).takeIf { it in CHART_HOURS } ?: CHART_HOURS.first()

    fun saveChartHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_HOURS, hours).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
