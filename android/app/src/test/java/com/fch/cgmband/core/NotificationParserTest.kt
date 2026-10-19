package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NotificationParserTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val posted = at("2026-10-19T16:34:10")

    @Test
    fun `parses iCan 2_5 notification views`() {
        val texts = mapOf(
            "tv_cgm_value" to "6.6",
            "tv_cgm_unit" to "mmol/L",
            "tv_cgm_time" to "16:33",
        )

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertEquals("6.6", reading.value)
        assertEquals("mmol/L", reading.unit)
        assertEquals(6.6, reading.mmol!!, 1e-9)
        assertEquals("16:33", reading.time)
        assertEquals(at("2026-10-19T16:33:00"), reading.readingAt)
        assertEquals(posted, reading.postedAt)
        assertTrue(reading.connected)
    }

    @Test
    fun `parses legacy iCan 2_4 view ids but leaves time unknown`() {
        val texts = mapOf("new_blood_tv" to "5.2", "new_blood_unit_tv" to "mmol/L")

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertEquals("5.2", reading.value)
        assertEquals(5.2, reading.mmol!!, 1e-9)
        assertNull(reading.readingAt)
    }

    @Test
    fun `converts mg per dL to mmol per L`() {
        val texts = mapOf("tv_cgm_value" to "119", "tv_cgm_unit" to "mg/dL", "tv_cgm_time" to "16:33")

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertEquals("119", reading.value)
        assertEquals(6.6, reading.mmol!!, 1e-9)
    }

    @Test
    fun `missing unit with an implausible mmol value is treated as mg per dL`() {
        val texts = mapOf("tv_cgm_value" to "119", "tv_cgm_time" to "16:33")

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertEquals("mg/dL", reading.unit)
        assertEquals(6.6, reading.mmol!!, 1e-9)
    }

    @Test
    fun `keeps non numeric values like LO without mmol`() {
        val texts = mapOf("tv_cgm_value" to "LO", "tv_cgm_unit" to "mmol/L", "tv_cgm_time" to "16:33")

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertEquals("LO", reading.value)
        assertNull(reading.mmol)
    }

    @Test
    fun `returns null when there is no glucose value`() {
        assertNull(NotificationParser.parse(emptyMap(), posted, zone))
        assertNull(NotificationParser.parse(mapOf("tv_cgm_value" to "--"), posted, zone))
        assertNull(NotificationParser.parse(mapOf("tv_cgm_value" to "  "), posted, zone))
    }

    @Test
    fun `reading time later than the post time belongs to the previous day`() {
        val justAfterMidnight = at("2026-10-20T00:02:00")
        val texts = mapOf("tv_cgm_value" to "6.0", "tv_cgm_time" to "23:58")

        val reading = NotificationParser.parse(texts, justAfterMidnight, zone)!!

        assertEquals(at("2026-10-19T23:58:00"), reading.readingAt)
    }

    @Test
    fun `reading time just past midnight while posting just before it belongs to the next day`() {
        val justBeforeMidnight = at("2026-10-19T23:59:40")
        val texts = mapOf("tv_cgm_value" to "6.0", "tv_cgm_time" to "00:01")

        val reading = NotificationParser.parse(texts, justBeforeMidnight, zone)!!

        assertEquals(at("2026-10-20T00:01:00"), reading.readingAt)
    }

    @Test
    fun `reading time far from the post time is treated as unknown`() {
        val texts = mapOf("tv_cgm_value" to "6.0", "tv_cgm_time" to "04:00")

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertNull(reading.readingAt)
    }

    @Test
    fun `time is unknown when the time text is missing`() {
        val reading = NotificationParser.parse(mapOf("tv_cgm_value" to "6.0"), posted, zone)!!

        assertNull(reading.readingAt)
        assertEquals("mmol/L", reading.unit)
    }

    @Test
    fun `detects disconnected sensor from any status text`() {
        val texts = mapOf(
            "tv_cgm_value" to "6.0",
            "tv_cgm_time" to "16:33",
            "connect_state_tv" to "设备未连接",
        )

        val reading = NotificationParser.parse(texts, posted, zone)!!

        assertFalse(reading.connected)
    }

    @Test
    fun `reports a disconnected sensor even when the value is a placeholder`() {
        assertTrue(NotificationParser.isSensorDisconnected(mapOf("tv_cgm_value" to "--", "x" to "连接断开")))
        assertFalse(NotificationParser.isSensorDisconnected(mapOf("tv_cgm_value" to "--")))
    }
}
