package com.fch.cgmband.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** Visible time span of the chart: ends at the next full hour, with five evenly spaced ticks. */
data class ChartWindow(val start: Long, val end: Long, val ticks: List<Long>) {
    companion object {
        private const val TICK_INTERVALS = 4

        fun forHours(hours: Int, now: Long, zone: ZoneId): ChartWindow {
            val end = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val start = end.minusHours(hours.toLong())
            val stepMinutes = hours * 60L / TICK_INTERVALS
            val ticks = (0..TICK_INTERVALS).map { start.plusMinutes(stepMinutes * it).toInstant().toEpochMilli() }
            return ChartWindow(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), ticks)
        }
    }
}

/**
 * Points to draw for one window: today's line, yesterday's line shifted onto today's axis,
 * and today's fingerstick results from the bound meter as separate markers.
 */
data class ChartData(
    val window: ChartWindow,
    val today: List<GlucosePoint>,
    val yesterday: List<GlucosePoint>,
    val yMax: Double,
    val fingersticks: List<Fingerstick> = emptyList(),
) {
    /** This chart with one fingerstick's tag replaced, so a retag shows before the database reload. */
    fun withTag(device: String, sequence: Int, tag: FingerstickTag?): ChartData =
        copy(
            fingersticks = fingersticks.map {
                if (it.device == device && it.record.sequence == sequence) it.copy(tag = tag) else it
            },
        )

    companion object {
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val MAX_GAP_MS = 15 * 60_000L
        private const val MIN_Y_MAX = 12.0
        private const val Y_STEP = 3.0

        fun build(
            points: List<GlucosePoint>,
            window: ChartWindow,
            fingersticks: List<Fingerstick> = emptyList(),
        ): ChartData {
            val today = points.filter { it.at in window.start..window.end }.sortedBy { it.at }
            val yesterday = points
                .filter { it.at in (window.start - DAY_MS)..(window.end - DAY_MS) }
                .map { it.copy(at = it.at + DAY_MS) }
                .sortedBy { it.at }
            // HI / LO without a number cannot be placed on the y axis.
            val meter = fingersticks
                .filter { it.at in window.start..window.end && it.record.mmol != null }
                .sortedBy { it.at }
            val highest = ((today + yesterday).map { it.mmol } + meter.mapNotNull { it.record.mmol }).maxOrNull() ?: 0.0
            val yMax = maxOf(MIN_Y_MAX, ceil(highest / Y_STEP) * Y_STEP)
            return ChartData(window, today, yesterday, yMax, meter)
        }

        /** Splits a sorted series wherever readings are missing, so the line is not drawn across gaps. */
        fun segments(points: List<GlucosePoint>): List<List<GlucosePoint>> =
            points.fold(emptyList()) { acc, point ->
                val last = acc.lastOrNull()?.lastOrNull()
                if (last != null && point.at - last.at <= MAX_GAP_MS) {
                    acc.dropLast(1) + listOf(acc.last() + point)
                } else {
                    acc + listOf(listOf(point))
                }
            }
    }
}
