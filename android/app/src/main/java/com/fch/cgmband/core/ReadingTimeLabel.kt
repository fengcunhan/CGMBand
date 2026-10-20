package com.fch.cgmband.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "刚刚 10-20 10:24" style label: how long ago, then the measurement date and time. */
object ReadingTimeLabel {

    private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    fun format(readingAt: Long?, now: Long, zone: ZoneId): String {
        if (readingAt == null) return "时间未知"
        val age = now - readingAt
        val relative = when {
            age < MINUTE_MS -> "刚刚"
            age < HOUR_MS -> "${age / MINUTE_MS}分钟前"
            age < DAY_MS -> "${age / HOUR_MS}小时前"
            else -> "${age / DAY_MS}天前"
        }
        return "$relative ${FORMAT.format(Instant.ofEpochMilli(readingAt).atZone(zone))}"
    }
}
