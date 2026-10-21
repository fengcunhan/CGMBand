package com.fch.cgmband

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.fch.cgmband.core.Fingerstick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 指尖血糖仪 card of the settings screen and its dialogs: bind (scan → pick → the system
 * asks for the PIN shown on the meter), sync, recent records and unbind. The card is
 * rebuilt whenever [MeterLink] reports a change.
 */
class MeterSettings(private val activity: Activity) {

    private lateinit var section: SettingsSections.Section
    private var scanDialog: AlertDialog? = null
    private var scanList: ArrayAdapter<String>? = null
    private var scanHint: TextView? = null
    private var shownMeters: List<FoundMeter> = emptyList()
    private var pendingAction: (() -> Unit)? = null
    private val refresh: () -> Unit = { activity.runOnUiThread(::render) }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun build(sections: SettingsSections) {
        section = sections.section(str(R.string.section_meter), str(R.string.footer_meter)) {}
    }

    fun onResume() {
        MeterLink.observe(refresh)
        render()
    }

    fun onPause() {
        MeterLink.stopObserving(refresh)
        scanDialog?.dismiss()
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_PERMISSIONS) return
        val action = pendingAction
        pendingAction = null
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        // No rationale after a denial means "don't ask again": only the app's settings page can grant it now.
        val blocked = !granted && MeterLink.permissions.none { activity.shouldShowRequestPermissionRationale(it) }
        when {
            blocked -> {
                toast(R.string.meter_permission_blocked)
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                )
            }
            !granted -> toast(R.string.meter_permission_denied)
            action != null -> whenReady(action)
        }
    }

    // ---- card ------------------------------------------------------------------------------

    private fun render() {
        if (activity.isDestroyed) return
        val bound = MeterStore.bound(activity)
        val status = MeterLink.status
        section.rebuild {
            if (bound == null) {
                row(str(R.string.row_meter_bind), str(R.string.row_meter_bind_sub)) { whenReady(::scan) }
            } else {
                row(bound.name, syncSummary(bound), ::confirmUnbind)
                row(str(R.string.row_meter_sync), str(R.string.row_meter_sync_sub)) {
                    whenReady { MeterLink.sync(activity) }
                }
            }
            statusRow(status)
            row(str(R.string.row_meter_records), onClick = ::showRecords)
        }
        renderScanDialog(status)
    }

    private fun SettingsSections.Section.statusRow(status: MeterLink.Status) {
        when (status) {
            is MeterLink.Status.Working -> {
                val (value, detail) = when (status.stage) {
                    MeterSession.Stage.CONNECTING -> R.string.meter_connecting to R.string.meter_connecting_detail
                    MeterSession.Stage.PAIRING -> R.string.meter_pairing to R.string.meter_pairing_detail
                    MeterSession.Stage.TRANSFERRING -> R.string.meter_transferring to R.string.meter_transferring_detail
                }
                row(str(R.string.row_meter_status), str(detail), MeterLink::cancel).setValue(str(value))
            }
            is MeterLink.Status.Synced -> {
                val detail = str(if (status.clockAhead) R.string.meter_clock_ahead else R.string.meter_synced_detail)
                row(str(R.string.row_meter_status), detail, ::showRecords)
                    .setValue(str(R.string.meter_synced, status.newRecords), R.color.level_in_range)
            }
            is MeterLink.Status.Failed -> row(str(R.string.row_meter_status), status.reason)
                .setValue(str(R.string.meter_failed), R.color.level_low)
            MeterLink.Status.Idle, is MeterLink.Status.Scanning -> Unit
        }
    }

    private fun syncSummary(bound: MeterStore.BoundMeter): String {
        val at = bound.lastSyncAt ?: return str(R.string.meter_never_synced)
        return str(R.string.meter_last_sync, timeFormat.format(Date(at)), bound.lastNewRecords)
    }

    // ---- binding -----------------------------------------------------------------------------

    private fun scan() {
        MeterLink.startScan(activity)
        showScanDialog()
    }

    private fun showScanDialog() {
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, mutableListOf())
        val hint = TextView(activity).apply {
            textSize = 14f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val list = ListView(activity).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ -> pick(position) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(list)
        }
        scanList = adapter
        scanHint = hint
        scanDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.meter_scan_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                scanDialog = null
                scanList = null
                scanHint = null
                if (MeterLink.status is MeterLink.Status.Scanning) MeterLink.stopScan()
            }
            .show()
        renderScanDialog(MeterLink.status)
    }

    private fun renderScanDialog(status: MeterLink.Status) {
        val adapter = scanList ?: return
        val hint = scanHint ?: return
        when (status) {
            is MeterLink.Status.Scanning -> {
                shownMeters = status.found
                adapter.clear()
                adapter.addAll(status.found.map { it.name })
                hint.text = str(
                    when {
                        !status.active -> R.string.meter_scan_done
                        status.found.isEmpty() -> R.string.meter_scan_hint
                        else -> R.string.meter_scan_pick
                    },
                )
            }
            is MeterLink.Status.Failed -> hint.text = status.reason
            else -> Unit
        }
    }

    private fun pick(position: Int) {
        val meter = shownMeters.getOrNull(position) ?: return
        scanDialog?.dismiss()
        MeterLink.bind(activity, meter)
    }

    private fun confirmUnbind() {
        val bound = MeterStore.bound(activity) ?: return
        AlertDialog.Builder(activity)
            .setTitle(str(R.string.meter_unbind_title, bound.name))
            .setMessage(R.string.meter_unbind_message)
            .setPositiveButton(R.string.meter_unbind) { _, _ -> MeterLink.unbind(activity) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- records -----------------------------------------------------------------------------

    /** Newest results first; tapping one asks when it was taken. */
    private fun showRecords() {
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, mutableListOf())
        var shown: List<Fingerstick> = emptyList()
        val hint = TextView(activity).apply {
            textSize = 14f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun reload() {
            MeterDatabase.get(activity).recent(RECORDS_SHOWN) { records ->
                shown = records
                hint.text = str(if (records.isEmpty()) R.string.meter_records_empty else R.string.meter_records_hint)
                adapter.clear()
                adapter.addAll(records.map(::line))
            }
        }
        val list = ListView(activity).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                shown.getOrNull(position)?.let { stick -> FingerstickTags.pick(activity, stick) { reload() } }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.meter_records_title)
            .setView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(hint)
                    addView(list)
                },
            )
            .setPositiveButton(R.string.close, null)
            .show()
        reload()
    }

    private fun line(stick: Fingerstick): String {
        val tag = FingerstickTags.label(activity, stick) ?: str(R.string.meter_untagged)
        return String.format(Locale.US, "%s   %4s   %s", timeFormat.format(Date(stick.at)), stick.record.label, tag)
    }

    // ---- preconditions -------------------------------------------------------------------------

    /** Runs [action] once Bluetooth exists, is permitted and is on; asks for what is missing. */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before BLUETOOTH_OFF is reported
    private fun whenReady(action: () -> Unit) {
        when (MeterLink.problem(activity)) {
            null -> action()
            MeterLink.Problem.NO_BLUETOOTH -> toast(R.string.meter_no_bluetooth)
            MeterLink.Problem.PERMISSION -> {
                pendingAction = action
                activity.requestPermissions(MeterLink.permissions, REQUEST_PERMISSIONS)
            }
            MeterLink.Problem.BLUETOOTH_OFF -> {
                toast(R.string.meter_bluetooth_off)
                runCatching { activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
            MeterLink.Problem.LOCATION_OFF -> {
                toast(R.string.meter_location_off)
                runCatching { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
        }
    }

    private fun str(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun toast(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQUEST_PERMISSIONS = 21
        const val RECORDS_SHOWN = 100
    }
}
