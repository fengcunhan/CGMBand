package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartCursorTest {

    private fun p(minute: Long, mmol: Double) = GlucosePoint(T0 + minute * MINUTE, mmol)

    private val window = ChartWindow(start = T0, end = T0 + 240 * MINUTE, ticks = emptyList())

    private fun data(
        today: List<GlucosePoint>,
        yesterday: List<GlucosePoint>,
        fingersticks: List<Fingerstick> = emptyList(),
        chartWindow: ChartWindow = window,
    ) = ChartData(chartWindow, today, yesterday, yMax = 12.0, fingersticks = fingersticks)

    private fun fingerstick(minute: Long, mmol: Double?, tag: FingerstickTag? = null) =
        Fingerstick("AA:BB", MeterRecord(minute.toInt(), T0 + minute * MINUTE, mmol, null, MeterFlag.NONE), tag)

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

    @Test
    fun `snaps to a nearby fingerstick and reads the CGM at its time`() {
        val stick = fingerstick(127, 4.4, FingerstickTag.FASTING)
        val chart = data(
            today = listOf(p(120, 6.1), p(123, 6.3), p(126, 6.4)),
            yesterday = listOf(p(127, 5.8)),
            fingersticks = listOf(stick),
        )

        val readout = ChartCursor.readout(chart, T0 + 121 * MINUTE)

        assertEquals(stick.at, readout.at)
        assertEquals(stick, readout.fingerstick)
        assertEquals(p(126, 6.4), readout.today)
        assertEquals(p(127, 5.8), readout.yesterday)
    }

    @Test
    fun `ignores fingersticks out of reach`() {
        val chart = data(today = listOf(p(120, 6.1)), yesterday = emptyList(), fingersticks = listOf(fingerstick(140, 4.4)))

        val readout = ChartCursor.readout(chart, T0 + 120 * MINUTE)

        assertNull(readout.fingerstick)
        assertEquals(T0 + 120 * MINUTE, readout.at)
    }

    @Test
    fun `fingerstick reach grows with the chart span so the dot stays easy to hit`() {
        val day = ChartWindow(start = T0, end = T0 + 24 * 60 * MINUTE, ticks = emptyList())
        val chart = data(emptyList(), emptyList(), listOf(fingerstick(600, 7.1)), chartWindow = day)

        assertEquals(7.1, ChartCursor.readout(chart, T0 + 560 * MINUTE).fingerstick!!.record.mmol!!, 1e-9)
        assertNull(ChartCursor.readout(chart, T0 + 500 * MINUTE).fingerstick)
    }

    @Test
    fun `reaches a fingerstick at least ten minutes away on the 4 hour chart`() {
        val chart = data(emptyList(), emptyList(), listOf(fingerstick(130, 6.0)))

        assertEquals(6.0, ChartCursor.readout(chart, T0 + 120 * MINUTE).fingerstick!!.record.mmol!!, 1e-9)
        assertNull(ChartCursor.readout(chart, T0 + 119 * MINUTE).fingerstick)
    }

    @Test
    fun `picks the closer of two nearby fingersticks`() {
        val first = fingerstick(100, 3.1)
        val retest = fingerstick(104, 5.2)
        val chart = data(emptyList(), emptyList(), listOf(first, retest))

        assertEquals(first, ChartCursor.readout(chart, T0 + 101 * MINUTE).fingerstick)
        assertEquals(retest, ChartCursor.readout(chart, T0 + 103 * MINUTE).fingerstick)
    }

    @Test
    fun `never snaps to a fingerstick without a value, which is not drawn`() {
        val chart = data(listOf(p(100, 6.0)), emptyList(), listOf(fingerstick(100, null)))

        assertNull(ChartCursor.readout(chart, T0 + 100 * MINUTE).fingerstick)
    }
}
