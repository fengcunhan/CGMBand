package com.fch.cgmband.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MeterTransferTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val morning = LocalDateTime.of(2026, 10, 21, 8, 15)

    private fun epoch(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()

    private fun measurement(
        sequence: Int,
        mmol: Double? = 5.6049,
        time: LocalDateTime? = morning,
        sampleType: Int? = 1,
        sampleLocation: Int? = 1,
        status: Int = 0,
    ) = Measurement(sequence, time, mmol, sampleType, sampleLocation, status, contextFollows = false)

    @Test
    fun `joins measurements with their meal context and rounds like the meter`() {
        val transfer = MeterTransfer.build(
            listOf(measurement(2, 7.2141, morning.plusHours(4)), measurement(1)),
            listOf(MeasurementContext(1, Meal.BEFORE_MEAL)),
            zone,
        )

        assertEquals(
            listOf(
                MeterRecord(1, epoch(morning), 5.6, Meal.BEFORE_MEAL, MeterFlag.NONE),
                MeterRecord(2, epoch(morning.plusHours(4)), 7.2, null, MeterFlag.NONE),
            ),
            transfer.records,
        )
        assertEquals(2, transfer.highestSequence)
    }

    @Test
    fun `drops control solution, untimed and empty results but still advances the sequence`() {
        val transfer = MeterTransfer.build(
            listOf(
                measurement(1, sampleType = 10, sampleLocation = 4),
                measurement(2, time = null),
                measurement(3, mmol = null),
                measurement(4),
            ),
            emptyList(),
            zone,
        )

        assertEquals(listOf(4), transfer.records.map { it.sequence })
        assertEquals(4, transfer.highestSequence)
    }

    @Test
    fun `keeps HI and LO results even without a number`() {
        val transfer = MeterTransfer.build(
            listOf(
                measurement(1, mmol = null, status = Measurement.STATUS_TOO_HIGH),
                measurement(2, mmol = 0.5, status = Measurement.STATUS_TOO_LOW),
            ),
            emptyList(),
            zone,
        )

        assertEquals(listOf(MeterFlag.HIGH, MeterFlag.LOW), transfer.records.map { it.flag })
        assertEquals(listOf("HI", "LO"), transfer.records.map { it.label })
        assertNull(transfer.records.first().mmol)
    }

    @Test
    fun `a repeated sequence keeps the last copy`() {
        val transfer = MeterTransfer.build(listOf(measurement(1, 5.0), measurement(1, 6.0)), emptyList(), zone)

        assertEquals(listOf(6.0), transfer.records.map { it.mmol })
    }

    @Test
    fun `an empty transfer has no highest sequence`() {
        assertNull(MeterTransfer.build(emptyList(), emptyList(), zone).highestSequence)
    }

    @Test
    fun `asks for everything first, then only what is new`() {
        assertArrayEquals(GlucoseProfile.requestAllRecords(), MeterTransfer.request(null))
        assertArrayEquals(GlucoseProfile.requestRecordsFrom(0x0124), MeterTransfer.request(0x0123))
        assertArrayEquals(GlucoseProfile.requestAllRecords(), MeterTransfer.request(0xFFFF))
    }

    @Test
    fun `notices results timed in the future, which means the meter clock is off`() {
        val transfer = MeterTransfer.build(listOf(measurement(1), measurement(2, time = morning.plusHours(2))), emptyList(), zone)

        assertEquals(false, transfer.hasFutureRecords(now = epoch(morning.plusHours(2))))
        assertEquals(false, transfer.hasFutureRecords(now = epoch(morning.plusHours(2).minusMinutes(9))))
        assertEquals(true, transfer.hasFutureRecords(now = epoch(morning.plusHours(1))))
    }

    @Test
    fun `labels numeric results with one decimal`() {
        assertEquals("5.6", MeterRecord(1, 0L, 5.6, null, MeterFlag.NONE).label)
        assertEquals("12.0", MeterRecord(1, 0L, 12.0, null, MeterFlag.NONE).label)
    }
}
