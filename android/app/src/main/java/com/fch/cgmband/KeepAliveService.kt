package com.fch.cgmband

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.fch.cgmband.core.StatusLine

/**
 * Foreground service whose only job is to keep this process out of HyperOS's freezer
 * ("greezer"): a frozen process receives notification callbacks only when thawed, which
 * delayed band updates by 20+ minutes in testing. Its ongoing notification doubles as a
 * glanceable status line with the latest reading.
 */
class KeepAliveService : Service() {

    private val refresh: () -> Unit = { notificationManager().notify(NOTIFICATION_ID, build()) }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ReadingRepository.observe(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ReadingRepository.stopObserving(refresh)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glucose)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(StatusLine.format(ReadingRepository.state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1

        /** Safe to call from anywhere; Android may refuse a background start unless battery optimisation is off. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
                .onFailure { e -> EventLog.add("无法启动后台保活服务: ${e.javaClass.simpleName}") }
        }
    }
}
