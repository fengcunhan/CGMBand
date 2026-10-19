package com.fch.cgmband.core

/**
 * JSON messages exchanged with the band app over Xiaomi interconnect.
 *
 * phone → band: {"type":"bg", value, unit, mmol, time, ts, trend, delta, connected}
 * band → phone: {"type":"sync"} asks for the latest reading again.
 *
 * Encoded by hand to keep the core free of Android's org.json (unavailable in JVM tests).
 */
object BandMessage {

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

    fun isSyncRequest(message: String): Boolean = SYNC_PATTERN.containsMatchIn(message)

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
