package com.fch.cgmband.core

/**
 * What the band should be showing, plus the history needed for the trend.
 * Transitions return a new state, or null when nothing changed for the band.
 */
data class ReadingState(
    val history: List<GlucoseReading> = emptyList(),
    val latest: GlucoseReading? = null,
    val message: String? = null,
) {
    /** No trend for a reading whose measurement time is unknown: it cannot be placed in the history. */
    val trend: Trend
        get() = if (latest?.readingAt != null) TrendCalculator.trend(history) else Trend.NONE

    val delta: Double?
        get() = if (latest?.readingAt != null) TrendCalculator.delta(history) else null

    companion object {

        fun accept(state: ReadingState, reading: GlucoseReading): ReadingState? {
            val previous = state.latest
            if (previous != null && isOlder(reading, previous)) return null

            val sameReading = previous != null &&
                previous.value == reading.value && previous.unit == reading.unit && previous.time == reading.time
            if (sameReading && previous?.connected == reading.connected) return null

            val history = if (sameReading) state.history else ReadingHistory.append(state.history, reading)
            val latest = if (sameReading) previous!!.copy(connected = reading.connected) else reading
            return withMessage(ReadingState(history, latest))
        }

        /** The sensor or the CGM app went away: keep the last value but tell the band it is no longer live. */
        fun disconnected(state: ReadingState): ReadingState? {
            val latest = state.latest?.takeIf { it.connected } ?: return null
            return withMessage(state.copy(latest = latest.copy(connected = false)))
        }

        private fun isOlder(reading: GlucoseReading, previous: GlucoseReading): Boolean {
            val at = reading.readingAt ?: return false
            val previousAt = previous.readingAt ?: return false
            return at < previousAt
        }

        private fun withMessage(state: ReadingState): ReadingState =
            state.copy(message = BandMessage.encodeReading(state.latest!!, state.trend, state.delta))
    }
}
