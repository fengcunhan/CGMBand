package com.fch.cgmband.core

enum class GlucoseLevel { LOW, IN_RANGE, HIGH, UNKNOWN }

/**
 * Target range in mmol/L used for colouring and time-in-range. Defaults match the
 * iCan app (3.9–7.8). Build user input through [of] so the range is always valid.
 */
data class TargetRange(val low: Double = 3.9, val high: Double = 7.8) {

    fun classify(mmol: Double?): GlucoseLevel = when {
        mmol == null -> GlucoseLevel.UNKNOWN
        mmol < low -> GlucoseLevel.LOW
        mmol > high -> GlucoseLevel.HIGH
        else -> GlucoseLevel.IN_RANGE
    }

    companion object {
        private const val MAX_MMOL = 33.3

        fun of(low: Double, high: Double): TargetRange? =
            if (low > 0 && low < high && high <= MAX_MMOL) TargetRange(low, high) else null
    }
}

/** A timed numeric reading, the unit of history, charts and statistics. */
data class GlucosePoint(val at: Long, val mmol: Double)

data class GlucoseStats(
    val timeInRangePercent: Double,
    val maxSwing: Double,
    val highest: GlucosePoint,
    val lowest: GlucosePoint,
) {
    companion object {
        /** Readings arrive at a fixed cadence, so the share of readings approximates the share of time. */
        fun compute(points: List<GlucosePoint>, range: TargetRange): GlucoseStats? {
            if (points.isEmpty()) return null
            val inRange = points.count { range.classify(it.mmol) == GlucoseLevel.IN_RANGE }
            val highest = points.maxBy { it.mmol }
            val lowest = points.minBy { it.mmol }
            return GlucoseStats(
                timeInRangePercent = roundToTenth(inRange * 100.0 / points.size),
                maxSwing = roundToTenth(highest.mmol - lowest.mmol),
                highest = highest,
                lowest = lowest,
            )
        }
    }
}
