package com.fch.cgmband

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.fch.cgmband.core.AlertThresholds
import com.fch.cgmband.core.arrow
import java.util.Locale

/** Setup / status screen: grants, band link, manual push and a live event log. */
class MainActivity : Activity() {

    private lateinit var readingView: TextView
    private lateinit var readingDetailView: TextView
    private lateinit var listenerStatusView: TextView
    private lateinit var bandStatusView: TextView
    private lateinit var batteryStatusView: TextView
    private lateinit var alertsEnabledView: CheckBox
    private lateinit var urgentLowInput: EditText
    private lateinit var lowInput: EditText
    private lateinit var highInput: EditText
    private lateinit var logView: TextView

    private val refreshReading: () -> Unit = { runOnUiThread(::renderReading) }
    private val refreshLog: () -> Unit = { runOnUiThread(::renderLog) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        readingView = findViewById(R.id.reading)
        readingDetailView = findViewById(R.id.reading_detail)
        listenerStatusView = findViewById(R.id.listener_status)
        bandStatusView = findViewById(R.id.band_status)
        batteryStatusView = findViewById(R.id.battery_status)
        alertsEnabledView = findViewById(R.id.alerts_enabled)
        urgentLowInput = findViewById(R.id.threshold_urgent_low)
        lowInput = findViewById(R.id.threshold_low)
        highInput = findViewById(R.id.threshold_high)
        renderAlertSettings()
        logView = findViewById(R.id.log)

        onClick(R.id.btn_notification_access, ::openNotificationAccess)
        onClick(R.id.btn_refresh_band, ::renderBandStatus)
        onClick(R.id.btn_request_permission, ::requestBandPermission)
        onClick(R.id.btn_launch_band_app, ::launchBandApp)
        onClick(R.id.btn_push_now, ::pushNow)
        onClick(R.id.btn_app_settings, ::openAppSettings)
        onClick(R.id.btn_battery, ::requestIgnoreBatteryOptimizations)
        onClick(R.id.btn_save_alerts, ::saveAlertSettings)
        onClick(R.id.btn_test_alert, ::sendTestAlert)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        ReadingRepository.observe(refreshReading)
        EventLog.observe(refreshLog)
        KeepAliveService.start(this)
        renderListenerStatus()
        renderBatteryStatus()
        renderReading()
        renderLog()
        renderBandStatus()
    }

    override fun onPause() {
        ReadingRepository.stopObserving(refreshReading)
        EventLog.stopObserving(refreshLog)
        super.onPause()
    }

    private fun onClick(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun renderReading() {
        val state = ReadingRepository.state
        val latest = state.latest
        if (latest == null) {
            readingView.text = getString(R.string.no_reading)
            readingDetailView.text = getString(R.string.waiting_detail)
            return
        }
        val trend = state.trend
        val delta = state.delta
        val disconnected = if (latest.connected) "" else " " + getString(R.string.sensor_disconnected)
        readingView.text = "${latest.value} ${trend.arrow}"
        readingDetailView.text = getString(
            R.string.reading_detail,
            latest.unit,
            if (latest.readingAt == null) getString(R.string.time_unknown) else latest.time,
            trend.name,
            delta?.let { "%+.1f".format(it) } ?: "-",
        ) + disconnected
    }

    private fun renderAlertSettings() {
        val settings = AlertMonitor.settings(this)
        alertsEnabledView.isChecked = settings.enabled
        urgentLowInput.setText(formatThreshold(settings.thresholds.urgentLow))
        lowInput.setText(formatThreshold(settings.thresholds.low))
        highInput.setText(formatThreshold(settings.thresholds.high))
    }

    private fun saveAlertSettings() {
        val thresholds = AlertThresholds.of(
            urgentLowInput.text.toString().toDoubleOrNull() ?: -1.0,
            lowInput.text.toString().toDoubleOrNull() ?: -1.0,
            highInput.text.toString().toDoubleOrNull() ?: -1.0,
        )
        if (thresholds == null) {
            toast(R.string.toast_alerts_invalid)
            return
        }
        AlertMonitor.saveSettings(this, AlertMonitor.Settings(alertsEnabledView.isChecked, thresholds))
        toast(R.string.toast_alerts_saved)
    }

    private fun sendTestAlert() {
        AlertMonitor.sendTest(this) { ok -> toast(if (ok) R.string.toast_sent else R.string.toast_send_failed) }
    }

    private fun formatThreshold(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun renderBatteryStatus() {
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryStatusView.text = getString(if (unrestricted) R.string.battery_ok else R.string.battery_restricted)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(request) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun renderListenerStatus() {
        val cgmInstalled = isInstalled(CgmNotificationListener.CGM_PACKAGE)
        val listenerText = getString(if (isListenerEnabled()) R.string.listener_on else R.string.listener_off)
        listenerStatusView.text = if (cgmInstalled) listenerText else "$listenerText\n${getString(R.string.cgm_missing)}"
    }

    private fun renderBandStatus() {
        bandStatusView.text = getString(R.string.band_checking)
        BandBridge.status(this) { status ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val node = status.node
                bandStatusView.text = if (node == null) {
                    getString(R.string.band_none)
                } else {
                    getString(
                        R.string.band_status,
                        node.name ?: node.id,
                        label(status.permissionGranted, R.string.granted, R.string.not_granted),
                        label(status.appInstalled, R.string.installed, R.string.not_installed),
                        label(status.notifyGranted, R.string.granted, R.string.not_granted),
                    )
                }
            }
        }
    }

    private fun renderLog() {
        logView.text = EventLog.entries.joinToString("\n")
    }

    private fun requestBandPermission() {
        BandBridge.requestPermission(this) { ok ->
            toast(if (ok) R.string.toast_permission_ok else R.string.toast_permission_failed)
            renderBandStatus()
        }
    }

    private fun launchBandApp() {
        BandBridge.launchBandApp(this) { ok -> if (!ok) toast(R.string.toast_launch_failed) }
    }

    private fun pushNow() {
        val message = ReadingRepository.latestMessage(this)
        if (message == null) {
            toast(R.string.toast_no_reading)
            return
        }
        BandBridge.send(this, message) { ok -> toast(if (ok) R.string.toast_sent else R.string.toast_send_failed) }
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, CgmNotificationListener::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(detail) }.onFailure { startActivity(fallback) }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(this, CgmNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun label(value: Boolean?, yes: Int, no: Int): String = when (value) {
        true -> getString(yes)
        false -> getString(no)
        null -> getString(R.string.unknown)
    }

    private fun toast(resId: Int) = runOnUiThread {
        if (!isDestroyed) Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
    }
}
