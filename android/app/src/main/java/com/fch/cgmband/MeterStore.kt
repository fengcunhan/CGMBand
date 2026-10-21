package com.fch.cgmband

import android.content.Context

/** The bound fingerstick meter and how far its records have been synced. */
object MeterStore {

    /**
     * @property lastSequence highest record sequence number already received, null before the first sync
     * @property lastSyncAt epoch millis of the last successful sync
     */
    data class BoundMeter(
        val address: String,
        val name: String,
        val lastSequence: Int?,
        val lastSyncAt: Long?,
        val lastNewRecords: Int,
    )

    private const val PREFS = "cgm_meter"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_SEQUENCE = "last_sequence"
    private const val KEY_SYNC_AT = "last_sync_at"
    private const val KEY_NEW_RECORDS = "last_new_records"

    fun bound(context: Context): BoundMeter? {
        val prefs = prefs(context)
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return BoundMeter(
            address = address,
            name = prefs.getString(KEY_NAME, null) ?: address,
            lastSequence = prefs.getInt(KEY_SEQUENCE, -1).takeIf { it >= 0 },
            lastSyncAt = prefs.getLong(KEY_SYNC_AT, -1L).takeIf { it >= 0 },
            lastNewRecords = prefs.getInt(KEY_NEW_RECORDS, 0),
        )
    }

    /** Binding another meter starts its sync from scratch. */
    @Synchronized
    fun bind(context: Context, address: String, name: String) {
        if (bound(context)?.address == address) return
        prefs(context).edit().clear()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    @Synchronized
    fun recordSync(context: Context, address: String, highestSequence: Int?, newRecords: Int, at: Long) {
        val current = bound(context)?.takeIf { it.address == address } ?: return
        val sequence = listOfNotNull(current.lastSequence, highestSequence).maxOrNull()
        prefs(context).edit()
            .apply { if (sequence != null) putInt(KEY_SEQUENCE, sequence) }
            .putLong(KEY_SYNC_AT, at)
            .putInt(KEY_NEW_RECORDS, newRecords)
            .apply()
    }

    fun unbind(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
