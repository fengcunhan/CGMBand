package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DemoDataTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.parse("2026-10-20T12:24:30").atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `covers two days at the CGM cadence ending at the latest minute`() {
        val points = DemoData.points(now, zone)

        assertTrue(now - points.first().at >= 48 * 60 * MINUTE)
        assertTrue(now - points.last().at < 3 * MINUTE)
        assertTrue(points.zipWithNext().all { (a, b) -> b.at - a.at == 3 * MINUTE })
    }

    @Test
    fun `is deterministic so screenshots are reproducible`() {
        assertEquals(DemoData.points(now, zone), DemoData.points(now, zone))
    }

    @Test
    fun `looks like a plausible day with meal peaks above the target range`() {
        val points = DemoData.points(now, zone)
        val range = TargetRange()

        assertTrue(points.all { it.mmol in 3.0..13.0 })
        assertTrue(points.any { range.classify(it.mmol) == GlucoseLevel.HIGH })
        assertTrue(points.count { range.classify(it.mmol) == GlucoseLevel.IN_RANGE } > points.size / 2)
        assertTrue(points.all { Math.round(it.mmol * 10) / 10.0 == it.mmol })
    }

    @Test
    fun `state ends at the newest point with a trend and a band message`() {
        val points = DemoData.points(now, zone)

        val state = DemoData.state(points, zone)

        val latest = state.latest
        assertNotNull(latest)
        latest!!
        assertEquals(points.last().at, latest.readingAt)
        assertEquals(points.last().mmol, latest.mmol!!, 1e-9)
        assertTrue(state.trend != Trend.NONE)
        assertTrue(state.message!!.contains(""""type":"bg""""))
    }

    @Test
    fun `fingersticks are tagged, today, not in the future and sit near the CGM line`() {
        val points = DemoData.points(now, zone)

        val fingersticks = DemoData.fingersticks(now, zone)

        assertTrue(fingersticks.isNotEmpty())
        assertEquals(fingersticks, DemoData.fingersticks(now, zone))
        val startOfDay = LocalDateTime.parse("2026-10-20T00:00:00").atZone(zone).toInstant().toEpochMilli()
        fingersticks.forEach { stick ->
            assertTrue(stick.at in startOfDay..now)
            assertNotNull(stick.tag)
            val cgm = points.minByOrNull { kotlin.math.abs(it.at - stick.at) }!!
            assertTrue(kotlin.math.abs(cgm.mmol - stick.record.mmol!!) < 1.0)
        }
    }
}
