package com.fch.cgmband

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small in-memory event log shown on the main screen, so problems are visible on the phone.
 * Repeated identical events collapse into one line with a counter. Entries marked
 * sensitive (glucose values) stay in memory and never reach logcat / bug reports.
 */
object EventLog {

    private const val TAG = "CgmBand"
    private const val MAX_ENTRIES = 40

    private data class Entry(val stamp: String, val message: String, val count: Int) {
        override fun toString() = if (count > 1) "$stamp  $message (×$count)" else "$stamp  $message"
    }

    @Volatile
    private var log: List<Entry> = emptyList()

    val entries: List<String>
        get() = log.map(Entry::toString)

    private val observers = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun add(message: String, sensitive: Boolean = false) {
        if (!sensitive) Log.i(TAG, message)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val head = log.firstOrNull()
        log = if (head != null && head.message == message) {
            listOf(head.copy(stamp = stamp, count = head.count + 1)) + log.drop(1)
        } else {
            (listOf(Entry(stamp, message, 1)) + log).take(MAX_ENTRIES)
        }
        observers.forEach { it() }
    }

    fun observe(observer: () -> Unit) = observers.add(observer)

    fun stopObserving(observer: () -> Unit) = observers.remove(observer)
}
