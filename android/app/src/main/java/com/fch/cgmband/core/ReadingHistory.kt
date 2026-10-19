package com.fch.cgmband.core

/** Immutable rolling window of recent timed readings, sorted by measurement time. */
object ReadingHistory {

    const val RETENTION_MS = 60 * 60_000L

    /**
     * Returns [history] itself when [reading] adds nothing new (duplicate, or no known
     * measurement time), otherwise a new list.
     */
    fun append(history: List<GlucoseReading>, reading: GlucoseReading): List<GlucoseReading> {
        val at = reading.readingAt ?: return history
        val existing = history.firstOrNull { it.readingAt == at }
        if (existing != null && existing.value == reading.value && existing.unit == reading.unit) {
            return history
        }

        val newest = maxOf(at, history.mapNotNull { it.readingAt }.maxOrNull() ?: at)
        return (history.filter { it.readingAt != at } + reading)
            .filter { newest - it.readingAt!! <= RETENTION_MS }
            .sortedBy { it.readingAt }
    }
}
