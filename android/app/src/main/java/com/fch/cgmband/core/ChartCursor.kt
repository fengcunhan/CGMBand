package com.fch.cgmband.core

import kotlin.math.abs

/** What the chart cursor points at: a time, and the readings of today / yesterday there. */
data class CursorReadout(val at: Long, val today: GlucosePoint?, val yesterday: GlucosePoint?)

/**
 * Maps a touched time on the chart to readings. The cursor snaps to today's nearest reading,
 * or to yesterday's where today has none (e.g. ahead of now); yesterday is already shifted
 * onto today's axis by [ChartData].
 */
object ChartCursor {

    private const val SNAP_MS = 10 * 60_000L

    fun readout(data: ChartData, requestedAt: Long): CursorReadout {
        val at = requestedAt.coerceIn(data.window.start, data.window.end)
        val today = nearest(data.today, at)
        val anchor = today?.at ?: nearest(data.yesterday, at)?.at ?: at
        return CursorReadout(anchor, today, nearest(data.yesterday, anchor))
    }

    private fun nearest(points: List<GlucosePoint>, at: Long): GlucosePoint? =
        points.minByOrNull { abs(it.at - at) }?.takeIf { abs(it.at - at) <= SNAP_MS }
}
