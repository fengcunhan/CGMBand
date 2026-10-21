package com.fch.cgmband.core

import kotlin.math.abs

/** What the chart cursor points at: a time, the readings of today / yesterday there, and a fingerstick if any. */
data class CursorReadout(
    val at: Long,
    val today: GlucosePoint?,
    val yesterday: GlucosePoint?,
    val fingerstick: Fingerstick? = null,
)

/**
 * Maps a touched time on the chart to readings. A fingerstick near the touch wins and the
 * cursor sits on it, so its value can be read against the CGM line at the same moment.
 * Otherwise the cursor snaps to today's nearest reading, or to yesterday's where today has
 * none (e.g. ahead of now); yesterday is already shifted onto today's axis by [ChartData].
 */
object ChartCursor {

    private const val SNAP_MS = 10 * 60_000L

    /** Fingersticks are sparse dots: reach a fixed share of the span, so they are as easy to hit on 24 h as on 4 h. */
    private const val FINGERSTICK_REACH = 0.04

    fun readout(data: ChartData, requestedAt: Long): CursorReadout {
        val at = requestedAt.coerceIn(data.window.start, data.window.end)
        val fingerstick = nearestFingerstick(data, at)
        val today = nearest(data.today, fingerstick?.at ?: at)
        val anchor = fingerstick?.at ?: today?.at ?: nearest(data.yesterday, at)?.at ?: at
        return CursorReadout(anchor, today, nearest(data.yesterday, anchor), fingerstick)
    }

    private fun nearestFingerstick(data: ChartData, at: Long): Fingerstick? {
        val reach = maxOf(SNAP_MS, ((data.window.end - data.window.start) * FINGERSTICK_REACH).toLong())
        return data.fingersticks
            .filter { it.record.mmol != null } // a HI / LO without a value is not drawn, so it cannot be pointed at
            .minByOrNull { abs(it.at - at) }
            ?.takeIf { abs(it.at - at) <= reach }
    }

    private fun nearest(points: List<GlucosePoint>, at: Long): GlucosePoint? =
        points.minByOrNull { abs(it.at - at) }?.takeIf { abs(it.at - at) <= SNAP_MS }
}
