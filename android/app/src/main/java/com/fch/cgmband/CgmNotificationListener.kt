package com.fch.cgmband

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fch.cgmband.core.NotificationParser
import java.time.ZoneId

/**
 * Watches the iCan app's ongoing notification (refreshed every ~3 minutes) and pushes
 * each new reading to the band. The system keeps this service bound while notification
 * access is granted, so it doubles as the long-lived host for the band sync listener.
 *
 * Only the ongoing notification of [CGM_PACKAGE] is ever inspected; every other
 * notification is ignored without being read.
 */
class CgmNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        EventLog.add("通知监听已启动")
        KeepAliveService.start(this)
        BandBridge.start(this) { resendLatest() }
        DesktopLink.start(this)
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        active.filter(::isCgmReading).sortedBy { it.postTime }.forEach(::handle)
    }

    override fun onListenerDisconnected() {
        EventLog.add("通知监听已断开，请求重新绑定")
        requestRebind(ComponentName(this, CgmNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isCgmReading(sbn)) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isCgmReading(sbn)) return
        EventLog.add("iCan 常驻通知消失（App 被关闭？）")
        ReadingRepository.markDisconnected(this)?.let(::push)
    }

    private fun isCgmReading(sbn: StatusBarNotification): Boolean =
        sbn.packageName == CGM_PACKAGE && sbn.isOngoing

    private fun handle(sbn: StatusBarNotification) {
        val texts = RemoteViewsTextExtractor.extract(this, sbn.notification)
        val reading = NotificationParser.parse(texts, sbn.postTime, ZoneId.systemDefault())
        reading?.let { ReadingDatabase.get(this).save(it) }
        val message = when {
            reading != null -> ReadingRepository.accept(this, reading)?.also {
                EventLog.add("新血糖 ${reading.value} ${reading.unit} @${reading.time.ifEmpty { "时间未知" }}", sensitive = true)
                AlertMonitor.onReading(this, reading, ReadingRepository.state.trend)
            }
            NotificationParser.isSensorDisconnected(texts) -> ReadingRepository.markDisconnected(this)?.also {
                EventLog.add("iCan 报告传感器未连接")
            }
            else -> null
        }
        message?.let(::push)
    }

    private fun push(message: String) {
        BandSync.pushReading(this, message) { ok -> if (ok) EventLog.add("已推送到手环") }
    }

    private fun resendLatest() {
        BandSync.pushLatest(this) { ok -> if (ok) EventLog.add("已向手环补发最新读数") }
    }

    companion object {
        const val CGM_PACKAGE = "com.sino.care"
    }
}
