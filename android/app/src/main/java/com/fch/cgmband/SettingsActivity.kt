package com.fch.cgmband

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fch.cgmband.core.AlertThresholds
import com.fch.cgmband.core.TargetRange
import java.util.Locale

/** Settings grouped into cards: data source, band, display, alerts, background running, diagnostics. */
class SettingsActivity : Activity() {

    private lateinit var listenerRow: SettingsSections.Row
    private lateinit var cgmRow: SettingsSections.Row
    private lateinit var bandRow: SettingsSections.Row
    private lateinit var targetRow: SettingsSections.Row
    private lateinit var thresholdsRow: SettingsSections.Row
    private lateinit var batteryRow: SettingsSections.Row
    private lateinit var logRow: SettingsSections.Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        buildSections(SettingsSections(findViewById(R.id.settings_container)))
    }

    override fun onResume() {
        super.onResume()
        renderSource()
        renderBand()
        renderTargetRange()
        renderThresholds()
        renderBattery()
        logRow.setValue(getString(R.string.log_count, EventLog.entries.size))
    }

    private fun buildSections(sections: SettingsSections) {
        sections.section(getString(R.string.section_source)) {
            listenerRow = row(getString(R.string.row_listener), getString(R.string.row_listener_sub), ::openNotificationAccess)
            cgmRow = row(getString(R.string.row_cgm_app))
        }
        sections.section(getString(R.string.section_band), getString(R.string.footer_band)) {
            bandRow = row(getString(R.string.row_band), getString(R.string.band_checking), ::renderBand)
            row(getString(R.string.row_band_permission), getString(R.string.row_band_permission_sub), ::requestBandPermission)
            row(getString(R.string.row_launch_band_app), onClick = ::launchBandApp)
            row(getString(R.string.row_push_now), onClick = ::pushNow)
        }
        sections.section(getString(R.string.section_display), getString(R.string.footer_target)) {
            targetRow = row(getString(R.string.row_target_range), onClick = ::editTargetRange)
        }
        sections.section(getString(R.string.section_alerts), getString(R.string.footer_alerts)) {
            switchRow(
                getString(R.string.row_alerts_enabled),
                getString(R.string.row_alerts_enabled_sub),
                AlertMonitor.settings(this@SettingsActivity).enabled,
                ::setAlertsEnabled,
            )
            thresholdsRow = row(getString(R.string.row_thresholds), getString(R.string.row_thresholds_sub), ::editThresholds)
            row(getString(R.string.row_test_alert), onClick = ::sendTestAlert)
        }
        sections.section(getString(R.string.section_background), getString(R.string.footer_background)) {
            batteryRow = row(getString(R.string.row_battery), onClick = ::requestIgnoreBatteryOptimizations)
            row(getString(R.string.row_app_settings), getString(R.string.row_app_settings_sub), ::openAppSettings)
        }
        sections.section(getString(R.string.section_diagnostics)) {
            logRow = row(getString(R.string.row_log), onClick = ::showLog)
        }
    }

    // ---- status rendering --------------------------------------------------------------

    private fun renderSource() {
        val enabled = SetupChecks.isListenerEnabled(this)
        listenerRow.setValue(status(enabled, R.string.on, R.string.off), statusColor(enabled))
        val installed = SetupChecks.isInstalled(this, CgmNotificationListener.CGM_PACKAGE)
        cgmRow.setValue(status(installed, R.string.installed, R.string.not_installed), statusColor(installed))
    }

    private fun renderBand() {
        bandRow.setSubtitle(getString(R.string.band_checking))
        BandBridge.status(this) { status ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val node = status.node
                if (node == null) {
                    bandRow.setTitle(getString(R.string.row_band))
                    bandRow.setValue(getString(R.string.band_disconnected), R.color.level_low)
                    bandRow.setSubtitle(getString(R.string.band_none))
                    return@runOnUiThread
                }
                // Only name what is missing; a full checklist does not fit on one line.
                val problems = listOfNotNull(
                    getString(R.string.band_problem_comm).takeIf { status.permissionGranted != true },
                    getString(R.string.band_problem_notify).takeIf { status.notifyGranted != true },
                    getString(R.string.band_problem_app).takeIf { status.appInstalled != true },
                )
                val ready = problems.isEmpty()
                bandRow.setTitle(node.name?.trim()?.ifEmpty { null } ?: getString(R.string.row_band))
                bandRow.setValue(getString(if (ready) R.string.band_ready else R.string.band_needs_setup), statusColor(ready))
                bandRow.setSubtitle(if (ready) getString(R.string.band_all_ok) else problems.joinToString(" · "))
            }
        }
    }

    private fun renderTargetRange() {
        val range = DisplaySettings.targetRange(this)
        targetRow.setValue(getString(R.string.range_value, format(range.low), format(range.high)))
    }

    private fun renderThresholds() {
        val t = AlertMonitor.settings(this).thresholds
        thresholdsRow.setValue(getString(R.string.thresholds_value, format(t.urgentLow), format(t.low), format(t.high)))
    }

    private fun renderBattery() {
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryRow.setValue(status(unrestricted, R.string.battery_off, R.string.battery_on), statusColor(unrestricted))
    }

    // ---- actions -------------------------------------------------------------------------

    private fun editTargetRange() {
        val range = DisplaySettings.targetRange(this)
        editNumbers(
            getString(R.string.row_target_range),
            listOf(getString(R.string.target_low) to range.low, getString(R.string.target_high) to range.high),
        ) { (low, high) ->
            val updated = TargetRange.of(low ?: -1.0, high ?: -1.0) ?: return@editNumbers getString(R.string.toast_target_invalid)
            DisplaySettings.saveTargetRange(this, updated)
            BandSync.pushHistory(this) // the band colours its chart and value with this range
            renderTargetRange()
            null
        }
    }

    private fun editThresholds() {
        val settings = AlertMonitor.settings(this)
        val t = settings.thresholds
        editNumbers(
            getString(R.string.row_thresholds),
            listOf(
                getString(R.string.threshold_urgent_low) to t.urgentLow,
                getString(R.string.threshold_low) to t.low,
                getString(R.string.threshold_high) to t.high,
            ),
        ) { (urgentLow, low, high) ->
            val updated = AlertThresholds.of(urgentLow ?: -1.0, low ?: -1.0, high ?: -1.0)
                ?: return@editNumbers getString(R.string.toast_alerts_invalid)
            AlertMonitor.saveSettings(this, settings.copy(thresholds = updated))
            renderThresholds()
            null
        }
    }

    private fun setAlertsEnabled(enabled: Boolean) {
        val current = AlertMonitor.settings(this)
        AlertMonitor.saveSettings(this, current.copy(enabled = enabled))
    }

    private fun sendTestAlert() {
        AlertMonitor.sendTest(this) { ok -> toast(if (ok) R.string.toast_test_sent else R.string.toast_send_failed) }
    }

    private fun requestBandPermission() {
        BandBridge.requestPermission(this) { ok ->
            toast(if (ok) R.string.toast_permission_ok else R.string.toast_permission_failed)
            renderBand()
        }
    }

    private fun launchBandApp() {
        BandBridge.launchBandApp(this) { ok -> toast(if (ok) R.string.toast_launched else R.string.toast_launch_failed) }
    }

    private fun pushNow() {
        if (ReadingRepository.latestMessage(this) == null) {
            toast(R.string.toast_no_reading)
            return
        }
        BandSync.pushLatest(this) { ok -> toast(if (ok) R.string.toast_sent else R.string.toast_send_failed) }
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, CgmNotificationListener::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        runCatching { startActivity(detail) }.onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(request) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun showLog() {
        val text = TextView(this).apply {
            text = EventLog.entries.joinToString("\n").ifEmpty { getString(R.string.log_empty) }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.row_log)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * Dialog with one "label  [number]" row per field. [onSave] receives the parsed values and
     * returns an error message to keep the dialog open, or null when saved.
     */
    private fun editNumbers(title: String, fields: List<Pair<String, Double>>, onSave: (List<Double?>) -> String?) {
        val root = layoutInflater.inflate(R.layout.dialog_number_fields, null) as LinearLayout
        val inputs = fields.map { (label, value) ->
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                gravity = Gravity.END
                setText(format(value))
                layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@SettingsActivity).apply {
                            text = label
                            textSize = 16f
                            setTextColor(getColor(R.color.text_primary))
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(input)
                },
            )
            input
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = onSave(inputs.map { it.text.toString().toDoubleOrNull() })
                if (error == null) dialog.dismiss() else Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun status(ok: Boolean, yes: Int, no: Int) = getString(if (ok) yes else no)

    private fun statusColor(ok: Boolean) = if (ok) R.color.level_in_range else R.color.level_low

    private fun format(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) = runOnUiThread {
        if (!isDestroyed) Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
