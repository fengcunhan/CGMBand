package com.fch.cgmband

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Binding and syncing the fingerstick meter: one scan or one session at a time. [status] is
 * replaced (never mutated) on every step and observers are called on the main thread.
 * Synced records go to [MeterDatabase]; the sync position goes to [MeterStore].
 */
// MissingPermission: callers go through problem() first.
// StaticFieldLeak: scanners and sessions only ever hold the application context.
@SuppressLint("MissingPermission", "StaticFieldLeak")
object MeterLink {

    sealed interface Status {
        data object Idle : Status
        data class Scanning(val found: List<FoundMeter>, val active: Boolean) : Status
        data class Working(val stage: MeterSession.Stage, val name: String) : Status
        /** [clockAhead]: some results are timed in the future, so the meter clock needs setting. */
        data class Synced(val newRecords: Int, val clockAhead: Boolean) : Status
        data class Failed(val reason: String) : Status
    }

    /** What stops a scan or sync from starting. */
    enum class Problem { NO_BLUETOOTH, PERMISSION, BLUETOOTH_OFF, LOCATION_OFF }

    @Volatile
    var status: Status = Status.Idle
        private set

    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private var scanner: MeterScanner? = null
    private var session: MeterSession? = null

    /** Bumped whenever an operation is stopped or started, so late results of an older one are dropped. */
    private var generation = 0

    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun problem(context: Context): Problem? {
        val adapter = adapter(context) ?: return Problem.NO_BLUETOOTH
        if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            return Problem.PERMISSION
        }
        if (!adapter.isEnabled) return Problem.BLUETOOTH_OFF
        // Up to Android 11 BLE scans silently find nothing while Location is switched off.
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationEnabled(context)) Problem.LOCATION_OFF else null
    }

    fun observe(observer: () -> Unit) = observers.add(observer)

    fun stopObserving(observer: () -> Unit) = observers.remove(observer)

    fun startScan(context: Context) {
        val app = context.applicationContext
        val adapter = adapter(app) ?: return
        stopAll()
        lateinit var current: MeterScanner
        current = MeterScanner(
            adapter,
            onUpdate = { found -> if (scanner === current) update(Status.Scanning(found, active = true)) },
            onStopped = { errorCode -> if (scanner === current) onScanStopped(app, errorCode) },
        )
        scanner = current
        if (current.start()) {
            update(Status.Scanning(emptyList(), active = true))
        } else {
            scanner = null
            update(Status.Failed(app.getString(R.string.meter_error_bluetooth_off)))
        }
    }

    /** The scan dialog was closed without picking a meter. */
    fun stopScan() {
        scanner?.stop()
        scanner = null
        if (status is Status.Scanning) update(Status.Idle)
    }

    /** First sync with [meter]: bonds if needed, then reads every stored record. */
    fun bind(context: Context, meter: FoundMeter) {
        stopAll()
        startSession(context.applicationContext, meter.device, meter.name, lastSequence = null, binding = true)
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        val bound = MeterStore.bound(app) ?: return
        val adapter = adapter(app) ?: return
        stopAll()
        startSession(app, adapter.getRemoteDevice(bound.address), bound.name, bound.lastSequence, binding = false)
    }

    /** Forgets the meter in this app; its records stay. The system-level bond is left alone. */
    fun unbind(context: Context) {
        stopAll()
        MeterStore.unbind(context)
        EventLog.add("已解除绑定血糖仪")
        update(Status.Idle)
    }

    /** The user stopped a scan or sync. */
    fun cancel() {
        val wasRunning = scanner != null || session != null
        stopAll()
        if (wasRunning) {
            EventLog.add("已取消血糖仪操作")
            update(Status.Idle)
        }
    }

    /** Stops whatever is running without reporting it; callers set the next status. */
    private fun stopAll() {
        generation++
        scanner?.stop()
        scanner = null
        val running = session
        session = null // detach first, so the cancelled session's own report is ignored
        running?.cancel()
    }

    private fun onScanStopped(context: Context, errorCode: Int?) {
        scanner = null
        val found = (status as? Status.Scanning)?.found.orEmpty()
        update(
            when {
                errorCode != null -> Status.Failed(context.getString(R.string.meter_error_scan, errorCode))
                found.isEmpty() -> Status.Failed(context.getString(R.string.meter_error_none_found))
                else -> Status.Scanning(found, active = false)
            },
        )
    }

    private fun startSession(app: Context, device: BluetoothDevice, name: String, lastSequence: Int?, binding: Boolean) {
        lateinit var current: MeterSession
        current = MeterSession(
            app,
            device,
            lastSequence,
            onStage = { stage ->
                if (session === current) {
                    // It came from a Glucose-service scan; once bonded it is ours even if this
                    // first transfer fails (the meter may stop advertising right after pairing).
                    val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    val alreadyBound = MeterStore.bound(app)?.address == device.address
                    if (binding && stage != MeterSession.Stage.PAIRING && bonded && !alreadyBound) {
                        MeterStore.bind(app, device.address, name)
                        EventLog.add("已绑定血糖仪 $name", sensitive = true) // the name carries the serial
                    }
                    update(Status.Working(stage, name))
                }
            },
            onDone = { outcome ->
                if (session === current) {
                    session = null
                    onSessionDone(app, device.address, outcome)
                }
            },
        )
        session = current
        current.start()
    }

    private fun onSessionDone(app: Context, address: String, outcome: MeterSession.Outcome) {
        val started = generation
        when (outcome) {
            is MeterSession.Outcome.Failed -> {
                EventLog.add("血糖仪：${outcome.reason}")
                update(Status.Failed(outcome.reason))
            }
            is MeterSession.Outcome.Synced -> {
                val transfer = outcome.transfer
                MeterDatabase.get(app).save(address, transfer.records) { inserted ->
                    if (inserted != null) {
                        MeterStore.recordSync(app, address, transfer.highestSequence, inserted, System.currentTimeMillis())
                    }
                    // The records are kept either way; only the status belongs to the operation that is current.
                    if (generation != started) return@save
                    if (inserted == null) {
                        update(Status.Failed(app.getString(R.string.meter_error_storage)))
                        return@save
                    }
                    val clockAhead = transfer.hasFutureRecords(System.currentTimeMillis())
                    EventLog.add("血糖仪同步完成，新增 $inserted 条" + if (clockAhead) "（血糖仪时间超前）" else "")
                    update(Status.Synced(inserted, clockAhead))
                }
            }
        }
    }

    private fun update(next: Status) {
        status = next
        observers.forEach { it() }
    }

    @Suppress("DEPRECATION")
    private fun locationEnabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true
        } else {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) !=
                Settings.Secure.LOCATION_MODE_OFF
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
}
