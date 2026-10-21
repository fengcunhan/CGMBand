package com.fch.cgmband

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.fch.cgmband.core.GlucoseProfile

/** A meter seen while scanning. */
data class FoundMeter(val device: BluetoothDevice, val name: String) {
    val address: String get() = device.address
}

/**
 * Looks for meters advertising the Glucose service (0x1808), as 轻糖生活 does. That filter
 * alone is enough here; the mini program additionally keeps only names containing "meter"
 * or "Accu", which the Roche meters advertise ("meter+<serial>").
 * Results are reported on the main thread, in the order the meters were first seen.
 */
@SuppressLint("MissingPermission") // MeterLink checks the Bluetooth permissions before scanning
class MeterScanner(
    private val adapter: BluetoothAdapter,
    private val onUpdate: (List<FoundMeter>) -> Unit,
    private val onStopped: (errorCode: Int?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var found: List<FoundMeter> = emptyList()
    private var scanning = false
    private val timeout = Runnable {
        stop()
        onStopped(null)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { add(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copy = results.toList()
            main.post { copy.forEach(::add) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (!scanning) return@post
                stop()
                onStopped(errorCode)
            }
        }
    }

    /** Returns false when the adapter has no LE scanner (Bluetooth off). */
    fun start(): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GlucoseProfile.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        scanning = true
        main.postDelayed(timeout, SCAN_MS)
        return true
    }

    fun stop() {
        main.removeCallbacks(timeout)
        if (!scanning) return
        scanning = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun add(result: ScanResult) {
        if (!scanning) return
        val device = result.device
        val name = (result.scanRecord?.deviceName ?: device.name)?.trim()?.ifEmpty { null } ?: device.address
        val meter = FoundMeter(device, name)
        val known = found.firstOrNull { it.address == meter.address }
        if (known == meter) return
        found = if (known == null) found + meter else found.map { if (it.address == meter.address) meter else it }
        onUpdate(found)
    }

    private companion object {
        const val SCAN_MS = 60_000L
    }
}
