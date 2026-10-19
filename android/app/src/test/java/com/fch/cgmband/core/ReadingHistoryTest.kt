package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReadingHistoryTest {

    @Test
    fun `append returns a new list and leaves the original untouched`() {
        val original = listOf(reading(0, 6.0))

        val updated = ReadingHistory.append(original, reading(3, 6.0))

        assertEquals(1, original.size)
        assertEquals(2, updated.size)
    }

    @Test
    fun `duplicate reading is ignored`() {
        val history = listOf(reading(0, 6.0), reading(3, 6.2))

        val updated = ReadingHistory.append(history, reading(3, 6.2))

        assertSame(history, updated)
    }

    @Test
    fun `reading without a known time is not added`() {
        val history = listOf(reading(0, 6.0))

        val updated = ReadingHistory.append(history, reading(null, 6.2))

        assertSame(history, updated)
    }

    @Test
    fun `same minute with a corrected value replaces the previous entry`() {
        val history = listOf(reading(0, 6.0), reading(3, 6.2))

        val updated = ReadingHistory.append(history, reading(3, 6.3))

        assertEquals(listOf(6.0, 6.3), updated.map { it.mmol })
    }

    @Test
    fun `drops readings older than the retention window`() {
        val history = (0L..60L step 3).map { reading(it, 6.0) }

        val updated = ReadingHistory.append(history, reading(63, 6.0))

        assertEquals(reading(3, 6.0).readingAt, updated.first().readingAt)
        assertEquals(reading(63, 6.0).readingAt, updated.last().readingAt)
    }

    @Test
    fun `keeps readings sorted by reading time`() {
        val history = listOf(reading(0, 6.0), reading(6, 6.0))

        val updated = ReadingHistory.append(history, reading(3, 6.0))

        assertEquals(listOf(0L, 3L, 6L), updated.map { (it.readingAt!! - T0) / MINUTE })
    }
}
