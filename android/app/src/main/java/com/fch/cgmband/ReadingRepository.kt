package com.fch.cgmband

import android.content.Context
import com.fch.cgmband.core.GlucoseReading
import com.fch.cgmband.core.ReadingState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide holder of [ReadingState]. State is replaced, never mutated, and the last
 * band message is persisted so a sync request after a process restart can still be answered.
 */
object ReadingRepository {

    private const val PREFS = "cgm_band"
    private const val KEY_MESSAGE = "last_band_message"

    @Volatile
    var state: ReadingState = ReadingState()
        private set

    private val observers = CopyOnWriteArrayList<() -> Unit>()

    /** Returns the band message to push when [reading] changes what the band should show, else null. */
    fun accept(context: Context, reading: GlucoseReading): String? =
        transition(context) { ReadingState.accept(it, reading) }

    /** Returns the band message to push when the band must be told the data is no longer live, else null. */
    fun markDisconnected(context: Context): String? =
        transition(context) { ReadingState.disconnected(it) }

    fun latestMessage(context: Context): String? =
        state.message ?: prefs(context).getString(KEY_MESSAGE, null)

    fun observe(observer: () -> Unit) = observers.add(observer)

    fun stopObserving(observer: () -> Unit) = observers.remove(observer)

    @Synchronized
    private fun transition(context: Context, next: (ReadingState) -> ReadingState?): String? {
        val updated = next(state) ?: return null
        state = updated
        prefs(context).edit().putString(KEY_MESSAGE, updated.message).apply()
        observers.forEach { it() }
        return updated.message
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
