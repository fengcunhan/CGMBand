package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val thresholds = AlertThresholds(urgentLow = 3.1, low = 3.9, high = 13.9)

    /** Reading taken at minute [m] and evaluated right away. */
    private fun at(m: Long?, mmol: Double?) = reading(m, mmol)

    private fun nowOf(m: Long) = T0 + m * MINUTE

    private fun evaluate(state: AlertState, m: Long, mmol: Double?, trend: Trend = Trend.FLAT) =
        AlertPolicy.evaluate(state, at(m, mmol), trend, thresholds, nowOf(m))

    @Test
    fun `classifies zones including LO HI and unknown`() {
        assertEquals(GlucoseZone.URGENT_LOW, AlertPolicy.zone(at(0, 3.0), thresholds))
        assertEquals(GlucoseZone.LOW, AlertPolicy.zone(at(0, 3.5), thresholds))
        assertEquals(GlucoseZone.IN_RANGE, AlertPolicy.zone(at(0, 3.9), thresholds))
        assertEquals(GlucoseZone.IN_RANGE, AlertPolicy.zone(at(0, 13.9), thresholds))
        assertEquals(GlucoseZone.HIGH, AlertPolicy.zone(at(0, 14.0), thresholds))
        assertEquals(GlucoseZone.URGENT_LOW, AlertPolicy.zone(at(0, null).copy(value = "LO"), thresholds))
        assertEquals(GlucoseZone.HIGH, AlertPolicy.zone(at(0, null).copy(value = "HI"), thresholds))
        assertEquals(GlucoseZone.UNKNOWN, AlertPolicy.zone(at(0, null).copy(value = "ERR"), thresholds))
    }

    @Test
    fun `no alert while in range`() {
        val (state, alert) = evaluate(AlertState(), 0, 6.0)

        assertNull(alert)
        assertEquals(GlucoseZone.IN_RANGE, state.zone)
    }

    @Test
    fun `entering low alerts once with value arrow and time`() {
        val (state, alert) = evaluate(AlertState(), 0, 3.6, Trend.SINGLE_DOWN)

        assertNotNull(alert)
        assertEquals("低血糖 3.6", alert!!.title)
        assertTrue(alert.message.contains("↓"))
        assertEquals(GlucoseZone.LOW, state.zone)
        assertEquals(nowOf(0), state.lastAlertAt)
    }

    @Test
    fun `staying low repeats only after the repeat interval`() {
        val (s1, _) = evaluate(AlertState(), 0, 3.6)

        val (s2, early) = evaluate(s1, 3, 3.5)
        assertNull(early)

        val (_, repeat) = evaluate(s2, 21, 3.5)
        assertNotNull(repeat)
    }

    @Test
    fun `worsening to urgent low alerts immediately`() {
        val (s1, _) = evaluate(AlertState(), 0, 3.6)

        val (s2, alert) = evaluate(s1, 3, 2.9)

        assertEquals("血糖过低 2.9", alert!!.title)
        assertEquals(GlucoseZone.URGENT_LOW, s2.zone)
    }

    @Test
    fun `improving from urgent low to low does not alert but keeps the repeat timer`() {
        val (s1, _) = evaluate(AlertState(), 0, 2.9)

        val (s2, alert) = evaluate(s1, 3, 3.4)

        assertNull(alert)
        assertEquals(GlucoseZone.LOW, s2.zone)
        assertEquals(nowOf(0), s2.lastAlertAt)
    }

    @Test
    fun `back in range resets so the next excursion alerts again`() {
        val (s1, _) = evaluate(AlertState(), 0, 14.5)
        val (s2, _) = evaluate(s1, 3, 9.0)
        assertEquals(GlucoseZone.IN_RANGE, s2.zone)
        assertNull(s2.lastAlertAt)

        val (_, alert) = evaluate(s2, 6, 14.2)
        assertEquals("高血糖 14.2", alert!!.title)
    }

    @Test
    fun `stale or untimed readings never alert and leave state untouched`() {
        val state = AlertState()

        val stale = AlertPolicy.evaluate(state, at(0, 2.5), Trend.FLAT, thresholds, nowOf(16))
        assertNull(stale.second)
        assertEquals(state, stale.first)

        val untimed = AlertPolicy.evaluate(state, at(null, 2.5), Trend.FLAT, thresholds, nowOf(0))
        assertNull(untimed.second)
        assertEquals(state, untimed.first)
    }

    @Test
    fun `unknown values leave state untouched`() {
        val (s1, _) = evaluate(AlertState(), 0, 3.6)

        val (s2, alert) = AlertPolicy.evaluate(s1, at(3, null).copy(value = "ERR"), Trend.NONE, thresholds, nowOf(3))

        assertNull(alert)
        assertEquals(s1, s2)
    }

    @Test
    fun `thresholds must be ordered`() {
        assertNull(AlertThresholds.of(3.9, 3.1, 13.9))
        assertNull(AlertThresholds.of(3.1, 3.9, 3.0))
        assertEquals(thresholds, AlertThresholds.of(3.1, 3.9, 13.9))
    }
}
