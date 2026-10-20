package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DashboardModelTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val range = TargetRange(low = 3.9, high = 7.8)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun point(text: String, mmol: Double) = GlucosePoint(at(text), mmol)

    // ---- target range ----

    @Test
    fun `target range must be ordered and plausible`() {
        assertEquals(range, TargetRange.of(3.9, 7.8))
        assertNull(TargetRange.of(7.8, 3.9))
        assertNull(TargetRange.of(0.0, 7.8))
        assertNull(TargetRange.of(3.9, 40.0))
    }

    @Test
    fun `classifies values against the target range inclusively`() {
        assertEquals(GlucoseLevel.LOW, range.classify(3.8))
        assertEquals(GlucoseLevel.IN_RANGE, range.classify(3.9))
        assertEquals(GlucoseLevel.IN_RANGE, range.classify(7.8))
        assertEquals(GlucoseLevel.HIGH, range.classify(8.1))
        assertEquals(GlucoseLevel.UNKNOWN, range.classify(null))
    }

    // ---- statistics ----

    @Test
    fun `no statistics without points`() {
        assertNull(GlucoseStats.compute(emptyList(), range))
    }

    @Test
    fun `computes time in range swing and extremes with their times`() {
        val points = listOf(
            point("2026-10-20T07:36:00", 5.8),
            point("2026-10-20T08:00:00", 6.0),
            point("2026-10-20T09:45:00", 8.9),
            point("2026-10-20T10:00:00", 7.8),
            point("2026-10-20T10:24:00", 8.1),
        )

        val stats = GlucoseStats.compute(points, range)!!

        assertEquals(60.0, stats.timeInRangePercent, 1e-9)
        assertEquals(3.1, stats.maxSwing, 1e-9)
        assertEquals(point("2026-10-20T09:45:00", 8.9), stats.highest)
        assertEquals(point("2026-10-20T07:36:00", 5.8), stats.lowest)
    }

    // ---- chart window ----

    @Test
    fun `window ends at the next full hour and has five ticks`() {
        val now = at("2026-10-20T10:24:00")

        val four = ChartWindow.forHours(4, now, zone)
        assertEquals(at("2026-10-20T07:00:00"), four.start)
        assertEquals(at("2026-10-20T11:00:00"), four.end)
        assertEquals(
            listOf("07:00", "08:00", "09:00", "10:00", "11:00").map { at("2026-10-20T$it:00") },
            four.ticks,
        )

        val twelve = ChartWindow.forHours(12, now, zone)
        assertEquals(at("2026-10-19T23:00:00"), twelve.start)
        assertEquals(
            listOf(at("2026-10-19T23:00:00"), at("2026-10-20T02:00:00"), at("2026-10-20T05:00:00"),
                at("2026-10-20T08:00:00"), at("2026-10-20T11:00:00")),
            twelve.ticks,
        )
    }

    // ---- chart data ----

    @Test
    fun `splits today and shifts yesterday onto today's axis`() {
        val window = ChartWindow.forHours(4, at("2026-10-20T10:24:00"), zone)
        val points = listOf(
            point("2026-10-19T08:00:00", 6.5), // yesterday, inside the shifted window
            point("2026-10-19T12:00:00", 9.0), // yesterday, outside
            point("2026-10-20T06:59:00", 7.0), // today, before the window
            point("2026-10-20T08:00:00", 6.0), // today, inside
        )

        val data = ChartData.build(points, window)

        assertEquals(listOf(point("2026-10-20T08:00:00", 6.0)), data.today)
        assertEquals(listOf(point("2026-10-20T08:00:00", 6.5)), data.yesterday)
    }

    @Test
    fun `y axis is at least 12 and grows in steps of 3`() {
        val window = ChartWindow.forHours(4, at("2026-10-20T10:24:00"), zone)

        assertEquals(12.0, ChartData.build(listOf(point("2026-10-20T08:00:00", 8.9)), window).yMax, 1e-9)
        assertEquals(15.0, ChartData.build(listOf(point("2026-10-20T08:00:00", 14.2)), window).yMax, 1e-9)
    }

    @Test
    fun `lines break across gaps longer than fifteen minutes`() {
        val points = listOf(
            point("2026-10-20T08:00:00", 6.0),
            point("2026-10-20T08:03:00", 6.1),
            point("2026-10-20T08:30:00", 6.4),
        )

        val segments = ChartData.segments(points)

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    // ---- time label ----

    @Test
    fun `labels reading time relative to now`() {
        val reading = at("2026-10-20T10:24:00")

        assertEquals("刚刚 10-20 10:24", ReadingTimeLabel.format(reading, reading + 30_000, zone))
        assertEquals("3分钟前 10-20 10:24", ReadingTimeLabel.format(reading, reading + 3 * 60_000, zone))
        assertEquals("2小时前 10-20 10:24", ReadingTimeLabel.format(reading, reading + 125 * 60_000, zone))
        assertEquals("时间未知", ReadingTimeLabel.format(null, reading, zone))
    }
}
