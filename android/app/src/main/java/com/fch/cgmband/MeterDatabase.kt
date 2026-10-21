package com.fch.cgmband

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fch.cgmband.core.Fingerstick
import com.fch.cgmband.core.FingerstickTag
import com.fch.cgmband.core.Meal
import com.fch.cgmband.core.MeterFlag
import com.fch.cgmband.core.MeterRecord
import java.util.concurrent.Executors

/**
 * Fingerstick results synced from the bound meter, keyed by (meter, sequence number) so a
 * record the meter sends twice is stored once, and a re-sync never overwrites the user's tag. Kept apart from the CGM history: these are
 * occasional spot checks, not a continuous line, and are never pushed to the band.
 * All disk work runs on one background thread; results are delivered on the main thread.
 */
class MeterDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE meter_readings (" +
                "device TEXT NOT NULL, " +
                "sequence INTEGER NOT NULL, " +
                "reading_at INTEGER NOT NULL, " +
                "mmol REAL, " +
                "meal INTEGER, " +
                "flag TEXT NOT NULL, " +
                "tag TEXT, " +
                "PRIMARY KEY (device, sequence))",
        )
        db.execSQL("CREATE INDEX meter_readings_at ON meter_readings (reading_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Stores [records] from meter [device]; [onDone] gets how many were not stored before,
     * or null when nothing could be written (the caller must then not advance its sync position).
     */
    fun save(device: String, records: List<MeterRecord>, onDone: (Int?) -> Unit) {
        io.execute {
            val inserted = runCatching { insertNew(device, records) }
                .onFailure { Log.w(TAG, "Failed to store meter readings", it) }
                .getOrNull()
            mainHandler.post { onDone(inserted) }
        }
    }

    /** The newest [limit] results of every meter, newest first. */
    fun recent(limit: Int, onResult: (List<Fingerstick>) -> Unit) =
        select("$COLUMNS ORDER BY reading_at DESC LIMIT ?", arrayOf(limit.toString()), onResult)

    /** Results with from <= reading_at <= to, oldest first, for the chart. */
    fun between(from: Long, to: Long, onResult: (List<Fingerstick>) -> Unit) =
        select("$COLUMNS WHERE reading_at BETWEEN ? AND ? ORDER BY reading_at", arrayOf(from.toString(), to.toString()), onResult)

    /** Sets (or with null clears) when a result was taken; [onDone] gets false when it could not be saved. */
    fun setTag(device: String, sequence: Int, tag: FingerstickTag?, onDone: (Boolean) -> Unit) {
        io.execute {
            val saved = runCatching {
                val values = ContentValues().apply { if (tag != null) put("tag", tag.name) else putNull("tag") }
                writableDatabase.update(
                    "meter_readings", values, "device = ? AND sequence = ?", arrayOf(device, sequence.toString()),
                ) > 0
            }.onFailure { Log.w(TAG, "Failed to tag meter reading", it) }.getOrDefault(false)
            mainHandler.post { onDone(saved) }
        }
    }

    private fun select(sql: String, args: Array<String>, onResult: (List<Fingerstick>) -> Unit) {
        io.execute {
            val rows = runCatching {
                readableDatabase.rawQuery(sql, args).use { cursor ->
                    generateSequence { if (cursor.moveToNext()) fingerstick(cursor) else null }.toList()
                }
            }.onFailure { Log.w(TAG, "Failed to load meter readings", it) }.getOrDefault(emptyList())
            mainHandler.post { onResult(rows) }
        }
    }

    private fun insertNew(device: String, records: List<MeterRecord>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = records.count { record ->
                db.insertWithOnConflict("meter_readings", null, values(device, record), SQLiteDatabase.CONFLICT_IGNORE) != -1L
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    private fun values(device: String, record: MeterRecord) = ContentValues().apply {
        put("device", device)
        put("sequence", record.sequence)
        put("reading_at", record.at)
        if (record.mmol != null) put("mmol", record.mmol) else putNull("mmol")
        if (record.meal != null) put("meal", record.meal.code) else putNull("meal")
        put("flag", record.flag.name)
    }

    private fun fingerstick(cursor: Cursor) = Fingerstick(
        device = cursor.getString(0),
        record = MeterRecord(
            sequence = cursor.getInt(1),
            at = cursor.getLong(2),
            mmol = if (cursor.isNull(3)) null else cursor.getDouble(3),
            meal = if (cursor.isNull(4)) null else Meal.of(cursor.getInt(4)),
            flag = enumOrNull<MeterFlag>(cursor.getString(5)) ?: MeterFlag.NONE,
        ),
        tag = if (cursor.isNull(6)) null else enumOrNull<FingerstickTag>(cursor.getString(6)),
    )

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    companion object {
        private const val TAG = "CgmMeterDb"
        private const val NAME = "meter.db"
        private const val VERSION = 1
        private const val COLUMNS = "SELECT device, sequence, reading_at, mmol, meal, flag, tag FROM meter_readings"

        private val io = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MeterDatabase? = null

        fun get(context: Context): MeterDatabase =
            instance ?: synchronized(this) {
                instance ?: MeterDatabase(context.applicationContext).also { instance = it }
            }
    }
}
