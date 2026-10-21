package com.fch.cgmband.core

import java.time.ZoneId
import java.util.Locale

/** Out-of-range results the meter shows as HI / LO instead of a number. */
enum class MeterFlag { NONE, HIGH, LOW }

/**
 * One fingerstick result as this app keeps it.
 *
 * @property at epoch millis, reading the meter's clock in the phone's time zone
 * @property mmol rounded to 0.1 as the meter displays it; null for a HI / LO without a value
 */
data class MeterRecord(
    val sequence: Int,
    val at: Long,
    val mmol: Double?,
    val meal: Meal?,
    val flag: MeterFlag,
) {
    val label: String
        get() = when (flag) {
            MeterFlag.HIGH -> "HI"
            MeterFlag.LOW -> "LO"
            MeterFlag.NONE -> mmol?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        }
}

/**
 * The outcome of one RACP transfer: the records worth keeping, plus the highest sequence
 * number the meter sent (including skipped ones) so the next sync asks only for newer records.
 */
data class MeterTransfer(val records: List<MeterRecord>, val highestSequence: Int?) {

    /**
     * True when a result is timed more than a few minutes after [now]: the meter clock is ahead
     * (it must be set to local time; 轻糖生活 asks for Beijing time for the same reason).
     */
    fun hasFutureRecords(now: Long): Boolean = records.any { it.at > now + CLOCK_TOLERANCE_MS }

    companion object {

        /** RACP request for the next sync: all records the first time, then those after [lastSequence]. */
        fun request(lastSequence: Int?): ByteArray =
            if (lastSequence == null || lastSequence >= MAX_SEQUENCE) {
                GlucoseProfile.requestAllRecords()
            } else {
                GlucoseProfile.requestRecordsFrom(lastSequence + 1)
            }

        /**
         * Joins measurements with the meal from their context. Control-solution tests,
         * results without a time and results without a value (other than HI / LO) are dropped.
         */
        fun build(measurements: List<Measurement>, contexts: List<MeasurementContext>, zone: ZoneId): MeterTransfer {
            val latest = measurements.associateBy { it.sequence }.values
            val meals = contexts.associate { it.sequence to it.meal }
            val records = latest
                .filterNot { it.isControlSolution }
                .mapNotNull { toRecord(it, meals[it.sequence], zone) }
                .sortedBy { it.sequence }
            return MeterTransfer(records, latest.maxOfOrNull { it.sequence })
        }

        private fun toRecord(m: Measurement, meal: Meal?, zone: ZoneId): MeterRecord? {
            val time = m.time ?: return null
            val flag = when {
                m.isHigh -> MeterFlag.HIGH
                m.isLow -> MeterFlag.LOW
                else -> MeterFlag.NONE
            }
            if (m.mmol == null && flag == MeterFlag.NONE) return null
            val at = time.atZone(zone).toInstant().toEpochMilli()
            return MeterRecord(m.sequence, at, m.mmol?.let(::roundToTenth), meal, flag)
        }

        private const val MAX_SEQUENCE = 0xFFFF
        private const val CLOCK_TOLERANCE_MS = 10 * 60_000L
    }
}
