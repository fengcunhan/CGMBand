package com.fch.cgmband.core

/** Alert thresholds in mmol/L; construct through [of] so the ordering is always valid. */
data class AlertThresholds(
    val urgentLow: Double = 3.1,
    val low: Double = 3.9,
    val high: Double = 13.9,
) {
    companion object {
        private const val MAX_MMOL = 33.3

        fun of(urgentLow: Double, low: Double, high: Double): AlertThresholds? =
            if (urgentLow > 0 && urgentLow < low && low < high && high <= MAX_MMOL) {
                AlertThresholds(urgentLow, low, high)
            } else {
                null
            }
    }
}

enum class GlucoseZone { URGENT_LOW, LOW, IN_RANGE, HIGH, UNKNOWN }

/** What was last alerted, to suppress repeats. The default is "in range, nothing alerted". */
data class AlertState(
    val zone: GlucoseZone = GlucoseZone.IN_RANGE,
    val lastAlertAt: Long? = null,
)

data class Alert(val title: String, val message: String)

/**
 * Decides when a reading should raise a vibrating notification on the band:
 * on entering (or worsening into) an out-of-range zone, and again every
 * [repeatIntervalMs] while it stays there. Stale or untimed readings never alert.
 */
object AlertPolicy {

    private const val MINUTE_MS = 60_000L
    private const val MAX_READING_AGE_MS = 15 * MINUTE_MS

    fun zone(reading: GlucoseReading, thresholds: AlertThresholds): GlucoseZone {
        val mmol = reading.mmol ?: return zoneOfText(reading.value)
        return when {
            mmol < thresholds.urgentLow -> GlucoseZone.URGENT_LOW
            mmol < thresholds.low -> GlucoseZone.LOW
            mmol > thresholds.high -> GlucoseZone.HIGH
            else -> GlucoseZone.IN_RANGE
        }
    }

    fun evaluate(
        state: AlertState,
        reading: GlucoseReading,
        trend: Trend,
        thresholds: AlertThresholds,
        now: Long,
    ): Pair<AlertState, Alert?> {
        val at = reading.readingAt ?: return state to null
        if (now - at > MAX_READING_AGE_MS) return state to null

        val zone = zone(reading, thresholds)
        return when {
            zone == GlucoseZone.UNKNOWN -> state to null
            zone == GlucoseZone.IN_RANGE -> AlertState() to null
            isImprovement(state.zone, zone) -> state.copy(zone = zone) to null
            zone != state.zone || isRepeatDue(state, zone, now) -> AlertState(zone, now) to alert(zone, reading, trend)
            else -> state to null
        }
    }

    private fun zoneOfText(value: String): GlucoseZone {
        val text = value.trim().uppercase()
        return when {
            text.startsWith("LO") -> GlucoseZone.URGENT_LOW
            text.startsWith("HI") -> GlucoseZone.HIGH
            else -> GlucoseZone.UNKNOWN
        }
    }

    private fun isImprovement(from: GlucoseZone, to: GlucoseZone): Boolean =
        from == GlucoseZone.URGENT_LOW && to == GlucoseZone.LOW

    private fun isRepeatDue(state: AlertState, zone: GlucoseZone, now: Long): Boolean {
        val last = state.lastAlertAt ?: return true
        return now - last >= repeatIntervalMs(zone)
    }

    private fun repeatIntervalMs(zone: GlucoseZone): Long = when (zone) {
        GlucoseZone.URGENT_LOW -> 10 * MINUTE_MS
        GlucoseZone.LOW -> 20 * MINUTE_MS
        else -> 60 * MINUTE_MS
    }

    private fun alert(zone: GlucoseZone, reading: GlucoseReading, trend: Trend): Alert {
        val label = when (zone) {
            GlucoseZone.URGENT_LOW -> "血糖过低"
            GlucoseZone.LOW -> "低血糖"
            else -> "高血糖"
        }
        val detail = listOfNotNull(reading.unit, trend.arrow.takeIf { it.isNotEmpty() }).joinToString(" ")
        return Alert(title = "$label ${reading.value}", message = "$detail · 测量于 ${reading.time}")
    }
}
