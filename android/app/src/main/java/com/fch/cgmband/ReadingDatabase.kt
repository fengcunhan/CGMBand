package com.fch.cgmband

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fch.cgmband.core.GlucosePoint
import com.fch.cgmband.core.GlucoseReading
import java.util.concurrent.Executors

/**
 * Local history of timed readings for the dashboard chart (iCan's own history is not
 * accessible, so the chart fills up from the moment this app starts listening).
 * All disk work runs on one background thread; results are delivered on the main thread.
 */
class ReadingDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE readings (" +
                "reading_at INTEGER PRIMARY KEY, " +
                "mmol REAL, " +
                "value TEXT NOT NULL, " +
                "unit TEXT NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Stores [reading] (replacing a correction for the same minute); untimed readings are skipped. */
    fun save(reading: GlucoseReading) {
        val at = reading.readingAt ?: return
        val values = ContentValues().apply {
            put("reading_at", at)
            if (reading.mmol != null) put("mmol", reading.mmol) else putNull("mmol")
            put("value", reading.value)
            put("unit", reading.unit)
        }
        io.execute {
            runCatching {
                writableDatabase.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                writableDatabase.delete("readings", "reading_at < ?", arrayOf((at - RETENTION_MS).toString()))
            }.onFailure { Log.w(TAG, "Failed to store reading", it) }
        }
    }

    /** Numeric readings with from <= reading_at <= to, oldest first, delivered on the main thread. */
    fun load(from: Long, to: Long, onResult: (List<GlucosePoint>) -> Unit) {
        io.execute {
            val points = runCatching { query(from, to) }
                .onFailure { Log.w(TAG, "Failed to load readings", it) }
                .getOrDefault(emptyList())
            mainHandler.post { onResult(points) }
        }
    }

    private fun query(from: Long, to: Long): List<GlucosePoint> =
        readableDatabase.rawQuery(
            "SELECT reading_at, mmol FROM readings WHERE reading_at BETWEEN ? AND ? AND mmol IS NOT NULL " +
                "ORDER BY reading_at",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            generateSequence { if (cursor.moveToNext()) GlucosePoint(cursor.getLong(0), cursor.getDouble(1)) else null }
                .toList()
        }

    companion object {
        private const val TAG = "CgmDatabase"
        private const val NAME = "readings.db"
        private const val VERSION = 1
        private const val RETENTION_MS = 14 * 24 * 60 * 60_000L

        private val io = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: ReadingDatabase? = null

        fun get(context: Context): ReadingDatabase =
            instance ?: synchronized(this) {
                instance ?: ReadingDatabase(context.applicationContext).also { instance = it }
            }
    }
}
