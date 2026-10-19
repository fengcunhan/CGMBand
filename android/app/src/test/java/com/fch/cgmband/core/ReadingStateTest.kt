package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStateTest {

    private fun accepted(vararg readings: GlucoseReading): ReadingState =
        readings.fold(ReadingState()) { state, r -> ReadingState.accept(state, r) ?: state }

    @Test
    fun `first reading produces a message`() {
        val next = ReadingState.accept(ReadingState(), reading(0, 6.0))

        assertNotNull(next)
        assertEquals(6.0, next!!.latest!!.mmol!!, 1e-9)
        assertTrue(next.message!!.contains(""""value":"6.0""""))
    }

    @Test
    fun `repeating the same reading produces nothing`() {
        val state = accepted(reading(0, 6.0))

        assertNull(ReadingState.accept(state, reading(0, 6.0)))
    }

    @Test
    fun `older reading never replaces a newer one`() {
        val state = accepted(reading(0, 6.0), reading(3, 6.3))

        assertNull(ReadingState.accept(state, reading(0, 6.0)))
    }

    @Test
    fun `connection change on the same reading is pushed`() {
        val state = accepted(reading(3, 6.3))

        val next = ReadingState.accept(state, reading(3, 6.3, connected = false))

        assertFalse(next!!.latest!!.connected)
        assertTrue(next.message!!.contains(""""connected":false"""))
    }

    @Test
    fun `reading with unknown time is sent with null ts and no trend`() {
        val state = accepted(reading(0, 6.0), reading(3, 6.6))

        val next = ReadingState.accept(state, reading(null, 7.0))

        val message = next!!.message!!
        assertTrue(message.contains(""""ts":null"""))
        assertTrue(message.contains(""""trend":"NONE""""))
        assertTrue(message.contains(""""delta":null"""))
    }

    @Test
    fun `trend and delta come from history`() {
        val state = accepted(reading(0, 6.0), reading(3, 6.6))

        assertTrue(state.message!!.contains(""""trend":"DOUBLE_UP""""))
        assertTrue(state.message!!.contains(""""delta":0.6"""))
    }

    @Test
    fun `marking the sensor disconnected re-sends the last reading`() {
        val state = accepted(reading(3, 6.3))

        val next = ReadingState.disconnected(state)

        assertFalse(next!!.latest!!.connected)
        assertTrue(next.message!!.contains(""""connected":false"""))
        assertTrue(next.message!!.contains(""""value":"6.3""""))
    }

    @Test
    fun `marking disconnected twice or with no data does nothing`() {
        assertNull(ReadingState.disconnected(ReadingState()))
        val once = ReadingState.disconnected(accepted(reading(3, 6.3)))!!
        assertNull(ReadingState.disconnected(once))
    }
}
