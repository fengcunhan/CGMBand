package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLineTest {

    @Test
    fun `waiting when there is no reading yet`() {
        assertEquals("等待 iCan 数据", StatusLine.format(ReadingState()))
    }

    @Test
    fun `shows value unit arrow delta and time`() {
        val state = listOf(reading(0, 6.0), reading(3, 6.3).copy(time = "17:24"))
            .fold(ReadingState()) { s, r -> ReadingState.accept(s, r) ?: s }

        assertEquals("6.3 mmol/L ↗ +0.3 · 17:24", StatusLine.format(state))
    }

    @Test
    fun `marks unknown time and lost sensor`() {
        val unknownTime = ReadingState.accept(ReadingState(), reading(null, 6.0))!!
        assertEquals("6.0 mmol/L · 时间未知", StatusLine.format(unknownTime))

        val lost = ReadingState.disconnected(ReadingState.accept(ReadingState(), reading(0, 6.0).copy(time = "17:00"))!!)!!
        assertEquals("6.0 mmol/L · 17:00 · 传感器未连接", StatusLine.format(lost))
    }
}
