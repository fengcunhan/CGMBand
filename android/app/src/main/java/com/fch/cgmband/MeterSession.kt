package com.fch.cgmband

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fch.cgmband.core.GlucoseProfile
import com.fch.cgmband.core.Measurement
import com.fch.cgmband.core.MeasurementContext
import com.fch.cgmband.core.MeterTransfer
import com.fch.cgmband.core.RecordAccessResponse
import java.time.ZoneId
import java.util.UUID

/**
 * One GATT session with a glucose meter: bond on first use (the system asks for the PIN
 * shown on the meter) → connect → find the Glucose service → subscribe to measurement,
 * context and RACP → write the RACP request → collect records until the RACP response →
 * disconnect.
 *
 * Bonding comes first, before any GATT connection, the way the system Bluetooth settings
 * pair: an Accu-Chek drops an unencrypted link from a phone it does not recognise within
 * ~100 ms (seen on a Guide that had kept a stale pairing with this phone), long before
 * service discovery could have started bonding. Every GATT callback hops to the main thread, so
 * the steps never overlap; each step is guarded by a timeout.
 */
@SuppressLint("MissingPermission") // MeterLink checks BLUETOOTH_CONNECT before starting a session
class MeterSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val lastSequence: Int?,
    private val onStage: (Stage) -> Unit,
    private val onDone: (Outcome) -> Unit,
) {
    enum class Stage { CONNECTING, PAIRING, TRANSFERRING }

    sealed interface Outcome {
        data class Synced(val transfer: MeterTransfer) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var finished = false
    private var subscriptions: List<BluetoothGattCharacteristic> = emptyList()
    private var recordAccess: BluetoothGattCharacteristic? = null
    private var filtered = false
    private var measurements: List<Measurement> = emptyList()
    private var contexts: List<MeasurementContext> = emptyList()
    private var bondReceiver: BroadcastReceiver? = null
    private var timeoutReason = R.string.meter_error_timeout
    private val timeout = Runnable { fail(timeoutReason) }

    fun start() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) connect() else pair()
    }

    private fun connect() {
        onStage(Stage.CONNECTING)
        arm(CONNECT_TIMEOUT_MS, R.string.meter_error_connect_timeout)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail(R.string.meter_error_connect)
    }

    fun cancel() = fail(R.string.meter_error_cancelled)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { onConnectionChanged(status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { onServices(g, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { onSubscribed(status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post { onRequestWritten(status) }
        }

        /** API 33+; the value is passed in rather than read from the shared characteristic. */
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid
            main.post { onValue(uuid, value) }
        }

        @Deprecated("Only called below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: return
            main.post { onValue(uuid, value) }
        }
    }

    // ---- connect & discover ----------------------------------------------------------------

    private fun onConnectionChanged(status: Int, newState: Int) {
        if (finished) return
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            EventLog.add("血糖仪已连接")
            arm(STEP_TIMEOUT_MS, R.string.meter_error_services)
            // A bonded link re-encrypts first; discovering too early can miss the services.
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            main.postDelayed(::discover, if (bonded) BONDED_DISCOVERY_DELAY_MS else DISCOVERY_DELAY_MS)
        } else if (status in BOND_LOST_DISCONNECTS) {
            fail(R.string.meter_error_bond_lost)
        } else {
            // Disconnected, or "connected" with an error status: either way this link is unusable.
            fail(context.getString(R.string.meter_error_disconnected, status))
        }
    }

    private fun discover() {
        if (finished) return
        if (gatt?.discoverServices() != true) fail(R.string.meter_error_services)
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        // Below Android 12 the stack may rediscover on its own after a Service Changed
        // indication (common right after bonding); the transfer is already under way.
        if (finished || recordAccess != null) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail(R.string.meter_error_services)
        val service = g.getService(GlucoseProfile.SERVICE) ?: return fail(R.string.meter_error_not_meter)
        val measurement = service.getCharacteristic(GlucoseProfile.MEASUREMENT)
        val racp = service.getCharacteristic(GlucoseProfile.RECORD_ACCESS)
        if (measurement == null || racp == null) return fail(R.string.meter_error_not_meter)
        recordAccess = racp
        // Context (meal) is optional in GLP; RACP goes last so no record can arrive unsubscribed.
        subscriptions = listOfNotNull(measurement, service.getCharacteristic(GlucoseProfile.MEASUREMENT_CONTEXT), racp)
        beginTransfer() // bonded before connecting; a bond the meter has since dropped shows up as an auth error
    }

    // ---- bonding ---------------------------------------------------------------------------

    private fun pair() {
        onStage(Stage.PAIRING)
        arm(BOND_TIMEOUT_MS, R.string.meter_error_pair_timeout)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (bondDevice(intent)?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> onBonded()
                    BluetoothDevice.BOND_NONE -> fail(R.string.meter_error_pair_failed)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // A protected system broadcast: only the Bluetooth stack can send it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        bondReceiver = receiver
        // Without a GATT link createBond() opens its own LE connection and sends the pairing
        // request straight away. Bonding may also already be under way (started by the meter).
        when {
            device.bondState == BluetoothDevice.BOND_BONDED -> onBonded()
            device.bondState == BluetoothDevice.BOND_BONDING -> Unit
            !device.createBond() -> fail(R.string.meter_error_pair_failed)
        }
    }

    private fun onBonded() {
        if (finished) return
        unregisterBondReceiver()
        EventLog.add("血糖仪配对成功")
        connect()
    }

    // ---- subscribe & request records ---------------------------------------------------------

    private fun beginTransfer() {
        onStage(Stage.TRANSFERRING)
        subscribeNext()
    }

    private fun subscribeNext() {
        val g = gatt ?: return
        val characteristic = subscriptions.firstOrNull() ?: return requestRecords(lastSequence)
        val descriptor = characteristic.getDescriptor(GlucoseProfile.CLIENT_CONFIG)
            ?: return fail(R.string.meter_error_not_meter)
        val notify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val value = if (notify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        arm(STEP_TIMEOUT_MS, R.string.meter_error_subscribe)
        if (!g.setCharacteristicNotification(characteristic, true) || !writeDescriptor(g, descriptor, value)) {
            fail(R.string.meter_error_subscribe)
        }
    }

    private fun onSubscribed(status: Int) {
        if (finished) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail(gattError(status))
        subscriptions = subscriptions.drop(1)
        subscribeNext()
    }

    /** Asks for records after [after], or for all of them when [after] is null. */
    private fun requestRecords(after: Int?) {
        val g = gatt ?: return
        val racp = recordAccess ?: return
        val command = MeterTransfer.request(after)
        filtered = !command.contentEquals(GlucoseProfile.requestAllRecords())
        arm(TRANSFER_IDLE_MS, R.string.meter_error_timeout)
        if (!writeCharacteristic(g, racp, command)) fail(R.string.meter_error_request)
    }

    private fun onRequestWritten(status: Int) {
        if (finished) return
        if (status != BluetoothGatt.GATT_SUCCESS) fail(gattError(status))
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        if (finished) return
        arm(TRANSFER_IDLE_MS, R.string.meter_error_timeout)
        when (uuid) {
            GlucoseProfile.MEASUREMENT -> GlucoseProfile.parseMeasurement(value)?.let { measurements = measurements + it }
            GlucoseProfile.MEASUREMENT_CONTEXT -> GlucoseProfile.parseContext(value)?.let { contexts = contexts + it }
            GlucoseProfile.RECORD_ACCESS -> GlucoseProfile.parseRecordAccessResponse(value)?.let(::onResponse)
        }
    }

    /** Notifications arrive in order, so every record precedes this response. */
    private fun onResponse(response: RecordAccessResponse) {
        when {
            response.finished ->
                finish(Outcome.Synced(MeterTransfer.build(measurements, contexts, ZoneId.systemDefault())))
            response.filterUnsupported && filtered -> {
                EventLog.add("血糖仪不支持按序号读取，改为读取全部记录")
                requestRecords(null)
            }
            else -> fail(context.getString(R.string.meter_error_racp, response.code))
        }
    }

    // ---- teardown ----------------------------------------------------------------------------

    private fun finish(outcome: Outcome) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        unregisterBondReceiver()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() } // always release the client slot, even if disconnect threw
        }
        gatt = null
        onDone(outcome)
    }

    private fun fail(reasonRes: Int) = fail(context.getString(reasonRes))

    private fun fail(reason: String) = finish(Outcome.Failed(reason))

    private fun arm(delayMs: Long, reasonRes: Int) {
        timeoutReason = reasonRes
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, delayMs)
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
    }

    /** Insufficient authentication / encryption means the meter no longer trusts this phone's bond. */
    private fun gattError(status: Int): String =
        if (status in AUTH_ERRORS) {
            context.getString(R.string.meter_error_bond_lost)
        } else {
            context.getString(R.string.meter_error_gatt, status)
        }

    @Suppress("DEPRECATION")
    private fun bondDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            g.writeCharacteristic(characteristic)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val STEP_TIMEOUT_MS = 10_000L
        const val BOND_TIMEOUT_MS = 90_000L // the user reads the PIN off the meter and types it in
        const val TRANSFER_IDLE_MS = 15_000L
        const val DISCOVERY_DELAY_MS = 300L
        const val BONDED_DISCOVERY_DELAY_MS = 1_600L

        /** GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION, GATT_AUTH_FAIL. */
        val AUTH_ERRORS = setOf(5, 15, 137)

        /**
         * HCI disconnect reasons when the meter has forgotten this phone (reset, or paired
         * elsewhere): authentication failure, PIN or key missing, MIC failure.
         */
        val BOND_LOST_DISCONNECTS = setOf(5, 6, 61)
    }
}
