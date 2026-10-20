package com.fch.cgmband

import android.content.Context
import com.fch.cgmband.core.BandMessage

/**
 * Everything the band app needs to draw its screen: the latest "bg" reading, followed by the
 * last four hours of history for its chart. Call on the main thread.
 */
object BandSync {

    /** Margin so a reading stamped slightly ahead of the phone clock is still included. */
    private const val CLOCK_SLACK_MS = 10 * 60_000L

    /** Push [message] (a "bg" reading), then the history; [onResult] reports the reading push. */
    fun pushReading(context: Context, message: String, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        BandBridge.send(app, message) { ok ->
            if (ok) {
                SyncStatus.bandSynced()
                pushHistory(app)
            }
            onResult(ok)
        }
    }

    /** Re-send what the band should show, e.g. when it asks via {"type":"sync"}. */
    fun pushLatest(context: Context, onResult: (Boolean) -> Unit = {}) {
        val message = ReadingRepository.latestMessage(context)
        if (message == null) {
            pushHistory(context)
            onResult(false)
            return
        }
        pushReading(context, message, onResult)
    }

    /** History (read from the local database) plus the current target range. */
    fun pushHistory(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val from = now - BandMessage.HISTORY_WINDOW_MS - CLOCK_SLACK_MS
        ReadingDatabase.get(app).load(from, now + CLOCK_SLACK_MS) { points ->
            BandBridge.send(app, BandMessage.encodeHistory(points, DisplaySettings.targetRange(app)))
        }
    }
}
