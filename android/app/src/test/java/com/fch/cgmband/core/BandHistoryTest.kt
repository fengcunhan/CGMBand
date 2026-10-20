package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandHistoryTest {

    private val range = TargetRange(3.9, 7.8)

    private fun point(minutesAfterT0: Long, mmol: Double) = GlucosePoint(T0 + minutesAfterT0 * MINUTE, mmol)

    @Test
    fun `empty history has no reference time`() {
        assertEquals(
            """{"type":"history","ref":null,"range":[3.9,7.8],"points":[]}""",
            BandMessage.encodeHistory(emptyList(), range),
        )
    }

    @Test
    fun `points are minutes before the newest reading and tenths of mmol`() {
        val points = listOf(point(0, 6.6), point(3, 6.5), point(6, 6.1))

        assertEquals(
            """{"type":"history","ref":${T0 + 6 * MINUTE},"range":[3.9,7.8],"points":[[6,66],[3,65],[0,61]]}""",
            BandMessage.encodeHistory(points, range),
        )
    }

    @Test
    fun `input order does not matter`() {
        val sorted = listOf(point(0, 6.6), point(3, 6.5), point(6, 6.1))

        assertEquals(BandMessage.encodeHistory(sorted, range), BandMessage.encodeHistory(sorted.reversed(), range))
    }

    @Test
    fun `keeps only the four hours before the newest reading`() {
        val points = listOf(point(0, 5.0), point(9, 6.0), point(249, 7.0))

        val json = BandMessage.encodeHistory(points, range)

        assertTrue(json.endsWith(""""points":[[240,60],[0,70]]}"""))
    }

    @Test
    fun `caps the number of points and always keeps the newest`() {
        val points = (0L..240L).map { point(it, 6.0) } // one per minute: 241 points

        val json = BandMessage.encodeHistory(points, range)
        val pairs = Regex("""\[(\d+),(\d+)]""").findAll(json.substringAfter("\"points\":")).toList()

        assertTrue(pairs.size <= BandMessage.MAX_HISTORY_POINTS)
        assertEquals("0", pairs.last().groupValues[1])
    }

    @Test
    fun `uses the configured target range`() {
        val json = BandMessage.encodeHistory(listOf(point(0, 6.0)), TargetRange(4.4, 10.0))

        assertTrue(json.contains(""""range":[4.4,10.0]"""))
    }
}
