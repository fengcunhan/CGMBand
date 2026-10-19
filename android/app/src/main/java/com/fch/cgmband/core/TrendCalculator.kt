package com.fch.cgmband.core

import kotlin.math.abs

/**
 * Trend arrow and delta from the two most recent readings, using the usual CGM
 * thresholds of 1 / 2 / 3 mg/dL per minute. When the newest reading is not numeric
 * (LO/HI) there is no trend: an arrow from older readings would be misleading.
 */
object TrendCalculator {

    private const val MIN_GAP_MS = 30_000L
    private const val MAX_GAP_MS = 15 * 60_000L
    private const val MS_PER_MINUTE = 60_000.0

    private const val FLAT_LIMIT = 1.0
    private const val FORTY_FIVE_LIMIT = 2.0
    private const val SINGLE_LIMIT = 3.0

    private data class Point(val at: Long, val mmol: Double)

    fun trend(history: List<GlucoseReading>): Trend {
        val (previous, latest) = recentPair(history) ?: return Trend.NONE
        val minutes = (latest.at - previous.at) / MS_PER_MINUTE
        return classify((latest.mmol - previous.mmol) * MGDL_PER_MMOL / minutes)
    }

    fun delta(history: List<GlucoseReading>): Double? {
        val (previous, latest) = recentPair(history) ?: return null
        return roundToTenth(latest.mmol - previous.mmol)
    }

    private fun recentPair(history: List<GlucoseReading>): Pair<Point, Point>? {
        val timed = history.filter { it.readingAt != null }.sortedBy { it.readingAt }
        if (timed.size < 2) return null
        val previous = timed[timed.size - 2].toPoint() ?: return null
        val latest = timed.last().toPoint() ?: return null
        return if (latest.at - previous.at in MIN_GAP_MS..MAX_GAP_MS) previous to latest else null
    }

    private fun GlucoseReading.toPoint(): Point? {
        val at = readingAt ?: return null
        val value = mmol ?: return null
        return Point(at, value)
    }

    private fun classify(rate: Double): Trend {
        val magnitude = abs(rate)
        val rising = rate > 0
        return when {
            magnitude < FLAT_LIMIT -> Trend.FLAT
            magnitude < FORTY_FIVE_LIMIT -> if (rising) Trend.FORTY_FIVE_UP else Trend.FORTY_FIVE_DOWN
            magnitude < SINGLE_LIMIT -> if (rising) Trend.SINGLE_UP else Trend.SINGLE_DOWN
            else -> if (rising) Trend.DOUBLE_UP else Trend.DOUBLE_DOWN
        }
    }
}
