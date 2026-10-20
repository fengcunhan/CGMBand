package com.fch.cgmband

import android.content.Context
import com.fch.cgmband.core.BandMessage

/**
 * Fans the latest "bg" reading and the four-hour history out to every display: the band app
 * and, when one is connected, the Mac menu-bar app. Call on the main thread.
 */
object BandSync {

    /** Margin so a reading stamped slightly ahead of the phone clock is still included. */
    private const val CLOCK_SLACK_MS = 10 * 60_000L

    /** Push [message] (a "bg" reading) and the history; [onResult] reports the band push. */
    fun pushReading(context: Context, message: String, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        DesktopLink.send(message)
        BandBridge.send(app, message) { ok ->
            if (ok) SyncStatus.bandSynced()
            onResult(ok)
        }
        pushHistory(app)
    }

    /** Re-send what the displays should show, e.g. when the band asks via {"type":"sync"}. */
    fun pushLatest(context: Context, onResult: (Boolean) -> Unit = {}) {
        val message = ReadingRepository.latestMessage(context)
        if (message == null) {
            pushHistory(context)
            onResult(false)
            return
        }
        pushReading(context, message, onResult)
    }

    /** History (read from the local database) plus the current target range, to every display. */
    fun pushHistory(context: Context) {
        loadHistory(context) { history ->
            BandBridge.send(context.applicationContext, history)
            DesktopLink.send(history)
        }
    }

    /** A Mac just connected or asked to sync: give it the latest reading and history only. */
    fun pushDesktopSnapshot(context: Context) {
        ReadingRepository.latestMessage(context)?.let(DesktopLink::send)
        loadHistory(context, DesktopLink::send)
    }

    private fun loadHistory(context: Context, deliver: (String) -> Unit) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val from = now - BandMessage.HISTORY_WINDOW_MS - CLOCK_SLACK_MS
        ReadingDatabase.get(app).load(from, now + CLOCK_SLACK_MS) { points ->
            deliver(BandMessage.encodeHistory(points, DisplaySettings.targetRange(app)))
        }
    }
}
