package com.fch.cgmband

import java.util.concurrent.CopyOnWriteArrayList

/** When the band last accepted a reading, for the dashboard's "手环已同步" chip. In-memory only. */
object SyncStatus {

    @Volatile
    var lastBandSyncAt: Long? = null
        private set

    private val observers = CopyOnWriteArrayList<() -> Unit>()

    fun bandSynced(at: Long = System.currentTimeMillis()) {
        lastBandSyncAt = at
        observers.forEach { it() }
    }

    fun observe(observer: () -> Unit) = observers.add(observer)

    fun stopObserving(observer: () -> Unit) = observers.remove(observer)
}
