package com.fch.cgmband.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthetic two days of CGM readings for README screenshots, so no real glucose data is
 * published. Deterministic: a flat night around 5.6 mmol/L, a rise after each meal (some
 * above the default 7.8 target), and small wobbles; yesterday's meals differ slightly.
 */
object DemoData {

    private const val CADENCE_MS = 3 * 60_000L
    private const val SPAN_MS = 49 * 60 * 60_000L
    private const val BASELINE = 5.6

    /** Meal start (minute of day), peak height (mmol/L) for today and yesterday. */
    private data class Meal(val startMinute: Double, val today: Double, val yesterday: Double)

    private val MEALS = listOf(
        Meal(7 * 60.0 + 30, 2.6, 2.1),
        Meal(12 * 60.0 + 10, 3.3, 2.4),
        Meal(18 * 60.0 + 40, 2.2, 3.0),
    )

    fun points(now: Long, zone: ZoneId): List<GlucosePoint> {
        val end = now - now % CADENCE_MS
        val start = end - SPAN_MS
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return generateSequence(start) { it + CADENCE_MS }
            .takeWhile { it <= end }
            .map { at ->
                val local = Instant.ofEpochMilli(at).atZone(zone)
                val minuteOfDay = local.hour * 60.0 + local.minute
                val isToday = local.toLocalDate() == today
                GlucosePoint(at, roundToTenth(glucoseAt(at, minuteOfDay, isToday)))
            }
            .toList()
    }

    /** Readings for the last few points fed through [ReadingState], so trend/delta/message are real. */
    fun state(points: List<GlucosePoint>, zone: ZoneId): ReadingState =
        points.takeLast(6).fold(ReadingState()) { state, p ->
            val time = Instant.ofEpochMilli(p.at).atZone(zone)
            val reading = GlucoseReading(
                value = String.format(java.util.Locale.US, "%.1f", p.mmol),
                unit = "mmol/L",
                mmol = p.mmol,
                time = String.format(java.util.Locale.US, "%02d:%02d", time.hour, time.minute),
                readingAt = p.at,
                postedAt = p.at,
                connected = true,
            )
            ReadingState.accept(state, reading) ?: state
        }

    private fun glucoseAt(at: Long, minuteOfDay: Double, isToday: Boolean): Double {
        val meals = MEALS.sumOf { meal ->
            val sinceMeal = minuteOfDay - meal.startMinute
            mealResponse(sinceMeal, if (isToday) meal.today else meal.yesterday)
        }
        val minutes = at / 60_000.0
        val wobble = 0.22 * sin(minutes / 37.0) + 0.12 * sin(minutes / 11.0 + 1.3)
        val dawn = 0.4 * bump(minuteOfDay, centre = 5 * 60.0, width = 70.0)
        return (BASELINE + meals + wobble + dawn).coerceIn(3.4, 12.5)
    }

    /** Rises for ~50 minutes after a meal, then decays over ~2.5 hours. */
    private fun mealResponse(sinceMeal: Double, peak: Double): Double = when {
        sinceMeal < 0 || sinceMeal > 240 -> 0.0
        sinceMeal < 50 -> peak * (sinceMeal / 50.0)
        else -> peak * exp(-(sinceMeal - 50) / 55.0)
    }

    private fun bump(x: Double, centre: Double, width: Double): Double {
        val d = (x - centre) / width
        return exp(-d * d)
    }
}
