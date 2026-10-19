package com.fch.cgmband.core

/**
 * One glucose reading as shown by the CGM app's ongoing notification.
 *
 * @property value display text exactly as the CGM app renders it ("6.6", "LO", ...)
 * @property mmol numeric value in mmol/L, or null when [value] is not a number
 * @property time "HH:mm" shown next to the value, empty when absent
 * @property readingAt epoch millis of the measurement, or null when it cannot be
 *   determined — never guessed, so stale values are not presented as fresh
 * @property postedAt epoch millis when the CGM app posted/updated the notification
 * @property connected false when the CGM app reports the sensor as disconnected
 */
data class GlucoseReading(
    val value: String,
    val unit: String,
    val mmol: Double?,
    val time: String,
    val readingAt: Long?,
    val postedAt: Long,
    val connected: Boolean,
)

enum class Trend {
    DOUBLE_UP,
    SINGLE_UP,
    FORTY_FIVE_UP,
    FLAT,
    FORTY_FIVE_DOWN,
    SINGLE_DOWN,
    DOUBLE_DOWN,
    NONE,
}

internal const val MGDL_PER_MMOL = 18.0

internal fun roundToTenth(x: Double): Double = Math.round(x * 10) / 10.0
