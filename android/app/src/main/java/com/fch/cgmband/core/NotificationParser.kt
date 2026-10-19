package com.fch.cgmband.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Turns the texts found in the iCan notification's custom layout (keyed by view
 * resource entry name) into a [GlucoseReading].
 *
 * View ids differ between app versions, so each field lists every id seen so far:
 * v2.5.x uses tv_cgm_*, v2.4.x used new_blood_*.
 */
object NotificationParser {

    private val VALUE_IDS = listOf("tv_cgm_value", "new_blood_tv")
    private val UNIT_IDS = listOf("tv_cgm_unit", "new_blood_unit_tv")
    private val TIME_IDS = listOf("tv_cgm_time")

    private val PLACEHOLDER_VALUES = setOf("", "-", "--", "---")
    private val DISCONNECTED_MARKERS = listOf("未连接", "断开")
    private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

    private const val MMOL_UNIT = "mmol/L"
    private const val MGDL_UNIT = "mg/dL"

    /** No CGM reports more than this in mmol/L, so a larger bare number must be mg/dL. */
    private const val MAX_PLAUSIBLE_MMOL = 33.3

    /** A reading time further than this from the post time is not trusted. */
    private const val MAX_TIME_SKEW_MS = 6 * 60 * 60_000L

    /**
     * @param postedAt when the notification was posted/updated ([android.service.notification.StatusBarNotification.getPostTime]);
     *   anchors the "HH:mm" text to a date.
     */
    fun parse(texts: Map<String, String>, postedAt: Long, zone: ZoneId): GlucoseReading? {
        val value = firstText(texts, VALUE_IDS)?.takeUnless { it in PLACEHOLDER_VALUES } ?: return null
        val unit = firstText(texts, UNIT_IDS)?.takeIf { it.isNotEmpty() } ?: guessUnit(value)
        val time = firstText(texts, TIME_IDS).orEmpty()

        return GlucoseReading(
            value = value,
            unit = unit,
            mmol = toMmol(value, unit),
            time = time,
            readingAt = readingTime(time, postedAt, zone),
            postedAt = postedAt,
            connected = !isSensorDisconnected(texts),
        )
    }

    fun isSensorDisconnected(texts: Map<String, String>): Boolean =
        texts.values.any { text -> DISCONNECTED_MARKERS.any(text::contains) }

    private fun firstText(texts: Map<String, String>, ids: List<String>): String? =
        ids.firstNotNullOfOrNull { texts[it]?.trim() }

    private fun guessUnit(value: String): String {
        val number = value.toDoubleOrNull()
        return if (number != null && number > MAX_PLAUSIBLE_MMOL) MGDL_UNIT else MMOL_UNIT
    }

    private fun toMmol(value: String, unit: String): Double? {
        val number = value.toDoubleOrNull() ?: return null
        return if (unit.contains("mg", ignoreCase = true)) roundToTenth(number / MGDL_PER_MMOL) else number
    }

    /** Picks the date (yesterday / today / tomorrow) that puts "HH:mm" closest to [postedAt]. */
    private fun readingTime(time: String, postedAt: Long, zone: ZoneId): Long? {
        val match = TIME_PATTERN.findAll(time).lastOrNull() ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null

        val postedDay = Instant.ofEpochMilli(postedAt).atZone(zone).toLocalDate()
        val closest = (-1L..1L)
            .map { offset -> postedDay.plusDays(offset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() }
            .minBy { abs(it - postedAt) }
        return closest.takeIf { abs(it - postedAt) <= MAX_TIME_SKEW_MS }
    }
}
