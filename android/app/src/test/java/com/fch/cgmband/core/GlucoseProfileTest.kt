package com.fch.cgmband.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class GlucoseProfileTest {

    private fun hex(text: String): ByteArray =
        text.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `uses the Bluetooth SIG glucose service and characteristics`() {
        assertEquals(UUID.fromString("00001808-0000-1000-8000-00805f9b34fb"), GlucoseProfile.SERVICE)
        assertEquals(UUID.fromString("00002a18-0000-1000-8000-00805f9b34fb"), GlucoseProfile.MEASUREMENT)
        assertEquals(UUID.fromString("00002a34-0000-1000-8000-00805f9b34fb"), GlucoseProfile.MEASUREMENT_CONTEXT)
        assertEquals(UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb"), GlucoseProfile.RECORD_ACCESS)
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), GlucoseProfile.CLIENT_CONFIG)
    }

    // ---- SFLOAT ---------------------------------------------------------------------------

    @Test
    fun `decodes SFLOAT mantissa and exponent`() {
        assertEquals(0.00101, GlucoseProfile.sfloat(0xB065)!!, 1e-12) // 101 x 10^-5
        assertEquals(0.0056, GlucoseProfile.sfloat(0xC038)!!, 1e-12) // 56 x 10^-4
        assertEquals(-1.0, GlucoseProfile.sfloat(0x0FFF)!!, 1e-12) // mantissa is signed 12-bit
        assertEquals(20.0, GlucoseProfile.sfloat(0x1002)!!, 1e-12) // positive exponent
    }

    @Test
    fun `SFLOAT special values carry no number`() {
        listOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801).forEach { assertNull(GlucoseProfile.sfloat(it)) }
    }

    // ---- Glucose Measurement (0x2A18) -------------------------------------------------------

    @Test
    fun `parses an Accu-Chek style measurement in kg per L`() {
        // flags: time offset + type/location + sensor status + context follows
        val m = GlucoseProfile.parseMeasurement(hex("1B 23 01 EA 07 0A 15 08 0F 1E 00 00 65 B0 11 00 00"))!!

        assertEquals(0x0123, m.sequence)
        assertEquals(LocalDateTime.of(2026, 10, 21, 8, 15, 30), m.time)
        assertEquals(101 / 18.02, m.mmol!!, 1e-9) // same factor as the 轻糖生活 mini program
        assertEquals(1, m.sampleType) // capillary whole blood
        assertEquals(1, m.sampleLocation) // finger
        assertEquals(0, m.status)
        assertTrue(m.contextFollows)
        assertFalse(m.isControlSolution)
    }

    @Test
    fun `applies the time offset to the base time`() {
        val m = GlucoseProfile.parseMeasurement(hex("03 24 01 EA 07 0A 15 0C 1E 00 C4 FF 7E B0 F8"))!!

        assertEquals(LocalDateTime.of(2026, 10, 21, 11, 30, 0), m.time) // 12:30 - 60 min
        assertEquals(126 / 18.02, m.mmol!!, 1e-9)
        assertEquals(8, m.sampleType)
        assertNull(m.sampleLocation) // 0xF = not available
        assertFalse(m.contextFollows)
    }

    @Test
    fun `honours the mol per L unit flag`() {
        val m = GlucoseProfile.parseMeasurement(hex("06 25 01 EA 07 0A 15 12 00 00 38 C0 11"))!!

        assertEquals(5.6, m.mmol!!, 1e-9)
    }

    @Test
    fun `measurement without concentration has no value`() {
        val m = GlucoseProfile.parseMeasurement(hex("00 26 01 EA 07 0A 15 12 00 00"))!!

        assertNull(m.mmol)
        assertNull(m.sampleType)
    }

    @Test
    fun `reads the sensor status annunciation`() {
        val m = GlucoseProfile.parseMeasurement(hex("0A 27 01 EA 07 0A 15 12 00 00 FF 07 11 20 00"))!!

        assertNull(m.mmol) // NaN
        assertTrue(m.isHigh)
        assertFalse(m.isLow)
    }

    @Test
    fun `flags control solution tests`() {
        val m = GlucoseProfile.parseMeasurement(hex("02 28 01 EA 07 0A 15 12 00 00 65 B0 4A"))!!

        assertTrue(m.isControlSolution)
    }

    @Test
    fun `an unset meter clock gives no time`() {
        val m = GlucoseProfile.parseMeasurement(hex("02 29 01 00 00 00 00 00 00 00 65 B0 11"))!!

        assertNull(m.time)
    }

    @Test
    fun `rejects truncated measurements`() {
        assertNull(GlucoseProfile.parseMeasurement(hex("1B 23 01 EA 07 0A 15")))
        assertNull(GlucoseProfile.parseMeasurement(hex("03 24 01 EA 07 0A 15 0C 1E 00 C4 FF 7E")))
    }

    // ---- Glucose Measurement Context (0x2A34) -----------------------------------------------

    @Test
    fun `parses the meal from a context`() {
        val c = GlucoseProfile.parseContext(hex("02 23 01 01"))!!

        assertEquals(0x0123, c.sequence)
        assertEquals(Meal.BEFORE_MEAL, c.meal)
    }

    @Test
    fun `skips carbohydrate and extended flags before the meal`() {
        assertEquals(Meal.AFTER_MEAL, GlucoseProfile.parseContext(hex("03 23 01 01 2C F0 02"))!!.meal)
        assertEquals(Meal.FASTING, GlucoseProfile.parseContext(hex("82 23 01 00 03"))!!.meal)
    }

    @Test
    fun `context without meal or with an unknown code has no meal`() {
        assertNull(GlucoseProfile.parseContext(hex("00 23 01"))!!.meal)
        assertNull(GlucoseProfile.parseContext(hex("02 23 01 09"))!!.meal)
        assertNull(GlucoseProfile.parseContext(hex("02 23")))
    }

    // ---- Record Access Control Point (0x2A52) -----------------------------------------------

    @Test
    fun `builds record requests`() {
        assertArrayEquals(hex("01 01"), GlucoseProfile.requestAllRecords())
        assertArrayEquals(hex("01 03 01 24 01"), GlucoseProfile.requestRecordsFrom(0x0124))
    }

    @Test
    fun `parses record access responses`() {
        val ok = GlucoseProfile.parseRecordAccessResponse(hex("06 00 01 01"))!!
        assertTrue(ok.finished)
        assertFalse(ok.filterUnsupported)

        assertTrue(GlucoseProfile.parseRecordAccessResponse(hex("06 00 01 06"))!!.finished) // no records found

        val unsupported = GlucoseProfile.parseRecordAccessResponse(hex("06 00 01 04"))!!
        assertFalse(unsupported.finished)
        assertTrue(unsupported.filterUnsupported)
        assertEquals(4, unsupported.code)
    }

    @Test
    fun `ignores record access messages that are not responses`() {
        assertNull(GlucoseProfile.parseRecordAccessResponse(hex("05 00 0A 00"))) // number of records
        assertNull(GlucoseProfile.parseRecordAccessResponse(hex("06 00")))
    }
}
