package com.fch.cgmband.core

import java.util.Locale

/** One-line summary for the phone's ongoing notification, e.g. "6.3 mmol/L ↗ +0.3 · 17:24". */
object StatusLine {

    fun format(state: ReadingState): String {
        val latest = state.latest ?: return "等待 iCan 数据"
        val reading = listOfNotNull(
            "${latest.value} ${latest.unit}",
            state.trend.arrow.takeIf { it.isNotEmpty() },
            state.delta?.let { String.format(Locale.US, "%+.1f", it) },
        ).joinToString(" ")
        val time = if (latest.readingAt == null) "时间未知" else latest.time
        val sensor = if (latest.connected) null else "传感器未连接"
        return listOfNotNull(reading, time, sensor).joinToString(" · ")
    }
}

val Trend.arrow: String
    get() = when (this) {
        Trend.DOUBLE_UP -> "⇈"
        Trend.SINGLE_UP -> "↑"
        Trend.FORTY_FIVE_UP -> "↗"
        Trend.FLAT -> "→"
        Trend.FORTY_FIVE_DOWN -> "↘"
        Trend.SINGLE_DOWN -> "↓"
        Trend.DOUBLE_DOWN -> "⇊"
        Trend.NONE -> ""
    }
