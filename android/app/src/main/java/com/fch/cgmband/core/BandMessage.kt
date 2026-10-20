package com.fch.cgmband.core

/**
 * JSON messages exchanged with the band app over Xiaomi interconnect.
 *
 * phone → band: {"type":"bg", value, unit, mmol, time, ts, trend, delta, connected}
 *               {"type":"history", ref, range:[low,high], points:[[minutesBeforeRef, mmol×10], ...]}
 * band → phone: {"type":"sync"} asks for the latest reading (and history) again.
 *
 * Encoded by hand to keep the core free of Android's org.json (unavailable in JVM tests).
 */
object BandMessage {

    /** Keeps each history message around 1 KB, small enough for one interconnect send. */
    const val MAX_HISTORY_POINTS = 100
    const val HISTORY_WINDOW_MS = 4 * 60 * 60_000L

    private const val MS_PER_MINUTE = 60_000L
    private val SYNC_PATTERN = Regex(""""type"\s*:\s*"sync"""")

    fun encodeReading(reading: GlucoseReading, trend: Trend, delta: Double?): String =
        listOf(
            "type" to string("bg"),
            "value" to string(reading.value),
            "unit" to string(reading.unit),
            "mmol" to number(reading.mmol),
            "time" to string(reading.time),
            "ts" to (reading.readingAt?.toString() ?: "null"),
            "trend" to string(trend.name),
            "delta" to number(delta),
            "connected" to reading.connected.toString(),
        ).joinToString(separator = ",", prefix = "{", postfix = "}") { (key, json) -> "${string(key)}:$json" }

    /**
     * The last [HISTORY_WINDOW_MS] before the newest point, as compact integer pairs
     * `[minutesBeforeRef, mmol×10]`, oldest first, thinned to at most [MAX_HISTORY_POINTS]
     * while always keeping the newest point.
     */
    fun encodeHistory(points: List<GlucosePoint>, range: TargetRange): String {
        val sorted = points.sortedBy { it.at }
        val ref = sorted.lastOrNull()?.at
        val pairs = if (ref == null) {
            emptyList()
        } else {
            thin(sorted.filter { ref - it.at <= HISTORY_WINDOW_MS })
                .map { "[${(ref - it.at) / MS_PER_MINUTE},${Math.round(it.mmol * 10)}]" }
        }
        return listOf(
            "type" to string("history"),
            "ref" to (ref?.toString() ?: "null"),
            "range" to "[${number(range.low)},${number(range.high)}]",
            "points" to pairs.joinToString(separator = ",", prefix = "[", postfix = "]"),
        ).joinToString(separator = ",", prefix = "{", postfix = "}") { (key, json) -> "${string(key)}:$json" }
    }

    fun isSyncRequest(message: String): Boolean = SYNC_PATTERN.containsMatchIn(message)

    /** Every k-th point counted back from the newest, so the latest reading is never dropped. */
    private fun thin(points: List<GlucosePoint>): List<GlucosePoint> {
        if (points.size <= MAX_HISTORY_POINTS) return points
        val step = (points.size + MAX_HISTORY_POINTS - 1) / MAX_HISTORY_POINTS
        val last = points.lastIndex
        return points.filterIndexed { i, _ -> (last - i) % step == 0 }
    }

    private fun number(x: Double?): String =
        if (x == null || x.isNaN() || x.isInfinite()) "null" else x.toString()

    private fun string(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
