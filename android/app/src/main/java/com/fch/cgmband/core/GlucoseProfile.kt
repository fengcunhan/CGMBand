package com.fch.cgmband.core

import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

/**
 * Bluetooth SIG Glucose Profile (GLP 1.0). This is what the Roche Accu-Chek meters supported
 * by the 轻糖生活 mini program speak (卓越金采 Performa Connect, 智航 Guide, 逸智 Instant,
 * 智航觅 Guide Me). See docs/meter-protocol.md. Pure parsing, no Android types.
 */
object GlucoseProfile {

    val SERVICE: UUID = sig(0x1808)
    val MEASUREMENT: UUID = sig(0x2A18)
    val MEASUREMENT_CONTEXT: UUID = sig(0x2A34)
    val RECORD_ACCESS: UUID = sig(0x2A52)
    val CLIENT_CONFIG: UUID = sig(0x2902)

    // Glucose Measurement flags
    private const val HAS_TIME_OFFSET = 0x01
    private const val HAS_CONCENTRATION = 0x02
    private const val UNIT_MOL_PER_L = 0x04
    private const val HAS_STATUS = 0x08
    private const val CONTEXT_FOLLOWS = 0x10

    // Glucose Measurement Context flags
    private const val HAS_CARBOHYDRATE = 0x01
    private const val HAS_MEAL = 0x02
    private const val HAS_EXTENDED_FLAGS = 0x80

    // Record Access Control Point
    private const val OP_REPORT_RECORDS = 0x01
    private const val OP_RESPONSE = 0x06
    private const val OPERATOR_ALL = 0x01
    private const val OPERATOR_AT_LEAST = 0x03
    private const val FILTER_SEQUENCE = 0x01

    /** kg/L → mg/dL. */
    private const val MGDL_PER_KG_PER_L = 100_000.0

    /** mol/L → mmol/L. */
    private const val MMOL_PER_MOL = 1_000.0

    /** mg/dL per mmol/L as the 轻糖生活 mini program converts, to match the meter display (the CGM side uses 18.0). */
    private const val METER_MGDL_PER_MMOL = 18.02

    private val SFLOAT_SPECIAL = setOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)
    private const val BASE_TIME_BYTES = 7
    private const val NOT_AVAILABLE = 0x0F

    /** RACP: report every stored record. */
    fun requestAllRecords(): ByteArray = bytes(OP_REPORT_RECORDS, OPERATOR_ALL)

    /** RACP: report records whose sequence number is at least [sequence]. */
    fun requestRecordsFrom(sequence: Int): ByteArray {
        require(sequence in 0..0xFFFF) { "sequence out of range: $sequence" }
        return bytes(OP_REPORT_RECORDS, OPERATOR_AT_LEAST, FILTER_SEQUENCE, sequence and 0xFF, sequence shr 8)
    }

    /** Decodes an IEEE 11073 16-bit SFLOAT; null for NaN, NRes, ±INF and the reserved value. */
    fun sfloat(raw: Int): Double? {
        val bits = raw and 0xFFFF
        if (bits in SFLOAT_SPECIAL) return null
        val mantissa = signed(bits and 0x0FFF, 12)
        val exponent = signed(bits shr 12, 4)
        return mantissa * 10.0.pow(exponent)
    }

    /** Parses a Glucose Measurement notification, or returns null when it is truncated. */
    fun parseMeasurement(data: ByteArray): Measurement? {
        val reader = Reader(data)
        val flags = reader.u8() ?: return null
        val sequence = reader.u16() ?: return null
        val base = reader.take(BASE_TIME_BYTES) ?: return null
        val offsetMinutes = if (flags has HAS_TIME_OFFSET) reader.s16() ?: return null else 0

        val sample = if (flags has HAS_CONCENTRATION) {
            val concentration = reader.u16() ?: return null
            val typeAndLocation = reader.u8() ?: return null
            Sample(
                mmol = sfloat(concentration)?.let { toMmol(it, flags has UNIT_MOL_PER_L) },
                type = reported(typeAndLocation and 0x0F),
                location = reported(typeAndLocation shr 4),
            )
        } else {
            Sample()
        }
        val status = if (flags has HAS_STATUS) reader.u16() ?: return null else 0

        return Measurement(
            sequence = sequence,
            time = baseTime(base)?.plusMinutes(offsetMinutes.toLong()),
            mmol = sample.mmol,
            sampleType = sample.type,
            sampleLocation = sample.location,
            status = status,
            contextFollows = flags has CONTEXT_FOLLOWS,
        )
    }

    private data class Sample(val mmol: Double? = null, val type: Int? = null, val location: Int? = null)

    /** A type / location nibble; 0 is reserved and 0xF means "not available". */
    private fun reported(nibble: Int): Int? = nibble.takeUnless { it == 0 || it == NOT_AVAILABLE }

    /** Parses a Glucose Measurement Context notification; only the meal is kept. */
    fun parseContext(data: ByteArray): MeasurementContext? {
        val reader = Reader(data)
        val flags = reader.u8() ?: return null
        val sequence = reader.u16() ?: return null
        if (flags has HAS_EXTENDED_FLAGS) reader.take(1) ?: return null
        if (flags has HAS_CARBOHYDRATE) reader.take(3) ?: return null // carbohydrate ID + SFLOAT
        val meal = if (flags has HAS_MEAL) Meal.of(reader.u8() ?: return null) else null
        return MeasurementContext(sequence, meal)
    }

    /** Parses a RACP response indication; other RACP messages (e.g. record counts) give null. */
    fun parseRecordAccessResponse(data: ByteArray): RecordAccessResponse? {
        if (data.size < 4 || data[0].toInt() and 0xFF != OP_RESPONSE) return null
        return RecordAccessResponse(requestOpCode = data[2].toInt() and 0xFF, code = data[3].toInt() and 0xFF)
    }

    private fun toMmol(value: Double, molPerL: Boolean): Double =
        if (molPerL) value * MMOL_PER_MOL else value * MGDL_PER_KG_PER_L / METER_MGDL_PER_MMOL

    /** Year, month or day 0 means the meter's clock was never set. */
    private fun baseTime(b: ByteArray): LocalDateTime? {
        val year = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val month = b[2].toInt() and 0xFF
        val day = b[3].toInt() and 0xFF
        if (year == 0 || month == 0 || day == 0) return null
        return try {
            LocalDateTime.of(year, month, day, b[4].toInt() and 0xFF, b[5].toInt() and 0xFF, b[6].toInt() and 0xFF)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun signed(value: Int, bits: Int): Int =
        if (value >= 1 shl (bits - 1)) value - (1 shl bits) else value

    private infix fun Int.has(flag: Int) = this and flag != 0

    private fun sig(short: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "%08x-0000-1000-8000-00805f9b34fb", short))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Little-endian cursor; every read returns null instead of running past the end. */
    private class Reader(private val data: ByteArray) {
        private var position = 0

        fun take(count: Int): ByteArray? {
            if (position + count > data.size) return null
            return data.copyOfRange(position, position + count).also { position += count }
        }

        fun u8(): Int? = take(1)?.let { it[0].toInt() and 0xFF }

        fun u16(): Int? = take(2)?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }

        fun s16(): Int? = u16()?.let { signed(it, 16) }
    }
}

/**
 * One Glucose Measurement record.
 *
 * @property time base time plus time offset, on the meter's own clock (which has no time
 *   zone); null when the clock was never set
 * @property mmol concentration in mmol/L, unrounded; null when absent or not a number
 * @property sampleType 1–10 per GLP (10 = control solution), null when not reported
 * @property sampleLocation 1–4 per GLP (4 = control solution), null when not reported
 * @property status Sensor Status Annunciation bits, 0 when not reported
 */
data class Measurement(
    val sequence: Int,
    val time: LocalDateTime?,
    val mmol: Double?,
    val sampleType: Int?,
    val sampleLocation: Int?,
    val status: Int,
    val contextFollows: Boolean,
) {
    val isControlSolution: Boolean
        get() = sampleType == TYPE_CONTROL_SOLUTION || sampleLocation == LOCATION_CONTROL_SOLUTION

    val isHigh: Boolean get() = status and STATUS_TOO_HIGH != 0

    val isLow: Boolean get() = status and STATUS_TOO_LOW != 0

    companion object {
        const val TYPE_CONTROL_SOLUTION = 10
        const val LOCATION_CONTROL_SOLUTION = 4
        const val STATUS_TOO_HIGH = 0x0020
        const val STATUS_TOO_LOW = 0x0040
    }
}

data class MeasurementContext(val sequence: Int, val meal: Meal?)

/** Meal codes of the Glucose Measurement Context. */
enum class Meal(val code: Int) {
    BEFORE_MEAL(1),
    AFTER_MEAL(2),
    FASTING(3),
    CASUAL(4),
    BEDTIME(5),
    ;

    companion object {
        fun of(code: Int): Meal? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The meter's answer to a RACP request. [finished] covers "no records found", which is how
 * a meter with nothing new ends an incremental sync.
 */
data class RecordAccessResponse(val requestOpCode: Int, val code: Int) {
    val finished: Boolean get() = code == SUCCESS || code == NO_RECORDS

    /** The meter cannot filter by sequence number; asking for all records still works. */
    val filterUnsupported: Boolean get() = code in FILTER_ERRORS

    private companion object {
        const val SUCCESS = 0x01
        const val NO_RECORDS = 0x06
        val FILTER_ERRORS = setOf(0x03, 0x04, 0x05, 0x09) // invalid / unsupported operator or operand
    }
}
