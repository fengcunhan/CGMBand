package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartCursorTest {

    private fun p(minute: Long, mmol: Double) = GlucosePoint(T0 + minute * MINUTE, mmol)

    private val window = ChartWindow(start = T0, end = T0 + 240 * MINUTE, ticks = emptyList())

    private fun data(today: List<GlucosePoint>, yesterday: List<GlucosePoint>) =
        ChartData(window, today, yesterday, yMax = 12.0)

    @Test
    fun `snaps to the nearest reading of today and reports yesterday at that time`() {
        val chart = data(
            today = listOf(p(120, 6.1), p(123, 6.3), p(126, 6.4)),
            yesterday = listOf(p(121, 5.8), p(124, 5.9)),
        )

        val readout = ChartCursor.readout(chart, T0 + 122 * MINUTE + 50_000)

        assertEquals(T0 + 123 * MINUTE, readout.at)
        assertEquals(p(123, 6.3), readout.today)
        assertEquals(p(124, 5.9), readout.yesterday)
    }

    @Test
    fun `falls back to yesterday where today has no data yet`() {
        val chart = data(today = listOf(p(120, 6.1)), yesterday = listOf(p(200, 7.2), p(203, 7.4)))

        val readout = ChartCursor.readout(chart, T0 + 202 * MINUTE)

        assertEquals(T0 + 203 * MINUTE, readout.at)
        assertNull(readout.today)
        assertEquals(p(203, 7.4), readout.yesterday)
    }

    @Test
    fun `no values when nothing is close enough`() {
        val chart = data(today = listOf(p(0, 6.0)), yesterday = listOf(p(100, 6.0)))

        val readout = ChartCursor.readout(chart, T0 + 50 * MINUTE)

        assertEquals(T0 + 50 * MINUTE, readout.at)
        assertNull(readout.today)
        assertNull(readout.yesterday)
    }

    @Test
    fun `cursor time is clamped to the chart window`() {
        val chart = data(today = emptyList(), yesterday = emptyList())

        assertEquals(window.start, ChartCursor.readout(chart, T0 - 30 * MINUTE).at)
        assertEquals(window.end, ChartCursor.readout(chart, T0 + 300 * MINUTE).at)
    }
}
