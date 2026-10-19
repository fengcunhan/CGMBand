package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendCalculatorTest {

    @Test
    fun `no trend with fewer than two readings`() {
        assertEquals(Trend.NONE, TrendCalculator.trend(emptyList()))
        assertEquals(Trend.NONE, TrendCalculator.trend(listOf(reading(0, 6.0))))
    }

    @Test
    fun `classifies rate of change like common CGM arrows`() {
        // 3-minute spacing; thresholds are 1/2/3 mg/dL per minute.
        assertEquals(Trend.FLAT, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 6.1))))
        assertEquals(Trend.FORTY_FIVE_UP, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 6.3))))
        assertEquals(Trend.SINGLE_UP, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 6.4))))
        assertEquals(Trend.DOUBLE_UP, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 6.6))))
        assertEquals(Trend.FORTY_FIVE_DOWN, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 5.7))))
        assertEquals(Trend.SINGLE_DOWN, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 5.6))))
        assertEquals(Trend.DOUBLE_DOWN, TrendCalculator.trend(listOf(reading(0, 6.0), reading(3, 5.4))))
    }

    @Test
    fun `uses only the two most recent readings`() {
        val history = listOf(reading(0, 9.0), reading(3, 6.0), reading(6, 6.0))

        assertEquals(Trend.FLAT, TrendCalculator.trend(history))
    }

    @Test
    fun `no trend or delta when the latest reading is not numeric`() {
        val history = listOf(reading(0, 4.0), reading(3, 3.9), reading(6, null))

        assertEquals(Trend.NONE, TrendCalculator.trend(history))
        assertNull(TrendCalculator.delta(history))
    }

    @Test
    fun `no trend when readings are too far apart`() {
        assertEquals(Trend.NONE, TrendCalculator.trend(listOf(reading(0, 6.0), reading(20, 9.0))))
    }

    @Test
    fun `delta is latest minus previous rounded to one decimal`() {
        assertEquals(0.3, TrendCalculator.delta(listOf(reading(0, 6.0), reading(3, 6.3)))!!, 1e-9)
        assertEquals(-0.2, TrendCalculator.delta(listOf(reading(0, 6.04), reading(3, 5.86)))!!, 1e-9)
        assertNull(TrendCalculator.delta(listOf(reading(0, 6.0))))
    }
}
